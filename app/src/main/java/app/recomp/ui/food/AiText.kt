package app.recomp.ui.food

import app.recomp.data.repo.AiGateView
import app.recomp.domain.ai.AiModel
import app.recomp.domain.ai.AiPricing
import app.recomp.domain.ai.AiState
import app.recomp.domain.ai.AiStatus
import app.recomp.domain.ai.Confidence
import app.recomp.domain.ai.TokenUsage
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** User-facing text for AI logging (PRD §7.7 Describe it, §7.12). */
object AiText {
    /** Why an estimate failed, as TRD §6.2's table words it. */
    fun reason(status: AiStatus): String = when (status) {
        AiStatus.OK -> "Estimated"
        AiStatus.REFUSAL -> "Claude declined to estimate this"
        AiStatus.MAX_TOKENS -> "The estimate was cut off"
        AiStatus.INVALID -> "Claude's reply couldn't be read"
        AiStatus.AUTH -> "Your API key was rejected. Check it in Settings → AI"
        AiStatus.BILLING -> "Your Anthropic account is out of credit"
        AiStatus.RATE_LIMIT -> "Claude is busy right now"
        AiStatus.SERVER -> "Claude is unavailable right now"
        AiStatus.BAD_REQUEST -> "Claude couldn't take this request"
        AiStatus.TIMEOUT -> "Claude didn't answer in time"
        AiStatus.NETWORK -> "Couldn't reach Claude"
    }

    /** Why Describe it can't estimate right now, or null when it can (PRD §7.7 *Not ready*). */
    fun notReady(gate: AiGateView?): String? = when (gate?.state) {
        null, AiState.READY, AiState.OFF -> null
        AiState.NO_KEY -> "Add your Claude API key to estimate meals. Until then you can keep this as a draft."
        AiState.OFFLINE -> "You're offline. Keep this as a draft and estimate it later; search, My meals and Quick add work offline."
        AiState.OVER_CAP ->
            "AI is paused until ${day(gate.resumesOn)}: this month's ${usd(gate.capUsd ?: 0.0)} cap is reached. Keep this as a draft for later."
    }

    fun confidence(c: Confidence) = when (c) {
        Confidence.HIGH -> "high confidence"
        Confidence.MEDIUM -> "medium confidence"
        Confidence.LOW -> "low confidence"
    }

    /** "$0.42", or "$0.003" below a cent, so small spends don't read as nothing. */
    fun usd(value: Double): String = when {
        value == 0.0 -> "$0.00"
        value < 0.01 -> String.format(Locale.ROOT, "$%.3f", value)
        else -> String.format(Locale.ROOT, "$%.2f", value)
    }

    /** A model's rough cost per estimate, from the price table at a typical 2,000 tokens in and 1,000 out. */
    fun perEstimate(model: AiModel): String {
        val cost = AiPricing.cost(TokenUsage(model.id, inputTokens = 2_000, outputTokens = 1_000))
        val cents = cost * 100
        return when {
            cents < 0.1 -> "under 0.1¢ an estimate"
            cents < 1 -> String.format(Locale.ROOT, "about %.1f¢ an estimate", cents)
            else -> String.format(Locale.ROOT, "about %.0f¢ an estimate", cents)
        }
    }

    fun model(model: AiModel) = "${model.label} · ${perEstimate(model)}"

    private fun day(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("d MMMM"))

    const val PRIVACY =
        "Describe it sends your description and its origin to Anthropic, with your diet and food region, your portion " +
            "sizes and your most-used own foods (names and values), so the estimate fits how you eat. It never sends your " +
            "name, body data, targets or food log. Requests use your API key and are billed to your Anthropic account."

    const val KEY_HELP =
        "In the Claude Console (platform.claude.com), create a workspace for this app with a spend limit, then an API key " +
            "in it, and paste it here. It's stored encrypted on this phone, never shown again, and never exported or backed up."
}
