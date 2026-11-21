package app.recomp.ui.food

import app.recomp.domain.nutrition.FitResult
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.MainNumber
import app.recomp.ui.onboarding.GoalText

/**
 * *Will it fit?* in words (NU-18, PRD §12.5): today's numbers before and after the dish, the goal's main number
 * first. Being over is a plain number, never a warning.
 */
object FitText {
    /** "Protein left 62 g → 30 g", "… → 12 g over", or "Protein 48 g → 80 g" in the ramp's baseline week, which has no target. */
    fun protein(fit: FitResult): String {
        val before = fit.proteinLeftBeforeG
        val after = fit.proteinLeftAfterG
        if (before == null || after == null) return "Protein ${fit.proteinBeforeG} g → ${fit.proteinAfterG} g"
        return when {
            before < 0 -> "Protein ${-before} g over → ${-after} g over"
            after < 0 -> "Protein left $before g → ${-after} g over"
            else -> "Protein left $before g → $after g"
        }
    }

    /** The calories line, as the goal reads calories (PRD §4.8). */
    fun calories(fit: FitResult): String = when (val after = fit.after.takeIf { fit.goal == Goal.MAINTAIN || fit.goal == Goal.LEAN_BULK }) {
        is MainNumber.CaloriesInRange ->
            "Calories ${GoalText.n(fit.kcalBefore)} → ${GoalText.n(fit.kcalAfter)} (range ${GoalText.n(after.band.first)}–${GoalText.n(after.band.last)})"
        is MainNumber.CaloriesToEat -> when {
            fit.kcalLeftBefore < 0 -> "Calories ${over(fit.kcalLeftBefore)} → ${over(fit.kcalLeftAfter)}"
            fit.kcalLeftAfter < 0 -> "Calories to eat ${GoalText.n(fit.kcalLeftBefore)} → ${over(fit.kcalLeftAfter)}"
            else -> "Calories to eat ${GoalText.n(fit.kcalLeftBefore)} → ${GoalText.n(fit.kcalLeftAfter)}"
        }
        else -> "Calories ${left(fit.kcalLeftBefore)} → ${left(fit.kcalLeftAfter)}"
    }

    /** The main number's line, then the other: recomp leads with protein, every other goal with calories. */
    fun lines(fit: FitResult): Pair<String, String> =
        if (fit.goal == Goal.RECOMP) protein(fit) to calories(fit) else calories(fit) to protein(fit)

    /** Both on one line: "Protein left 62 g → 30 g · calories 380 left → 40 over". */
    fun line(fit: FitResult): String = lines(fit).let { (main, other) -> "$main · ${other.replaceFirstChar(Char::lowercase)}" }

    private fun left(kcal: Int) = if (kcal >= 0) "${GoalText.n(kcal)} left" else over(kcal)

    private fun over(kcal: Int) = "${GoalText.n(-kcal)} over"
}
