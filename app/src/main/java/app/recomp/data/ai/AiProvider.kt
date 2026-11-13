package app.recomp.data.ai

import app.recomp.domain.ai.AiImage
import app.recomp.domain.ai.AiProviderId
import app.recomp.domain.ai.AiStatus
import app.recomp.domain.ai.AiTarget
import app.recomp.domain.ai.TokenUsage
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLHandshakeException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A reply's JSON schema, written by hand (TRD §6.2). Shared by every provider, so it carries no array-length or
 * number-range constraints, which Anthropic rejects; code checks those. Every property is required and
 * `additionalProperties` is false, as each provider's strict mode expects. [name] is the schema's name where a
 * provider asks for one.
 */
class AiSchema(val name: String, val json: Map<String, Any>) {
    /** The same schema as a JSON tree, for the adapters that build their request bodies with kotlinx.serialization. */
    val element: JsonObject by lazy { toJson(json) as JsonObject }

    private fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k as String to toJson(v) })
        is List<*> -> JsonArray(value.map(::toJson))
        else -> error("Not JSON: ${value.javaClass.simpleName}")
    }
}

/**
 * One request to a provider (AI-05): the system prompt, the user message, any photos and the reply's schema.
 * [upperBound] is what it could cost at most, used for a request that was sent but never answered.
 */
class AiCall(
    val target: AiTarget,
    val system: String,
    val user: String,
    val schema: AiSchema,
    val upperBound: TokenUsage,
    val images: List<AiImage> = emptyList(),
) {
    override fun toString() = "AiCall($target)"
}

/**
 * One request's outcome, the same shape whichever provider answered. [json] is the parsed reply and is set only
 * when [status] is [AiStatus.OK]; what it holds is still validated by the caller (`MealCheck`). [attempts] holds
 * every attempt's tokens, already mapped to input, cached input and output with reasoning included, and
 * [servedBy] the model that produced the reply. [text] is the raw reply text, never the prompt or the key.
 * [detail] is a stop reason, an error code or an exception's class name: never a message, which could carry the key.
 */
data class AiResult(
    val status: AiStatus,
    val json: JsonObject?,
    val text: String?,
    val servedBy: String,
    val attempts: List<TokenUsage>,
    val latencyMs: Long,
    val detail: String? = null,
)

/**
 * One AI service (AI-05, TRD §6.2). An implementation never throws (cancellation aside): every failure comes
 * back as an [AiStatus] from the one shared table, and the key is held only for the request.
 */
interface AiProvider {
    suspend fun send(call: AiCall): AiResult
}

/** The adapter for each provider. The OpenAI-compatible provider speaks OpenAI's protocol at its own base URL. */
@Singleton
class AiProviders @Inject constructor(
    private val anthropic: AnthropicProvider,
    private val openAi: OpenAiProvider,
    private val gemini: GeminiProvider,
) {
    fun of(id: AiProviderId): AiProvider = when (id) {
        AiProviderId.ANTHROPIC -> anthropic
        AiProviderId.OPENAI, AiProviderId.COMPATIBLE -> openAi
        AiProviderId.GEMINI -> gemini
    }
}

/** What every adapter shares: the time budget, the one retry, and how failures map onto [AiStatus]. */
object AiWire {
    /** One attempt's time budget (TRD §6.2). */
    val TIMEOUT: Duration = Duration.ofSeconds(20)

    /** The first attempt and one retry. */
    const val MAX_ATTEMPTS = 2

    /** The longest a retry waits, whatever `retry-after` asks, so a request stays well inside its time budget. */
    const val MAX_RETRY_WAIT_MS = 2_000L
    const val DEFAULT_RETRY_WAIT_MS = 500L

    /** `retry-after-ms`, else `retry-after` in seconds, capped. */
    fun retryDelayMs(retryAfterMs: String?, retryAfter: String?): Long {
        val asked = retryAfterMs?.toDoubleOrNull()?.toLong() ?: retryAfter?.toDoubleOrNull()?.times(1_000)?.toLong()
        return (asked ?: DEFAULT_RETRY_WAIT_MS).coerceIn(0, MAX_RETRY_WAIT_MS)
    }

    /** An HTTP error status, where the provider's body doesn't say more. */
    fun statusFor(code: Int): AiStatus = when {
        code == 401 || code == 403 -> AiStatus.AUTH
        code == 402 -> AiStatus.BILLING
        code == 429 -> AiStatus.RATE_LIMIT
        code >= 500 -> AiStatus.SERVER
        else -> AiStatus.BAD_REQUEST
    }

    /** Statuses where the server did no billable work, so one retry is safe: rate-limited, overloaded or failing. */
    fun retryable(code: Int): Boolean = code == 429 || code >= 500

    fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }

    /** The connection never got as far as sending: no route, no host, refused, or the TLS handshake failed. */
    fun notSent(e: Throwable): Boolean = e.causes().any {
        it is ConnectException || it is UnknownHostException || it is NoRouteToHostException || it is SSLHandshakeException ||
            (it is SocketTimeoutException && it.message.orEmpty().contains("connect", ignoreCase = true))
    }

    fun timedOut(e: Throwable): Boolean = e.causes().any { it is InterruptedIOException }

    private val lenient = Json { ignoreUnknownKeys = true }

    /**
     * The reply text as a JSON object, or null when it isn't one. A server in plain JSON mode may wrap the object
     * in a code fence or a sentence, so the outermost braces are tried as well.
     */
    fun parseObject(text: String): JsonObject? {
        fun parse(s: String) = runCatching { lenient.parseToJsonElement(s) as? JsonObject }.getOrNull()
        parse(text.trim())?.let { return it }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start in 0 until end) parse(text.substring(start, end + 1)) else null
    }
}
