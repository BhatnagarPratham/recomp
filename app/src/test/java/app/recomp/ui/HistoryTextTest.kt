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
import app.recomp.ui.exercises.ExerciseChartText
import app.recomp.ui.history.historyDetail
import app.recomp.ui.history.historyTitle
import app.recomp.ui.train.volumeScale
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/** What a History row and the weekly sets card say. */
class HistoryTextTest {
    private fun row(kind: SessionKind, routine: String?, minutes: Long, exercises: Int = 0, sets: Int = 0, style: String? = null, durationMin: Int? = null) = HistoryRow(
        WorkoutSessionEntity(1, 1, kind, SessionStatus.COMPLETED, startedAt = 0, endedAt = minutes * 60_000, logDate = LocalDate.of(2026, 11, 10)),
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
        assertEquals("Estimated 1RM and top set: 2 sessions, from 56 kg to 57.5 kg", ExerciseChartText.describe(e1rm, d1))
        assertEquals("Estimated 1RM and top set: 1 sessions, from 57.5 kg to 57.5 kg", ExerciseChartText.describe(e1rm, d2))
        val reps = ExerciseChartData(ExerciseChartData.Kind.REPS, listOf(ChartValue(d1, 12.0), ChartValue(d2, 14.0)), emptyList())
        assertEquals("Best set's reps: 2 sessions, from 12 reps to 14 reps", ExerciseChartText.describe(reps, d1))
        assertEquals("The least assistance each session, in kg. Lower is better.", ExerciseChartText.caption(ExerciseChartData.Kind.ASSISTANCE))
        assertEquals("12 weeks", ExerciseChartText.range(ChartRange.WEEKS_12))
    }
}
