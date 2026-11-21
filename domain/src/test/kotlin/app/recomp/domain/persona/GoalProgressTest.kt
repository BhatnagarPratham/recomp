package app.recomp.domain.persona

import app.recomp.domain.checkin.CheckinFlag
import app.recomp.domain.checkin.CheckinProposal
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.GoalPolicy
import app.recomp.domain.nutrition.Pace
import app.recomp.domain.nutrition.Sex
import app.recomp.domain.nutrition.TargetInputs
import app.recomp.domain.nutrition.TargetOverrides
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GL-06, PS-04 and GL-09's pregnancy guardrail (PRD §12.15, TRD §5.8). Illustrative numbers. */
class GoalProgressTest {
    private val start = LocalDate.of(2026, 6, 1)

    private fun plan(goal: Goal = Goal.RECOMP, finish: FinishLine? = FinishLine.GoalWeight, goalKg: Double? = 78.0) =
        GoalPlan.of(goal, Pace.STANDARD, goalKg, finish, start)

    private fun day(n: Long): LocalDate = start.plusDays(n)

    // Reached

    @Test
    fun `a goal weight is reached when the trend crosses it in the goal's direction`() {
        assertFalse(FinishLines.reached(plan(), 78.1, null, day(30)))
        assertTrue(FinishLines.reached(plan(), 78.0, null, day(30)))
        assertTrue(FinishLines.reached(plan(Goal.FAT_LOSS), 77.4, null, day(30)))
        // A lean bulk gains towards its goal weight.
        assertFalse(FinishLines.reached(plan(Goal.LEAN_BULK, goalKg = 84.0), 83.9, null, day(30)))
        assertTrue(FinishLines.reached(plan(Goal.LEAN_BULK, goalKg = 84.0), 84.2, null, day(30)))
        // Maintain has no direction, and nothing is reached without a trend, a goal weight or a finish line.
        assertFalse(FinishLines.reached(plan(Goal.MAINTAIN), 70.0, null, day(30)))
        assertFalse(FinishLines.reached(plan(), null, null, day(30)))
        assertFalse(FinishLines.reached(plan(goalKg = null), 70.0, null, day(30)))
        assertFalse(FinishLines.reached(plan(finish = null), 70.0, null, day(30)))
    }

    @Test
    fun `a date is reached on the day itself and after, whatever the weight`() {
        val byDate = plan(Goal.LEAN_BULK, FinishLine.ByDate(day(112)), goalKg = null)
        assertFalse(FinishLines.reached(byDate, 80.0, null, day(111)))
        assertTrue(FinishLines.reached(byDate, null, null, day(112)))
        assertTrue(FinishLines.reached(byDate, 80.0, null, day(140)))
        assertTrue(FinishLines.reached(plan(Goal.MAINTAIN, FinishLine.ByDate(day(10))), null, null, day(10)))
    }

    @Test
    fun `a body-fat line needs a scan at or under it`() {
        val line = plan(finish = FinishLine.BodyFat(18.0))
        assertFalse(FinishLines.reached(line, 70.0, null, day(30)))
        assertFalse(FinishLines.reached(line, 70.0, 18.4, day(30)))
        assertTrue(FinishLines.reached(line, 85.0, 18.0, day(30)))
    }

    @Test
    fun `reaching is marked once, on the day it's seen, and never during a break`() {
        val marked = GoalProgress.markReached(plan(), 77.9, null, day(40))!!
        assertEquals(day(40), marked.reachedOn)
        // Already marked: the trend bouncing back up, or another launch, changes nothing.
        assertNull(GoalProgress.markReached(marked, 78.4, null, day(41)))
        assertNull(GoalProgress.markReached(marked, 77.0, null, day(41)))
        assertNull(GoalProgress.markReached(plan(), 78.3, null, day(40)))
        // At maintenance the scale moves with water, so nothing is marked until the goal is back.
        assertNull(GoalProgress.markReached(plan().copy(breakUntil = day(50)), 77.0, null, day(40)))
    }

    // The maintenance break

    @Test
    fun `a break is due at exactly 12 weeks in a deficit`() {
        for (goal in listOf(Goal.FAT_LOSS, Goal.RECOMP)) {
            assertFalse(MaintenanceBreak.due(plan(goal), day(83)))
            assertTrue(MaintenanceBreak.due(plan(goal), day(84)))
            assertTrue(MaintenanceBreak.due(plan(goal), day(200)))
        }
        assertFalse(MaintenanceBreak.due(plan(Goal.MAINTAIN), day(200)))
        assertFalse(MaintenanceBreak.due(plan(Goal.LEAN_BULK, goalKg = 90.0), day(200)))
        // Not once the finish line is reached, and not during a break.
        assertFalse(MaintenanceBreak.due(plan().copy(reachedOn = day(80)), day(84)))
        assertFalse(MaintenanceBreak.due(plan().copy(breakUntil = day(90)), day(84)))
    }

    @Test
    fun `the 12 weeks count again from the end of the last break`() {
        val after = MaintenanceBreak.end(MaintenanceBreak.start(plan(), day(84), 2), day(98))
        assertNull(after.breakUntil)
        assertEquals(day(98), after.lastBreakEndedOn)
        assertEquals(day(98), MaintenanceBreak.countingFrom(after))
        assertFalse(MaintenanceBreak.due(after, day(98 + 83)))
        assertTrue(MaintenanceBreak.due(after, day(98 + 84)))
        assertEquals(GoalStatus.BreakDue(after, 12), GoalProgress.status(after, day(98 + 84)))
    }

    @Test
    fun `accepting sets the end date 1 or 2 weeks out, and nothing else is a break`() {
        assertEquals(day(91), MaintenanceBreak.start(plan(), day(84), 1).breakUntil)
        assertEquals(day(98), MaintenanceBreak.start(plan(), day(84), 2).breakUntil)
        for (weeks in listOf(0, 3, -1)) {
            assertTrue(runCatching { MaintenanceBreak.start(plan(), day(84), weeks) }.isFailure)
        }
    }

    @Test
    fun `the goal resumes on the first launch on or after the end date, however late`() {
        val onBreak = MaintenanceBreak.start(plan(), day(84), 1)
        assertFalse(MaintenanceBreak.shouldResume(onBreak, day(90)))
        assertTrue(MaintenanceBreak.shouldResume(onBreak, day(91)))
        assertTrue(MaintenanceBreak.shouldResume(onBreak, day(120)))
        assertFalse(MaintenanceBreak.shouldResume(plan(), day(120)))
        // After a reached goal the 2 weeks end with a choice, never with the deficit coming back on its own.
        assertFalse(MaintenanceBreak.shouldResume(onBreak.copy(reachedOn = day(80)), day(120)))
    }

    @Test
    fun `Not now asks again after 4 weeks`() {
        val snoozed = MaintenanceBreak.snooze(plan(), day(84))
        assertEquals(day(112), snoozed.breakSnoozedUntil)
        assertTrue(MaintenanceBreak.due(snoozed, day(100)))
        assertFalse(MaintenanceBreak.suggested(snoozed, day(84)))
        assertFalse(MaintenanceBreak.suggested(snoozed, day(111)))
        assertTrue(MaintenanceBreak.suggested(snoozed, day(112)))
        assertEquals(GoalStatus.None, GoalProgress.status(snoozed, day(111)))
        assertEquals(GoalStatus.BreakDue(snoozed, 16), GoalProgress.status(snoozed, day(112)))
        // Accepting later clears the snooze.
        assertNull(MaintenanceBreak.start(snoozed, day(112), 1).breakSnoozedUntil)
    }

    @Test
    fun `during a break the targets follow maintenance at the goal's own protein, and the goal stays underneath`() {
        val fatLoss = plan(Goal.FAT_LOSS)
        val onBreak = MaintenanceBreak.start(fatLoss, day(84), 2)
        assertEquals(Goal.FAT_LOSS, onBreak.goal)
        assertTrue(onBreak.onBreak)
        val target = onBreak.targetPlan
        assertEquals(Goal.MAINTAIN, target.goal)
        assertEquals(0.0, target.policy.lossRatePctPerWeek, 0.0)
        assertEquals(fatLoss.policy.proteinGPerKg, target.policy.proteinGPerKg, 0.0)
        assertEquals(78.0, target.goalWeightKg)
        assertNull(target.pace)
        assertEquals(fatLoss, fatLoss.targetPlan)
    }

    @Test
    fun `no flag during a break, nor until the flag's window is back on the goal`() {
        val onBreak = MaintenanceBreak.start(plan(Goal.FAT_LOSS), day(84), 1)
        assertTrue(MaintenanceBreak.flagsQuiet(onBreak, day(90)))
        assertFalse(MaintenanceBreak.flagsQuiet(plan(Goal.FAT_LOSS), day(90)))
        // Fat loss and a lean bulk judge two weekly rates; a recomp and Maintain judge 4 weeks of trend.
        val back = MaintenanceBreak.end(onBreak, day(91))
        assertTrue(MaintenanceBreak.flagsQuiet(back, day(104)))
        assertFalse(MaintenanceBreak.flagsQuiet(back, day(105)))
        val recomp = MaintenanceBreak.end(MaintenanceBreak.start(plan(), day(84), 1), day(91))
        assertTrue(MaintenanceBreak.flagsQuiet(recomp, day(118)))
        assertFalse(MaintenanceBreak.flagsQuiet(recomp, day(119)))
    }

    // The card's state

    @Test
    fun `reached offers maintenance once, runs it, and then waits for a choice`() {
        val reached = plan().copy(reachedOn = day(60))
        assertEquals(GoalStatus.Reached(reached, null, maintenanceDone = false, offerMaintenance = true), GoalProgress.status(reached, day(60)))

        val resting = MaintenanceBreak.start(reached, day(61), MaintenanceBreak.REACHED_WEEKS)
        assertEquals(GoalStatus.Reached(resting, day(75), maintenanceDone = false, offerMaintenance = false), GoalProgress.status(resting, day(74)))
        assertEquals(GoalStatus.Reached(resting, null, maintenanceDone = true, offerMaintenance = false), GoalProgress.status(resting, day(75)))
        assertEquals(GoalStatus.Reached(resting, null, maintenanceDone = true, offerMaintenance = false), GoalProgress.status(resting, day(200)))

        // Keep going clears the finish line and ends the maintenance; nothing is left to show.
        val going = GoalProgress.keepGoing(resting, day(80))
        assertNull(going.finishLine)
        assertNull(going.reachedOn)
        assertNull(going.breakUntil)
        assertEquals(day(80), going.lastBreakEndedOn)
        assertEquals(78.0, going.goalWeightKg)
        assertEquals(GoalStatus.None, GoalProgress.status(going, day(80)))
        // A break that ended the same day the goal was reached doesn't take the offer away (found on the phone).
        val sameDay = MaintenanceBreak.end(MaintenanceBreak.start(plan(), day(84), 1), day(88)).copy(reachedOn = day(88))
        assertTrue((GoalProgress.status(sameDay, day(88)) as GoalStatus.Reached).offerMaintenance)
        // Maintain has nothing to rest from.
        val steady = plan(Goal.MAINTAIN, FinishLine.ByDate(day(10))).copy(reachedOn = day(10))
        assertFalse((GoalProgress.status(steady, day(10)) as GoalStatus.Reached).offerMaintenance)
    }

    @Test
    fun `a running break shows before a due one, and reached before both`() {
        val onBreak = MaintenanceBreak.start(plan(), day(84), 2)
        assertEquals(GoalStatus.OnBreak(onBreak, day(98)), GoalProgress.status(onBreak, day(90)))
        assertEquals(GoalStatus.None, GoalProgress.status(plan(), day(83)))
        assertEquals(GoalStatus.BreakDue(plan(), 12), GoalProgress.status(plan(), day(84)))
        assertTrue(GoalProgress.status(plan().copy(reachedOn = day(90)), day(90)) is GoalStatus.Reached)
    }

    // Goal switch (PS-04)

    @Test
    fun `only a change of goal resets the start`() {
        val stored = plan().copy(lastBreakEndedOn = day(98), breakSnoozedUntil = day(300), reachedOn = day(150))
        // A pace, goal-weight or finish-line edit: the goal flow builds its plan as of today.
        val edited = GoalPlan.of(Goal.RECOMP, Pace.GENTLE, 76.0, FinishLine.ByDate(day(400)), day(200))
        val kept = GoalChange.settle(stored, edited, day(200))
        assertEquals(start, kept.startedOn)
        assertEquals(Pace.GENTLE, kept.pace)
        assertEquals(76.0, kept.goalWeightKg)
        assertEquals(day(98), kept.lastBreakEndedOn)
        assertEquals(day(300), kept.breakSnoozedUntil)
        // The finish line may have moved, so it's judged afresh.
        assertNull(kept.reachedOn)

        val switched = GoalChange.settle(stored, GoalPlan.of(Goal.LEAN_BULK, Pace.STANDARD, null, null, day(200)), day(200))
        assertEquals(day(200), switched.startedOn)
        assertNull(switched.lastBreakEndedOn)
        assertNull(switched.breakSnoozedUntil)
        assertNull(switched.reachedOn)
        assertEquals(day(200), GoalChange.settle(null, edited, day(200)).startedOn)
    }

    @Test
    fun `an edit during a break ends it that day`() {
        val onBreak = MaintenanceBreak.start(plan(), day(84), 2)
        val kept = GoalChange.settle(onBreak, GoalPlan.of(Goal.RECOMP, Pace.GENTLE, 78.0, FinishLine.GoalWeight, day(90)), day(90))
        assertNull(kept.breakUntil)
        assertEquals(day(90), kept.lastBreakEndedOn)
        assertEquals(start, kept.startedOn)
    }

    @Test
    fun `guardrails re-applied leave the goal alone unless they moved it`() {
        val stored = plan().copy(lastBreakEndedOn = day(98))
        assertEquals(stored, GoalChange.afterGuardrails(stored, stored, day(120)))
        val moved = GoalPlan.of(Goal.MAINTAIN, Pace.STANDARD, null, null, start)
        assertEquals(day(120), GoalChange.afterGuardrails(stored, moved, day(120)).startedOn)
        assertNull(GoalChange.afterGuardrails(stored, moved, day(120)).lastBreakEndedOn)
    }

    @Test
    fun `every change of goal has something to expect, and no change has nothing`() {
        for (from in Goal.entries) for (to in Goal.entries) {
            if (from == to) assertNull(GoalChange.expect(from, to)) else assertTrue("$from to $to", GoalChange.expect(from, to) != null)
        }
        assertEquals(Expectation.DEFICIT_TO_SURPLUS, GoalChange.expect(Goal.FAT_LOSS, Goal.LEAN_BULK))
        assertEquals(Expectation.DEFICIT_TO_SURPLUS, GoalChange.expect(Goal.RECOMP, Goal.LEAN_BULK))
        assertEquals(Expectation.DEFICIT_TO_MAINTENANCE, GoalChange.expect(Goal.RECOMP, Goal.MAINTAIN))
        assertEquals(Expectation.SURPLUS_TO_DEFICIT, GoalChange.expect(Goal.LEAN_BULK, Goal.FAT_LOSS))
        assertEquals(Expectation.MAINTENANCE_TO_DEFICIT, GoalChange.expect(Goal.MAINTAIN, Goal.RECOMP))
        assertEquals(Expectation.GENTLER_DEFICIT, GoalChange.expect(Goal.FAT_LOSS, Goal.RECOMP))
        assertEquals(Expectation.LARGER_DEFICIT, GoalChange.expect(Goal.RECOMP, Goal.FAT_LOSS))
        assertEquals(Expectation.entries.toSet(), Goal.entries.flatMap { a -> Goal.entries.mapNotNull { b -> GoalChange.expect(a, b) } }.toSet())
    }

    // Pregnancy and breastfeeding (GL-09)

    private val adult = Profile(Sex.FEMALE, LocalDate.of(1994, 3, 1), heightCm = 165.0, weightKg = 70.0)
    private val pregnant = adult.copy(pregnant = true)

    @Test
    fun `the guardrail moves an active deficit to Maintain and greys out the deficit goals`() {
        for (goal in listOf(Goal.FAT_LOSS, Goal.RECOMP)) {
            val guarded = Guardrails.apply(pregnant, plan(goal), day(10))
            assertEquals(Goal.MAINTAIN, guarded.plan.goal)
            assertNull(guarded.plan.finishLine)
            assertEquals(listOf(GuardrailNote.PREGNANT_MAINTAIN), guarded.notes)
            assertFalse(Guardrails.goalAvailable(goal, pregnant))
            assertTrue(Guardrails.goalAvailable(goal, adult))
        }
        // Maintain and a lean bulk stay as they are.
        for (goal in listOf(Goal.MAINTAIN, Goal.LEAN_BULK)) {
            val kept = plan(goal, finish = null)
            assertEquals(kept, Guardrails.apply(pregnant, kept, day(10)).plan)
            assertTrue(Guardrails.goalAvailable(goal, pregnant))
        }
        assertFalse(Guardrails.deficitAllowed(pregnant))
        assertTrue(Guardrails.deficitAllowed(adult))
        // The reason named is the one you can switch off, even when BMI would block it too.
        assertEquals(GuardrailNote.PREGNANT_MAINTAIN, Guardrails.deficitBlock(pregnant.copy(weightKg = 45.0)))
        assertEquals(GuardrailNote.LOW_BMI_MAINTAIN, Guardrails.deficitBlock(adult.copy(weightKg = 45.0)))
        assertNull(Guardrails.deficitBlock(adult))
    }

    @Test
    fun `while deficits are off, a suggestion is never a deficit (found on the phone)`() {
        for (intent in Intent.entries) {
            val advice = GoalRecommender.recommend(intent, Experience.BEGINNER, Sex.FEMALE, bmi = 26.0, deficitAllowed = false)
            assertFalse("$intent", Guardrails.isDeficitGoal(advice.goal))
        }
        assertEquals(AdviceReason.NO_DEFICIT, GoalRecommender.recommend(Intent.BOTH, Experience.BEGINNER, Sex.FEMALE, bmi = 26.0, deficitAllowed = false).reason)
        assertEquals(Goal.LEAN_BULK, GoalRecommender.recommend(Intent.BUILD_MUSCLE, Experience.BEGINNER, Sex.FEMALE, bmi = 22.0, deficitAllowed = false).goal)
        assertEquals(Goal.RECOMP, GoalRecommender.recommend(Intent.BOTH, Experience.BEGINNER, Sex.FEMALE, bmi = 26.0).goal)
    }

    @Test
    fun `a calorie target of your own below maintenance isn't used while it's on`() {
        assertNull(Guardrails.kcalOverride(pregnant, 1700, maintenanceKcal = 2204.0))
        assertNull(Guardrails.kcalOverride(pregnant, 2190, maintenanceKcal = 2204.0))
        // Maintenance on the 10 kcal grid, and anything above it, stays.
        assertEquals(2200, Guardrails.kcalOverride(pregnant, 2200, maintenanceKcal = 2204.0))
        assertEquals(2500, Guardrails.kcalOverride(pregnant, 2500, maintenanceKcal = 2204.0))
        assertEquals(1700, Guardrails.kcalOverride(adult, 1700, maintenanceKcal = 2204.0))
        assertNull(Guardrails.kcalOverride(adult, null, maintenanceKcal = 2204.0))
    }

    @Test
    fun `a check-in proposal or a review suggestion is never a deficit while deficits are off`() {
        // A deficit policy that slipped past the guardrails still can't propose below maintenance.
        val inputs = TargetInputs(Sex.FEMALE, 32, 165.0, 70.0, policy = GoalPolicy.defaultFor(Goal.FAT_LOSS))
        val allowed = CheckinProposal.of(CheckinFlag.LOSS_SLOW, inputs, 2200.0, TargetOverrides(), currentKcal = 2200)!!
        assertTrue(allowed.kcal < 2200)
        assertNull(CheckinProposal.of(CheckinFlag.LOSS_SLOW, inputs, 2200.0, TargetOverrides(), currentKcal = 2200, deficitAllowed = false))

        // At Maintain the proposal is maintenance itself, and Adjust can't step below it.
        val maintain = inputs.copy(policy = GoalPolicy.defaultFor(Goal.MAINTAIN))
        val proposal = CheckinProposal.of(CheckinFlag.DRIFT, maintain, 2300.0, TargetOverrides(), currentKcal = 2200, deficitAllowed = false)!!
        assertEquals(2300, proposal.kcal)
        val range = CheckinProposal.adjustRange(maintain, 2300.0, deficitAllowed = false)
        assertEquals(2300, range.first)
        assertEquals(2300, CheckinProposal.step(2300, up = false, range))
        assertTrue(CheckinProposal.adjustRange(maintain, 2300.0).first < 2300)
    }
}
