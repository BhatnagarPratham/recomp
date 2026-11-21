package app.recomp.ui

import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.Pace
import app.recomp.domain.persona.Expectation
import app.recomp.domain.persona.FinishLine
import app.recomp.domain.persona.GoalPlan
import app.recomp.domain.persona.GoalProgress
import app.recomp.domain.persona.GoalStatus
import app.recomp.domain.persona.GuardrailNote
import app.recomp.domain.persona.MaintenanceBreak
import app.recomp.ui.goal.GoalCardText
import app.recomp.ui.onboarding.GoalText
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import app.recomp.domain.units.UnitStyle

/** The Goal reached card, the break's lines and *What to expect* (PRD §12.15). Illustrative numbers. */
class GoalCardTextTest {
    private val start = LocalDate.of(2026, 8, 3)
    private val recomp = GoalPlan.of(Goal.RECOMP, Pace.STANDARD, 78.0, FinishLine.GoalWeight, start)

    init {
        Locale.setDefault(Locale.UK)
    }

    @Test
    fun `the reached card and What to expect in lb (PR-05)`() {
        val reached = recomp.copy(reachedOn = start.plusDays(60))
        assertEquals("You reached 172 lb on the trend", GoalCardText.reached(reached, UnitStyle.IMPERIAL))
        assertTrue(GoalText.expect(Expectation.DEFICIT_TO_SURPLUS, UnitStyle.IMPERIAL).contains("jump 2–4 lb"))
        assertTrue(GoalText.expect(Expectation.SURPLUS_TO_MAINTENANCE, UnitStyle.IMPERIAL).contains("about 2 lb"))
        assertTrue(GoalText.expect(Expectation.MAINTENANCE_TO_SURPLUS, UnitStyle.IMPERIAL).contains("quick 1–2 lb"))
        assertTrue(GoalText.expect(Expectation.MAINTENANCE_TO_SURPLUS, UnitStyle(decimal = ',')).contains("quick 0,5–1 kg"))
        Expectation.entries.forEach { assertTrue(it.name, " kg" !in GoalText.expect(it, UnitStyle.IMPERIAL)) }
    }

    @Test
    fun `the reached card names what was reached and what comes next`() {
        val reached = recomp.copy(reachedOn = start.plusDays(60))
        assertEquals("You reached 78 kg on the trend", GoalCardText.reached(reached, UnitStyle.METRIC))
        assertEquals("You reached your finish date, Mon 3 Aug", GoalCardText.reached(recomp.copy(finishLine = FinishLine.ByDate(start)), UnitStyle.METRIC))
        assertEquals("You reached 18% body fat", GoalCardText.reached(recomp.copy(finishLine = FinishLine.BodyFat(18.0)), UnitStyle.METRIC))

        val first = GoalProgress.status(reached, start.plusDays(60)) as GoalStatus.Reached
        assertEquals("Next: 2 weeks at maintenance, then choose a new goal.", GoalCardText.next(first))
        val resting = MaintenanceBreak.start(reached, start.plusDays(60), 2)
        assertEquals("At maintenance until Fri 16 Oct. Then choose a new goal.", GoalCardText.next(GoalProgress.status(resting, start.plusDays(61)) as GoalStatus.Reached))
        assertTrue(GoalCardText.next(GoalProgress.status(resting, start.plusDays(80)) as GoalStatus.Reached).startsWith("Your 2 weeks at maintenance are done"))
        val bulk = GoalPlan.of(Goal.LEAN_BULK, Pace.STANDARD, null, FinishLine.ByDate(start), start).copy(reachedOn = start)
        assertEquals("Next: maintain for a while, or start a fat-loss phase.", GoalCardText.next(GoalProgress.status(bulk, start) as GoalStatus.Reached))
    }

    @Test
    fun `the break's lines say when the goal is back`() {
        // 12 weeks from Monday 3 August is Monday 26 October; a week's break ends on Monday 2 November.
        val due = GoalProgress.status(recomp, start.plusWeeks(12)) as GoalStatus.BreakDue
        assertEquals("12 weeks in a deficit", GoalCardText.breakTitle(due))
        assertTrue(GoalCardText.breakBody(due).contains("your recomp resumes on its own"))
        // The goal as a noun, never a card title (found on the phone: "your lose fat").
        assertEquals("your fat loss", GoalCardText.yourGoal(Goal.FAT_LOSS))
        assertEquals("your lean bulk", GoalCardText.yourGoal(Goal.LEAN_BULK))
        val onBreak = MaintenanceBreak.start(recomp, start.plusWeeks(12), 1)
        val status = GoalProgress.status(onBreak, start.plusWeeks(12)) as GoalStatus.OnBreak
        assertEquals("Maintenance break · back to your recomp on Mon 2 Nov", GoalCardText.onBreak(status))
        assertEquals(GoalCardText.onBreak(status), GoalCardText.settingsLine(status))
        assertNull(GoalCardText.settingsLine(due))
        assertNull(GoalCardText.settingsLine(GoalStatus.None))
        assertEquals("1 week", GoalCardText.weeks(1))
        assertEquals("2 weeks", GoalCardText.weeks(2))
    }

    @Test
    fun `a cut to a bulk says the scale will jump, and every change says something`() {
        assertEquals(
            "The scale will jump 1–2 kg in the first two weeks as water and glycogen come back; judge from week 3.",
            GoalText.expect(Expectation.DEFICIT_TO_SURPLUS, UnitStyle.METRIC),
        )
        assertEquals(Expectation.entries.size, Expectation.entries.map { GoalText.expect(it, UnitStyle.METRIC) }.toSet().size)
    }

    @Test
    fun `the pregnancy guardrail's notes suggest a doctor or midwife`() {
        assertTrue(GoalText.guardrail(GuardrailNote.PREGNANT_MAINTAIN).contains("doctor or midwife"))
        assertTrue(GoalText.PREGNANT_ON.contains("doctor or midwife"))
        assertEquals(GoalText.PREGNANT_CARD, GoalText.unavailable(GuardrailNote.PREGNANT_MAINTAIN))
        assertEquals(GoalText.LOW_BMI_CARD, GoalText.unavailable(GuardrailNote.LOW_BMI_MAINTAIN))
        assertNull(GoalText.unavailable(null))
    }
}
