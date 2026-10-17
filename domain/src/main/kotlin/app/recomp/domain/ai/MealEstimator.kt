package app.recomp.domain.ai

/**
 * How a request ended (TRD §6.2's failure table). Stored as `ai_request_log.status`, and as a draft's
 * `reason` when it wasn't [OK].
 */
enum class AiStatus {
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
}

/**
 * What the estimator sends: the persona-built system prompt and the meal's user message (`MealPrompt`).
 * [apiKey] is used for this request only and never logged.
 */
class MealRequest(val apiKey: String, val model: AiModel, val system: String, val user: String) {
    override fun toString() = "MealRequest(model=$model)"
}

/**
 * One request's outcome. [estimate] is set only when [status] is [AiStatus.OK]. [attempts] holds every
 * attempt's tokens (a refused one and its fallback), [servedBy] the model that produced the reply, and
 * [responseText] the raw reply text for `ai_request_log.response_json`.
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
