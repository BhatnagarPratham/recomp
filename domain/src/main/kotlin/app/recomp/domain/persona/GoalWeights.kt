package app.recomp.domain.persona

import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.NutritionCalculator
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Suggested goal weights and default finish lines (PRD §4.2, §4.7, TRD §5.8). */
object GoalWeights {
    const val SUGGESTED_BMI = 25.0
    const val LOWEST_SUGGESTED_BMI = 20.0
    const val LEAN_SUGGESTION_FRACTION = 0.95
    const val LEAN_BULK_WEEKS = 16L

    /**
     * The BMI-25 weight for your height; at a BMI of 25 or less, 5% below your current weight but not
     * below BMI 20, and never above your current weight. Rounded to the nearest kg.
     * TODO(M4): with a recent scan, suggest lean mass ÷ (1 − target body fat) instead (IB-05).
     */
    fun suggest(heightCm: Double, weightKg: Double): Double {
        val raw = if (Bmi.of(weightKg, heightCm) > SUGGESTED_BMI) {
            Bmi.weightAt(SUGGESTED_BMI, heightCm)
        } else {
            min(weightKg, max(weightKg * LEAN_SUGGESTION_FRACTION, Bmi.weightAt(LOWEST_SUGGESTED_BMI, heightCm)))
        }
        return raw.roundToInt().toDouble()
    }

    /** Fat loss and recomp aim at the goal weight; a lean bulk ends 16 weeks out; Maintain has none. */
    fun defaultFinishLine(goal: Goal, today: LocalDate): FinishLine? = when (goal) {
        Goal.FAT_LOSS, Goal.RECOMP -> FinishLine.GoalWeight
        Goal.LEAN_BULK -> FinishLine.ByDate(today.plusWeeks(LEAN_BULK_WEEKS))
        Goal.MAINTAIN -> null
    }

    /** Whether the goal suggests a goal weight at all. Others may still set one. */
    fun suggestsGoalWeight(goal: Goal) = goal == Goal.FAT_LOSS || goal == Goal.RECOMP
}

/** The pace picker's approximate projection (GL-04, TRD §5.8). Recomputed weekly from the trend once M4 has one. */
object Projection {
    /** Negative while losing. */
    fun kgPerWeek(adjustmentKcal: Double): Double = adjustmentKcal * 7 / NutritionCalculator.KCAL_PER_KG_FAT

    /** Weeks until [targetKg] at [kgPerWeek], or null when there's no movement or it moves the other way. */
    fun weeksTo(currentKg: Double, targetKg: Double, kgPerWeek: Double): Double? {
        val gap = targetKg - currentKg
        if (gap == 0.0) return 0.0
        if (kgPerWeek == 0.0 || (gap > 0) != (kgPerWeek > 0)) return null
        return abs(gap / kgPerWeek)
    }

    fun finishDate(today: LocalDate, weeks: Double): LocalDate = today.plusDays(ceil(weeks * 7).toLong())

    /** Expected change by [date], in kg. */
    fun changeBy(today: LocalDate, date: LocalDate, kgPerWeek: Double): Double =
        kgPerWeek * java.time.temporal.ChronoUnit.DAYS.between(today, date) / 7.0
}
