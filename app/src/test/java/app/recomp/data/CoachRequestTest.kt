package app.recomp.data

import app.recomp.data.ai.AiCall
import app.recomp.data.ai.AiEffort
import app.recomp.data.ai.AnthropicProvider
import app.recomp.data.ai.CoachSchema
import app.recomp.data.ai.GeminiProvider
import app.recomp.data.ai.OpenAiProvider
import app.recomp.data.repo.AiRepository
import app.recomp.domain.ai.AiModel
import app.recomp.domain.ai.AiPricing
import app.recomp.domain.ai.AiProviderId
import app.recomp.domain.ai.AiStatus
import app.recomp.domain.ai.AiTarget
import app.recomp.domain.coach.CoachPrompt
import app.recomp.domain.coach.SuggestionKind
import app.recomp.domain.coach.TargetTerms
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The weekly programme review's request against a local server (TRD §10 M11): the hand-written schema, effort
 * `medium`, the larger output limit, and the reply parsed. No real key or network; the values are invented.
 */
class CoachRequestTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start(java.net.InetAddress.getByName("127.0.0.1"), 0) }
    }

    @After
    fun tearDown() = server.shutdown()

    private val reply = """{"summary":"Good week.","suggestions":[""" +
        """{"kind":"SETS","routine_ref":"r1","exercise_ref":"e2","replacement_ref":"","value":{"min":4,"max":4},"reason":"Chest is under 10 sets."},""" +
        """{"kind":"ADVICE","routine_ref":"","exercise_ref":"","replacement_ref":"","value":{"min":0,"max":0},"reason":"Sleep more.","extra":1}]}"""

    private fun call(target: AiTarget) = AiCall(
        target,
        CoachPrompt.system(TargetTerms.RELATIVE),
        "Weekly programme review.",
        CoachSchema.schema,
        AiPricing.upperBound(target.model, 100, AiRepository.COACH_MAX_TOKENS),
        effort = AiEffort.MEDIUM,
        timeout = AiRepository.COACH_TIMEOUT,
        maxTokens = AiRepository.COACH_MAX_TOKENS,
    )

    @Test
    fun `Anthropic gets the schema at medium effort with room to answer, and the reply parses`() = runBlocking {
        val anthropic = AnthropicProvider().apply { baseUrl = "http://127.0.0.1:${server.port}"; timeout = Duration.ofSeconds(5) }
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[{"type":"text","text":${Json.encodeToString(reply)}}],""" +
                    """"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":3000,"output_tokens":900}}""",
            ),
        )
        val r = anthropic.send(call(AiTarget.anthropic("test-key", AiModel.OPUS_5_5)))
        assertEquals(AiStatus.OK, r.status)
        val body = Json.parseToJsonElement(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
        assertEquals("16000", body.getValue("max_tokens").jsonPrimitive.content)
        val config = body.getValue("output_config").jsonObject
        assertEquals("medium", config.getValue("effort").jsonPrimitive.content)
        val schema = config.getValue("format").jsonObject.getValue("schema").jsonObject
        assertSchema(schema)

        val parsed = CoachSchema.parse(r.json!!.toString())
        assertEquals("Good week.", parsed.summary)
        assertEquals(listOf("SETS", "ADVICE"), parsed.suggestions.map { it.kind })
        assertEquals(4.0, parsed.suggestions[0].min, 0.0)
        assertEquals("e2", parsed.suggestions[0].exerciseRef)
    }

    @Test
    fun `OpenAI gets reasoning effort medium and the same schema`() = runBlocking {
        val openAi = OpenAiProvider().apply { baseUrl = "http://127.0.0.1:${server.port}/v1"; timeout = Duration.ofSeconds(5) }
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"id":"c1","object":"chat.completion","model":"gpt-6.1-sol","choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":${Json.encodeToString(reply)}}}],""" +
                    """"usage":{"prompt_tokens":3000,"completion_tokens":900}}""",
            ),
        )
        val r = openAi.send(call(AiTarget(AiProviderId.OPENAI, "test-key", "gpt-6.1-sol")))
        val body = Json.parseToJsonElement(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
        assertEquals("medium", body.getValue("reasoning_effort").jsonPrimitive.content)
        assertEquals("16000", body.getValue("max_completion_tokens").jsonPrimitive.content)
        assertSchema(body.getValue("response_format").jsonObject.getValue("json_schema").jsonObject.getValue("schema").jsonObject)
        assertEquals(AiStatus.OK, r.status)
    }

    @Test
    fun `Gemini gets thinking at MEDIUM and room to answer`() = runBlocking {
        val gemini = GeminiProvider().apply { baseUrl = "http://127.0.0.1:${server.port}/v1beta"; timeout = Duration.ofSeconds(5) }
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"candidates":[{"content":{"role":"model","parts":[{"text":${Json.encodeToString(reply)}}]},"finishReason":"STOP"}],""" +
                    """"usageMetadata":{"promptTokenCount":3000,"candidatesTokenCount":900,"totalTokenCount":3900},"modelVersion":"gemini-4-hydrogen"}""",
            ),
        )
        val r = gemini.send(call(AiTarget(AiProviderId.GEMINI, "test-key", "gemini-4-hydrogen")))
        val body = Json.parseToJsonElement(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
        val config = body.getValue("generationConfig").jsonObject
        assertEquals("MEDIUM", config.getValue("thinkingConfig").jsonObject.getValue("thinkingLevel").jsonPrimitive.content)
        assertEquals("16000", config.getValue("maxOutputTokens").jsonPrimitive.content)
        assertSchema(config.getValue("responseFormat").jsonObject.getValue("text").jsonObject.getValue("schema").jsonObject)
        assertEquals(AiStatus.OK, r.status)
        assertEquals("Good week.", CoachSchema.parse(r.json!!.toString()).summary)
    }

    @Test
    fun `a reply that doesn't fit the schema throws, so it's never applied`() {
        assertTrue(runCatching { CoachSchema.parse("""{"suggestions":[]}""") }.isFailure)
        assertTrue(runCatching { CoachSchema.parse("""{"summary":"x","suggestions":[{"reason":"no kind"}]}""") }.isFailure)
        assertEquals(0, CoachSchema.parse("""{"summary":"x","suggestions":[]}""").suggestions.size)
    }

    private fun assertSchema(schema: JsonObject) {
        assertEquals("false", schema.getValue("additionalProperties").jsonPrimitive.content)
        val item = schema.getValue("properties").jsonObject.getValue("suggestions").jsonObject.getValue("items").jsonObject
        val kinds = item.getValue("properties").jsonObject.getValue("kind").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(SuggestionKind.entries.map { it.name }, kinds)
        assertEquals(6, item.getValue("required").jsonArray.size)
        // No array-length or number-range constraints: SuggestionCheck enforces those.
        val text = schema.toString()
        listOf("maxItems", "minItems", "minimum", "maximum").forEach { assertFalse(it, it in text) }
    }
}
