package app.recomp.ui

import app.recomp.data.db.CheckinDecision
import app.recomp.data.db.NutritionTargetEntity
import app.recomp.data.db.TargetReason
import app.recomp.data.db.WeeklyCheckinEntity
import app.recomp.data.repo.CheckinView
import app.recomp.domain.checkin.CheckinEvaluation
import app.recomp.domain.checkin.CheckinFlag
import app.recomp.domain.checkin.CheckinStatus
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.Pace
import app.recomp.domain.persona.GoalPlan
import app.recomp.ui.checkin.CheckinText
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The check-in screen's sentences (PRD §12.1) where they depend on state. Numbers are invented. */
class CheckinTextTest {
    private val week = LocalDate.of(2026, 10, 5)
    private val row = WeeklyCheckinEntity(week, computedAt = 1, windowDays = 21, loggedDays = 20, weighIns = 21, tdeeObs = 2300, tdeePrev = 2400, tdeeNew = 2350, flag = CheckinFlag.LOSS_SLOW)
    private val target = NutritionTargetEntity(1, week, Goal.FAT_LOSS, 1900, 140, 55, 200, 2400, 1800, TargetReason.INITIAL, 1)
    private val evaluation = CheckinEvaluation(CheckinStatus.FLAGGED, CheckinFlag.LOSS_SLOW, ratePct = -0.2, previousRatePct = -0.25, fourWeekPct = -0.9, fourWeekKg = -0.7, waistChangeCm = null, trendKg = 80.0)

    private fun view(goal: Goal, flagged: WeeklyCheckinEntity = row) = CheckinView(
        row = flagged, plan = GoalPlan.of(goal, Pace.STANDARD, null, null, week), notes = emptyList(), status = CheckinStatus.FLAGGED,
        evaluation = evaluation, gap = null, week = null, formulaTdee = 2450, current = target, proposal = null, adjustRange = null,
        kcalOverride = null, written = null, decidedOn = null,
    )

    @Test
    fun `a refused answer says so only while the week is still open`() {
        assertNotNull(CheckinText.refused(row))
        assertNotNull(CheckinText.refused(null))
        // A second tap finds the week already answered: the screen shows the answer, with no error under it.
        assertNull(CheckinText.refused(row.copy(decision = CheckinDecision.ACCEPTED)))
        assertNull(CheckinText.refused(row.copy(decision = CheckinDecision.LAPSED)))
    }

    @Test
    fun `the verdict compares against the pace of the goal the flag belongs to`() {
        assertEquals("Losing slower than planned: −0.2% a week against −0.75%", CheckinText.verdict(view(Goal.FAT_LOSS)))
        // The goal has since changed to a lean bulk: the cut's flag is named without the bulk's pace.
        assertEquals("Losing slower than planned", CheckinText.verdict(view(Goal.LEAN_BULK)))
    }

    @Test
    fun `the decision line uses the log date it was decided on`() {
        val kept = row.copy(decision = CheckinDecision.KEPT, decidedAt = 1)
        assertEquals("You kept your target on 12 Oct.", CheckinText.decision(kept, null, LocalDate.of(2026, 10, 12)))
        assertEquals("You kept your target.", CheckinText.decision(kept, null, null))
    }
}
