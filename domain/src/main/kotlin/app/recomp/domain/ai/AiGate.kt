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

    /** The OpenAI-compatible provider has no usable base URL or model name yet (AI-05). */
    NO_URL,

    /** The server is on the local network and Android's local-network permission was refused (AI-05). */
    NO_LOCAL_NETWORK,

    READY,
}

/**
 * Where AI requests go (AI-05, PRD §12.3). The name is stored in `ai_provider` and `ai_request_log.provider`.
 * [service] is what the status messages call it ("Claude is busy right now"), [account] whose bill it is
 * ("Your OpenAI account is out of credit"), and [outputBound] the most output tokens one request can be billed
 * for, which the cap's reservation uses.
 */
enum class AiProviderId(val label: String, val service: String, val account: String, val outputBound: Long) {
    ANTHROPIC("Anthropic", "Claude", "Anthropic", 8_000),

    /** `max_completion_tokens` covers reasoning and the reply together. */
    OPENAI("OpenAI", "OpenAI", "OpenAI", 8_000),

    /** `maxOutputTokens` covers thinking and the reply together. */
    GEMINI("Google Gemini", "Gemini", "Google", 8_000),

    /** OpenRouter, or a server on your own network such as Ollama: a base URL, a typed model and typed prices. */
    COMPATIBLE("OpenAI-compatible", "The AI server", "provider", 8_000),
    ;

    companion object {
        val DEFAULT = ANTHROPIC

        /** A stored name, or the default when it's missing or unknown. */
        fun of(name: String?): AiProviderId = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * The models Settings offers for each provider (TRD §6.2); the OpenAI-compatible provider's model is typed
 * instead. Each needs a price in `AiPricing`. [fallbackTargets] are the models Anthropic's server-side refusal
 * fallback (`fallbacks: "default"`) can retry a refused request on; empty means the model has no server-side
 * fallback, so its requests don't ask for one. It is Anthropic's alone.
 */
enum class AiModel(val provider: AiProviderId, val id: String, val label: String, val fallbackTargets: List<String> = emptyList()) {
    OPUS_5_5(AiProviderId.ANTHROPIC, "claude-opus-5-5", "Claude Opus 5.5", listOf("claude-opus-5", "claude-opus-4-8")),
    SONNET_5_5(AiProviderId.ANTHROPIC, "claude-sonnet-5-5", "Claude Sonnet 5.5", listOf("claude-sonnet-5")),
    HAIKU_5_5(AiProviderId.ANTHROPIC, "claude-haiku-5-5", "Claude Haiku 5.5"),

    // OpenAI's GPT-6 line (developers.openai.com/api/docs/models, read 2026-11-13). All read images.
    GPT_6_1_SOL(AiProviderId.OPENAI, "gpt-6.1-sol", "GPT-6.1 Sol"),
    GPT_6_1_TERRA(AiProviderId.OPENAI, "gpt-6.1-terra", "GPT-6.1 Terra"),
    GPT_6_LUNA(AiProviderId.OPENAI, "gpt-6-luna", "GPT-6 Luna"),

    // Gemini (aistudio.google.com/docs/models, read 2026-11-13). All read images and have a free tier.
    GEMINI_4_HYDROGEN(AiProviderId.GEMINI, "gemini-4-hydrogen", "Gemini 4 Hydrogen"),
    GEMINI_3_8_FLASH(AiProviderId.GEMINI, "gemini-3.8-flash", "Gemini 3.8 Flash"),
    GEMINI_3_5_FLASH_LITE(AiProviderId.GEMINI, "gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite"),
    ;

    val serverFallback: Boolean get() = fallbackTargets.isNotEmpty()

    companion object {
        val DEFAULT = OPUS_5_5

        /** The provider's models, its default first. Empty for the OpenAI-compatible provider. */
        fun of(provider: AiProviderId): List<AiModel> = entries.filter { it.provider == provider }

        fun default(provider: AiProviderId): AiModel? = of(provider).firstOrNull()

        /** A stored model id for [provider], or its default when the id is missing or no longer offered. */
        fun of(provider: AiProviderId, id: String?): AiModel? = of(provider).firstOrNull { it.id == id } ?: default(provider)

        /** An Anthropic model id, or the default. */
        fun of(id: String?): AiModel = of(AiProviderId.ANTHROPIC, id) ?: DEFAULT
    }
}

/** What an AI request was for (TRD §4, `ai_request_log.kind`). M3c sends only [MEAL_TEXT]. */
enum class AiKind { MEAL_TEXT, MEAL_PHOTO, LABEL, INBODY, FIT_CHECK, COACH }

/**
 * One privacy notice: what a kind of request sends is shown before its first send to each provider (TRD §6.2),
 * and again after you change provider. For the OpenAI-compatible provider [host] is the server, so pointing the
 * app at another server shows the notice again too.
 */
data class AiNotice(val provider: AiProviderId, val kind: AiKind, val host: String? = null) {
    /** As stored in `ai_notices_seen`. */
    val key: String get() = if (host == null) "${provider.name}:${kind.name}" else "${provider.name}/$host:${kind.name}"
}

object AiGate {
    /**
     * The first rule that applies wins: off, then the provider's setup (its URL and model, then its key), then the
     * cap, then the connection, then the local-network permission. [hasKey] is true for a provider that needs no
     * key (a server reached over plain http is never sent one), and [online] for a local server, which needs no
     * internet.
     */
    fun state(
        enabled: Boolean,
        hasKey: Boolean,
        monthSpendUsd: Double,
        monthlyCapUsd: Double?,
        online: Boolean,
        configured: Boolean = true,
        localNetworkAllowed: Boolean = true,
    ): AiState = when {
        !enabled -> AiState.OFF
        !configured -> AiState.NO_URL
        !hasKey -> AiState.NO_KEY
        monthlyCapUsd != null && monthSpendUsd >= monthlyCapUsd -> AiState.OVER_CAP
        !online -> AiState.OFFLINE
        !localNetworkAllowed -> AiState.NO_LOCAL_NETWORK
        else -> AiState.READY
    }

    /** Spend counts toward the local calendar month. */
    fun monthStart(today: LocalDate): LocalDate = today.withDayOfMonth(1)

    /** The day a reached cap lifts: the 1st of next month. */
    fun resumesOn(today: LocalDate): LocalDate = monthStart(today).plusMonths(1)
}
