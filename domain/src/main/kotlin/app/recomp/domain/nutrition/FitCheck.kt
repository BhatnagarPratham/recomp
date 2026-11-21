package app.recomp.domain.nutrition

import app.recomp.domain.food.Macros
import kotlin.math.roundToInt

/**
 * *Will it fit?* (NU-18): what a dish would do to today's numbers, without logging it. [before] and [after] are
 * the goal's main number (GL-07); the calories and protein lines are there for every goal. Being over is a plain
 * number, so "left" may be negative.
 */
data class FitResult(
    val goal: Goal,
    val before: MainNumber,
    val after: MainNumber,
    val kcalTarget: Int,
    val kcalBefore: Int,
    val kcalAfter: Int,
    /** Null during the protein ramp's baseline week, which has no target. */
    val proteinTargetG: Int?,
    val proteinBeforeG: Int,
    val proteinAfterG: Int,
) {
    val kcalLeftBefore: Int get() = kcalTarget - kcalBefore
    val kcalLeftAfter: Int get() = kcalTarget - kcalAfter
    val proteinLeftBeforeG: Int? get() = proteinTargetG?.let { it - proteinBeforeG }
    val proteinLeftAfterG: Int? get() = proteinTargetG?.let { it - proteinAfterG }
}

object FitCheck {
    fun of(goal: Goal, kcalTarget: Int, protein: ProteinGoal, eaten: Intake, dish: Macros): FitResult {
        val after = Intake(eaten.kcal + dish.kcal.roundToInt(), eaten.proteinG + dish.proteinG.roundToInt())
        return FitResult(
            goal = goal,
            before = TodayNumbers.main(goal, kcalTarget, protein, eaten),
            after = TodayNumbers.main(goal, kcalTarget, protein, after),
            kcalTarget = kcalTarget,
            kcalBefore = eaten.kcal,
            kcalAfter = after.kcal,
            proteinTargetG = protein.todayG,
            proteinBeforeG = eaten.proteinG,
            proteinAfterG = after.proteinG,
        )
    }
}
