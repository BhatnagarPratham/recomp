package app.recomp.ui

import app.recomp.data.db.WeekRecordRow
import app.recomp.data.repo.BodyWeek
import app.recomp.data.repo.ExtraDone
import app.recomp.data.repo.TrainingWeek
import app.recomp.data.repo.WeekPlan
import app.recomp.domain.body.ScanChange
import app.recomp.domain.body.ScanPoint
import app.recomp.domain.body.ScanSlopes
import app.recomp.domain.model.LoadType
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.progression.PrType
import app.recomp.domain.review.FinishOutlook
import app.recomp.ui.body.BodyText
import app.recomp.ui.checkin.ReviewText
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The review's sentences (HM-03, PRD §12.4) where they depend on state. Invented numbers. */
class ReviewTextTest {
    private fun pr(exercise: String, type: PrType, value: Long, loadG: Int? = null, loadType: LoadType = LoadType.WEIGHTED) =
        WeekRecordRow(exercise, loadType, type, value, loadG)

    @Test
    fun `PRs make one line per lift, in the order they were set, with each kind's latest value`() {
        val lines = ReviewText.records(
            listOf(
                pr("Squat", PrType.MAX_LOAD, 60_000),
                pr("Bench press", PrType.SESSION_VOLUME, 1_200_000),
                pr("Squat", PrType.SESSION_VOLUME, 1_800_000),
                pr("Squat", PrType.MAX_LOAD, 62_500), // a later workout that week beats Monday's
            ),
        )
        assertEquals(2, lines.size)
        assertEquals("Squat: heaviest weight 62.5 kg · best session volume 1,800 kg", lines[0])
        assertEquals("Bench press: best session volume 1,200 kg", lines[1])
    }

    @Test
    fun `an assisted lift's PR shows its assistance`() {
        assertEquals(
            listOf("Assisted pull-up: least assistance assist 20 kg"),
            ReviewText.records(listOf(pr("Assisted pull-up", PrType.MAX_LOAD, 0, loadG = 20_000, loadType = LoadType.ASSISTED))),
        )
        assertEquals("and 3 more lifts with PRs", ReviewText.moreRecords(3))
        assertEquals("and 1 more lift with PRs", ReviewText.moreRecords(1))
    }

    @Test
    fun `the finish line names the goal as it was set`() {
        val date = LocalDate.of(2027, 3, 15)
        assertEquals("At this rate: around mid-March 2027 (goal 78 kg)", ReviewText.outlook(FinishOutlook.Around(date, 78.0)))
        assertEquals("At this rate: around mid-March 2027 (goal 77.5 kg)", ReviewText.outlook(FinishOutlook.Around(date, 77.5)))
        assertEquals("At this rate: around mid-March 2027 (goal 15% body fat)", ReviewText.outlook(FinishOutlook.Around(date, 15.0, bodyFat = true)))
        assertEquals("Moving away from your goal", ReviewText.outlook(FinishOutlook.MovingAway))
        // At the goal or past it, it's reached, never "moving away".
        assertEquals("You've reached your goal weight (76 kg)", ReviewText.outlook(FinishOutlook.Reached(76.0)))
        assertEquals("You've reached your body-fat goal (15%)", ReviewText.outlook(FinishOutlook.Reached(15.0, bodyFat = true)))
    }

    private fun body(changeKg: Double?, changePct: Double?) = BodyWeek(80.9, changeKg, changePct, null, null, null)

    @Test
    fun `a change that rounds to nothing reads as no change, never minus zero`() {
        assertEquals("Trend 80.9 kg · no change this week", ReviewText.trend(body(-0.03, -0.04)))
        assertEquals("Trend 80.9 kg · no change this week", ReviewText.trend(body(0.04, 0.05)))
        assertEquals("Trend 80.9 kg · −0.3 kg this week (−0.37%)", ReviewText.trend(body(-0.3, -0.37)))
        assertEquals("Trend 80.9 kg · no weigh-in this week", ReviewText.trend(body(null, null)))
        assertEquals("0.0 kg", BodyText.signed(-0.04, "kg"))
        assertEquals("+0.1 kg", BodyText.signed(0.06, "kg"))
        assertEquals("−1.2 cm", BodyText.signed(-1.2, "cm"))
    }

    @Test
    fun `a change in body fat is in points`() {
        val day = LocalDate.of(2026, 9, 7)
        val change = ScanChange(ScanPoint(day, 1, 33_000, 20_000, 25.0, true), ScanPoint(day.plusDays(7), 2, 33_400, 19_200, 24.2, true))
        assertEquals("+0.4 kg muscle · within noise; −0.8 pts body fat · within noise", BodyText.scanChange(change))
        assertEquals("Per 4 weeks: muscle +1.0 kg, fat mass −2.0 kg, body fat −2.0 pts", BodyText.scanSlopes(ScanSlopes(1.0, -2.0, -2.0)))
        assertEquals("from 4 on-protocol scans in the last 12 weeks", BodyText.slopeFrom(4, 12))
        assertEquals("from all 9 on-protocol scans", BodyText.slopeFrom(9, null))
    }

    @Test
    fun `this week counts against the plan, and a past week is what was done`() {
        val extras = listOf(ExtraDone("Yoga class", 1, 1), ExtraDone("Walk", 2, 0))
        assertEquals("Strength 2 of 3 days · Yoga class 1 of 1 · Walk 2", ReviewText.plan(TrainingWeek(2, 3, extras, emptyList(), emptyList())))
        assertEquals("Strength 1 day · Yoga class 1", ReviewText.plan(TrainingWeek(1, null, listOf(ExtraDone("Yoga class", 1, null)), emptyList(), emptyList())))
        assertEquals("Strength 0 days", ReviewText.plan(TrainingWeek(0, null, emptyList(), emptyList(), emptyList())))
    }

    @Test
    fun `a week before the current plan says why it has no pace or finish line`() {
        val since = LocalDate.of(2026, 8, 3)
        assertNull(ReviewText.earlierPlan(WeekPlan.Current))
        assertNull(ReviewText.earlierPlan(null))
        assertEquals(
            "This week's goal was Lean bulk, before your current one from ${BodyText.shortDate(since)}, so it isn't judged against today's pace or finish line.",
            ReviewText.earlierPlan(WeekPlan.EarlierGoal(Goal.LEAN_BULK, since)),
        )
        assertEquals(
            "This week was before your current plan from ${BodyText.shortDate(since)}. The plan then isn't kept, so there's no pace or finish line for it.",
            ReviewText.earlierPlan(WeekPlan.EarlierPlan(since)),
        )
    }
}
