package app.recomp.data.ai

import java.util.Base64
import app.recomp.domain.ai.AiProviderId
import app.recomp.domain.ai.AiStatus
import app.recomp.domain.ai.MealRequest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * OpenAI's Chat Completions, and any server that speaks it (AI-05, TRD §6.2).
 *
 * **OpenAI:** `response_format` with a strict `json_schema`, the system prompt as a `developer` message,
 * `reasoning_effort: low` (the lowest every listed model takes) and `max_completion_tokens`, which covers
 * reasoning and the reply together.
 *
 * **OpenAI-compatible** (OpenRouter, Ollama and the like), at the base URL you typed: the same request with a
 * `system` message, `max_tokens` and no reasoning setting, since servers differ on those. A server that refuses
 * the schema is asked again in plain JSON mode with the schema written into the prompt; that's remembered for the
 * server and model while the app runs. Either way the reply goes through the same validation. The key goes only
 * over https: a server reached over plain http is never sent one.
 *
 * **Usage.** OpenAI counts cached tokens inside `prompt_tokens` and reasoning tokens inside `completion_tokens`,
 * so cached input is taken out of the input and the output is used as it is. A compatible server that reports no
 * usage is costed at the request's upper bound.
 */
@Singleton
class OpenAiProvider @Inject constructor() : HttpProvider() {
    /** Tests point OpenAI's own adapter at a local server. */
    internal var baseUrl: String = "https://api.openai.com/v1"

    /** Servers and models that refused the schema and took plain JSON mode: "base model". */
    private val jsonModeOnly = ConcurrentHashMap.newKeySet<String>()

    private fun compatible(call: AiCall) = call.target.provider == AiProviderId.COMPATIBLE

    private fun base(call: AiCall): String = if (compatible(call)) checkNotNull(call.target.baseUrl) { "No base URL" }.base else baseUrl

    private fun serverKey(call: AiCall) = "${base(call)} ${call.target.model}"

    override fun firstMode(call: AiCall): WireMode = if (compatible(call) && serverKey(call) in jsonModeOnly) WireMode.JSON_OBJECT else WireMode.SCHEMA

    override fun nextMode(call: AiCall, mode: WireMode, code: Int): WireMode? =
        if (compatible(call) && mode == WireMode.SCHEMA && code in SCHEMA_REFUSED) WireMode.JSON_OBJECT else null

    override fun accepted(call: AiCall, mode: WireMode) {
        if (compatible(call) && mode == WireMode.JSON_OBJECT) jsonModeOnly += serverKey(call)
    }

    override fun request(call: AiCall, mode: WireMode): WireRequest {
        val compatible = compatible(call)
        val key = call.target.apiKey
        // Never a key over plain http (AI-05): a compatible server gets it only when its URL is https.
        val sendKey = key != null && (!compatible || call.target.baseUrl?.sendsKey == true)
        val system = if (mode == WireMode.SCHEMA) call.system else
            call.system + "\n\nReply with one JSON object and nothing else. It must match this JSON Schema:\n" + call.schema.element
        val body = buildJsonObject {
            put("model", call.target.model)
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject { put("role", if (compatible) "system" else "developer"); put("content", system) })
                    add(buildJsonObject { put("role", "user"); put("content", userContent(call)) })
                },
            )
            putJsonObject("response_format") {
                if (mode == WireMode.SCHEMA) {
                    put("type", "json_schema")
                    putJsonObject("json_schema") {
                        put("name", call.schema.name)
                        put("strict", true)
                        put("schema", call.schema.element)
                    }
                } else {
                    put("type", "json_object")
                }
            }
            if (compatible) {
                put("max_tokens", MAX_TOKENS)
            } else {
                put("max_completion_tokens", MAX_TOKENS)
                put("reasoning_effort", "low")
            }
        }
        return WireRequest("${base(call)}/chat/completions", if (sendKey) mapOf("Authorization" to "Bearer $key") else emptyMap(), body)
    }

    /** The text alone, or photos (as data URLs) followed by the text that says what to do with them. */
    private fun userContent(call: AiCall) = if (call.images.isEmpty()) JsonPrimitive(call.user) else buildJsonArray {
        call.images.forEach { image ->
            add(
                buildJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") { put("url", "data:${image.mediaType};base64," + Base64.getEncoder().encodeToString(image.bytes)) }
                },
            )
        }
        add(buildJsonObject { put("type", "text"); put("text", call.user) })
    }

    override fun errorStatus(code: Int, body: JsonObject?): AiStatus {
        val error = body?.obj("error")
        val reasons = listOfNotNull(error?.string("code"), error?.string("type"))
        return when {
            // Out of credit or over a spend limit arrives as a 429 that waiting won't fix.
            reasons.any { it in OUT_OF_CREDIT } -> AiStatus.BILLING
            else -> AiWire.statusFor(code)
        }
    }

    override fun errorDetail(body: JsonObject?): String? = body?.obj("error")?.let { it.string("code") ?: it.string("type") }

    override fun read(call: AiCall, body: JsonObject, latencyMs: Long): AiResult {
        val servedBy = body.string("model") ?: call.target.model
        val usage = body.obj("usage")
        val attempts = listOf(
            if (usage == null) {
                call.upperBound
            } else {
                val details = usage.obj("prompt_tokens_details")
                // A cache write is charged on top of the input it is part of, which over-counts a little and never under-counts.
                usage(call, usage.long("prompt_tokens"), details?.long("cached_tokens") ?: 0, usage.long("completion_tokens"), details?.long("cache_write_tokens") ?: 0)
            },
        )
        fun done(status: AiStatus, json: JsonObject? = null, text: String? = null, detail: String? = null) =
            AiResult(status, json, text, servedBy, attempts, latencyMs, detail)

        // OpenRouter can answer 200 with an error inside.
        body.obj("error")?.let { error ->
            val code = error.string("code")?.toIntOrNull()
            return done(if (code != null) errorStatus(code, body) else AiStatus.SERVER, detail = errorDetail(body)?.let(::token))
        }
        val choice = (body["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return done(AiStatus.INVALID, detail = "no choice")
        val message = choice.obj("message")
        val text = message?.get("content")?.let(::text)?.ifEmpty { null }
        val finish = choice.string("finish_reason")
        // The refusal and the finish reason decide before any content is read.
        return when {
            !message?.string("refusal").isNullOrBlank() -> done(AiStatus.REFUSAL, text = text, detail = "refusal")
            finish == "content_filter" -> done(AiStatus.REFUSAL, text = text, detail = finish)
            finish == "length" -> done(AiStatus.MAX_TOKENS, text = text)
            finish != null && finish != "stop" -> done(AiStatus.INVALID, text = text, detail = token(finish))
            text == null -> done(AiStatus.INVALID, detail = "no text")
            else -> AiWire.parseObject(text)?.let { done(AiStatus.OK, it, text) } ?: done(AiStatus.INVALID, text = text, detail = "not JSON")
        }
    }

    /** `content` as a string, or the text parts of an array, which some servers send. */
    private fun text(content: kotlinx.serialization.json.JsonElement): String = when (content) {
        is JsonNull -> ""
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.joinToString("") { part -> (part as? JsonObject)?.string("text").orEmpty() }
        else -> ""
    }

    companion object {
        const val MAX_TOKENS = MealRequest.MAX_OUTPUT_TOKENS

        /** How servers that can't take `response_format: json_schema` answer: a bad request, no such route, or not implemented. */
        val SCHEMA_REFUSED = setOf(400, 404, 415, 422, 501)

        /** OpenAI's codes and types for an account that can't pay (developers.openai.com/api/docs/guides/error-codes). */
        val OUT_OF_CREDIT = setOf(
            "credit_balance_exhausted", "insufficient_quota", "billing_hard_limit_reached",
            "organization_spend_limit_exceeded", "project_spend_limit_exceeded", "organization_usage_limit_exceeded",
        )
    }
}
