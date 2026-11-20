package app.recomp.ui.food

import app.recomp.data.repo.DescribedMeal
import app.recomp.domain.ai.AiProviderId
import app.recomp.domain.ai.AiState

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
        meal.alreadyCounted.takeIf { it.isNotEmpty() }?.let { "Left out of the AI's part, because it was already counted: ${it.joinToString()}." },
    ).joinToString(" ").ifEmpty { null }
}
