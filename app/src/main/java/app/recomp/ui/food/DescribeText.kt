package app.recomp.ui.food

import app.recomp.data.repo.DescribedMeal
import app.recomp.domain.ai.AiProviderId
import app.recomp.domain.ai.AiState
import app.recomp.domain.ai.EstimatedItem
import app.recomp.domain.food.Portions
import app.recomp.ui.onboarding.GoalText
import kotlin.math.roundToInt

/** User-facing text for Describe it's local parser and the review it shares with *Will it fit?* (PRD §12.5). */
object DescribeText {
    /** Under the text box with AI off: what *Estimate* does without it (AI-04). */
    const val PARSER_ONLY =
        "Estimate matches what you type against your own foods and the database. Anything it can't find, you can search for or quick add."

    /** After the reason AI isn't ready: the parser still answers, and the draft is still there. */
    const val PARSER_MEANWHILE = "Estimate still matches your own foods and the database."

    /**
     * What the review says above its items, or null when there's nothing to say: why the AI's part is missing,
     * that the parser answered alone, or that the AI repeated something already counted.
     */
    fun notice(meal: DescribedMeal, provider: AiProviderId): String? = listOfNotNull(
        meal.aiFailure?.let { "${AiText.reason(it, provider)}, so these are only the foods matched from your own and the database." },
        "Matched from your own foods and the database, without AI.".takeIf { meal.aiFailure == null && meal.requestId == null && meal.aiState != AiState.READY && meal.aiState != AiState.OFF },
    ).joinToString(" ").ifEmpty { null }

    /** Why a save didn't go through. The repository's own checks say it in words; anything else gets a general line. */
    fun saveFailed(e: Exception): String = when (val reason = (e as? IllegalArgumentException)?.message) {
        "That food is gone" -> "Couldn't save: one of these foods is no longer in your foods (a backup import may have replaced them). Remove it, or search for it again."
        "Nothing to log" -> "Couldn't save: there's nothing to log. Add an item, or go back."
        null -> "Couldn't save this. Nothing was logged; try again."
        else -> "Couldn't save: ${reason.replaceFirstChar(Char::lowercase)}. Nothing was logged."
    }

    /** A best guess's line: "Best guess: Stewed apple · 1 bowl · 120 kcal". */
    fun guess(item: EstimatedItem): String {
        val unit = item.unit.trim().lowercase()
        val amount = if (unit in Portions.GRAM_LABELS && unit != "ml") "${item.grams.roundToInt()} g" else "${Portions.format(item.quantity)} ${item.unit.trim()}"
        return "Best guess: ${item.name} · $amount · ${GoalText.kcal(item.kcal)}"
    }
}
