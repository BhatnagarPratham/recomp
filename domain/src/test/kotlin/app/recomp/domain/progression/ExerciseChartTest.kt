package app.recomp.domain.progression

import app.recomp.domain.model.LoadType
import app.recomp.domain.model.SetType.DROP
import app.recomp.domain.model.SetType.WARMUP
import app.recomp.domain.model.SetType.WORKING
import app.recomp.domain.training.LoggedSet
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseChartTest {
    private val d1 = LocalDate.of(2026, 11, 2)
    private val d2 = LocalDate.of(2026, 11, 5)
    private fun w(load: Int?, reps: Int) = LoggedSet(WORKING, load, reps)

    @Test
    fun `weighted lifts plot the best e1RM as the line and the top set as dots, oldest first`() {
        val chart = ExerciseChart.of(
            listOf(
                DatedExposure(d2, listOf(LoggedSet(WARMUP, 80_000, 2), w(45_000, 8), w(42_500, 10))),
                DatedExposure(d1, listOf(w(40_000, 12), w(40_000, 15), LoggedSet(DROP, 30_000, 12))),
            ),
            LoadType.WEIGHTED,
        )
        assertEquals(ExerciseChartData.Kind.E1RM, chart.kind)
        // 40 × 12 = 56 kg (the 15-rep set doesn't count); 45 × 8 = 57 kg beats 42.5 × 10 ≈ 56.7.
        assertEquals(listOf(ChartValue(d1, 56.0), ChartValue(d2, 57.0)), chart.line)
        // The warm-up at 80 kg is never the top set.
        assertEquals(listOf(ChartValue(d1, 40.0), ChartValue(d2, 45.0)), chart.dots)
        assertTrue(chart.drawable)
    }

    @Test
    fun `a session with only high-rep sets has a dot but no e1RM`() {
        val chart = ExerciseChart.of(listOf(DatedExposure(d1, listOf(w(20_000, 15))), DatedExposure(d2, listOf(w(20_000, 10)))), LoadType.WEIGHTED)
        assertEquals(listOf(ChartValue(d2, 20 * (1 + 10 / 30.0))), chart.line)
        assertEquals(2, chart.dots.size)
        assertTrue(chart.drawable)
    }

    @Test
    fun `bodyweight plots reps and assisted plots the least assistance`() {
        val bw = ExerciseChart.of(listOf(DatedExposure(d1, listOf(w(null, 10), w(null, 12))), DatedExposure(d2, listOf(w(null, 14)))), LoadType.BODYWEIGHT)
        assertEquals(ExerciseChartData.Kind.REPS, bw.kind)
        assertEquals(listOf(ChartValue(d1, 12.0), ChartValue(d2, 14.0)), bw.line)

        val assisted = ExerciseChart.of(listOf(DatedExposure(d1, listOf(w(30_000, 8), w(25_000, 6))), DatedExposure(d2, listOf(w(0, 5)))), LoadType.ASSISTED)
        assertEquals(ExerciseChartData.Kind.ASSISTANCE, assisted.kind)
        assertEquals(listOf(ChartValue(d1, 25.0), ChartValue(d2, 0.0)), assisted.line)
        assertTrue(assisted.dots.isEmpty())
    }

    @Test
    fun `one session, or warm-ups only, isn't enough to draw`() {
        assertFalse(ExerciseChart.of(listOf(DatedExposure(d1, listOf(w(40_000, 8)))), LoadType.WEIGHTED).drawable)
        assertFalse(ExerciseChart.of(listOf(DatedExposure(d1, listOf(w(40_000, 8))), DatedExposure(d2, listOf(LoggedSet(WARMUP, 20_000, 5)))), LoadType.WEIGHTED).drawable)
    }

    @Test
    fun `12 weeks starts 84 days back, and everything starts at the first session`() {
        val today = LocalDate.of(2026, 11, 21)
        assertEquals(LocalDate.of(2026, 8, 29), ChartRange.WEEKS_12.from(today, d1))
        assertEquals(d1, ChartRange.ALL.from(today, d1))
        assertEquals(today.minusDays(1), ChartRange.ALL.from(today, today))
    }
}
