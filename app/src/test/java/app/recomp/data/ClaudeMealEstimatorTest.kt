package app.recomp.data

import app.recomp.data.ai.ClaudeMealEstimator
import app.recomp.domain.ai.AiModel
import app.recomp.domain.ai.AiStatus
import app.recomp.domain.ai.Confidence
import app.recomp.domain.ai.MealRequest
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Claude client against a local server (TRD §6.2, §8): the request it builds, how each reply and error maps
 * to a status, the one retry and the timeout. No real key or network is involved; the values are invented.
 */
class ClaudeMealEstimatorTest {
    private lateinit var server: MockWebServer
    private val estimator = ClaudeMealEstimator()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start(java.net.InetAddress.getByName("127.0.0.1"), 0) }
        estimator.baseUrl = "http://127.0.0.1:${server.port}"
        estimator.timeout = Duration.ofSeconds(2)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun request(model: AiModel = AiModel.OPUS_5_5) = MealRequest("test-key", model, "SYSTEM PROMPT", "Origin: Home\nMeal: 2 phulka")

    private val reply = """
        {"items":[
          {"name":"Phulka","quantity":2,"unit":"piece","grams":70,"kcal":210,"protein_g":6.5,"carbs_g":42,"fat_g":1.5,"fiber_g":4,"confidence":"high","assumptions":"no ghee"},
          {"name":"Dal","quantity":1,"unit":"katori","grams":150,"kcal":170,"protein_g":9,"carbs_g":24,"fat_g":4,"fiber_g":5,"confidence":"Medium","assumptions":"","extra":"ignored"}
        ],"clarifying_question":"Was the dal tempered with ghee?"}
    """.trimIndent()

    private fun message(
        text: String? = reply,
        stopReason: String = "end_turn",
        model: String = "claude-opus-5-5",
        usage: String = """{"input_tokens":1200,"output_tokens":300,"cache_read_input_tokens":100,"cache_creation_input_tokens":50}""",
        extra: String = "",
    ): MockResponse {
        val content = if (text == null) "[]" else """[{"type":"thinking","thinking":"","signature":"sig"},{"type":"text","text":${Json.encodeToString(text)}}]"""
        return MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"id":"msg_1","type":"message","role":"assistant","model":"$model","content":$content,"stop_reason":"$stopReason","stop_sequence":null,"usage":$usage$extra}""",
        )
    }

    private fun error(code: Int, type: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json")
        .setHeader("retry-after-ms", "10")
        .setBody("""{"type":"error","error":{"type":"$type","message":"invented"}}""")

    @Test
    fun `a reply is parsed into items, and its tokens are counted`() = runBlocking {
        server.enqueue(message())
        val r = estimator.estimate(request())
        assertEquals(AiStatus.OK, r.status)
        val items = r.estimate!!.items
        assertEquals(listOf("Phulka", "Dal"), items.map { it.name })
        assertEquals(70.0, items[0].grams, 0.0)
        assertEquals(6.5, items[0].proteinG, 0.0)
        assertEquals(Confidence.HIGH, items[0].confidence)
        assertEquals(Confidence.MEDIUM, items[1].confidence)
        assertEquals("Was the dal tempered with ghee?", r.estimate!!.clarifyingQuestion)
        assertEquals("claude-opus-5-5", r.servedBy)
        assertEquals(1200L, r.attempts.single().inputTokens)
        assertEquals(100L, r.attempts.single().cacheReadTokens)
        assertEquals(50L, r.attempts.single().cacheWriteTokens)
        assertTrue(r.responseText!!.contains("Phulka"))
    }

    @Test
    fun `the request has the hand-written schema, low effort, a cached system prompt and the refusal fallback`() = runBlocking {
        server.enqueue(message())
        estimator.estimate(request())
        val recorded = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/messages", recorded.requestUrl!!.encodedPath)
        assertEquals("test-key", recorded.getHeader("x-api-key"))
        assertTrue(recorded.getHeader("anthropic-beta")!!.contains(ClaudeMealEstimator.FALLBACK_BETA))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject

        assertEquals("claude-opus-5-5", body.getValue("model").jsonPrimitive.content)
        assertEquals(ClaudeMealEstimator.MAX_TOKENS.toString(), body.getValue("max_tokens").jsonPrimitive.content)
        assertNull("thinking is the model's adaptive default", body["thinking"])
        assertEquals("default", body.getValue("fallbacks").jsonPrimitive.content)

        val system = body.getValue("system").jsonArray.single().jsonObject
        assertEquals("SYSTEM PROMPT", system.getValue("text").jsonPrimitive.content)
        assertEquals("ephemeral", system.getValue("cache_control").jsonObject.getValue("type").jsonPrimitive.content)

        val output = body.getValue("output_config").jsonObject
        assertEquals("low", output.getValue("effort").jsonPrimitive.content)
        val format = output.getValue("format").jsonObject
        assertEquals("json_schema", format.getValue("type").jsonPrimitive.content)
        val schema = format.getValue("schema").jsonObject
        assertEquals("false", schema.getValue("additionalProperties").jsonPrimitive.content)
        val item = schema.getValue("properties").jsonObject.getValue("items").jsonObject.getValue("items").jsonObject
        assertEquals(
            listOf("name", "quantity", "unit", "grams", "kcal", "protein_g", "carbs_g", "fat_g", "fiber_g", "confidence", "assumptions"),
            item.getValue("required").jsonArray.map { it.jsonPrimitive.content },
        )

        val user = body.getValue("messages").jsonArray.single().jsonObject
        assertEquals("user", user.getValue("role").jsonPrimitive.content)
        assertTrue(user.toString().contains("2 phulka"))
    }

    @Test
    fun `Haiku has no server-side fallback, so its request doesn't ask for one`() = runBlocking {
        server.enqueue(message(model = "claude-haiku-5-5"))
        estimator.estimate(request(AiModel.HAIKU_5_5))
        val recorded = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertFalse(recorded.getHeader("anthropic-beta").orEmpty().contains("server-side-fallback"))
        assertNull(Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject["fallbacks"])
    }

    @Test
    fun `a refusal that fell back counts both attempts, each at its own model`() = runBlocking {
        val iterations = """{"input_tokens":900,"output_tokens":250,"iterations":[
            {"type":"message","model":"claude-opus-5-5","input_tokens":1000,"output_tokens":0,"cache_read_input_tokens":0,"cache_creation_input_tokens":0},
            {"type":"fallback_message","model":"claude-opus-5","input_tokens":900,"output_tokens":250,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}]}"""
        server.enqueue(message(model = "claude-opus-5", usage = iterations))
        val r = estimator.estimate(request())
        assertEquals(AiStatus.OK, r.status)
        assertEquals("claude-opus-5", r.servedBy)
        assertEquals(listOf("claude-opus-5-5" to 1000L, "claude-opus-5" to 900L), r.attempts.map { it.model to it.inputTokens })
    }

    @Test
    fun `the stop reason decides before any content is read`() = runBlocking {
        server.enqueue(message(text = null, stopReason = "refusal", extra = ""","stop_details":{"type":"refusal","category":"cyber","explanation":"invented"}"""))
        assertEquals(AiStatus.REFUSAL, estimator.estimate(request()).status)

        server.enqueue(message(text = """{"items":[{"name":"Phul""", stopReason = "max_tokens"))
        assertEquals(AiStatus.MAX_TOKENS, estimator.estimate(request()).status)

        server.enqueue(message(text = "not json"))
        assertEquals(AiStatus.INVALID, estimator.estimate(request()).status)

        server.enqueue(message(text = null))
        assertEquals(AiStatus.INVALID, estimator.estimate(request()).status)
    }

    @Test
    fun `a rejected key and a billing problem fail at once, without a retry`() = runBlocking {
        server.enqueue(error(401, "authentication_error"))
        val auth = estimator.estimate(request())
        assertEquals(AiStatus.AUTH, auth.status)
        assertTrue(auth.attempts.isEmpty())
        assertEquals(1, server.requestCount)

        server.enqueue(error(402, "billing_error"))
        assertEquals(AiStatus.BILLING, estimator.estimate(request()).status)

        server.enqueue(error(403, "permission_error"))
        assertEquals(AiStatus.AUTH, estimator.estimate(request()).status)

        server.enqueue(error(400, "invalid_request_error"))
        assertEquals(AiStatus.BAD_REQUEST, estimator.estimate(request()).status)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `a 429 is retried once, then reported`() = runBlocking {
        server.enqueue(error(429, "rate_limit_error"))
        server.enqueue(error(429, "rate_limit_error"))
        assertEquals(AiStatus.RATE_LIMIT, estimator.estimate(request()).status)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a 429 that clears on the retry succeeds`() = runBlocking {
        server.enqueue(error(429, "rate_limit_error"))
        server.enqueue(message())
        assertEquals(AiStatus.OK, estimator.estimate(request()).status)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `server errors and overload are retried once, then reported`() = runBlocking {
        server.enqueue(error(500, "api_error"))
        server.enqueue(error(529, "overloaded_error"))
        assertEquals(AiStatus.SERVER, estimator.estimate(request()).status)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a timed-out request isn't sent again, and counts at its upper bound`() = runBlocking {
        estimator.timeout = Duration.ofMillis(500)
        server.enqueue(message().setHeadersDelay(3, TimeUnit.SECONDS))
        val started = System.nanoTime()
        val r = estimator.estimate(request())
        assertEquals(AiStatus.TIMEOUT, r.status)
        assertEquals("the server may still be working on it, so it isn't sent twice", 1, server.requestCount)
        assertTrue("gave up well before the server answered", (System.nanoTime() - started) / 1_000_000 < 3_000)
        // The usage is unknown and may have been billed: the prompt and every allowed output token stand in for it.
        assertEquals(listOf(request().upperBound), r.attempts)
        assertEquals(ClaudeMealEstimator.MAX_TOKENS, r.attempts.single().outputTokens)
    }

    @Test
    fun `a connection dropped after sending is a network error that may have been billed`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val r = estimator.estimate(request())
        assertEquals(AiStatus.NETWORK, r.status)
        assertEquals(1, server.requestCount)
        assertEquals(listOf(request().upperBound), r.attempts)
    }

    @Test
    fun `no server at all is a network error that costs nothing, after one retry`() = runBlocking {
        server.shutdown()
        val r = estimator.estimate(request())
        assertEquals(AiStatus.NETWORK, r.status)
        assertTrue(r.attempts.isEmpty())
    }

    @Test
    fun `only errors where nothing was processed are retried`() {
        assertTrue(ClaudeMealEstimator.notSent(RuntimeException(java.net.ConnectException("refused"))))
        assertTrue(ClaudeMealEstimator.notSent(RuntimeException(java.net.SocketTimeoutException("connect timed out"))))
        assertFalse(ClaudeMealEstimator.notSent(RuntimeException(java.net.SocketTimeoutException("Read timed out"))))
        assertFalse(ClaudeMealEstimator.unprocessed(IllegalStateException("anything else")))
    }

    @Test
    fun `a key OkHttp can't put in a header fails quietly, without the key in what's kept`() = runBlocking {
        server.enqueue(message())
        val r = estimator.estimate(MealRequest("test\u200Bkey", AiModel.OPUS_5_5, "SYSTEM PROMPT", "Meal: dal"))
        assertEquals(AiStatus.UNEXPECTED, r.status)
        assertEquals(0, server.requestCount)
        assertFalse(r.toString().contains("test"))
        assertTrue(r.attempts.isEmpty())
    }

    @Test
    fun `the default timeout is 20 seconds`() {
        assertEquals(Duration.ofSeconds(20), ClaudeMealEstimator.TIMEOUT)
        assertEquals(AiStatus.BILLING, ClaudeMealEstimator.statusFor(402))
        assertEquals(AiStatus.SERVER, ClaudeMealEstimator.statusFor(529))
        assertEquals(AiStatus.BAD_REQUEST, ClaudeMealEstimator.statusFor(413))
    }
}
