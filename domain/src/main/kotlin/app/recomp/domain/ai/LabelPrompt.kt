package app.recomp.domain.ai

import app.recomp.domain.food.FoodDiet
import app.recomp.domain.food.LabelBasis
import app.recomp.domain.food.LabelValues
import kotlin.math.roundToInt

/**
 * What the AI read from a nutrition label (NU-19, `AiKind.LABEL`): values per 100 g, with null for anything the
 * label doesn't show. [name] and [brand] are null when it can't tell.
 */
data class LabelEstimate(
    val name: String?,
    val brand: String?,
    val kcal: Double?,
    val proteinG: Double?,
    val carbsG: Double?,
    val fatG: Double?,
    val fiberG: Double?,
    val servingG: Double?,
    val diet: FoodDiet?,
) {
    /** As the form takes it: per 100 g, like the on-device reading it replaces. */
    fun values(): LabelValues = LabelValues(LabelBasis.PER_100G, kcal, proteinG, carbsG, fatG, fiberG, servingG)
}

/**
 * The label request's prompts (TRD §6.2). With AI on, the photo and the on-device recogniser's text go to the
 * provider for a cleaner fill (PRD §12.14). Nothing about the person goes with them: a label is the same for
 * everyone, so the system prompt has no persona in it.
 */
object LabelPrompt {
    /** The reply's stand-in for "not on the label", since not every provider's schema subset takes null. */
    const val MISSING = -1.0

    val system: String =
        "You read a packaged food's nutrition label from a photo, helped by text an on-device recogniser read from the " +
            "same photo (it may have mistakes). Give the values per 100 g: when the label only gives them per serving, " +
            "convert using the serving's weight in grams. Energy is in kcal; convert kJ by dividing by 4.184. Give " +
            "$MISSING for any value the label doesn't show, never a guess. Give the product's name and brand as printed, or " +
            "an empty string. Tag the diet from the ingredients or the veg/non-veg mark if visible: vegan, vegetarian " +
            "(dairy or honey), egg, fish (fish or seafood), meat, or unknown."

    fun user(ocrText: String): String = buildString {
        appendLine("The photo is attached.")
        val text = ocrText.trim()
        if (text.isEmpty()) append("The on-device recogniser read no text.")
        else {
            appendLine("Text the on-device recogniser read:")
            append(text.take(MAX_OCR_CHARS))
        }
    }.trimEnd()

    /** Enough for any nutrition panel; a whole wrapper's text adds cost and nothing else. */
    const val MAX_OCR_CHARS = 3_000
}

/** Checks a label reply (TRD §6.2: nothing the model sends is trusted as given). */
object LabelCheck {
    /**
     * Drops impossible values (negative, a nutrient over 100 g per 100 g, energy over 950 kcal), turns [LabelPrompt.MISSING]
     * into null, and rounds to one decimal. Null when nothing usable is left.
     */
    fun check(raw: LabelEstimate): LabelEstimate? {
        fun grams(x: Double?) = x?.takeIf { it.isFinite() && it >= 0 && it <= 100 }?.round1()
        val checked = raw.copy(
            name = raw.name?.trim()?.takeIf { it.isNotEmpty() },
            brand = raw.brand?.trim()?.takeIf { it.isNotEmpty() },
            kcal = raw.kcal?.takeIf { it.isFinite() && it >= 0 && it <= 950 }?.round1(),
            proteinG = grams(raw.proteinG), carbsG = grams(raw.carbsG), fatG = grams(raw.fatG), fiberG = grams(raw.fiberG),
            servingG = raw.servingG?.takeIf { it.isFinite() && it > 0 && it <= 2_000 }?.round1(),
        )
        return checked.takeUnless { it.values().empty && it.name == null }
    }

    private fun Double.round1(): Double = (this * 10).roundToInt() / 10.0
}
