package app.recomp.data

import app.recomp.data.prefs.AiSettings
import app.recomp.data.prefs.SettingsKeys
import app.recomp.data.repo.AiGateView
import app.recomp.domain.ai.AiProviderId
import app.recomp.domain.ai.AiState
import app.recomp.domain.ai.AiStatus
import app.recomp.domain.ai.AiUrl
import app.recomp.ui.food.AiText
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI-05's wording (PRD §12.3): every message names the provider in use, and the app declares what a local server needs. */
class AiTextTest {
    @Test
    fun `the status messages name the provider in use`() {
        assertEquals("Your Anthropic account is out of credit", AiText.reason(AiStatus.BILLING))
        assertEquals("Your OpenAI account is out of credit", AiText.reason(AiStatus.BILLING, AiProviderId.OPENAI))
        assertEquals("Your Google account is out of credit", AiText.reason(AiStatus.BILLING, AiProviderId.GEMINI))
        assertEquals("Claude is busy right now", AiText.reason(AiStatus.RATE_LIMIT))
        assertEquals("OpenAI is busy right now", AiText.reason(AiStatus.RATE_LIMIT, AiProviderId.OPENAI))
        assertEquals("Gemini didn't answer in time", AiText.reason(AiStatus.TIMEOUT, AiProviderId.GEMINI))
        assertEquals("Couldn't reach the AI server", AiText.reason(AiStatus.NETWORK, AiProviderId.COMPATIBLE))
        assertEquals("The AI server's reply couldn't be read", AiText.reason(AiStatus.INVALID, AiProviderId.COMPATIBLE))
        assertEquals("Your Gemini API key was rejected. Check it in Settings → AI", AiText.reason(AiStatus.AUTH, AiProviderId.GEMINI))
        // Only Claude's messages say Claude.
        for (provider in AiProviderId.entries.filter { it != AiProviderId.ANTHROPIC }) {
            for (status in AiStatus.entries) {
                val text = AiText.reason(status, provider)
                assertFalse("$provider $status: $text", text.contains("Claude") || text.contains("Anthropic"))
            }
        }
        // A model that can't read images says so when you send a photo.
        assertTrue(AiText.reason(AiStatus.BAD_REQUEST, AiProviderId.COMPATIBLE, photo = true).contains("may not read photos"))
        assertFalse(AiText.reason(AiStatus.BAD_REQUEST, AiProviderId.COMPATIBLE).contains("photos"))
    }

    @Test
    fun `not ready says which key or what's missing, and a refused permission gets its own message`() {
        fun gate(state: AiState, provider: AiProviderId) = AiGateView(state, null, LocalDate.of(2026, 12, 1), provider)
        assertTrue(AiText.notReady(gate(AiState.NO_KEY, AiProviderId.ANTHROPIC))!!.startsWith("Add your Claude API key"))
        assertTrue(AiText.notReady(gate(AiState.NO_KEY, AiProviderId.OPENAI))!!.startsWith("Add your OpenAI API key"))
        assertTrue(AiText.notReady(gate(AiState.NO_URL, AiProviderId.COMPATIBLE))!!.contains("address and model"))
        val refused = AiText.notReady(gate(AiState.NO_LOCAL_NETWORK, AiProviderId.COMPATIBLE))!!
        assertTrue(refused.contains("local network") && refused.contains("isn't allowed"))
        assertFalse("not worded as a timeout or as being offline", refused.contains("answer in time") || refused.contains("offline"))
        assertNull(AiText.notReady(gate(AiState.READY, AiProviderId.GEMINI)))
    }

    @Test
    fun `the privacy notice names who the description goes to`() {
        assertTrue(AiText.privacy(AiProviderId.ANTHROPIC).contains("to Anthropic,"))
        assertTrue(AiText.privacy(AiProviderId.OPENAI).contains("to OpenAI,"))
        val google = AiText.privacy(AiProviderId.GEMINI)
        assertTrue(google.contains("to Google,") && google.contains("free tier"))
        assertTrue(AiText.privacy(AiProviderId.COMPATIBLE, "openrouter.ai").contains("to the server at openrouter.ai,"))
        val settings = AiSettings(provider = AiProviderId.COMPATIBLE, baseUrl = "http://192.168.1.20:11434/v1", compatibleModel = "m")
        assertTrue(AiText.privacy(settings).contains("192.168.1.20"))
        assertEquals("m · 192.168.1.20", AiText.compatible(settings))
        assertEquals("Check the address", AiText.compatible(settings.copy(baseUrl = "http://example.com/v1")))
        assertEquals("free", AiText.perEstimate(settings.typedRates!!))
        for (provider in AiProviderId.entries) assertTrue(AiText.keyHelp(provider).contains("never exported"))
        for (problem in AiUrl.Problem.entries) assertTrue(AiText.urlProblem(problem).isNotBlank())
    }

    @Test
    fun `no AI setting's name looks like a secret, so each is exported, and the keys aren't settings`() {
        val names = listOf(
            SettingsKeys.AI_PROVIDER, SettingsKeys.AI_MODEL, SettingsKeys.AI_MODEL_OPENAI, SettingsKeys.AI_MODEL_GEMINI, SettingsKeys.AI_MODEL_COMPATIBLE,
            SettingsKeys.AI_BASE_URL, SettingsKeys.AI_COMPATIBLE_INPUT_USD, SettingsKeys.AI_COMPATIBLE_OUTPUT_USD, SettingsKeys.AI_NOTICES_SEEN,
        ).map { it.name }
        names.forEach { assertTrue(it, SettingsKeys.exported(it)) }
        // The key store's own entry names would never be exported even if one landed in settings.
        AiProviderId.entries.forEach { assertFalse(SettingsKeys.exported(app.recomp.data.ai.AiKeyStore.name(it))) }
    }

    @Test
    fun `the manifest declares the local-network permission and the config that lets a local server be reached`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.ACCESS_LOCAL_NETWORK"))
        assertEquals("android.permission.ACCESS_LOCAL_NETWORK", app.recomp.data.ai.LocalNetwork.PERMISSION)
        // The permission only exists from Android 17: an older phone is never told it was refused.
        assertTrue(app.recomp.data.ai.LocalNetwork.allowed(sdkInt = 35, granted = false))
        assertTrue(app.recomp.data.ai.LocalNetwork.allowed(sdkInt = 36, granted = false))
        assertFalse(app.recomp.data.ai.LocalNetwork.allowed(sdkInt = 37, granted = false))
        assertTrue(app.recomp.data.ai.LocalNetwork.allowed(sdkInt = 37, granted = true))
        assertTrue(manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        assertTrue(File("src/main/res/xml/network_security_config.xml").readText().contains("cleartextTrafficPermitted=\"true\""))
        // Backups still list only the database and the settings: no key file is ever included.
        val rules = File("src/main/res/xml/data_extraction_rules.xml").readText()
        assertFalse(Regex("<include[^>]*secrets").containsMatchIn(rules))
    }
}
