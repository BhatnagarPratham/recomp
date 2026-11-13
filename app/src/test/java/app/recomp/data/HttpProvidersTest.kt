package app.recomp.data

import app.recomp.data.ai.AiCall
import app.recomp.data.ai.AiProviders
import app.recomp.data.ai.AiResult
import app.recomp.data.ai.AnthropicProvider
import app.recomp.data.ai.GeminiProvider
import app.recomp.data.ai.MealSchema
import app.recomp.data.ai.OpenAiProvider
import app.recomp.data.ai.ProviderMealEstimator
import app.recomp.data.ai.WireMode
import app.recomp.domain.ai.AiImage
import app.recomp.domain.ai.AiModel
import app.recomp.domain.ai.AiPricing
import app.recomp.domain.ai.AiProviderId
import app.recomp.domain.ai.AiStatus
import app.recomp.domain.ai.AiTarget
import app.recomp.domain.ai.AiUrl
import app.recomp.domain.ai.MealCheck
import app.recomp.domain.ai.MealRequest
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The OpenAI, OpenAI-compatible and Gemini adapters against a local server (AI-05, TRD §6.2): the request each
 * builds, how each provider's replies, usage and errors map onto the one shared table, the schema fallback, and
 * that the key stays out of everything kept. No real key or network is involved; the values are invented.
 */
class HttpProvidersTest {
    private lateinit var server: MockWebServer
    private val openAi = OpenAiProvider()
    private val gemini = GeminiProvider()
    private val estimator = ProviderMealEstimator(AiProviders(AnthropicProvider(), openAi, gemini))
    private val key = "test-key-0451"

    @Before
    fun setUp() {
        server = MockWebServer().apply { start(java.net.InetAddress.getByName("127.0.0.1"), 0) }
        openAi.baseUrl = "http://127.0.0.1:${server.port}/v1"
        gemini.baseUrl = "http://127.0.0.1:${server.port}/v1beta"
        openAi.timeout = Duration.ofSeconds(2)
        gemini.timeout = Duration.ofSeconds(2)
    }

    @After
    fun tearDown() = server.shutdown()

    private val meal = """
        {"items":[
          {"name":"Phulka","quantity":2,"unit":"piece","grams":70,"kcal":210,"protein_g":6.5,"carbs_g":42,"fat_g":1.5,"fiber_g":4,"confidence":"high","assumptions":"no ghee","diet":"vegan"},
          {"name":"Nothing","quantity":0,"unit":"piece","grams":0,"kcal":0,"protein_g":0,"carbs_g":0,"fat_g":0,"fiber_g":0,"confidence":"low","assumptions":"","diet":"vegan"}
        ],"clarifying_question":""}
    """.trimIndent()

    private val photo = AiImage("image/jpeg", byteArrayOf(1, 2, 3))

    private fun call(target: AiTarget, images: List<AiImage> = emptyList()): AiCall {
        val request = MealRequest(target, "SYSTEM PROMPT", "Origin: Home\nMeal: 2 phulka", images)
        return AiCall(target, request.system, request.user, MealSchema.schema, request.upperBound, images)
    }

    private fun openAiTarget(model: AiModel = AiModel.GPT_6_1_SOL) = AiTarget(AiProviderId.OPENAI, key, model.id)
    private fun geminiTarget(model: AiModel = AiModel.GEMINI_4_HYDROGEN) = AiTarget(AiProviderId.GEMINI, key, model.id)

    /** A compatible server at the mock server's own address: plain http to this machine, so no key goes with it. */
    private fun localTarget(model: String = "test-model", rates: app.recomp.domain.ai.Rates = AiPricing.typed(0.5, 1.5)) =
        AiTarget(AiProviderId.COMPATIBLE, key, model, baseUrl = AiUrl.ok("http://127.0.0.1:${server.port}/v1")!!, rates = rates)

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun chat(
        content: String? = meal,
        finish: String? = "stop",
        model: String = "gpt-6.1-sol-2026-09-30",
        usage: String? = """{"prompt_tokens":1200,"completion_tokens":700,"total_tokens":1900,"prompt_tokens_details":{"cached_tokens":1000},"completion_tokens_details":{"reasoning_tokens":400}}""",
        refusal: String? = null,
    ) = json(
        """{"id":"c1","object":"chat.completion","model":"$model","choices":[{"index":0,"message":{"role":"assistant","content":${Json.encodeToString(content)},"refusal":${Json.encodeToString(refusal)}},"finish_reason":${Json.encodeToString(finish)}}]${usage?.let { ""","usage":$it""" }.orEmpty()}}""",
    )

    private fun openAiError(code: Int, errorCode: String?, type: String = "invalid_request_error") = json(
        """{"error":{"message":"Incorrect API key provided: $key","type":"$type","param":null,"code":${Json.encodeToString(errorCode)}}}""",
    ).setResponseCode(code).setHeader("retry-after", "0")

    private fun generated(
        text: String? = meal,
        finish: String = "STOP",
        usage: String = """{"promptTokenCount":1200,"cachedContentTokenCount":1000,"candidatesTokenCount":300,"thoughtsTokenCount":400,"totalTokenCount":1900}""",
        extra: String = "",
    ): MockResponse {
        val parts = if (text == null) "[]" else """[{"text":"thinking it over","thought":true},{"text":${Json.encodeToString(text)}}]"""
        return json("""{"candidates":[{"content":{"role":"model","parts":$parts},"finishReason":"$finish","index":0}],"usageMetadata":$usage,"modelVersion":"gemini-4-hydrogen-001"$extra}""")
    }

    private fun geminiError(code: Int, status: String, reason: String? = null) = json(
        """{"error":{"code":$code,"message":"API key not valid: $key","status":"$status","details":[${reason?.let { """{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"$it"}""" }.orEmpty()}]}}""",
    ).setResponseCode(code)

    private fun body(request: RecordedRequest): JsonObject = Json.parseToJsonElement(request.body.readUtf8()).jsonObject

    private fun take() = server.takeRequest(1, TimeUnit.SECONDS)!!

    private fun assertNoKey(result: AiResult) = assertFalse("the key is in what's kept", result.toString().contains(key))

    // OpenAI

    @Test
    fun `OpenAI's request has the strict schema, a developer message, low effort and the key in a header`() = runBlocking {
        server.enqueue(chat())
        val call = call(openAiTarget())
        assertEquals(AiStatus.OK, openAi.send(call).status)
        val recorded = take()
        assertEquals("/v1/chat/completions", recorded.requestUrl!!.encodedPath)
        assertEquals("Bearer $key", recorded.getHeader("Authorization"))
        assertNull("no key in the address", recorded.requestUrl!!.query)
        val body = body(recorded)
        assertEquals("gpt-6.1-sol", body.getValue("model").jsonPrimitive.content)
        val format = body.getValue("response_format").jsonObject
        assertEquals("json_schema", format.getValue("type").jsonPrimitive.content)
        val schema = format.getValue("json_schema").jsonObject
        assertEquals("meal_estimate", schema.getValue("name").jsonPrimitive.content)
        assertEquals("true", schema.getValue("strict").jsonPrimitive.content)
        assertEquals(MealSchema.schema.element, schema.getValue("schema").jsonObject)
        val messages = body.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(listOf("developer", "user"), messages.map { it.getValue("role").jsonPrimitive.content })
        assertEquals("SYSTEM PROMPT", messages[0].getValue("content").jsonPrimitive.content)
        assertEquals("Origin: Home\nMeal: 2 phulka", messages[1].getValue("content").jsonPrimitive.content)
        assertEquals("low", body.getValue("reasoning_effort").jsonPrimitive.content)
        assertEquals("8000", body.getValue("max_completion_tokens").jsonPrimitive.content)
        assertFalse(body.containsKey("max_tokens"))
        assertFalse("the key isn't in the body", body.toString().contains(key))
        assertFalse(call.toString().contains(key))
        assertFalse(call.target.toString().contains(key))
    }

    @Test
    fun `OpenAI gets a photo as a data URL before the text`() = runBlocking {
        server.enqueue(chat())
        openAi.send(call(openAiTarget(), listOf(photo)))
        val parts = body(take()).getValue("messages").jsonArray[1].jsonObject.getValue("content").jsonArray.map { it.jsonObject }
        assertEquals(listOf("image_url", "text"), parts.map { it.getValue("type").jsonPrimitive.content })
        assertEquals("data:image/jpeg;base64,AQID", parts[0].getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content)
        assertEquals("Origin: Home\nMeal: 2 phulka", parts[1].getValue("text").jsonPrimitive.content)
    }

    @Test
    fun `OpenAI's cached tokens come out of the input and its reasoning tokens stay in the output`() = runBlocking {
        server.enqueue(chat())
        val r = openAi.send(call(openAiTarget()))
        val usage = r.attempts.single()
        // 1,200 prompt tokens of which 1,000 were cached; 700 completion tokens of which 400 were reasoning.
        assertEquals(listOf(200L, 1_000L, 700L, 0L), listOf(usage.inputTokens, usage.cacheReadTokens, usage.outputTokens, usage.cacheWriteTokens))
        // Priced at the model that was asked for, though a dated snapshot answered.
        assertEquals("gpt-6.1-sol-2026-09-30", r.servedBy)
        assertEquals("gpt-6.1-sol", usage.model)
        assertEquals((200 * 2.00 + 1_000 * 0.10 + 700 * 10.00) / 1_000_000, AiPricing.cost(r.attempts), 1e-12)
        assertNoKey(r)
    }

    @Test
    fun `OpenAI's cache writes are charged, and a reply with no usage counts at the upper bound`() = runBlocking {
        server.enqueue(chat(usage = """{"prompt_tokens":1200,"completion_tokens":300,"prompt_tokens_details":{"cached_tokens":0,"cache_write_tokens":1100}}"""))
        val written = openAi.send(call(openAiTarget())).attempts.single()
        assertEquals(listOf(1_200L, 0L, 300L, 1_100L), listOf(written.inputTokens, written.cacheReadTokens, written.outputTokens, written.cacheWriteTokens))

        server.enqueue(chat(usage = null))
        val call = call(openAiTarget())
        assertEquals(listOf(call.upperBound), openAi.send(call).attempts)
    }

    @Test
    fun `OpenAI's refusal, filter and cut-off are read before the content`() = runBlocking {
        server.enqueue(chat(content = null, refusal = "I can't help with that."))
        assertEquals(AiStatus.REFUSAL, openAi.send(call(openAiTarget())).status)

        server.enqueue(chat(content = meal, finish = "content_filter"))
        val filtered = openAi.send(call(openAiTarget()))
        assertEquals(AiStatus.REFUSAL, filtered.status)
        assertNull(filtered.json)

        server.enqueue(chat(content = """{"items":[{"name":"Phul""", finish = "length"))
        assertEquals(AiStatus.MAX_TOKENS, openAi.send(call(openAiTarget())).status)

        server.enqueue(chat(content = "not json"))
        assertEquals(AiStatus.INVALID, openAi.send(call(openAiTarget())).status)

        server.enqueue(chat(content = null))
        assertEquals(AiStatus.INVALID, openAi.send(call(openAiTarget())).status)

        server.enqueue(chat(finish = "tool_calls"))
        assertEquals(AiStatus.INVALID, openAi.send(call(openAiTarget())).status)

        server.enqueue(json("<html>gateway</html>"))
        val html = openAi.send(call(openAiTarget()))
        assertEquals(AiStatus.INVALID, html.status)
        // A refused request still used tokens, and they're counted.
        assertEquals(1, filtered.attempts.size)
    }

    @Test
    fun `OpenAI's rejected key and empty account fail at once, and the error's message is never kept`() = runBlocking {
        server.enqueue(openAiError(401, "invalid_api_key"))
        val auth = openAi.send(call(openAiTarget()))
        assertEquals(AiStatus.AUTH, auth.status)
        assertTrue(auth.attempts.isEmpty())
        assertNoKey(auth)
        assertEquals("HTTP 401 invalid_api_key", auth.detail)

        // Out of credit is a 429 that waiting won't fix: no retry.
        server.enqueue(openAiError(429, "credit_balance_exhausted", type = "insufficient_quota"))
        assertEquals(AiStatus.BILLING, openAi.send(call(openAiTarget())).status)
        server.enqueue(openAiError(429, null, type = "insufficient_quota"))
        assertEquals(AiStatus.BILLING, openAi.send(call(openAiTarget())).status)
        server.enqueue(openAiError(429, "project_spend_limit_exceeded"))
        assertEquals(AiStatus.BILLING, openAi.send(call(openAiTarget())).status)

        server.enqueue(openAiError(403, "unsupported_country_region_territory"))
        assertEquals(AiStatus.AUTH, openAi.send(call(openAiTarget())).status)

        // OpenAI itself is never asked again in JSON mode.
        server.enqueue(openAiError(400, "invalid_json_schema"))
        assertEquals(AiStatus.BAD_REQUEST, openAi.send(call(openAiTarget())).status)
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `OpenAI's rate limit and overload are retried once`() = runBlocking {
        server.enqueue(openAiError(429, "slow_down", type = "rate_limit_error"))
        server.enqueue(openAiError(429, "slow_down", type = "rate_limit_error"))
        assertEquals(AiStatus.RATE_LIMIT, openAi.send(call(openAiTarget())).status)
        assertEquals(2, server.requestCount)

        server.enqueue(openAiError(503, "server_is_overloaded", type = "service_unavailable_error"))
        server.enqueue(chat())
        assertEquals(AiStatus.OK, openAi.send(call(openAiTarget())).status)

        server.enqueue(openAiError(500, null, type = "server_error"))
        server.enqueue(openAiError(503, "server_is_overloaded"))
        assertEquals(AiStatus.SERVER, openAi.send(call(openAiTarget())).status)
        assertEquals(6, server.requestCount)

        // OkHttp would re-send a 503 with `Retry-After: 0` by itself: the adapter's one retry is the only one.
        repeat(3) { server.enqueue(openAiError(503, "server_is_overloaded")) }
        assertEquals(AiStatus.SERVER, openAi.send(call(openAiTarget())).status)
        assertEquals("two sends, not three", 8, server.requestCount)
    }

    @Test
    fun `a timed-out request isn't sent again and counts at its upper bound, on every provider`() = runBlocking {
        openAi.timeout = Duration.ofMillis(500)
        gemini.timeout = Duration.ofMillis(500)
        server.enqueue(chat().setHeadersDelay(3, TimeUnit.SECONDS))
        val call = call(openAiTarget())
        val r = openAi.send(call)
        assertEquals(AiStatus.TIMEOUT, r.status)
        assertEquals(listOf(call.upperBound), r.attempts)
        assertEquals(8_000L, r.attempts.single().outputTokens)
        assertEquals(1, server.requestCount)

        server.enqueue(generated().setHeadersDelay(3, TimeUnit.SECONDS))
        val g = call(geminiTarget())
        val timedOut = gemini.send(g)
        assertEquals(AiStatus.TIMEOUT, timedOut.status)
        assertEquals(listOf(g.upperBound), timedOut.attempts)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a dropped connection may have been billed, and no server at all costs nothing`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val call = call(openAiTarget())
        val dropped = openAi.send(call)
        assertEquals(AiStatus.NETWORK, dropped.status)
        assertEquals(listOf(call.upperBound), dropped.attempts)

        server.shutdown()
        val none = openAi.send(call)
        assertEquals(AiStatus.NETWORK, none.status)
        assertTrue(none.attempts.isEmpty())
        assertEquals(AiStatus.NETWORK, gemini.send(call(geminiTarget())).status)
    }

    @Test
    fun `a redirect is never followed, so the key can't be carried somewhere else`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "http://127.0.0.1:${server.port}/elsewhere"))
        assertEquals(AiStatus.BAD_REQUEST, openAi.send(call(openAiTarget())).status)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a key that can't go in a header fails quietly on every provider, without the key in what's kept`() = runBlocking {
        val bad = "test​key"
        val r = openAi.send(call(AiTarget(AiProviderId.OPENAI, bad, "gpt-6-luna")))
        assertEquals(AiStatus.UNEXPECTED, r.status)
        assertFalse(r.toString().contains("test"))
        val g = gemini.send(call(AiTarget(AiProviderId.GEMINI, bad, "gemini-3.8-flash")))
        assertEquals(AiStatus.UNEXPECTED, g.status)
        assertFalse(g.toString().contains("test"))
        assertEquals(0, server.requestCount)
    }

    // OpenAI-compatible

    @Test
    fun `a compatible server gets a system message, max_tokens and no reasoning setting`() = runBlocking {
        server.enqueue(chat(model = "test-model"))
        val r = openAi.send(call(localTarget()))
        assertEquals(AiStatus.OK, r.status)
        val body = body(take())
        assertEquals("test-model", body.getValue("model").jsonPrimitive.content)
        assertEquals("system", body.getValue("messages").jsonArray[0].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("8000", body.getValue("max_tokens").jsonPrimitive.content)
        assertFalse(body.containsKey("reasoning_effort"))
        assertFalse(body.containsKey("max_completion_tokens"))
        assertEquals("json_schema", body.getValue("response_format").jsonObject.getValue("type").jsonPrimitive.content)
        // The prices you typed, with cached input at the input price.
        assertEquals((1_200 * 0.5 + 700 * 1.5) / 1_000_000, AiPricing.cost(r.attempts), 1e-12)
    }

    @Test
    fun `a key is never sent over plain http, and is over https`() = runBlocking {
        server.enqueue(chat())
        openAi.send(call(localTarget()))
        val recorded = take()
        assertNull(recorded.getHeader("Authorization"))
        assertFalse(recorded.headers.toString().contains(key))
        assertFalse(recorded.body.readUtf8().contains(key))

        val lan = AiTarget(AiProviderId.COMPATIBLE, key, "m", baseUrl = AiUrl.ok("http://192.168.1.20:11434/v1")!!)
        assertTrue(openAi.request(call(lan), WireMode.SCHEMA).headers.isEmpty())

        val https = AiTarget(AiProviderId.COMPATIBLE, key, "m", baseUrl = AiUrl.ok("https://openrouter.ai/api/v1/")!!)
        val request = openAi.request(call(https), WireMode.SCHEMA)
        assertEquals("https://openrouter.ai/api/v1/chat/completions", request.url)
        assertEquals(mapOf("Authorization" to "Bearer $key"), request.headers)
        assertFalse(request.toString().contains(key))

        // No key stored: nothing to send, anywhere.
        assertTrue(openAi.request(call(AiTarget(AiProviderId.COMPATIBLE, null, "m", baseUrl = https.baseUrl)), WireMode.SCHEMA).headers.isEmpty())
    }

    @Test
    fun `a server that rejects the schema is asked again in JSON mode, and that's remembered`() = runBlocking {
        server.enqueue(json("""{"error":{"message":"response_format json_schema is not supported","type":"invalid_request_error"}}""").setResponseCode(400))
        // A fenced reply, as a model in plain JSON mode may give.
        server.enqueue(chat(content = "```json\n$meal\n```", model = "test-model"))
        val result = estimator.estimate(MealRequest(localTarget(), "SYSTEM PROMPT", "Meal: 2 phulka"))
        assertEquals(AiStatus.OK, result.status)
        // The same validation as any provider's reply: the item with no weight is rejected by MealCheck.
        val checked = MealCheck.check(result.estimate!!)
        assertEquals(listOf("Phulka"), checked.items.map { it.name })
        assertEquals(listOf("Nothing"), checked.rejected)

        val first = body(take())
        assertEquals("json_schema", first.getValue("response_format").jsonObject.getValue("type").jsonPrimitive.content)
        val second = body(take())
        assertEquals(mapOf("type" to "json_object"), second.getValue("response_format").jsonObject.mapValues { it.value.jsonPrimitive.content })
        val system = second.getValue("messages").jsonArray[0].jsonObject.getValue("content").jsonPrimitive.content
        assertTrue(system.startsWith("SYSTEM PROMPT"))
        assertTrue("the schema is written into the prompt", system.contains("JSON Schema") && system.contains("\"clarifying_question\""))

        // Next time the same server and model go straight to JSON mode; another model starts with the schema again.
        server.enqueue(chat(model = "test-model"))
        openAi.send(call(localTarget()))
        assertEquals("json_object", body(take()).getValue("response_format").jsonObject.getValue("type").jsonPrimitive.content)
        server.enqueue(chat(model = "other-model"))
        openAi.send(call(localTarget(model = "other-model")))
        assertEquals("json_schema", body(take()).getValue("response_format").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `a server that rejects both shapes is a refused request, and JSON mode that isn't JSON is invalid`() = runBlocking {
        server.enqueue(json("""{"error":{"message":"no such model"}}""").setResponseCode(404))
        server.enqueue(json("""{"error":{"message":"no such model"}}""").setResponseCode(404))
        assertEquals(AiStatus.BAD_REQUEST, openAi.send(call(localTarget(model = "missing"))).status)
        assertEquals(2, server.requestCount)

        server.enqueue(json("{}").setResponseCode(422))
        server.enqueue(chat(content = "Sure! Two phulka is about 210 kcal."))
        assertEquals(AiStatus.INVALID, openAi.send(call(localTarget(model = "chatty"))).status)
    }

    @Test
    fun `a compatible server's errors map onto the same table`() = runBlocking {
        // OpenRouter: 402 with no credit left, and an error inside a 200.
        server.enqueue(json("""{"error":{"code":402,"message":"Insufficient credits"}}""").setResponseCode(402))
        assertEquals(AiStatus.BILLING, openAi.send(call(localTarget())).status)

        server.enqueue(json("""{"error":{"code":429,"message":"Rate limited upstream"}}"""))
        assertEquals(AiStatus.RATE_LIMIT, openAi.send(call(localTarget())).status)

        server.enqueue(json("""{"error":{"code":401,"message":"No auth credentials found"}}""").setResponseCode(401))
        assertEquals(AiStatus.AUTH, openAi.send(call(localTarget())).status)

        // llama.cpp while it loads a model.
        server.enqueue(json("""{"error":{"code":503,"message":"Loading model","type":"unavailable_error"}}""").setResponseCode(503).setHeader("retry-after", "0"))
        server.enqueue(chat())
        assertEquals(AiStatus.OK, openAi.send(call(localTarget())).status)
    }

    @Test
    fun `a free local server with no usage report costs nothing, and a priced one counts at its upper bound`() = runBlocking {
        server.enqueue(chat(usage = null, finish = null))
        val free = openAi.send(call(localTarget(rates = AiPricing.FREE)))
        assertEquals(AiStatus.OK, free.status)
        assertEquals(0.0, AiPricing.cost(free.attempts), 0.0)

        server.enqueue(chat(usage = null))
        val call = call(localTarget())
        val priced = openAi.send(call)
        assertEquals(listOf(call.upperBound), priced.attempts)
        assertTrue(AiPricing.cost(priced.attempts) > 0)
    }

    // Gemini

    @Test
    fun `Gemini's request has the key in a header, the schema in responseFormat, low thinking and the photo inline`() = runBlocking {
        server.enqueue(generated())
        assertEquals(AiStatus.OK, gemini.send(call(geminiTarget(), listOf(photo))).status)
        val recorded = take()
        assertEquals("/v1beta/models/gemini-4-hydrogen:generateContent", recorded.requestUrl!!.encodedPath)
        assertEquals(key, recorded.getHeader("x-goog-api-key"))
        assertNull("the key never goes in the address", recorded.requestUrl!!.query)
        assertFalse(recorded.requestUrl.toString().contains(key))
        val body = body(recorded)
        assertFalse(body.toString().contains(key))
        assertEquals("SYSTEM PROMPT", body.getValue("systemInstruction").jsonObject.getValue("parts").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content)
        val content = body.getValue("contents").jsonArray.single().jsonObject
        assertEquals("user", content.getValue("role").jsonPrimitive.content)
        val parts = content.getValue("parts").jsonArray.map { it.jsonObject }
        assertEquals(mapOf("mimeType" to "image/jpeg", "data" to "AQID"), parts[0].getValue("inlineData").jsonObject.mapValues { it.value.jsonPrimitive.content })
        assertEquals("Origin: Home\nMeal: 2 phulka", parts[1].getValue("text").jsonPrimitive.content)
        val config = body.getValue("generationConfig").jsonObject
        val format = config.getValue("responseFormat").jsonObject.getValue("text").jsonObject
        assertEquals("APPLICATION_JSON", format.getValue("mimeType").jsonPrimitive.content)
        assertEquals(MealSchema.schema.element, format.getValue("schema").jsonObject)
        assertEquals("8000", config.getValue("maxOutputTokens").jsonPrimitive.content)
        assertEquals("LOW", config.getValue("thinkingConfig").jsonObject.getValue("thinkingLevel").jsonPrimitive.content)
        assertFalse("the older fields aren't sent", config.containsKey("responseSchema") || config.containsKey("responseMimeType"))
    }

    @Test
    fun `Gemini's thinking tokens are added to the output and its cached tokens come out of the input`() = runBlocking {
        server.enqueue(generated())
        val r = gemini.send(call(geminiTarget()))
        assertEquals(AiStatus.OK, r.status)
        // The thought part isn't the reply.
        assertFalse(r.text!!.contains("thinking it over"))
        assertEquals(2, r.json!!.getValue("items").jsonArray.size)
        val usage = r.attempts.single()
        // 1,200 prompt tokens of which 1,000 cached; 300 reply tokens plus 400 of thinking, all billed as output.
        assertEquals(listOf(200L, 1_000L, 700L), listOf(usage.inputTokens, usage.cacheReadTokens, usage.outputTokens))
        assertEquals("gemini-4-hydrogen", usage.model)
        assertEquals("gemini-4-hydrogen-001", r.servedBy)
        assertEquals((200 * 1.50 + 1_000 * 0.075 + 700 * 7.50) / 1_000_000, AiPricing.cost(r.attempts), 1e-12)
        assertNoKey(r)
    }

    @Test
    fun `Gemini's blocked prompt and safety stops are refusals, and a cut-off is a cut-off`() = runBlocking {
        server.enqueue(json("""{"promptFeedback":{"blockReason":"SAFETY"},"usageMetadata":{"promptTokenCount":900,"totalTokenCount":900}}"""))
        val blocked = gemini.send(call(geminiTarget()))
        assertEquals(AiStatus.REFUSAL, blocked.status)
        assertEquals(900L, blocked.attempts.single().inputTokens)

        for (reason in listOf("SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII")) {
            server.enqueue(generated(text = null, finish = reason))
            assertEquals(reason, AiStatus.REFUSAL, gemini.send(call(geminiTarget())).status)
        }
        server.enqueue(generated(text = """{"items":[{"name":"Phul""", finish = "MAX_TOKENS"))
        assertEquals(AiStatus.MAX_TOKENS, gemini.send(call(geminiTarget())).status)
        // A finish reason this build doesn't know is never a success.
        server.enqueue(generated(finish = "CONTINUATION"))
        assertEquals(AiStatus.INVALID, gemini.send(call(geminiTarget())).status)
        server.enqueue(generated(text = "not json"))
        assertEquals(AiStatus.INVALID, gemini.send(call(geminiTarget())).status)
        server.enqueue(generated(text = null))
        assertEquals(AiStatus.INVALID, gemini.send(call(geminiTarget())).status)
        server.enqueue(json("""{"candidates":[]}"""))
        assertEquals(AiStatus.INVALID, gemini.send(call(geminiTarget())).status)
    }

    @Test
    fun `Gemini's errors map onto the same table, a wrong key being a 400`() = runBlocking {
        server.enqueue(geminiError(400, "INVALID_ARGUMENT", "API_KEY_INVALID"))
        val auth = gemini.send(call(geminiTarget()))
        assertEquals(AiStatus.AUTH, auth.status)
        assertEquals("HTTP 400 API_KEY_INVALID", auth.detail)
        assertNoKey(auth)
        assertTrue(auth.attempts.isEmpty())

        server.enqueue(geminiError(403, "PERMISSION_DENIED"))
        assertEquals(AiStatus.AUTH, gemini.send(call(geminiTarget())).status)
        // Prepaid credit used up: not retried.
        server.enqueue(geminiError(402, "RESOURCE_EXHAUSTED"))
        assertEquals(AiStatus.BILLING, gemini.send(call(geminiTarget())).status)
        server.enqueue(geminiError(400, "INVALID_ARGUMENT"))
        assertEquals(AiStatus.BAD_REQUEST, gemini.send(call(geminiTarget())).status)
        server.enqueue(geminiError(404, "NOT_FOUND"))
        assertEquals(AiStatus.BAD_REQUEST, gemini.send(call(geminiTarget())).status)
        assertEquals(5, server.requestCount)

        server.enqueue(geminiError(429, "RESOURCE_EXHAUSTED"))
        server.enqueue(geminiError(429, "RESOURCE_EXHAUSTED"))
        assertEquals(AiStatus.RATE_LIMIT, gemini.send(call(geminiTarget())).status)
        server.enqueue(geminiError(503, "UNAVAILABLE"))
        server.enqueue(geminiError(500, "INTERNAL"))
        assertEquals(AiStatus.SERVER, gemini.send(call(geminiTarget())).status)
        server.enqueue(geminiError(503, "UNAVAILABLE"))
        server.enqueue(generated())
        assertEquals(AiStatus.OK, gemini.send(call(geminiTarget())).status)
        assertEquals(11, server.requestCount)
    }

    // Shared

    @Test
    fun `every provider's reply goes through the same decoding and validation`() = runBlocking {
        server.enqueue(chat())
        server.enqueue(generated())
        for (target in listOf(openAiTarget(), geminiTarget())) {
            val r = estimator.estimate(MealRequest(target, "SYSTEM PROMPT", "Meal: 2 phulka"))
            assertEquals(target.provider.name, AiStatus.OK, r.status)
            val checked = MealCheck.check(r.estimate!!)
            assertEquals(listOf("Phulka"), checked.items.map { it.name })
            assertEquals(listOf("Nothing"), checked.rejected)
            assertFalse(r.toString().contains(key))
        }
        // JSON that parses but isn't a meal is invalid, whoever sent it.
        server.enqueue(chat(content = """{"answer":42}"""))
        assertEquals(AiStatus.INVALID, estimator.estimate(MealRequest(openAiTarget(), "S", "U")).status)
        server.enqueue(generated(text = """{"items":"none"}"""))
        assertEquals(AiStatus.INVALID, estimator.estimate(MealRequest(geminiTarget(), "S", "U")).status)
    }

    @Test
    fun `the shared schema has no array-length or number-range constraints`() {
        val text = MealSchema.schema.element.toString()
        for (keyword in listOf("minItems", "maxItems", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf", "minLength", "maxLength", "pattern")) {
            assertFalse(keyword, text.contains("\"$keyword\""))
        }
        // Strict mode on every provider: each object closes itself and requires every property.
        fun check(schema: JsonObject) {
            if (schema["type"]?.jsonPrimitive?.content == "object") {
                assertEquals("false", schema.getValue("additionalProperties").jsonPrimitive.content)
                val properties = schema.getValue("properties").jsonObject
                assertEquals(properties.keys, schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
                properties.values.forEach { check(it.jsonObject) }
            }
            schema["items"]?.let { check(it.jsonObject) }
        }
        check(MealSchema.schema.element)
    }
}
