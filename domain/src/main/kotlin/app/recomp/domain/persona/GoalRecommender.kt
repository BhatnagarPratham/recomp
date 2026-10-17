package app.recomp.domain.persona

import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.Sex

/** "What matters most to you?" (PRD §4.6). AI maps free text to one of these too (GL-03), never to a goal. */
enum class Intent { LOSE_FAT, BUILD_MUSCLE, BOTH, MAINTAIN }

enum class AdviceReason {
    UNDERWEIGHT,
    LOSE_FAT,
    BOTH_NEW_TO_LIFTING,
    BOTH_HIGHER_FAT,
    BOTH_LEAN_AND_TRAINED,
    MUSCLE_HIGH_FAT,
    MUSCLE,
    STAY,
}

/** What decided a fat-based rule: a body-fat % when known, otherwise BMI. */
data class FatBasis(val bodyFatPct: Double?, val bmi: Double) {
    val usesBodyFat: Boolean get() = bodyFatPct != null
}

/** A recommended goal with the reason it was chosen (TRD §5.7). You confirm it or pick another card. */
data class GoalAdvice(val goal: Goal, val reason: AdviceReason, val basis: FatBasis)

/** The thresholds in one table (TRD §5.7), so they're easy to tune. */
object GoalThresholds {
    const val UNDERWEIGHT_BMI = 18.5
    const val HIGH_FAT_BMI = 27.0
    const val VERY_HIGH_FAT_BMI = 30.0

    fun highFatPct(sex: Sex): Double = if (sex == Sex.MALE) 20.0 else 28.0 // pii-ok: thresholds

    fun veryHighFatPct(sex: Sex): Double = if (sex == Sex.MALE) 25.0 else 32.0 // pii-ok: thresholds
}

/**
 * The offline goal suggestion (GL-02, TRD §5.7). Pure rules, so the outcome is testable and identical
 * with AI on or off.
 */
object GoalRecommender {
    /**
     * @param experience training age: under a year is [Experience.BEGINNER].
     * @param bodyFatPct from a scan in the last 60 days, or typed for this suggestion only; null falls back to BMI.
     */
    fun recommend(intent: Intent, experience: Experience, sex: Sex, bmi: Double, bodyFatPct: Double? = null): GoalAdvice {
        val basis = FatBasis(bodyFatPct, bmi)
        val underweight = bmi < GoalThresholds.UNDERWEIGHT_BMI
        val highFat = bodyFatPct?.let { it >= GoalThresholds.highFatPct(sex) } ?: (bmi >= GoalThresholds.HIGH_FAT_BMI)
        val veryHighFat = bodyFatPct?.let { it >= GoalThresholds.veryHighFatPct(sex) } ?: (bmi >= GoalThresholds.VERY_HIGH_FAT_BMI)
        val newToLifting = experience == Experience.BEGINNER

        fun advice(goal: Goal, reason: AdviceReason) = GoalAdvice(goal, reason, basis)
        return when (intent) {
            Intent.LOSE_FAT -> if (underweight) advice(Goal.MAINTAIN, AdviceReason.UNDERWEIGHT) else advice(Goal.FAT_LOSS, AdviceReason.LOSE_FAT)
            Intent.BOTH -> when {
                underweight -> advice(Goal.MAINTAIN, AdviceReason.UNDERWEIGHT)
                newToLifting -> advice(Goal.RECOMP, AdviceReason.BOTH_NEW_TO_LIFTING)
                highFat -> advice(Goal.RECOMP, AdviceReason.BOTH_HIGHER_FAT)
                else -> advice(Goal.LEAN_BULK, AdviceReason.BOTH_LEAN_AND_TRAINED)
            }
            // A recomp is a deficit, so it's never suggested below BMI 18.5.
            Intent.BUILD_MUSCLE -> if (veryHighFat && !underweight) advice(Goal.RECOMP, AdviceReason.MUSCLE_HIGH_FAT) else advice(Goal.LEAN_BULK, AdviceReason.MUSCLE)
            Intent.MAINTAIN -> advice(Goal.MAINTAIN, AdviceReason.STAY)
        }
    }
}
