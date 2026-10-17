package app.recomp.domain.ai

import java.time.LocalDate

/** Whether AI can be used right now (AI-01 to AI-03, TRD §6.2). Only [READY] lets a request leave the phone. */
enum class AiState {
    /** AI is switched off: AI buttons are hidden. */
    OFF,

    /** AI is on but no API key is stored yet. */
    NO_KEY,

    /** This month's spend has reached the monthly cap; AI resumes on the 1st. */
    OVER_CAP,

    /** No validated internet connection. */
    OFFLINE,

    READY,
}

/**
 * The models Settings offers (TRD §6.2). [fallbackTargets] are the models the API's server-side refusal fallback
 * (`fallbacks: "default"`) can retry a refused request on; each needs a price in `AiPricing`. Empty means the model
 * has no server-side fallback, so its requests don't ask for one.
 */
enum class AiModel(val id: String, val label: String, val fallbackTargets: List<String>) {
    OPUS_5_5("claude-opus-5-5", "Claude Opus 5.5", listOf("claude-opus-5", "claude-opus-4-8")),
    SONNET_5_5("claude-sonnet-5-5", "Claude Sonnet 5.5", listOf("claude-sonnet-5")),
    HAIKU_5_5("claude-haiku-5-5", "Claude Haiku 5.5", emptyList()),
    ;

    val serverFallback: Boolean get() = fallbackTargets.isNotEmpty()

    companion object {
        val DEFAULT = OPUS_5_5

        /** A stored model id, or the default when it's missing or no longer offered. */
        fun of(id: String?): AiModel = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** What an AI request was for (TRD §4, `ai_request_log.kind`). M3c sends only [MEAL_TEXT]. */
enum class AiKind { MEAL_TEXT, MEAL_PHOTO, LABEL, INBODY, FIT_CHECK, COACH }

object AiGate {
    /** The first rule that applies wins: off, then no key, then the cap, then the connection. */
    fun state(enabled: Boolean, hasKey: Boolean, monthSpendUsd: Double, monthlyCapUsd: Double?, online: Boolean): AiState = when {
        !enabled -> AiState.OFF
        !hasKey -> AiState.NO_KEY
        monthlyCapUsd != null && monthSpendUsd >= monthlyCapUsd -> AiState.OVER_CAP
        !online -> AiState.OFFLINE
        else -> AiState.READY
    }

    /** Spend counts toward the local calendar month. */
    fun monthStart(today: LocalDate): LocalDate = today.withDayOfMonth(1)

    /** The day a reached cap lifts: the 1st of next month. */
    fun resumesOn(today: LocalDate): LocalDate = monthStart(today).plusMonths(1)
}
