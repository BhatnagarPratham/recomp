package app.recomp.domain.ai

import app.recomp.domain.food.Origin
import app.recomp.domain.food.Per100g
import app.recomp.domain.food.Portions
import app.recomp.domain.persona.Diet
import app.recomp.domain.persona.FoodRegion
import java.util.Locale
import kotlin.math.roundToInt

/** One of your own foods for the prompt: a custom or AI-learned food with its values and units. */
data class OwnFood(val name: String, val per100g: Per100g, val units: List<Pair<String, Double>>)

/** A clarifying question from an earlier estimate and your answer, for *Re-estimate*. */
data class Clarification(val question: String, val answer: String)

/**
 * Describe it's prompts (TRD §6.2). Built only from the persona (diet, region) and your own food data,
 * through the per-value tables below (PS-03). The system prompt doesn't depend on the meal, so it's the
 * same from call to call and can be cached; the origin and the description go in the user message.
 */
object MealPrompt {
    /** How many of your own foods the prompt lists. */
    const val TOP_FOODS = 30

    fun system(diet: Diet, region: FoodRegion, household: Map<String, Double>, foods: List<OwnFood>): String = buildString {
        appendLine(
            "You estimate the nutrition of one meal that the user describes in a food-logging app. Split the description " +
                "into its separate foods and drinks, one item each. For every item give the amount as the user would say it " +
                "(quantity and unit), its weight in grams as eaten, its calories, protein, carbohydrate, fat and fibre, a " +
                "confidence of high, medium or low, and a short note of the assumptions you made (size, recipe, oil, sugar).",
        )
        appendLine(
            "Tag each item's diet by its strictest ingredient: vegan; vegetarian (has dairy or honey); egg; fish (fish or " +
                "seafood); or meat.",
        )
        appendLine(
            "Keep each item's calories consistent with its macros (4 kcal per gram of protein and carbohydrate, 9 per gram " +
                "of fat). Use typical values for the dish; never leave out an item because you're unsure, and use a lower " +
                "confidence instead. Leave clarifying_question empty unless one answer would change the meal's total by more " +
                "than about a fifth; then ask that one short question.",
        )
        appendLine()
        appendLine("Diet: ${dietRule(diet)}")
        appendLine(
            "If the user names a dish their diet excludes, estimate it as named and say so in its assumptions; never swap it " +
                "silently.",
        )
        appendLine()
        appendLine("Portions and cooking: ${regionRule(region)}")
        if (household.isNotEmpty()) {
            appendLine(
                "The user's household measures: " +
                    household.entries.joinToString("; ") { (label, grams) -> "1 $label = ${grams.roundToInt()} g" } +
                    ". Liquids count 1 ml as 1 g.",
            )
        }
        appendLine()
        appendLine("Origin. The user message says which applies to the whole meal:")
        Origin.entries.forEach { appendLine("- ${originName(it)}: ${originRule(it)}") }
        if (foods.isNotEmpty()) {
            appendLine()
            appendLine(
                "The user's own foods, with values they set themselves. When the meal includes one of these, use its values " +
                    "and units rather than typical ones:",
            )
            foods.take(TOP_FOODS).forEach { appendLine("- ${ownFood(it)}") }
        }
    }.trimEnd()

    /** The meal itself: which origin applies, the description, and, for a re-estimate, the earlier question and your answer. */
    fun user(text: String, origin: Origin, clarification: Clarification? = null): String = buildString {
        appendLine("Origin: ${originName(origin)}")
        appendLine("Meal: ${text.trim()}")
        if (clarification != null) {
            appendLine()
            appendLine("You asked: ${clarification.question.trim()}")
            appendLine("The user answered: ${clarification.answer.trim()}")
            append("Estimate the meal again with this answer, and don't ask again.")
        }
    }.trimEnd()

    fun dietRule(diet: Diet): String = when (diet) {
        Diet.VEGAN ->
            "vegan. No meat, fish, egg, dairy (milk, curd, paneer, ghee, butter, cream) or honey. Assume the vegan version " +
                "of ambiguous dishes."
        Diet.LACTO_VEGETARIAN ->
            "lacto-vegetarian. No meat, fish or egg; dairy is fine. Assume the vegetarian version of ambiguous dishes, such " +
                "as biryani, momos, cutlets or curry."
        Diet.EGGETARIAN ->
            "eggetarian. No meat or fish; egg and dairy are fine. Assume the vegetarian version of ambiguous dishes unless " +
                "egg is named."
        Diet.PESCATARIAN ->
            "pescatarian. No meat; fish, seafood, egg and dairy are fine. Assume the vegetarian version of ambiguous dishes " +
                "unless fish or seafood is named."
        Diet.NON_VEGETARIAN ->
            "eats everything. Take dishes as named; when a dish is commonly made both with and without meat and the user " +
                "doesn't say, assume the most common version and say which in its assumptions."
    }

    fun regionRule(region: FoodRegion): String = when (region) {
        FoodRegion.INDIAN ->
            "Indian home food and Indian household portions: phulka or roti, katori, glass, piece, plate, spoon. Assume " +
                "home-style Indian recipes unless the origin says otherwise."
        FoodRegion.INTERNATIONAL ->
            "international household portions: cup, slice, piece, tablespoon, bowl. Assume common home recipes unless the " +
                "origin says otherwise."
    }

    private fun originName(origin: Origin) = when (origin) {
        Origin.HOME -> "Home"
        Origin.ORDERED -> "Ordered"
        Origin.PACKAGED -> "Packaged"
    }

    fun originRule(origin: Origin): String = when (origin) {
        Origin.HOME -> "cooked at home, with moderate oil and home portion sizes."
        Origin.ORDERED ->
            "restaurant or delivery food. Assume restaurant levels of oil, ghee, butter and cream, and restaurant portion " +
                "sizes, which usually carry more fat than the same dish made at home."
        Origin.PACKAGED -> "packaged food. Use typical label values for the product described."
    }

    private fun ownFood(food: OwnFood): String {
        val p = food.per100g
        val units = food.units.filter { (label, grams) -> label != Portions.GRAMS && grams > 0 }
            .joinToString(", ") { (label, grams) -> "1 $label = ${grams.roundToInt()} g" }
        return "${food.name}: per 100 g ${n(p.kcal)} kcal, ${n(p.proteinG)} g protein, ${n(p.carbsG)} g carbohydrate, " +
            "${n(p.fatG)} g fat, ${n(p.fiberG)} g fibre" + if (units.isNotEmpty()) "; $units" else ""
    }

    /** Whole numbers, or one decimal below 10. */
    private fun n(value: Double): String =
        if (value < 10 && value % 1.0 != 0.0) String.format(Locale.ROOT, "%.1f", value) else value.roundToInt().toString()
}
