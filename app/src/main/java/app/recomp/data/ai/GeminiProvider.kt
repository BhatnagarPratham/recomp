package app.recomp.data.ai

import java.util.Base64
import app.recomp.domain.ai.AiStatus
import app.recomp.domain.ai.MealRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Google Gemini's `generateContent` (AI-05, TRD §6.2). The key goes in the `x-goog-api-key` header, never in the
 * URL. Structured output is `generationConfig.responseFormat.text` with `mimeType: APPLICATION_JSON` and the
 * hand-written schema, the field the REST reference gives today. Thinking is at `thinkingLevel: LOW`, which every
 * listed model takes (Gemini 4 can't switch it off), and `maxOutputTokens` covers thinking and the reply together.
 *
 * **Usage.** `promptTokenCount` includes the cached tokens, so they're taken out of the input. Thinking tokens are
 * reported apart from the reply's (`thoughtsTokenCount`, `candidatesTokenCount`) and billed as output, so the two
 * are added.
 *
 * A blocked prompt (`promptFeedback.blockReason`) or a safety finish reason is a refusal.
 */
@Singleton
class GeminiProvider @Inject constructor() : HttpProvider() {
    /** Tests point the adapter at a local server. */
    internal var baseUrl: String = "https://generativelanguage.googleapis.com/v1beta"

    override fun request(call: AiCall, mode: WireMode): WireRequest {
        val body = buildJsonObject {
            putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", call.system) }) } }
            put(
                "contents",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            putJsonArray("parts") {
                                // Photos first, then the text that says what to do with them.
                                call.images.forEach { image ->
                                    add(
                                        buildJsonObject {
                                            putJsonObject("inlineData") {
                                                put("mimeType", image.mediaType)
                                                put("data", Base64.getEncoder().encodeToString(image.bytes))
                                            }
                                        },
                                    )
                                }
                                add(buildJsonObject { put("text", call.user) })
                            }
                        },
                    )
                },
            )
            putJsonObject("generationConfig") {
                putJsonObject("responseFormat") {
                    putJsonObject("text") {
                        put("mimeType", "APPLICATION_JSON")
                        put("schema", call.schema.element)
                    }
                }
                put("maxOutputTokens", MAX_TOKENS)
                putJsonObject("thinkingConfig") { put("thinkingLevel", "LOW") }
            }
        }
        val model = call.target.model
        // A listed model id; checked all the same, since it becomes part of the path.
        require(model.isNotEmpty() && model.all { it.isLetterOrDigit() || it in "-._" }) { "Not a model id" }
        return WireRequest("$baseUrl/models/$model:generateContent", mapOf("x-goog-api-key" to call.target.apiKey.orEmpty()), body)
    }

    private fun reasons(body: JsonObject?): List<String> =
        (body?.obj("error")?.get("details") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("reason") }

    override fun errorStatus(code: Int, body: JsonObject?): AiStatus = when {
        // A wrong key is a 400 here (INVALID_ARGUMENT with this reason), not a 401.
        reasons(body).any { it in BAD_KEY } -> AiStatus.AUTH
        // 402 is prepaid credit used up; 429 is a rate or quota limit, which may lift.
        else -> AiWire.statusFor(code)
    }

    override fun errorDetail(body: JsonObject?): String? = reasons(body).firstOrNull() ?: body?.obj("error")?.string("status")

    override fun read(call: AiCall, body: JsonObject, latencyMs: Long): AiResult {
        val servedBy = body.string("modelVersion") ?: call.target.model
        val usage = body.obj("usageMetadata")
        val attempts = listOf(
            if (usage == null) {
                call.upperBound
            } else {
                usage(
                    call,
                    prompt = usage.long("promptTokenCount") + usage.long("toolUsePromptTokenCount"),
                    cached = usage.long("cachedContentTokenCount"),
                    output = usage.long("candidatesTokenCount") + usage.long("thoughtsTokenCount"),
                )
            },
        )
        fun done(status: AiStatus, json: JsonObject? = null, text: String? = null, detail: String? = null) =
            AiResult(status, json, text, servedBy, attempts, latencyMs, detail)

        // A blocked prompt comes back with no candidates.
        body.obj("promptFeedback")?.string("blockReason")?.let { return done(AiStatus.REFUSAL, detail = token(it)) }
        val candidate = (body["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return done(AiStatus.INVALID, detail = "no candidate")
        val parts = (candidate.obj("content")?.get("parts") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        // Thought summaries are parts too; they aren't the reply.
        val text = parts.filter { (it["thought"] as? JsonPrimitive)?.booleanOrNull != true }.joinToString("") { it.string("text").orEmpty() }.ifEmpty { null }
        // The finish reason decides before any content is read.
        return when (val finish = candidate.string("finishReason")) {
            "STOP" -> when {
                text == null -> done(AiStatus.INVALID, detail = "no text")
                else -> AiWire.parseObject(text)?.let { done(AiStatus.OK, it, text) } ?: done(AiStatus.INVALID, text = text, detail = "not JSON")
            }
            "MAX_TOKENS" -> done(AiStatus.MAX_TOKENS, text = text)
            in BLOCKED -> done(AiStatus.REFUSAL, text = text, detail = finish)
            else -> done(AiStatus.INVALID, text = text, detail = finish?.let(::token) ?: "no finish reason")
        }
    }

    companion object {
        const val MAX_TOKENS = MealRequest.MAX_OUTPUT_TOKENS

        val BAD_KEY = setOf("API_KEY_INVALID", "API_KEY_SERVICE_BLOCKED", "API_KEY_EXPIRED")

        /** Finish reasons where Gemini withheld the reply (aistudio.google.com/api-reference/generate-content). */
        val BLOCKED = setOf(
            "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII",
            "IMAGE_SAFETY", "IMAGE_PROHIBITED_CONTENT", "IMAGE_RECITATION",
        )
    }
}
