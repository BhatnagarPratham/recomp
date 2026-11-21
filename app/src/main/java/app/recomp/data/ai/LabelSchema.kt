package app.recomp.data.ai

import app.recomp.domain.ai.LabelEstimate
import app.recomp.domain.ai.LabelPrompt
import app.recomp.domain.food.FoodDiet
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Scan label's reply schema (NU-19, `AiKind.LABEL`; TRD §6.2), written by hand like [MealSchema] and the same for
 * every provider. Every property is required; a value the label doesn't show is [LabelPrompt.MISSING] rather than
 * null, since not every provider's schema subset takes a null type. `LabelCheck` checks the numbers.
 */
object LabelSchema {
    private fun number(description: String) = mapOf("type" to "number", "description" to "$description; ${LabelPrompt.MISSING.toInt()} if the label doesn't show it")

    val json: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "name" to mapOf("type" to "string", "description" to "The product's name as printed, or empty"),
            "brand" to mapOf("type" to "string", "description" to "The brand as printed, or empty"),
            "kcal_100g" to number("Energy per 100 g in kcal"),
            "protein_100g" to number("Protein per 100 g in grams"),
            "carbs_100g" to number("Carbohydrate per 100 g in grams"),
            "fat_100g" to number("Total fat per 100 g in grams"),
            "fiber_100g" to number("Dietary fibre per 100 g in grams"),
            "serving_g" to number("One serving's weight in grams"),
            "diet" to mapOf("type" to "string", "enum" to listOf("vegan", "vegetarian", "egg", "fish", "meat", "unknown")),
        ),
        "required" to listOf("name", "brand", "kcal_100g", "protein_100g", "carbs_100g", "fat_100g", "fiber_100g", "serving_g", "diet"),
        "additionalProperties" to false,
    )

    val schema = AiSchema("nutrition_label", json)

    @Serializable
    data class Reply(
        val name: String = "",
        val brand: String = "",
        @SerialName("kcal_100g") val kcal: Double = LabelPrompt.MISSING,
        @SerialName("protein_100g") val protein: Double = LabelPrompt.MISSING,
        @SerialName("carbs_100g") val carbs: Double = LabelPrompt.MISSING,
        @SerialName("fat_100g") val fat: Double = LabelPrompt.MISSING,
        @SerialName("fiber_100g") val fiber: Double = LabelPrompt.MISSING,
        @SerialName("serving_g") val serving: Double = LabelPrompt.MISSING,
        val diet: String = "unknown",
    )

    private val parser = Json { ignoreUnknownKeys = true }

    /** Parses the reply text, before `LabelCheck`. Throws on a reply that doesn't fit. */
    fun parse(text: String): LabelEstimate {
        val r = parser.decodeFromString<Reply>(text)
        fun v(x: Double) = x.takeIf { it != LabelPrompt.MISSING }
        return LabelEstimate(
            name = r.name, brand = r.brand, kcal = v(r.kcal), proteinG = v(r.protein), carbsG = v(r.carbs), fatG = v(r.fat),
            fiberG = v(r.fiber), servingG = v(r.serving),
            diet = FoodDiet.entries.firstOrNull { it.name.equals(r.diet.trim(), ignoreCase = true) },
        )
    }
}
