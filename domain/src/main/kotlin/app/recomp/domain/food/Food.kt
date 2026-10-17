package app.recomp.domain.food

import app.recomp.domain.persona.Diet
import kotlin.math.roundToInt

/**
 * A food's diet tag (TRD §6.4), from least to most restrictive to eat. [VEGETARIAN] means vegetarian
 * but not vegan: dairy, or honey. A recipe takes its strictest ingredient's tag.
 */
enum class FoodDiet { VEGAN, VEGETARIAN, EGG, FISH, MEAT }

/** How strict each diet is: it allows every food tag up to its own level (NU-11). */
private val Diet.level: Int
    get() = when (this) {
        Diet.VEGAN -> 0
        Diet.LACTO_VEGETARIAN -> 1
        Diet.EGGETARIAN -> 2
        Diet.PESCATARIAN -> 3
        Diet.NON_VEGETARIAN -> 4
    }

/** The food tags search shows for this diet: vegan, then dairy, egg, fish and finally meat. */
val Diet.allowedFoods: Set<FoodDiet> get() = FoodDiet.entries.take(level + 1).toSet()

fun Diet.allows(tag: FoodDiet): Boolean = tag.ordinal <= level

/** The tag a food you create gets by default: the most your diet allows, so it always shows for you. */
val Diet.ownFoodTag: FoodDiet get() = FoodDiet.entries[level]

enum class FoodSource { SEED, INDB, CUSTOM, AI, OFF, LABEL_OCR }

/** Why a food's numbers deserve a warning (TRD §6.4). */
enum class FoodCaveat {
    /** INDB counted all the oil used for deep frying as eaten, so the food reads too high. */
    FRYING_OIL,
}

/** NU-15. Ordered never changes a database food's numbers in M3b; the tag feeds HM-03 and AI estimates. */
enum class Origin { HOME, ORDERED, PACKAGED }

/** How a log row was made (TRD §4). A copy keeps its original's source. */
enum class LogSource { MANUAL, SAVED_MEAL, QUICK_ADD, AI_TEXT, AI_PHOTO, LOCAL_PARSE, LABEL, BARCODE }

/** Macros for an amount of food. Null carbs, fat or fibre are unknown, as in a quick add (NU-14). */
data class Macros(
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double? = 0.0,
    val fatG: Double? = 0.0,
    val fiberG: Double? = 0.0,
) {
    /** True when this entry's calories can't be split into protein, carbs and fat. */
    val unsplit: Boolean get() = carbsG == null || fatG == null

    companion object {
        val ZERO = Macros(0.0, 0.0)
    }
}

/** A food's values per 100 g (TRD §4). Log rows snapshot [forGrams], so editing a food never rewrites history. */
data class Per100g(val kcal: Double, val proteinG: Double, val carbsG: Double, val fatG: Double, val fiberG: Double = 0.0) {
    fun forGrams(grams: Double): Macros {
        val f = grams / 100.0
        return Macros(kcal * f, proteinG * f, carbsG * f, fatG * f, fiberG * f)
    }

    companion object {
        /** Per-serving values entered for a custom food (NU-12), converted to per 100 g. */
        fun fromServing(servingGrams: Double, serving: Macros): Per100g {
            require(servingGrams > 0) { "A serving needs a positive weight" }
            val f = 100.0 / servingGrams
            return Per100g(serving.kcal * f, serving.proteinG * f, (serving.carbsG ?: 0.0) * f, (serving.fatG ?: 0.0) * f, (serving.fiberG ?: 0.0) * f)
        }
    }
}

/** Display rounding: whole numbers for calories and grams of macros. */
fun Double.whole(): Int = roundToInt()
