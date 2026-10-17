package app.recomp.domain.persona

import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.GoalPolicy
import app.recomp.domain.nutrition.Pace
import app.recomp.domain.nutrition.Sex
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PRD §4.9 and TRD §5.8 guardrails. Illustrative profiles. */
class GuardrailsTest {
    private val today = LocalDate.of(2026, 10, 17)
    private val adult = Profile(Sex.MALE, LocalDate.of(1996, 3, 1), heightCm = 175.0, weightKg = 95.0)
    private val underweight = adult.copy(weightKg = 55.0) // BMI ≈ 18.0
    private val minor = adult.copy(birthDate = LocalDate.of(2010, 1, 1)) // 16

    private fun plan(goal: Goal, pace: Pace = Pace.STANDARD) =
        GoalPlan.of(goal, pace, goalWeightKg = 78.0, finishLine = FinishLine.GoalWeight, startedOn = today)

    @Test
    fun `an adult in a healthy range keeps the plan with no notes`() {
        val guarded = Guardrails.apply(adult, plan(Goal.RECOMP), today)
        assertEquals(plan(Goal.RECOMP), guarded.plan)
        assertTrue(guarded.notes.isEmpty())
    }

    @Test
    fun `below BMI 18_5 deficit goals become maintain with a reason`() {
        for (goal in listOf(Goal.FAT_LOSS, Goal.RECOMP)) {
            val guarded = Guardrails.apply(underweight, plan(goal), today)
            assertEquals(Goal.MAINTAIN, guarded.plan.goal)
            assertNull(guarded.plan.pace)
            assertNull(guarded.plan.finishLine)
            assertEquals(listOf(GuardrailNote.LOW_BMI_MAINTAIN), guarded.notes)
            assertFalse(Guardrails.goalAvailable(goal, underweight))
        }
        assertTrue(Guardrails.goalAvailable(Goal.LEAN_BULK, underweight))
        assertTrue(Guardrails.goalAvailable(Goal.MAINTAIN, underweight))
        assertEquals(Goal.LEAN_BULK, Guardrails.apply(underweight, plan(Goal.LEAN_BULK), today).plan.goal)
    }

    @Test
    fun `under 18 a deficit is clamped to gentle with a note`() {
        val guarded = Guardrails.apply(minor, plan(Goal.FAT_LOSS, Pace.AGGRESSIVE), today)
        assertEquals(Pace.GENTLE, guarded.plan.pace)
        assertEquals(GoalPolicy.defaultFor(Goal.FAT_LOSS, Pace.GENTLE).lossRatePctPerWeek, guarded.plan.policy.lossRatePctPerWeek, 0.0)
        // Clamped below aggressive, so no warning is needed.
        assertEquals(listOf(GuardrailNote.UNDER_18_GENTLE_ONLY), guarded.notes)
        assertEquals(listOf(Pace.GENTLE), Guardrails.allowedPaces(Goal.RECOMP, minor, today))
    }

    @Test
    fun `under 18 a custom rate is clamped to gentle's rate and stays custom`() {
        val custom = plan(Goal.RECOMP).copy(policy = GoalPolicy(Goal.RECOMP, lossRatePctPerWeek = 0.6, proteinGPerKg = 2.0), pace = null)
        val guarded = Guardrails.apply(minor, custom, today)
        assertNull(guarded.plan.pace)
        assertEquals(0.25, guarded.plan.policy.lossRatePctPerWeek, 0.0)
        assertEquals(2.0, guarded.plan.policy.proteinGPerKg, 0.0)
    }

    @Test
    fun `under 18 a gentle deficit keeps its plan but still carries the note`() {
        val gentle = plan(Goal.RECOMP, Pace.GENTLE)
        val guarded = Guardrails.apply(minor, gentle, today)
        assertEquals(gentle, guarded.plan)
        assertEquals(listOf(GuardrailNote.UNDER_18_GENTLE_ONLY), guarded.notes)
    }

    @Test
    fun `under 18 a surplus or maintenance is not limited`() {
        assertEquals(Pace.entries, Guardrails.allowedPaces(Goal.LEAN_BULK, minor, today))
        assertTrue(Guardrails.apply(minor, plan(Goal.LEAN_BULK), today).notes.isEmpty())
        assertTrue(Guardrails.apply(minor, plan(Goal.MAINTAIN), today).notes.isEmpty())
    }

    @Test
    fun `the 18th birthday lifts the limit`() {
        val eighteen = adult.copy(birthDate = today.minusYears(18))
        assertEquals(Pace.entries, Guardrails.allowedPaces(Goal.FAT_LOSS, eighteen, today))
        assertEquals(listOf(Pace.GENTLE), Guardrails.allowedPaces(Goal.FAT_LOSS, eighteen, today.minusDays(1)))
    }

    @Test
    fun `an aggressive pace needs one acknowledgement`() {
        for (goal in listOf(Goal.FAT_LOSS, Goal.RECOMP, Goal.LEAN_BULK)) {
            val aggressive = plan(goal, Pace.AGGRESSIVE)
            assertTrue(Guardrails.apply(adult, aggressive, today).needsAcknowledgement)
            assertFalse(Guardrails.apply(adult, aggressive.copy(aggressiveAcknowledged = true), today).needsAcknowledgement)
            assertFalse(Guardrails.apply(adult, plan(goal, Pace.STANDARD), today).needsAcknowledgement)
        }
    }

    @Test
    fun `a custom rate at or beyond aggressive also needs the acknowledgement`() {
        val custom = plan(Goal.FAT_LOSS).copy(policy = GoalPolicy(Goal.FAT_LOSS, lossRatePctPerWeek = 1.2, proteinGPerKg = 2.0), pace = null)
        assertTrue(Guardrails.apply(adult, custom, today).needsAcknowledgement)
        assertFalse(Guardrails.isAggressive(GoalPolicy.defaultFor(Goal.MAINTAIN)))
    }
}
