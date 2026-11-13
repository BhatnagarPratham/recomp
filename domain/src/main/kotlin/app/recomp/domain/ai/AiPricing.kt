package app.recomp.domain.ai

/**
 * Dollars per million tokens. Cache writes (Anthropic's, 5-minute TTL) cost 1.25 × the input rate; the other
 * providers cache on their own and report no cache writes.
 */
data class Rates(val input: Double, val output: Double, val cacheRead: Double) {
    val cacheWrite: Double get() = input * 1.25
}

/**
 * One attempt's tokens, mapped from each provider's own usage report (TRD §6.2) so that nothing is counted twice
 * or missed: [inputTokens] excludes cached input, which is [cacheReadTokens]; [outputTokens] includes reasoning
 * or thinking tokens, which every provider bills as output. [model] is the model that ran it. [rates] are the
 * prices you typed for an OpenAI-compatible model, which the table can't know.
 */
data class TokenUsage(
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val rates: Rates? = null,
)

/**
 * AI-03's spend (TRD §6.2): tokens × a price table kept in code and updated by hand when prices change.
 * A model the table doesn't know is priced at its highest rates, so a monthly cap errs on the early side.
 */
object AiPricing {
    val table: Map<String, Rates> = mapOf(
        "claude-opus-5-5" to Rates(input = 4.00, output = 20.00, cacheRead = 0.20),
        "claude-sonnet-5-5" to Rates(input = 2.00, output = 10.00, cacheRead = 0.20),
        // Prompts up to 100K tokens, as all of ours are. The cache-read rate assumes 0.1 × input.
        "claude-haiku-5-5" to Rates(input = 0.10, output = 0.50, cacheRead = 0.01),
        // Refusal-fallback targets (`fallbacks: "default"`, AiModel.fallbackTargets).
        "claude-opus-5" to Rates(input = 5.00, output = 25.00, cacheRead = 0.50),
        "claude-opus-4-8" to Rates(input = 5.00, output = 25.00, cacheRead = 0.50),
        "claude-sonnet-5" to Rates(input = 2.00, output = 10.00, cacheRead = 0.20),
        // Not a fallback target: the most expensive current model, whose rates any unknown model is charged at.
        "claude-fable-5-1" to Rates(input = 10.00, output = 50.00, cacheRead = 0.25),
        // OpenAI, standard tier, prompts up to 272K tokens (developers.openai.com/api/docs/pricing, read 2026-11-13).
        // Its 30-minute cache-write price is 1.25 × input, the same multiple as Anthropic's.
        "gpt-6.1-sol" to Rates(input = 2.00, output = 10.00, cacheRead = 0.10),
        "gpt-6.1-terra" to Rates(input = 1.00, output = 5.00, cacheRead = 0.05),
        "gpt-6-luna" to Rates(input = 0.10, output = 0.50, cacheRead = 0.01),
        // Gemini, paid tier; output includes thinking (aistudio.google.com/docs/pricing, read 2026-11-13).
        // 3.8 Flash's prices are due to double on 2027-01-01: update this row then.
        "gemini-4-hydrogen" to Rates(input = 1.50, output = 7.50, cacheRead = 0.075),
        "gemini-3.8-flash" to Rates(input = 0.75, output = 3.75, cacheRead = 0.075),
        "gemini-3.5-flash-lite" to Rates(input = 0.30, output = 2.50, cacheRead = 0.03),
    )

    private val highest = Rates(
        input = table.values.maxOf { it.input },
        output = table.values.maxOf { it.output },
        cacheRead = table.values.maxOf { it.cacheRead },
    )

    fun rates(model: String): Rates = table[model] ?: highest

    fun cost(usage: TokenUsage): Double {
        val r = usage.rates ?: rates(usage.model)
        return (usage.inputTokens * r.input + usage.outputTokens * r.output +
            usage.cacheReadTokens * r.cacheRead + usage.cacheWriteTokens * r.cacheWrite) / 1_000_000.0
    }

    /** Every attempt of one request, each at its own model's rates: a declined attempt and its fallback both count. */
    fun cost(attempts: List<TokenUsage>): Double = attempts.sumOf(::cost)

    /** Prompt tokens beyond the text itself: the output schema and the API's own framing. */
    const val PROMPT_OVERHEAD_TOKENS = 1_500L

    /**
     * The most a request can cost when its usage is unknown: its prompt at 3 characters a token (text averages
     * nearer 4, so this overcounts) plus the overhead, and every output token it was allowed. Reserved before a
     * request is sent, and kept for one that was sent but never answered, which the server may still have billed.
     */
    fun upperBound(model: String, promptChars: Int, maxOutputTokens: Long, images: Int = 0, rates: Rates? = null): TokenUsage = TokenUsage(
        model,
        inputTokens = (promptChars + 2) / 3 + PROMPT_OVERHEAD_TOKENS + images * IMAGE_TOKENS,
        outputTokens = maxOutputTokens,
        rates = rates,
    )

    /** More than any provider counts for one photo downscaled to about 1500 px on its long edge. */
    const val IMAGE_TOKENS = 5_000L

    /** A free local server: the monthly cap can't count it, and Settings says so. */
    val FREE = Rates(input = 0.0, output = 0.0, cacheRead = 0.0)

    /** The prices typed for an OpenAI-compatible model, per million tokens. Cached input is charged as input, which never under-counts. */
    fun typed(inputUsd: Double, outputUsd: Double): Rates = Rates(input = inputUsd, output = outputUsd, cacheRead = inputUsd)
}
