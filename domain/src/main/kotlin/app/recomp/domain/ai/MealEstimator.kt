package app.recomp.domain.ai

/**
 * How a request ended (TRD §6.2's failure table). Stored as `ai_request_log.status`, and as a draft's
 * `reason` when it wasn't [OK].
 */
enum class AiStatus {
    /** Sent and not yet settled: the row holds an upper-bound cost until the reply arrives (or forever, if the app died). */
    PENDING,
    OK,
    REFUSAL,
    MAX_TOKENS,
    INVALID,
    AUTH,
    BILLING,
    RATE_LIMIT,
    SERVER,
    BAD_REQUEST,
    TIMEOUT,
    NETWORK,

    /** Anything else that went wrong sending the request; only the exception's class name is kept. */
    UNEXPECTED,
}

/**
 * What the estimator sends: the persona-built system prompt and the meal's user message (`MealPrompt`).
 * [apiKey] is used for this request only and never logged.
 */
class MealRequest(val apiKey: String, val model: AiModel, val system: String, val user: String) {
    override fun toString() = "MealRequest(model=$model)"

    /** Characters of prompt, for an upper-bound cost before the real usage is known. */
    val promptChars: Int get() = system.length + user.length

    /** What this request could cost at most: [AiPricing.upperBound] at [MAX_OUTPUT_TOKENS]. */
    val upperBound: TokenUsage get() = AiPricing.upperBound(model.id, promptChars, MAX_OUTPUT_TOKENS)

    companion object {
        /** `max_tokens`: room for the thinking and a long meal (TRD §6.2). */
        const val MAX_OUTPUT_TOKENS = 8_000L
    }
}

/**
 * One request's outcome. [estimate] is set only when [status] is [AiStatus.OK]. [attempts] holds every
 * attempt's tokens (a refused one and its fallback), [servedBy] the model that produced the reply, and
 * [responseText] the raw reply text for `ai_request_log.response_json`. When the request was sent but no reply
 * came (a timeout, a dropped connection), the server may still have done and billed the work: [attempts] then
 * holds the request's upper bound, since the real usage is unknown.
 */
data class EstimatorReply(
    val status: AiStatus,
    val estimate: MealEstimate?,
    val servedBy: String,
    val attempts: List<TokenUsage>,
    val latencyMs: Long,
    val responseText: String?,
    val detail: String? = null,
)

/** Describe it (NU-17): one meal description in, an itemised estimate out. Implemented by the Claude client in `:app`. */
interface MealEstimator {
    suspend fun estimate(request: MealRequest): EstimatorReply
}
