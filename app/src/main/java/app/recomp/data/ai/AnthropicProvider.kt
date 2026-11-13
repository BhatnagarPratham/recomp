package app.recomp.data.ai

import java.util.Base64
import app.recomp.data.ai.AiWire.causes
import app.recomp.domain.ai.AiStatus
import app.recomp.domain.ai.MealRequest
import app.recomp.domain.ai.TokenUsage
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicRetryableException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams
import java.io.InterruptedIOException
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Anthropic through its Java SDK (TRD §6.2): structured output (`output_config.format`) with the call's
 * hand-written schema, adaptive thinking at effort `low`, a cached system prompt, and the server-side refusal
 * fallback where the model has one. Each attempt times out after 20 s. One retry, and only when the request clearly
 * wasn't processed (429, 5xx, no connection): a timed-out request may still be running, and billed, on the server,
 * so it isn't sent again. Errors come back as an [AiStatus], never as an exception, and the key is held only for
 * the request.
 */
@Singleton
class AnthropicProvider @Inject constructor() : AiProvider {
    /** Tests point the client at a local server and shorten the timeout. */
    internal var baseUrl: String? = null
    internal var timeout: Duration = TIMEOUT

    override suspend fun send(call: AiCall): AiResult = withContext(Dispatchers.IO) {
        val request = call
        val started = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - started) / 1_000_000
        /** [maybeBilled]: sent, but no reply came, so the request's upper bound stands in for its unknown usage. */
        fun failure(status: AiStatus, e: Throwable, maybeBilled: Boolean = false) = AiResult(
            status, null, null, request.target.model, if (maybeBilled) listOf(request.upperBound) else emptyList(), elapsedMs(),
            detail = e.javaClass.simpleName,
        )
        fun ioFailure(e: Throwable) = when {
            notSent(e) -> failure(AiStatus.NETWORK, e)
            e.causes().any { it is InterruptedIOException } -> failure(AiStatus.TIMEOUT, e, maybeBilled = true)
            else -> failure(AiStatus.NETWORK, e, maybeBilled = true)
        }

        var client: AnthropicClient? = null
        try {
            client = AnthropicOkHttpClient.builder()
                .apiKey(request.target.apiKey.orEmpty())
                .timeout(timeout)
                .maxRetries(0)
                .apply { baseUrl?.let { baseUrl(it) } }
                .build()
            reply(send(client, request), request, elapsedMs())
        } catch (e: UnauthorizedException) {
            failure(AiStatus.AUTH, e)
        } catch (e: PermissionDeniedException) {
            failure(AiStatus.AUTH, e)
        } catch (e: RateLimitException) {
            failure(AiStatus.RATE_LIMIT, e)
        } catch (e: InternalServerException) {
            failure(AiStatus.SERVER, e)
        } catch (e: AnthropicServiceException) {
            failure(statusFor(e.statusCode()), e)
        } catch (e: AnthropicIoException) {
            ioFailure(e)
        } catch (e: AnthropicRetryableException) {
            ioFailure(e)
        } catch (e: AnthropicException) {
            // The response arrived but didn't fit the SDK's models.
            failure(AiStatus.INVALID, e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never let an exception escape: some carry request headers, the key among them, in their message.
            failure(AiStatus.UNEXPECTED, e)
        } finally {
            client?.close()
        }
    }

    /** Sends the request, once more if the first attempt clearly wasn't processed. Throws the last attempt's error. */
    private suspend fun send(client: AnthropicClient, request: AiCall): BetaMessage {
        val params = params(request)
        var attempt = 1
        while (true) {
            try {
                return client.beta().messages().create(params)
            } catch (e: Exception) {
                if (attempt >= MAX_ATTEMPTS || !unprocessed(e)) throw e
                delay(retryDelayMs(e))
                attempt++
            }
        }
    }

    private fun params(request: AiCall): MessageCreateParams {
        val system = BetaTextBlockParam.builder()
            .text(request.system)
            .cacheControl(BetaCacheControlEphemeral.builder().build())
            .build()
        return MessageCreateParams.builder()
            .model(request.target.model)
            .maxTokens(MAX_TOKENS)
            .systemOfBetaTextBlockParams(listOf(system))
            .outputConfig(
                BetaOutputConfig.builder()
                    .effort(BetaOutputConfig.Effort.LOW)
                    .format(BetaJsonOutputFormat.builder().schema(schema(request.schema)).build())
                    .build(),
            )
            .apply {
                if (request.images.isEmpty()) {
                    addUserMessage(request.user)
                } else {
                    // Photos first, then the text that says what to do with them.
                    val photos = request.images.map {
                        BetaContentBlockParam.ofImage(
                            BetaBase64ImageSource.builder()
                                .mediaType(BetaBase64ImageSource.MediaType.of(it.mediaType))
                                .data(Base64.getEncoder().encodeToString(it.bytes))
                                .build(),
                        )
                    }
                    addUserMessageOfBetaContentBlockParams(photos + BetaContentBlockParam.ofText(request.user))
                }
                // Retries a safety-classifier refusal on the model Anthropic recommends, in the same round trip.
                if (request.target.serverFallback) addBeta(FALLBACK_BETA).fallbacks(BetaFallbacksParam.ofDefault())
            }
            .build()
    }

    private fun schema(schema: AiSchema): BetaJsonOutputFormat.Schema =
        BetaJsonOutputFormat.Schema.builder().putAllAdditionalProperties(schema.json.mapValues { (_, v) -> JsonValue.from(v) }).build()

    private fun reply(response: BetaMessage, request: AiCall, latencyMs: Long): AiResult {
        val servedBy = response.model().asString()
        val attempts = attempts(response, request.target.model)
        val text = response.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("").ifEmpty { null }
        fun done(status: AiStatus, json: JsonObject? = null, detail: String? = null) = AiResult(status, json, text, servedBy, attempts, latencyMs, detail)

        // The stop reason decides before any content is read.
        return when (response.stopReason().orElse(null)) {
            BetaStopReason.REFUSAL -> done(AiStatus.REFUSAL, detail = response.stopDetails().flatMap { it.category() }.map { it.asString() }.orElse(null))
            BetaStopReason.MAX_TOKENS -> done(AiStatus.MAX_TOKENS)
            BetaStopReason.END_TURN -> when {
                text == null -> done(AiStatus.INVALID, detail = "no text")
                else -> AiWire.parseObject(text)?.let { done(AiStatus.OK, it) } ?: done(AiStatus.INVALID, detail = "not JSON")
            }
            else -> done(AiStatus.INVALID, detail = response.stopReason().map { it.asString() }.orElse("no stop reason"))
        }
    }

    /** Every attempt's tokens: `usage.iterations` when the API reports them (a refusal and its fallback), else the top-level usage. */
    private fun attempts(response: BetaMessage, requested: String): List<TokenUsage> {
        val usage = response.usage()
        val iterations = usage.iterations().orElse(emptyList()).filter { it.isMessage() || it.isFallbackMessage() }
        if (iterations.isEmpty()) {
            return listOf(
                TokenUsage(
                    model = response.model().asString(),
                    inputTokens = usage.inputTokens(),
                    outputTokens = usage.outputTokens(),
                    cacheReadTokens = usage.cacheReadInputTokens().orElse(0L),
                    cacheWriteTokens = usage.cacheCreationInputTokens().orElse(0L),
                ),
            )
        }
        return iterations.map {
            TokenUsage(
                model = it.model().map { m -> m.asString() }.orElse(requested),
                inputTokens = it.inputTokens(),
                outputTokens = it.outputTokens(),
                cacheReadTokens = it.cacheReadInputTokens(),
                cacheWriteTokens = it.cacheCreationInputTokens(),
            )
        }
    }

    companion object {
        val TIMEOUT: Duration = AiWire.TIMEOUT
        const val MAX_TOKENS = MealRequest.MAX_OUTPUT_TOKENS
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        const val MAX_ATTEMPTS = AiWire.MAX_ATTEMPTS
        const val MAX_RETRY_WAIT_MS = AiWire.MAX_RETRY_WAIT_MS

        /** Errors where the server did no billable work: rate-limited, overloaded or failing, or never reached. */
        fun unprocessed(e: Throwable): Boolean = when (e) {
            is RateLimitException, is InternalServerException -> true
            is AnthropicServiceException -> AiWire.retryable(e.statusCode())
            is AnthropicIoException, is AnthropicRetryableException -> notSent(e)
            else -> false
        }

        fun notSent(e: Throwable): Boolean = AiWire.notSent(e)

        private fun retryDelayMs(e: Throwable): Long {
            val headers = (e as? AnthropicServiceException)?.headers()
            return AiWire.retryDelayMs(headers?.values("retry-after-ms")?.firstOrNull(), headers?.values("retry-after")?.firstOrNull())
        }

        /** An HTTP status the SDK has no dedicated exception for. */
        fun statusFor(code: Int): AiStatus = AiWire.statusFor(code)
    }
}
