package app.recomp.domain.persona

import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.GoalPolicy
import app.recomp.domain.nutrition.Pace
import java.time.LocalDate
import kotlin.math.min

/** Why a choice was rejected, clamped or needs a warning (TRD §5.8). The UI turns each into a sentence. */
enum class GuardrailNote {
    /** BMI below 18.5: deficit goals are unavailable, so the plan became Maintain. */
    LOW_BMI_MAINTAIN,
    /** Under 18: deficits are limited to Gentle, with a note to involve a parent or doctor. */
    UNDER_18_GENTLE_ONLY,
    /** An aggressive pace needs one acknowledgement of its warning. */
    AGGRESSIVE_NEEDS_ACK,
}

data class Guarded(val plan: GoalPlan, val notes: List<GuardrailNote>) {
    val needsAcknowledgement: Boolean get() = GuardrailNote.AGGRESSIVE_NEEDS_ACK in notes
}

/**
 * PRD §4.9 guardrails, checked whenever a goal, pace or profile changes. The intake floor lives in
 * [app.recomp.domain.nutrition.NutritionCalculator] because it applies to every target, overrides included.
 */
object Guardrails {
    const val MIN_DEFICIT_BMI = 18.5
    const val ADULT_AGE = 18

    fun isDeficitGoal(goal: Goal): Boolean = goal == Goal.FAT_LOSS || goal == Goal.RECOMP

    fun deficitAllowed(profile: Profile): Boolean = profile.bmi >= MIN_DEFICIT_BMI

    fun isMinor(profile: Profile, today: LocalDate): Boolean = profile.ageOn(today) < ADULT_AGE

    /** Goals offered on the goal cards; deficits are greyed out below BMI 18.5. */
    fun goalAvailable(goal: Goal, profile: Profile): Boolean = !isDeficitGoal(goal) || deficitAllowed(profile)

    fun allowedPaces(goal: Goal, profile: Profile, today: LocalDate): List<Pace> =
        if (isDeficitGoal(goal) && isMinor(profile, today)) listOf(Pace.GENTLE) else Pace.entries

    /** At or beyond the Aggressive preset, whether chosen as a pace or typed under Advanced. */
    fun isAggressive(policy: GoalPolicy): Boolean {
        val aggressive = GoalPolicy.defaultFor(policy.goal, Pace.AGGRESSIVE)
        return when (policy.goal) {
            Goal.FAT_LOSS, Goal.RECOMP -> policy.lossRatePctPerWeek >= aggressive.lossRatePctPerWeek
            Goal.LEAN_BULK -> policy.surplusFraction >= aggressive.surplusFraction
            Goal.MAINTAIN -> false
        }
    }

    fun apply(profile: Profile, plan: GoalPlan, today: LocalDate): Guarded {
        val notes = mutableListOf<GuardrailNote>()
        var result = plan
        if (isDeficitGoal(plan.goal) && !deficitAllowed(profile)) {
            notes += GuardrailNote.LOW_BMI_MAINTAIN
            result = GoalPlan.of(Goal.MAINTAIN, Pace.STANDARD, goalWeightKg = null, finishLine = null, startedOn = plan.startedOn)
        } else if (isDeficitGoal(plan.goal) && isMinor(profile, today)) {
            notes += GuardrailNote.UNDER_18_GENTLE_ONLY
            val gentle = GoalPolicy.defaultFor(plan.goal, Pace.GENTLE).lossRatePctPerWeek
            if (plan.policy.lossRatePctPerWeek > gentle) {
                result = plan.copy(
                    policy = plan.policy.copy(lossRatePctPerWeek = min(plan.policy.lossRatePctPerWeek, gentle)),
                    pace = if (plan.pace != null) Pace.GENTLE else null,
                )
            }
        }
        if (isAggressive(result.policy) && !result.aggressiveAcknowledged) notes += GuardrailNote.AGGRESSIVE_NEEDS_ACK
        return Guarded(result, notes)
    }
}
