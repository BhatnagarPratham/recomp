package app.recomp.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI-05's rules in `:domain` (TRD §6.2): the providers and their models, prices, the URL rules and the gate. */
class AiProvidersTest {
    private fun ok(text: String): AiUrl = (AiUrl.check(text) as AiUrl.Check.Ok).url
    private fun bad(text: String?): AiUrl.Problem = (AiUrl.check(text) as AiUrl.Check.Bad).problem

    @Test
    fun `https may go anywhere, and carries the key`() {
        val router = ok(" https://openrouter.ai/api/v1/ ")
        assertEquals("https://openrouter.ai/api/v1", router.base)
        assertEquals("openrouter.ai", router.host)
        assertTrue(router.https && router.sendsKey)
        assertFalse(router.local)

        // https to your own network is fine too, and still needs Android's permission.
        val lan = ok("https://192.168.1.20:8443/v1")
        assertTrue(lan.sendsKey && lan.localNetwork && lan.local)
        assertEquals("https://192.168.1.20:8443/v1", lan.base)
        assertEquals("https://example.com", ok("HTTPS://Example.COM").base)
    }

    @Test
    fun `plain http is only for this phone, a private-range address or a local name, and never carries the key`() {
        for (url in listOf("http://localhost:11434/v1", "http://127.0.0.1:11434/v1", "http://127.8.9.10/v1", "http://[::1]:8080/v1", "http://ollama.localhost/v1")) {
            val u = ok(url)
            assertTrue(url, u.loopback && u.local)
            assertFalse("this phone isn't the local network", u.localNetwork)
            assertFalse("no key over http: $url", u.sendsKey)
        }
        for (url in listOf(
            "http://10.0.0.5:1234/v1", "http://172.16.0.1/v1", "http://172.31.255.254/v1", "http://192.168.1.20:11434/v1",
            "http://169.254.10.10/v1", "http://100.64.0.1/v1", "http://100.127.255.1/v1", "http://desktop.local:11434/v1", "http://Desktop.LOCAL./v1",
            "http://[fd12:3456:789a::1]/v1", "http://[fe80::1]/v1", "http://[::ffff:192.168.1.20]/v1",
        )) {
            val u = ok(url)
            assertTrue(url, u.localNetwork && u.local)
            assertFalse(url, u.loopback)
            assertFalse("no key over http: $url", u.sendsKey)
        }
        for (url in listOf(
            "http://openrouter.ai/api/v1", "http://example.com/v1", "http://8.8.8.8/v1", "http://172.15.0.1/v1", "http://172.32.0.1/v1",
            "http://192.169.1.1/v1", "http://100.63.0.1/v1", "http://100.128.0.1/v1", "http://11.0.0.1/v1", "http://[2001:db8::1]/v1",
            // Names that only look local: decided from the text, with no lookup.
            "http://local/v1", "http://mylocal/v1", "http://example.local.com/v1", "http://localhost.example.com/v1", "http://0.0.0.0/v1",
        )) {
            assertEquals(url, AiUrl.Problem.HTTP_NOT_LOCAL, bad(url))
        }
        // Shorthand and odd spellings of an address are refused one way or the other, never read as local.
        for (url in listOf("http://127.1/v1", "http://2130706433/v1", "http://0x7f.0.0.1/v1", "http://999.1.1.1/v1", "http://192.168.1/v1", "http://127.0.0.1.example.com/v1")) {
            assertNull(url, AiUrl.ok(url))
        }
    }

    @Test
    fun `an address that isn't a base URL is refused, with the reason`() {
        assertEquals(AiUrl.Problem.EMPTY, bad(null))
        assertEquals(AiUrl.Problem.EMPTY, bad("   "))
        for (text in listOf("openrouter.ai/api/v1", "ftp://example.com/v1", "file:///etc/hosts", "https://", "not a url", "https:///v1", "javascript:alert(1)", "https://exa mple.com")) {
            assertEquals(text, AiUrl.Problem.NOT_A_URL, bad(text))
        }
        // A password in the URL would be stored and exported with the settings.
        assertEquals(AiUrl.Problem.CREDENTIALS, bad("https://user:secret@example.com/v1"))
        assertEquals(AiUrl.Problem.CREDENTIALS, bad("http://user@localhost:11434/v1"))
        assertEquals(AiUrl.Problem.QUERY, bad("https://example.com/v1?key=abc"))
        assertEquals(AiUrl.Problem.QUERY, bad("https://example.com/v1#frag"))
        assertNull(AiUrl.ok("http://example.com"))
        assertNotNull(AiUrl.ok("https://example.com"))
    }

    @Test
    fun `every listed model has a price, an owner and a default`() {
        for (model in AiModel.entries) {
            assertNotNull("${model.id} has no price", AiPricing.table[model.id])
            model.fallbackTargets.forEach { assertNotNull("$it has no price", AiPricing.table[it]) }
            if (model.provider != AiProviderId.ANTHROPIC) assertFalse("the refusal fallback is Anthropic's alone", model.serverFallback)
        }
        assertEquals(AiModel.entries.size, AiModel.entries.map { it.id }.toSet().size)
        assertEquals(AiModel.OPUS_5_5, AiModel.default(AiProviderId.ANTHROPIC))
        assertEquals(AiModel.GPT_6_1_SOL, AiModel.default(AiProviderId.OPENAI))
        assertEquals(AiModel.GEMINI_4_HYDROGEN, AiModel.default(AiProviderId.GEMINI))
        assertNull("its model is typed", AiModel.default(AiProviderId.COMPATIBLE))
        for (provider in listOf(AiProviderId.ANTHROPIC, AiProviderId.OPENAI, AiProviderId.GEMINI)) assertEquals(3, AiModel.of(provider).size)

        // A stored id belongs to its own provider; anything else is that provider's default.
        assertEquals(AiModel.GPT_6_LUNA, AiModel.of(AiProviderId.OPENAI, "gpt-6-luna"))
        assertEquals(AiModel.GPT_6_1_SOL, AiModel.of(AiProviderId.OPENAI, "claude-haiku-5-5"))
        assertEquals(AiModel.GEMINI_4_HYDROGEN, AiModel.of(AiProviderId.GEMINI, null))
        assertNull(AiModel.of(AiProviderId.COMPATIBLE, "anything"))
        assertEquals(AiProviderId.ANTHROPIC, AiProviderId.of(null))
        assertEquals(AiProviderId.ANTHROPIC, AiProviderId.of("RETIRED"))
        assertEquals(AiProviderId.GEMINI, AiProviderId.of("GEMINI"))
    }

    @Test
    fun `the new providers' prices are the ones their pricing pages list`() {
        assertEquals(Rates(2.00, 10.00, 0.10), AiPricing.rates("gpt-6.1-sol"))
        assertEquals(Rates(1.00, 5.00, 0.05), AiPricing.rates("gpt-6.1-terra"))
        assertEquals(Rates(0.10, 0.50, 0.01), AiPricing.rates("gpt-6-luna"))
        assertEquals(Rates(1.50, 7.50, 0.075), AiPricing.rates("gemini-4-hydrogen"))
        assertEquals(Rates(0.75, 3.75, 0.075), AiPricing.rates("gemini-3.8-flash"))
        assertEquals(Rates(0.30, 2.50, 0.03), AiPricing.rates("gemini-3.5-flash-lite"))
        // A model no table knows still costs the highest rates of any provider.
        assertEquals(Rates(10.00, 50.00, 0.50), AiPricing.rates("some-new-model"))
    }

    @Test
    fun `typed prices are used as typed, cached input at the input price, and zero is free`() {
        val typed = AiPricing.typed(inputUsd = 0.5, outputUsd = 1.5)
        val usage = TokenUsage("whatever-the-server-calls-it", inputTokens = 1_000_000, outputTokens = 1_000_000, cacheReadTokens = 1_000_000, rates = typed)
        assertEquals(0.5 + 1.5 + 0.5, AiPricing.cost(usage), 1e-12)
        // Even a name the table knows is priced as typed.
        assertEquals(2.5, AiPricing.cost(usage.copy(model = "claude-opus-5-5")), 1e-12)
        assertEquals(0.0, AiPricing.cost(usage.copy(rates = AiPricing.FREE)), 0.0)
        assertEquals(AiPricing.FREE, AiPricing.typed(0.0, 0.0))
    }

    @Test
    fun `each provider's upper bound covers its prompt, its photos and every output token it can bill`() {
        for (provider in AiProviderId.entries) {
            assertEquals(provider.name, MealRequest.MAX_OUTPUT_TOKENS, provider.outputBound)
            val target = AiTarget(provider, "dummy-key", AiModel.default(provider)?.id ?: "typed", rates = if (provider == AiProviderId.COMPATIBLE) AiPricing.typed(1.0, 2.0) else null)
            val text = MealRequest(target, "s".repeat(2_000), "u".repeat(1_000))
            assertEquals(1_000 + AiPricing.PROMPT_OVERHEAD_TOKENS, text.upperBound.inputTokens)
            assertEquals(provider.outputBound, text.upperBound.outputTokens)
            assertTrue(AiPricing.cost(text.upperBound) > 0)
            val photo = MealRequest(target, "s".repeat(2_000), "u".repeat(1_000), listOf(AiImage("image/jpeg", ByteArray(4)), AiImage("image/jpeg", ByteArray(4))))
            assertEquals(text.upperBound.inputTokens + 2 * AiPricing.IMAGE_TOKENS, photo.upperBound.inputTokens)
            assertFalse("the key isn't printed", target.toString().contains("dummy") || text.toString().contains("dummy"))
        }
        // The compatible provider's bound is priced as typed.
        val typed = MealRequest(AiTarget(AiProviderId.COMPATIBLE, null, "m", rates = AiPricing.typed(1.0, 2.0)), "", "")
        assertEquals((AiPricing.PROMPT_OVERHEAD_TOKENS * 1.0 + 8_000 * 2.0) / 1_000_000, AiPricing.cost(typed.upperBound), 1e-12)
    }

    @Test
    fun `the gate asks for the server before the key, and for the local network last`() {
        fun state(configured: Boolean = true, key: Boolean = true, spend: Double = 0.0, cap: Double? = null, online: Boolean = true, local: Boolean = true) =
            AiGate.state(true, key, spend, cap, online, configured, local)

        assertEquals(AiState.READY, state())
        assertEquals(AiState.NO_URL, state(configured = false, key = false, online = false, local = false))
        assertEquals(AiState.NO_KEY, state(key = false, online = false, local = false))
        assertEquals(AiState.OVER_CAP, state(spend = 5.0, cap = 5.0, online = false, local = false))
        assertEquals(AiState.OFFLINE, state(online = false, local = false))
        assertEquals(AiState.NO_LOCAL_NETWORK, state(local = false))
        assertEquals(AiState.OFF, AiGate.state(false, false, 0.0, null, false, false, false))
    }

    @Test
    fun `a notice is per provider and kind, and per server for a compatible one`() {
        val keys = listOf(
            AiNotice(AiProviderId.ANTHROPIC, AiKind.MEAL_TEXT),
            AiNotice(AiProviderId.OPENAI, AiKind.MEAL_TEXT),
            AiNotice(AiProviderId.OPENAI, AiKind.COACH),
            AiNotice(AiProviderId.COMPATIBLE, AiKind.MEAL_TEXT, "openrouter.ai"),
            AiNotice(AiProviderId.COMPATIBLE, AiKind.MEAL_TEXT, "192.168.1.20"),
        ).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals("ANTHROPIC:MEAL_TEXT", keys[0])
        assertEquals("COMPATIBLE/openrouter.ai:MEAL_TEXT", keys[3])
    }
}
