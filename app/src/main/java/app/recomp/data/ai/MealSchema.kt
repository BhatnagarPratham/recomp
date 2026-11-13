package app.recomp.data.ai

import app.recomp.domain.ai.Confidence
import app.recomp.domain.ai.EstimatedItem
import app.recomp.domain.ai.MealEstimate
import app.recomp.domain.food.FoodDiet
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Describe it's reply schema (TRD §6.2), written by hand and the same for every provider. The Anthropic SDK's
 * class-based `.outputConfig(MyClass::class.java)` derives a schema with `java.lang.reflect.AnnotatedType`, which
 * Android doesn't have, so it would crash on the phone (M0). Every property is required, as structured outputs
 * expect with `additionalProperties: false`; `clarifying_question` is "" when there's none. There are no
 * array-length or number-range constraints: `MealCheck` checks the numbers.
 */
object MealSchema {
    private fun number(description: String) = mapOf("type" to "number", "description" to description)

    private val item = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "name" to mapOf("type" to "string", "description" to "The food or drink, as a short name"),
            "quantity" to number("How many units, as the user said or as is typical"),
            "unit" to mapOf("type" to "string", "description" to "The unit of quantity, e.g. piece, katori, cup, glass, g"),
            "grams" to number("Weight as eaten, in grams; liquids count 1 ml as 1 g"),
            "kcal" to number("Energy in kcal"),
            "protein_g" to number("Protein in grams"),
            "carbs_g" to number("Carbohydrate in grams"),
            "fat_g" to number("Fat in grams"),
            "fiber_g" to number("Fibre in grams"),
            "confidence" to mapOf("type" to "string", "enum" to listOf("high", "medium", "low")),
            "diet" to mapOf(
                "type" to "string",
                "enum" to listOf("vegan", "vegetarian", "egg", "fish", "meat"),
                "description" to "By the strictest ingredient: vegetarian has dairy or honey, fish includes seafood",
            ),
            "assumptions" to mapOf("type" to "string", "description" to "Size, recipe, oil or sugar assumed, in a few words"),
        ),
        "required" to listOf("name", "quantity", "unit", "grams", "kcal", "protein_g", "carbs_g", "fat_g", "fiber_g", "confidence", "assumptions", "diet"),
        "additionalProperties" to false,
    )

    val json: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "items" to mapOf("type" to "array", "items" to item),
            "clarifying_question" to mapOf("type" to "string", "description" to "One short question, or empty when none is needed"),
        ),
        "required" to listOf("items", "clarifying_question"),
        "additionalProperties" to false,
    )

    val schema = AiSchema("meal_estimate", json)

    @Serializable
    data class Item(
        val name: String,
        val quantity: Double,
        val unit: String,
        val grams: Double,
        val kcal: Double,
        @SerialName("protein_g") val proteinG: Double,
        @SerialName("carbs_g") val carbsG: Double,
        @SerialName("fat_g") val fatG: Double,
        @SerialName("fiber_g") val fiberG: Double = 0.0,
        val confidence: String = "medium",
        val assumptions: String = "",
        val diet: String? = null,
    )

    @Serializable
    data class Reply(val items: List<Item>, @SerialName("clarifying_question") val clarifyingQuestion: String = "")

    private val parser = Json { ignoreUnknownKeys = true }

    /** Parses the reply text. Throws [SerializationException] or [IllegalArgumentException] when it doesn't fit. */
    fun parse(text: String): MealEstimate {
        val reply = parser.decodeFromString<Reply>(text)
        return MealEstimate(
            items = reply.items.map {
                EstimatedItem(
                    name = it.name, quantity = it.quantity, unit = it.unit, grams = it.grams, kcal = it.kcal,
                    proteinG = it.proteinG, carbsG = it.carbsG, fatG = it.fatG, fiberG = it.fiberG,
                    confidence = Confidence.entries.firstOrNull { c -> c.name.equals(it.confidence.trim(), ignoreCase = true) } ?: Confidence.MEDIUM,
                    assumptions = it.assumptions,
                    diet = it.diet?.let { d -> FoodDiet.entries.firstOrNull { tag -> tag.name.equals(d.trim(), ignoreCase = true) } },
                )
            },
            clarifyingQuestion = reply.clarifyingQuestion.trim().ifEmpty { null },
        )
    }
}
