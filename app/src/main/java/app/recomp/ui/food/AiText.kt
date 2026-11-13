package app.recomp.ui.food

import app.recomp.data.repo.AiGateView
import app.recomp.domain.ai.AiModel
import app.recomp.data.prefs.AiSettings
import app.recomp.domain.ai.AiPricing
import app.recomp.domain.ai.AiProviderId
import app.recomp.domain.ai.AiUrl
import app.recomp.domain.ai.Rates
import app.recomp.domain.ai.AiState
import app.recomp.domain.ai.AiStatus
import app.recomp.domain.ai.Confidence
import app.recomp.domain.ai.TokenUsage
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** User-facing text for AI logging (PRD §7.7 Describe it, §7.12, §12.3). Every message names the provider in use. */
object AiText {
    /**
     * Why an estimate failed, as TRD §6.2's table words it, naming [provider] ("Claude", "OpenAI", "Gemini", "The AI
     * server"). With a [photo], a refused request adds that the model may not read images (PRD §12.3).
     */
    fun reason(status: AiStatus, provider: AiProviderId = AiProviderId.ANTHROPIC, photo: Boolean = false): String {
        val name = provider.service
        return when (status) {
            AiStatus.OK -> "Estimated"
            AiStatus.PENDING -> "Still waiting for ${lower(name)}"
            AiStatus.REFUSAL -> "$name declined to estimate this"
            AiStatus.MAX_TOKENS -> "The estimate was cut off"
            AiStatus.INVALID -> "${possessive(name)} reply couldn't be read"
            AiStatus.AUTH -> "Your ${keyName(provider)} was rejected. Check it in Settings → AI"
            AiStatus.BILLING -> if (provider == AiProviderId.COMPATIBLE) "Your account with the AI server is out of credit" else "Your ${provider.account} account is out of credit"
            AiStatus.RATE_LIMIT -> "$name is busy right now"
            AiStatus.SERVER -> "$name is unavailable right now"
            AiStatus.BAD_REQUEST -> if (photo) "$name couldn't take this request. The model may not read photos" else "$name couldn't take this request"
            AiStatus.TIMEOUT -> "$name didn't answer in time"
            AiStatus.NETWORK -> "Couldn't reach ${lower(name)}"
            AiStatus.UNEXPECTED -> "Something went wrong sending it"
        }
    }

    /**
     * Why a draft is a draft, on the Food tab. A draft keeps its failed request's status but not which provider
     * it went to, and it may be read days later with another provider in use, so this names none and is in the
     * past tense.
     */
    fun draftReason(status: AiStatus): String = when (status) {
        AiStatus.OK -> "Estimated"
        AiStatus.PENDING -> "The estimate never finished"
        AiStatus.REFUSAL -> "The AI declined to estimate this"
        AiStatus.MAX_TOKENS -> "The estimate was cut off"
        AiStatus.INVALID -> "The AI's reply couldn't be read"
        AiStatus.AUTH -> "The API key was rejected. Check it in Settings → AI"
        AiStatus.BILLING -> "The AI account was out of credit"
        AiStatus.RATE_LIMIT -> "The AI service was busy"
        AiStatus.SERVER -> "The AI service was unavailable"
        AiStatus.BAD_REQUEST -> "The AI service couldn't take the request"
        AiStatus.TIMEOUT -> "The AI service didn't answer in time"
        AiStatus.NETWORK -> "The AI service couldn't be reached"
        AiStatus.UNEXPECTED -> "Something went wrong sending it"
    }

    /** "The AI server" mid-sentence. */
    private fun lower(name: String) = if (name.startsWith("The ")) "the" + name.drop(3) else name

    private fun possessive(name: String) = "$name's"

    /** "Claude API key", "OpenAI API key", "Gemini API key", "API key" for a server of your own. */
    fun keyName(provider: AiProviderId): String = if (provider == AiProviderId.COMPATIBLE) "API key" else "${provider.service} API key"

    /** Why Describe it can't estimate right now, or null when it can (PRD §7.7 *Not ready*). */
    fun notReady(gate: AiGateView?): String? = when (gate?.state) {
        null, AiState.READY, AiState.OFF -> null
        AiState.NO_KEY -> "Add your ${keyName(gate.provider)} to estimate meals. Until then you can keep this as a draft."
        AiState.NO_URL -> "Set the server's address and model in Settings → AI to estimate meals. Until then you can keep this as a draft."
        AiState.OFFLINE -> "You're offline. Keep this as a draft and estimate it later; search, My meals and Quick add work offline."
        AiState.NO_LOCAL_NETWORK -> LOCAL_NETWORK_REFUSED + " Until then you can keep this as a draft."
        AiState.OVER_CAP ->
            "AI is paused until ${day(gate.resumesOn)}: this month's ${usd(gate.capUsd ?: 0.0)} cap is reached. Keep this as a draft for later."
    }

    /** Android's local-network permission was refused: said outright, since the request itself would only time out. */
    const val LOCAL_NETWORK_REFUSED =
        "Your AI server is on your local network, and Recomp isn't allowed to reach devices there. Allow it (Android lists it " +
            "under Nearby devices) to estimate meals."

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

    /** A rough cost per estimate, at a typical 2,000 tokens in and 1,000 out. */
    private fun perEstimate(usage: TokenUsage): String {
        val cents = AiPricing.cost(usage) * 100
        return when {
            cents == 0.0 -> "free"
            cents < 0.1 -> "under 0.1¢ an estimate"
            cents < 1 -> String.format(Locale.ROOT, "about %.1f¢ an estimate", cents)
            else -> String.format(Locale.ROOT, "about %.0f¢ an estimate", cents)
        }
    }

    /** A listed model's rough cost per estimate, from the price table. */
    fun perEstimate(model: AiModel): String = perEstimate(TokenUsage(model.id, inputTokens = 2_000, outputTokens = 1_000))

    /** The same for an OpenAI-compatible model, from the prices you typed. */
    fun perEstimate(rates: Rates): String = perEstimate(TokenUsage("", inputTokens = 2_000, outputTokens = 1_000, rates = rates))

    fun model(model: AiModel) = "${model.label} · ${perEstimate(model)}"

    /** What the OpenAI-compatible provider's row shows: the model and the server, or what's missing. */
    fun compatible(s: AiSettings): String = when {
        s.baseUrl.isBlank() -> "Not set"
        s.url == null -> "Check the address"
        s.compatibleModel.isBlank() -> "No model yet"
        else -> "${s.compatibleModel} · ${s.url?.host}"
    }

    /** Why a typed base URL can't be used (AI-05's URL rules). */
    fun urlProblem(problem: AiUrl.Problem): String = when (problem) {
        AiUrl.Problem.EMPTY -> "Type the server's address, e.g. https://openrouter.ai/api/v1"
        AiUrl.Problem.NOT_A_URL -> "That isn't an address Recomp can use. It starts with https:// (or http:// for a server on your network)."
        AiUrl.Problem.HTTP_NOT_LOCAL -> "Plain http is only for this phone or your own network (localhost, a private address or a .local name). Use https for anything else."
        AiUrl.Problem.CREDENTIALS -> "Leave the user name and password out of the address. The key goes in API key."
        AiUrl.Problem.QUERY -> "Leave out anything after ? or #. The address ends where /chat/completions would be added, usually at /v1."
    }

    private fun day(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("d MMMM"))

    /**
     * What a meal description sends and to whom (TRD §6.2): shown before the first estimate with each provider, and
     * in Settings. [host] is an OpenAI-compatible server's.
     */
    fun privacy(provider: AiProviderId, host: String? = null): String {
        val to = when (provider) {
            AiProviderId.ANTHROPIC -> "Anthropic"
            AiProviderId.OPENAI -> "OpenAI"
            AiProviderId.GEMINI -> "Google"
            AiProviderId.COMPATIBLE -> if (host != null) "the server at $host" else "the server you set"
        }
        val sends = "Describe it sends your description and its origin to $to, with your diet and food region, your portion " +
            "sizes and your most-used own foods (names and values), so the estimate fits how you eat. It never sends your " +
            "name, body data, targets or food log."
        val after = when (provider) {
            AiProviderId.ANTHROPIC -> " Requests use your API key and are billed to your Anthropic account."
            AiProviderId.OPENAI -> " Requests use your API key and are billed to your OpenAI account."
            AiProviderId.GEMINI -> " Requests use your API key. On Google's free tier, Google may use what you send to improve its " +
                "products and reviewers may read it; on the paid tier it doesn't."
            AiProviderId.COMPATIBLE -> " What happens to it there is up to whoever runs that server."
        }
        return sends + after
    }

    fun privacy(s: AiSettings): String = privacy(s.provider, s.url?.host)

    /** Where the key comes from, per provider. Every key is stored the same way. */
    fun keyHelp(provider: AiProviderId): String = when (provider) {
        AiProviderId.ANTHROPIC -> "In the Claude Console (platform.claude.com), create a workspace for this app with a spend limit, then an API key in it, and paste it here."
        AiProviderId.OPENAI -> "In the OpenAI platform (platform.openai.com), create a project for this app with a budget, then an API key in it, and paste it here."
        AiProviderId.GEMINI -> "In Google AI Studio (aistudio.google.com), create an API key and paste it here."
        AiProviderId.COMPATIBLE -> "Paste the key your server or service gave you (OpenRouter: openrouter.ai/keys). It's only ever sent over https."
    } + " It's stored encrypted on this phone, never shown again, and never exported or backed up. Your other providers' keys are kept."

    /** The second guard's note under the cap, naming where the hard stop is. */
    fun capNote(provider: AiProviderId): String = when (provider) {
        AiProviderId.ANTHROPIC -> "The Console workspace's spend limit is the hard stop; the cap here is a second guard."
        AiProviderId.OPENAI -> "Your OpenAI project's budget is the hard stop; the cap here is a second guard."
        AiProviderId.GEMINI -> "Your Google billing limits are the hard stop; the cap here is a second guard."
        AiProviderId.COMPATIBLE -> "Any limit your service has is the hard stop; the cap here is a second guard."
    } + " One cap counts every provider's requests together."

    const val FREE_NOTE = "The prices are 0, so this server's requests count as free and the monthly cap can't count them."
}
