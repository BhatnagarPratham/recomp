package app.recomp.data.ai

import app.recomp.domain.ai.GoalInterpretation
import app.recomp.domain.ai.GoalPrompt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The goal interpretation's reply schema (GL-03, TRD §5.7), written by hand like [MealSchema] and the same for
 * every provider: an intent and, at most, one follow-up question (`""` when there's none). Both are required,
 * with `additionalProperties: false`, as each provider's strict mode expects.
 */
object GoalSchema {
    val json: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "intent" to mapOf("type" to "string", "enum" to GoalPrompt.INTENTS, "description" to "What the person wants; unclear when the text doesn't say"),
            "follow_up_question" to mapOf("type" to "string", "description" to "One short question that would settle it, or empty when none is needed"),
        ),
        "required" to listOf("intent", "follow_up_question"),
        "additionalProperties" to false,
    )

    val schema = AiSchema("goal_intent", json)

    @Serializable
    data class Reply(val intent: String, @SerialName("follow_up_question") val followUpQuestion: String = "")

    private val parser = Json { ignoreUnknownKeys = true }

    /**
     * Parses the reply. An intent outside the five is a reply that doesn't fit, so it throws
     * [SerializationException] like any other; `unclear` is a fit and comes back as a null intent. With
     * [answered] (the follow-up was already asked) a second question is dropped: there's at most one.
     */
    fun parse(text: String, answered: Boolean): GoalInterpretation {
        val reply = parser.decodeFromString<Reply>(text)
        val name = reply.intent.trim().lowercase()
        if (name !in GoalPrompt.INTENTS) throw SerializationException("Not an intent")
        val question = reply.followUpQuestion.trim().replace(Regex("\\s+"), " ").take(GoalPrompt.MAX_QUESTION)
        return GoalInterpretation(GoalPrompt.intent(name), question.takeIf { it.isNotEmpty() && !answered })
    }
}
