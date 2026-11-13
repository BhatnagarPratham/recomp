package app.recomp.ui

import app.recomp.data.db.WeekRecordRow
import app.recomp.domain.model.LoadType
import app.recomp.domain.progression.PrType
import app.recomp.domain.review.FinishOutlook
import app.recomp.ui.checkin.ReviewText
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/** The review's PR lines (HM-03): one per lift, each kind of record at its latest value that week. Invented numbers. */
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
    }
}
