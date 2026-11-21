package app.recomp.ui

import app.recomp.data.db.HistoryRow
import app.recomp.data.db.WorkoutSessionEntity
import app.recomp.data.prefs.VolumeBand
import app.recomp.domain.model.SessionKind
import app.recomp.domain.model.SessionStatus
import app.recomp.domain.progression.ChartRange
import app.recomp.domain.progression.ChartValue
import app.recomp.domain.progression.ExerciseChartData
import app.recomp.domain.training.MuscleSets
import app.recomp.domain.persona.Units
import app.recomp.domain.units.UnitStyle
import app.recomp.ui.exercises.ExerciseChartText
import app.recomp.ui.history.historyDetail
import app.recomp.ui.history.historyTitle
import app.recomp.ui.train.volumeScale
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/** What a History row and the weekly sets card say. */
class HistoryTextTest {
    private fun row(
        kind: SessionKind, routine: String?, minutes: Long, exercises: Int = 0, sets: Int = 0, style: String? = null, durationMin: Int? = null,
        notes: String? = null, hcRecordId: String? = null,
    ) = HistoryRow(
        WorkoutSessionEntity(1, 1, kind, SessionStatus.COMPLETED, startedAt = 0, endedAt = minutes * 60_000, logDate = LocalDate.of(2026, 11, 10), notes = notes, hcRecordId = hcRecordId),
        routine, exercises, sets, style, durationMin,
    )

    @Test
    fun `a workout shows its routine, length and what was logged`() {
        val push = row(SessionKind.STRENGTH, "Push", 52, exercises = 5, sets = 15)
        assertEquals("Push", historyTitle(push))
        assertEquals("52 min · 5 exercises · 15 sets", historyDetail(push))
        val adHoc = row(SessionKind.STRENGTH, null, 20, exercises = 1, sets = 1)
        assertEquals("Workout", historyTitle(adHoc))
        assertEquals("20 min · 1 exercise · 1 set", historyDetail(adHoc))
    }

    @Test
    fun `an extra session shows its length and style, without repeating its name`() {
        assertEquals("60 min · Vinyasa", historyDetail(row(SessionKind.YOGA, "Yoga class", 60, style = "Vinyasa", durationMin = 60)))
        assertEquals("45 min", historyDetail(row(SessionKind.YOGA, "Yoga class", 45, style = "Yoga class", durationMin = 45)))
        assertEquals("Yoga class", historyTitle(row(SessionKind.YOGA, "Yoga class", 45)))
    }

    @Test
    fun `the bars are scaled to the band, or to the biggest muscle past it`() {
        val band = VolumeBand(10, 20)
        assertEquals(40, volumeScale(listOf(MuscleSets("chest", 15)), band))
        assertEquals(52, volumeScale(listOf(MuscleSets("chest", 52), MuscleSets("lats", 9)), band))
        assertEquals(40, volumeScale(emptyList(), band))
    }

    @Test
    fun `the exercise chart says what it shows, in words for a screen reader too`() {
        val d1 = LocalDate.of(2026, 11, 2)
        val d2 = LocalDate.of(2026, 11, 16)
        val e1rm = ExerciseChartData(ExerciseChartData.Kind.E1RM, listOf(ChartValue(d1, 56.0), ChartValue(d2, 57.5)), listOf(ChartValue(d1, 40.0), ChartValue(d2, 45.0)))
        assertEquals("Estimated 1RM and top set: 2 sessions, from 56 kg to 57.5 kg", ExerciseChartText.describe(e1rm, d1, UnitStyle.METRIC))
        assertEquals("Estimated 1RM and top set: 1 sessions, from 57.5 kg to 57.5 kg", ExerciseChartText.describe(e1rm, d2, UnitStyle.METRIC))
        val reps = ExerciseChartData(ExerciseChartData.Kind.REPS, listOf(ChartValue(d1, 12.0), ChartValue(d2, 14.0)), emptyList())
        assertEquals("Best set's reps: 2 sessions, from 12 reps to 14 reps", ExerciseChartText.describe(reps, d1, UnitStyle.METRIC))
        assertEquals("The least assistance each session, in kg. Lower is better.", ExerciseChartText.caption(ExerciseChartData.Kind.ASSISTANCE, UnitStyle.METRIC))
        assertEquals("12 weeks", ExerciseChartText.range(ChartRange.WEEKS_12))
    }

    @Test
    fun `the chart says why it isn't drawn`() {
        val d = LocalDate.of(2026, 11, 2)
        val one = ExerciseChartData(ExerciseChartData.Kind.REPS, listOf(ChartValue(d, 10.0)), emptyList())
        assertEquals("A chart appears after two sessions.", ExerciseChartText.notEnough(one))
        val two = ExerciseChartData(ExerciseChartData.Kind.REPS, listOf(ChartValue(d.minusDays(200), 8.0), ChartValue(d, 10.0)), emptyList())
        assertEquals("Fewer than two sessions in the last 12 weeks. Choose Everything to see them all.", ExerciseChartText.notEnough(two))
    }

    @Test
    fun `another app's session says where it came from, and your own notes never show in the list`() {
        assertEquals("35 min · Walk · from Google Fit", historyDetail(row(SessionKind.CARDIO, null, 35, style = "Walk", durationMin = 35, notes = "from Google Fit", hcRecordId = "hc-1")))
        assertEquals("60 min · Vinyasa", historyDetail(row(SessionKind.YOGA, "Yoga class", 60, style = "Vinyasa", durationMin = 60, notes = "Hips")))
    }

    @Test
    fun `in lb the exercise chart plots and reads in lb (PR-05)`() {
        val d1 = LocalDate.of(2026, 11, 2)
        val d2 = LocalDate.of(2026, 11, 16)
        // The domain's chart is in kg: 100 kg and 102.5 kg, shown as 220.5 lb and 226 lb.
        val kgChart = ExerciseChartData(ExerciseChartData.Kind.E1RM, listOf(ChartValue(d1, 100.0), ChartValue(d2, 102.5)), listOf(ChartValue(d1, 90.0)))
        val lb = ExerciseChartText.inUnits(kgChart, Units.IMPERIAL)
        assertEquals("Estimated 1RM and top set: 2 sessions, from 220.5 lb to 226 lb", ExerciseChartText.describe(lb, d1, UnitStyle.IMPERIAL))
        assertEquals(198.4, lb.dots.single().value, 0.05)
        assertEquals(kgChart, ExerciseChartText.inUnits(kgChart, Units.METRIC))
        val reps = ExerciseChartData(ExerciseChartData.Kind.REPS, listOf(ChartValue(d1, 12.0)), emptyList())
        assertEquals(reps, ExerciseChartText.inUnits(reps, Units.IMPERIAL))
        assertEquals("Line: best estimated 1RM each session. Circles: the heaviest set. In lb.", ExerciseChartText.caption(ExerciseChartData.Kind.E1RM, UnitStyle.IMPERIAL))
        assertEquals(
            "Estimated 1RM and top set: 2 sessions, from 220,5 lb to 226 lb",
            ExerciseChartText.describe(lb, d1, UnitStyle(Units.IMPERIAL, ',')),
        )
    }
}
