package app.recomp.domain.persona

import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.NutritionCalculator
import app.recomp.domain.nutrition.Sex
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

    /** The body fat % a scan-based suggestion aims at (IB-05): the low end of a healthy, athletic range. */
    fun suggestedBodyFatPct(sex: Sex): Double = if (sex == Sex.MALE) 15.0 else 23.0

    /** A body-fat finish line's goal weight (IB-05, PRD §12.11): lean mass ÷ (1 − target %), to 0.1 kg. */
    fun forBodyFat(leanKg: Double, targetPct: Double): Double {
        require(targetPct > 0 && targetPct < 100) { "A body-fat target is a percentage" }
        return Math.round(leanKg / (1 - targetPct / 100) * 10) / 10.0
    }

    /**
     * With a recent on-protocol scan ([leanKg]), the weight at [suggestedBodyFatPct] for your sex, rounded to the
     * nearest kg (PRD §4.2). When you're already that lean, or without a scan, the BMI rule below.
     */
    fun suggest(heightCm: Double, weightKg: Double, leanKg: Double?, sex: Sex): Double {
        val fromScan = leanKg?.let { forBodyFat(it, suggestedBodyFatPct(sex)) }?.takeIf { it < weightKg }
        return fromScan?.roundToInt()?.toDouble() ?: suggest(heightCm, weightKg)
    }

    /**
     * The BMI-25 weight for your height; at a BMI of 25 or less, 5% below your current weight but not
     * below BMI 20, and never above your current weight. Rounded to the nearest kg.
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
