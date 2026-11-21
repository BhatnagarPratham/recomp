package app.recomp.data.ai

import app.recomp.domain.coach.CoachReply
import app.recomp.domain.coach.RawSuggestion
import app.recomp.domain.coach.SuggestionKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The weekly programme review's reply schema (HM-06, TRD §10 M11), written by hand like [MealSchema] and the same
 * for every provider. `kind` is an enum; exercises and routines are the report's own refs (`e1…`, `r1…`); `value` is
 * `{min, max}`, with min = max for a single number. Every property is required, and unused ones are "" or 0. There
 * are no array-length or number-range constraints: `SuggestionCheck` keeps at most 5 and checks every range.
 */
object CoachSchema {
    private fun string(description: String) = mapOf("type" to "string", "description" to description)

    private val value = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "min" to mapOf("type" to "number"),
            "max" to mapOf("type" to "number"),
        ),
        "required" to listOf("min", "max"),
        "additionalProperties" to false,
    )

    private val suggestion = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "kind" to mapOf("type" to "string", "enum" to SuggestionKind.entries.map { it.name }),
            "routine_ref" to string("A routine's ref from the report, e.g. r1, or empty"),
            "exercise_ref" to string("An exercise's ref from the report, e.g. e3, or empty"),
            "replacement_ref" to string("For SWAP, the ref of the exercise to swap in, or empty"),
            "value" to value,
            "reason" to string("Why, in one or two sentences"),
        ),
        "required" to listOf("kind", "routine_ref", "exercise_ref", "replacement_ref", "value", "reason"),
        "additionalProperties" to false,
    )

    val json: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "summary" to string("Two or three sentences on the week"),
            "suggestions" to mapOf("type" to "array", "items" to suggestion),
        ),
        "required" to listOf("summary", "suggestions"),
        "additionalProperties" to false,
    )

    val schema = AiSchema("programme_review", json)

    @Serializable
    data class Value(val min: Double = 0.0, val max: Double = 0.0)

    @Serializable
    data class Item(
        val kind: String,
        @SerialName("routine_ref") val routineRef: String = "",
        @SerialName("exercise_ref") val exerciseRef: String = "",
        @SerialName("replacement_ref") val replacementRef: String = "",
        val value: Value = Value(),
        val reason: String = "",
    )

    @Serializable
    data class Reply(val summary: String, val suggestions: List<Item> = emptyList())

    private val parser = Json { ignoreUnknownKeys = true }

    /** Parses the reply text. Throws [SerializationException] or [IllegalArgumentException] when it doesn't fit. */
    fun parse(text: String): CoachReply {
        val reply = parser.decodeFromString<Reply>(text)
        return CoachReply(
            summary = reply.summary.trim(),
            suggestions = reply.suggestions.map { RawSuggestion(it.kind, it.routineRef, it.exerciseRef, it.replacementRef, it.value.min, it.value.max, it.reason) },
        )
    }
}
