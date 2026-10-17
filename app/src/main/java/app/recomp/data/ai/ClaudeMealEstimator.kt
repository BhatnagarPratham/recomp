package app.recomp.data.ai

import app.recomp.domain.ai.AiStatus
import app.recomp.domain.ai.Confidence
import app.recomp.domain.ai.EstimatedItem
import app.recomp.domain.ai.EstimatorReply
import app.recomp.domain.ai.MealEstimate
import app.recomp.domain.ai.MealEstimator
import app.recomp.domain.ai.MealRequest
import app.recomp.domain.ai.TokenUsage
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
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Describe it's reply schema (TRD §6.2), written by hand. The SDK's class-based `.outputConfig(MyClass::class.java)`
 * derives a schema with `java.lang.reflect.AnnotatedType`, which Android doesn't have, so it would crash on the
 * phone (M0). Every property is required, as structured outputs expect with `additionalProperties: false`;
 * `clarifying_question` is "" when there's none.
 */
object MealSchema {
    private fun number(description: String) = mapOf("type" to "number", "description" to description)

    private val item = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "name" to mapOf("type" to "string", "description" to "The food or drink, as a short name"),
            "quantity" to number("How many units, as the user said or as is typical"),
            "unit" to mapOf("type" to "string", "description" to "The unit of quantity, e.g. piece, katori, cup, glass, g"),
            "grams" to number("Weight as eaten, in grams; liquids count 1 ml as 1 g"),
            "kcal" to number("Energy in kcal"),
            "protein_g" to number("Protein in grams"),
            "carbs_g" to number("Carbohydrate in grams"),
            "fat_g" to number("Fat in grams"),
            "fiber_g" to number("Fibre in grams"),
            "confidence" to mapOf("type" to "string", "enum" to listOf("high", "medium", "low")),
            "assumptions" to mapOf("type" to "string", "description" to "Size, recipe, oil or sugar assumed, in a few words"),
        ),
        "required" to listOf("name", "quantity", "unit", "grams", "kcal", "protein_g", "carbs_g", "fat_g", "fiber_g", "confidence", "assumptions"),
        "additionalProperties" to false,
    )

    val json: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "items" to mapOf("type" to "array", "items" to item),
            "clarifying_question" to mapOf("type" to "string", "description" to "One short question, or empty when none is needed"),
        ),
        "required" to listOf("items", "clarifying_question"),
        "additionalProperties" to false,
    )

    val schema: BetaJsonOutputFormat.Schema =
        BetaJsonOutputFormat.Schema.builder().putAllAdditionalProperties(json.mapValues { (_, v) -> JsonValue.from(v) }).build()

    @Serializable
    data class Item(
        val name: String,
        val quantity: Double,
        val unit: String,
        val grams: Double,
        val kcal: Double,
        @SerialName("protein_g") val proteinG: Double,
        @SerialName("carbs_g") val carbsG: Double,
        @SerialName("fat_g") val fatG: Double,
        @SerialName("fiber_g") val fiberG: Double = 0.0,
        val confidence: String = "medium",
        val assumptions: String = "",
    )

    @Serializable
    data class Reply(val items: List<Item>, @SerialName("clarifying_question") val clarifyingQuestion: String = "")

    private val parser = Json { ignoreUnknownKeys = true }

    /** Parses the reply text. Throws [SerializationException] or [IllegalArgumentException] when it doesn't fit. */
    fun parse(text: String): MealEstimate {
        val reply = parser.decodeFromString<Reply>(text)
        return MealEstimate(
            items = reply.items.map {
                EstimatedItem(
                    name = it.name, quantity = it.quantity, unit = it.unit, grams = it.grams, kcal = it.kcal,
                    proteinG = it.proteinG, carbsG = it.carbsG, fatG = it.fatG, fiberG = it.fiberG,
                    confidence = Confidence.entries.firstOrNull { c -> c.name.equals(it.confidence.trim(), ignoreCase = true) } ?: Confidence.MEDIUM,
                    assumptions = it.assumptions,
                )
            },
            clarifyingQuestion = reply.clarifyingQuestion.trim().ifEmpty { null },
        )
    }
}

/**
 * Describe it through the Anthropic Java SDK (TRD §6.2): structured output with [MealSchema], adaptive thinking at
 * effort `low`, a cached system prompt, and the server-side refusal fallback where the model has one. Each attempt
 * times out after 20 s, with one retry. Errors come back as an [AiStatus], never as an exception, and the key is
 * held only for the request.
 */
@Singleton
class ClaudeMealEstimator @Inject constructor() : MealEstimator {
    /** Tests point the client at a local server and shorten the timeout. */
    internal var baseUrl: String? = null
    internal var timeout: Duration = TIMEOUT

    override suspend fun estimate(request: MealRequest): EstimatorReply = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - started) / 1_000_000
        fun failure(status: AiStatus, e: Throwable) =
            EstimatorReply(status, null, request.model.id, emptyList(), elapsedMs(), responseText = null, detail = e.javaClass.simpleName)

        val client = AnthropicOkHttpClient.builder()
            .apiKey(request.apiKey)
            .timeout(timeout)
            .maxRetries(1)
            .apply { baseUrl?.let { baseUrl(it) } }
            .build()
        try {
            reply(client.beta().messages().create(params(request)), request, elapsedMs())
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
            failure(if (e.causes().any { it is InterruptedIOException }) AiStatus.TIMEOUT else AiStatus.NETWORK, e)
        } catch (e: AnthropicRetryableException) {
            failure(if (e.causes().any { it is InterruptedIOException }) AiStatus.TIMEOUT else AiStatus.NETWORK, e)
        } catch (e: AnthropicException) {
            // The response arrived but didn't fit the SDK's models.
            failure(AiStatus.INVALID, e)
        } finally {
            client.close()
        }
    }

    private fun params(request: MealRequest): MessageCreateParams {
        val system = BetaTextBlockParam.builder()
            .text(request.system)
            .cacheControl(BetaCacheControlEphemeral.builder().build())
            .build()
        return MessageCreateParams.builder()
            .model(request.model.id)
            .maxTokens(MAX_TOKENS)
            .systemOfBetaTextBlockParams(listOf(system))
            .outputConfig(
                BetaOutputConfig.builder()
                    .effort(BetaOutputConfig.Effort.LOW)
                    .format(BetaJsonOutputFormat.builder().schema(MealSchema.schema).build())
                    .build(),
            )
            .addUserMessage(request.user)
            .apply {
                // Retries a safety-classifier refusal on the model Anthropic recommends, in the same round trip.
                if (request.model.serverFallback) addBeta(FALLBACK_BETA).fallbacks(BetaFallbacksParam.ofDefault())
            }
            .build()
    }

    private fun reply(response: BetaMessage, request: MealRequest, latencyMs: Long): EstimatorReply {
        val servedBy = response.model().asString()
        val attempts = attempts(response, request.model.id)
        val text = response.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("").ifEmpty { null }
        fun done(status: AiStatus, estimate: MealEstimate? = null, detail: String? = null) =
            EstimatorReply(status, estimate, servedBy, attempts, latencyMs, text, detail)

        // The stop reason decides before any content is read.
        return when (response.stopReason().orElse(null)) {
            BetaStopReason.REFUSAL -> done(AiStatus.REFUSAL, detail = response.stopDetails().flatMap { it.category() }.map { it.asString() }.orElse(null))
            BetaStopReason.MAX_TOKENS -> done(AiStatus.MAX_TOKENS)
            BetaStopReason.END_TURN -> {
                if (text == null) {
                    done(AiStatus.INVALID, detail = "no text")
                } else {
                    try {
                        done(AiStatus.OK, MealSchema.parse(text))
                    } catch (e: SerializationException) {
                        done(AiStatus.INVALID, detail = e.javaClass.simpleName)
                    } catch (e: IllegalArgumentException) {
                        done(AiStatus.INVALID, detail = e.javaClass.simpleName)
                    }
                }
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
        val TIMEOUT: Duration = Duration.ofSeconds(20)
        const val MAX_TOKENS = 8_000L
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

        /** An HTTP status the SDK has no dedicated exception for. */
        fun statusFor(code: Int): AiStatus = when {
            code == 401 || code == 403 -> AiStatus.AUTH
            code == 402 -> AiStatus.BILLING
            code == 429 -> AiStatus.RATE_LIMIT
            code >= 500 -> AiStatus.SERVER
            else -> AiStatus.BAD_REQUEST
        }

        private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }
    }
}
