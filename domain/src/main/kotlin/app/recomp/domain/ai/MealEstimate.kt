package app.recomp.domain.ai

import app.recomp.domain.food.FoodDiet
import app.recomp.domain.food.Macros
import app.recomp.domain.food.Per100g
import kotlin.math.abs
import kotlin.math.max

enum class Confidence { HIGH, MEDIUM, LOW }

/** One item of an estimated meal (TRD §6.2's schema), in the units the model used. */
data class EstimatedItem(
    val name: String,
    val quantity: Double,
    val unit: String,
    val grams: Double,
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val fiberG: Double,
    val confidence: Confidence,
    val assumptions: String,
    /** What the item contains, by its strictest ingredient (TRD §6.4). Null when the model gave none it could read. */
    val diet: FoodDiet? = null,
) {
    val macros: Macros get() = Macros(kcal, proteinG, carbsG, fatG, fiberG)

    /** The calories the macros add up to: 4 per gram of protein and carbs, 9 per gram of fat. */
    val macroKcal: Double get() = 4 * proteinG + 4 * carbsG + 9 * fatG

    /** Values per 100 g, for the learning loop's food. Needs positive grams. */
    val per100g: Per100g get() = Per100g.fromServing(grams, macros)

    /**
     * The same food at another weight, with its macros scaled. The amount stays: you know you ate 2 phulka,
     * and a new weight says how big yours are (which the learning loop keeps as the unit's weight).
     */
    fun scaledTo(newGrams: Double): EstimatedItem {
        val f = newGrams / grams
        return copy(
            grams = newGrams,
            kcal = kcal * f,
            proteinG = proteinG * f,
            carbsG = carbsG * f,
            fatG = fatG * f,
            fiberG = fiberG * f,
        )
    }
}

/** A parsed reply. [clarifyingQuestion] is null when the model had none. */
data class MealEstimate(val items: List<EstimatedItem>, val clarifyingQuestion: String?)

/** A reply after validation: the kept items, the names of rejected ones, and totals from the kept items. */
data class CheckedMeal(val items: List<EstimatedItem>, val rejected: List<String>, val clarifyingQuestion: String?) {
    val totals: Macros get() = MealCheck.totals(items)
}

/** Client-side validation (TRD §6.2). Nothing the model sends is trusted as given. */
object MealCheck {
    /** An item's kcal may be this far from 4P + 4C + 9F before it's flagged. */
    const val MISMATCH = 0.15

    /** Differences below this many kcal never flag, so a garnish of 5 kcal against 4 doesn't. */
    const val MIN_GAP_KCAL = 10.0

    /** Usable: a name, positive grams, and every number finite and not negative. */
    fun usable(item: EstimatedItem): Boolean {
        val numbers = listOf(item.quantity, item.grams, item.kcal, item.proteinG, item.carbsG, item.fatG, item.fiberG)
        return item.name.isNotBlank() && item.grams > 0 && numbers.all { it.isFinite() && it >= 0 }
    }

    /** Whether the item's calories disagree with its macros by more than 15% (and at least 10 kcal). */
    fun mismatched(item: EstimatedItem): Boolean {
        val gap = abs(item.kcal - item.macroKcal)
        return gap > max(MISMATCH * item.macroKcal, MIN_GAP_KCAL)
    }

    fun check(estimate: MealEstimate): CheckedMeal {
        val (kept, rejected) = estimate.items.partition(::usable)
        return CheckedMeal(
            items = kept.map { it.copy(name = it.name.trim(), assumptions = it.assumptions.trim()) },
            rejected = rejected.map { it.name.trim().ifEmpty { "An unnamed item" } },
            clarifyingQuestion = estimate.clarifyingQuestion?.trim()?.ifEmpty { null },
        )
    }

    /** Totals recomputed from the items. */
    fun totals(items: List<EstimatedItem>): Macros = Macros(
        kcal = items.sumOf { it.kcal },
        proteinG = items.sumOf { it.proteinG },
        carbsG = items.sumOf { it.carbsG },
        fatG = items.sumOf { it.fatG },
        fiberG = items.sumOf { it.fiberG },
    )
}
