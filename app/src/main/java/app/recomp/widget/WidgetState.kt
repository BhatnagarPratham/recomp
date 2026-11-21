package app.recomp.widget

import app.recomp.data.repo.TodaySnapshot
import app.recomp.domain.nutrition.MainNumber
import app.recomp.ui.onboarding.GoalText

/**
 * What the home-screen widget shows (HM-05): the goal's main number, protein (or calories when protein is the
 * main number), and the next workout or a rest day. [ready] is false before onboarding has set a target.
 */
data class WidgetState(
    val ready: Boolean,
    val mainLabel: String = "",
    val mainValue: String = "",
    val second: String = "",
    val workout: String? = null,
) {
    companion object {
        val SETUP = WidgetState(ready = false)

        fun of(s: TodaySnapshot): WidgetState {
            val food = s.food ?: return SETUP
            val proteinLeft = food.proteinTargetG?.let { (it - food.proteinEatenG).coerceAtLeast(0) }
            val protein = if (proteinLeft != null) "Protein $proteinLeft g left" else "Protein ${food.proteinEatenG} g so far"
            val kcalLeft = food.kcalTarget - food.kcalEaten
            val calories = if (kcalLeft >= 0) "Calories ${GoalText.n(kcalLeft)} left" else "Calories ${GoalText.n(-kcalLeft)} over"
            val (label, value, second) = when (val m = food.main) {
                is MainNumber.CaloriesLeft -> Triple("Calories left", if (m.left >= 0) GoalText.n(m.left) else "${GoalText.n(-m.left)} over", protein)
                is MainNumber.ProteinLeft ->
                    if (m.left == null) Triple("Protein so far", "${m.eaten} g", calories) else Triple("Protein left", "${m.left} g", calories)
                is MainNumber.CaloriesInRange -> Triple(
                    "Calories",
                    when {
                        m.inRange -> "In range"
                        m.aboveBy > 0 -> "${GoalText.n(m.aboveBy)} over"
                        else -> "${GoalText.n(m.belowBy)} to go"
                    },
                    protein,
                )
                is MainNumber.CaloriesToEat -> Triple("Calories to eat", if (m.left > 0) GoalText.n(m.left) else "Target reached", protein)
            }
            return WidgetState(
                ready = true,
                mainLabel = label,
                mainValue = value,
                second = second,
                workout = when {
                    s.workoutActive -> "Workout in progress"
                    s.rest != null -> "Rest day"
                    s.nextRoutine != null -> "${s.nextRoutine} next"
                    else -> null
                },
            )
        }
    }
}

/** Which layout fits the widget's size (review N6): one row at 4×1, a compact row when narrower, a block at 2×2. */
enum class WidgetLayout {
    NARROW, WIDE, SQUARE;

    companion object {
        fun of(widthDp: Float, heightDp: Float): WidgetLayout = when {
            heightDp >= 110f -> SQUARE
            widthDp >= 250f -> WIDE
            else -> NARROW
        }
    }
}
