package app.recomp.domain.ai

/** Dollars per million tokens. Cache writes (5-minute TTL) cost 1.25 × the input rate. */
data class Rates(val input: Double, val output: Double, val cacheRead: Double) {
    val cacheWrite: Double get() = input * 1.25
}

/** One attempt's tokens, as `usage` (or one `usage.iterations` entry) reports them. [model] is the model that ran it. */
data class TokenUsage(
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
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
        // Refusal-fallback targets (`fallbacks: "default"`).
        "claude-opus-5" to Rates(input = 5.00, output = 25.00, cacheRead = 0.50),
        "claude-opus-4-8" to Rates(input = 5.00, output = 25.00, cacheRead = 0.50),
        "claude-fable-5-1" to Rates(input = 10.00, output = 50.00, cacheRead = 0.25),
    )

    private val highest = Rates(
        input = table.values.maxOf { it.input },
        output = table.values.maxOf { it.output },
        cacheRead = table.values.maxOf { it.cacheRead },
    )

    fun rates(model: String): Rates = table[model] ?: highest

    fun cost(usage: TokenUsage): Double {
        val r = rates(usage.model)
        return (usage.inputTokens * r.input + usage.outputTokens * r.output +
            usage.cacheReadTokens * r.cacheRead + usage.cacheWriteTokens * r.cacheWrite) / 1_000_000.0
    }

    /** Every attempt of one request, each at its own model's rates: a declined attempt and its fallback both count. */
    fun cost(attempts: List<TokenUsage>): Double = attempts.sumOf(::cost)
}
