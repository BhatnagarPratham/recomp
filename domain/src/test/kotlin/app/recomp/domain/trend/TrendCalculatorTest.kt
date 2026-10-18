package app.recomp.domain.trend

import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.GoalPolicy
import app.recomp.domain.nutrition.Pace
import app.recomp.domain.persona.WeightSource
import app.recomp.domain.persona.WeightSource.GYM_SCALE
import app.recomp.domain.persona.WeightSource.HOME_SCALE
import app.recomp.domain.persona.WeightSource.SCAN
import java.time.LocalDate
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BM-01, TRD §5.2: the time-aware EWMA, one reading per day from the primary source, and the weekly rate. */
class TrendCalculatorTest {
    private val start = LocalDate.of(2026, 10, 5)
    private val eps = 1e-9

    /** Invented weights, in kg, at 07:00 on [day] days after [start]. */
    private fun w(day: Long, kg: Double, source: WeightSource = HOME_SCALE, hour: Int = 7) =
        WeighIn(start.plusDays(day), start.plusDays(day).toEpochDay() * 86_400_000L + hour * 3_600_000L, (kg * 1000).toInt(), source)

    private fun trend(vararg weighIns: WeighIn, primary: WeightSource = HOME_SCALE) = TrendCalculator.trend(weighIns.toList(), primary)

    @Test
    fun `alpha is a tenth a day and compounds over gaps`() {
        assertEquals(0.1, TrendCalculator.alpha(1), eps)
        assertEquals(1 - 0.9.pow(7), TrendCalculator.alpha(7), eps)
        assertEquals(0.5217031, TrendCalculator.alpha(7), 1e-7)
    }

    @Test
    fun `the first weigh-in seeds the trend`() {
        val points = trend(w(0, 80.0))
        assertEquals(listOf(TrendPoint(start, 80.0, 80.0)), points)
    }

    @Test
    fun `daily weigh-ins move the trend a tenth of the way each day`() {
        val points = trend(w(0, 80.0), w(1, 81.0), w(2, 81.0))
        assertEquals(80.1, points[1].trendKg, eps)
        assertEquals(80.1 + 0.1 * (81.0 - 80.1), points[2].trendKg, eps)
    }

    @Test
    fun `a weekly weigh-in moves the trend as far as seven daily ones at that weight`() {
        val weekly = trend(w(0, 80.0), w(7, 78.0)).last().trendKg
        val daily = trend(w(0, 80.0), *(1L..7L).map { w(it, 78.0) }.toTypedArray()).last().trendKg
        assertEquals(daily, weekly, eps)
        // About halfway: 80 − 0.52 × 2.
        assertEquals(80.0 - 2 * TrendCalculator.alpha(7), weekly, eps)
    }

    @Test
    fun `mixed spacing uses each gap's own alpha`() {
        val points = trend(w(0, 80.0), w(1, 79.0), w(2, 79.0), w(6, 78.0))
        val day1 = 80.0 + 0.1 * (79.0 - 80.0)
        val day2 = day1 + 0.1 * (79.0 - day1)
        val day6 = day2 + TrendCalculator.alpha(4) * (78.0 - day2)
        listOf(day1, day2, day6).zip(points.drop(1)).forEach { (expected, point) -> assertEquals(expected, point.trendKg, eps) }
    }

    @Test
    fun `only the primary source counts`() {
        val all = listOf(w(0, 80.0), w(1, 82.5, GYM_SCALE), w(2, 79.0, SCAN), w(3, 80.0))
        val home = TrendCalculator.trend(all, HOME_SCALE)
        assertEquals(listOf(start, start.plusDays(3)), home.map { it.date })
        assertEquals(80.0, home.last().trendKg, eps)

        // Switching the primary source recomputes from that source alone.
        val gym = TrendCalculator.trend(all, GYM_SCALE)
        assertEquals(listOf(TrendPoint(start.plusDays(1), 82.5, 82.5)), gym)
        assertTrue(TrendCalculator.trend(all, SCAN).single().trendKg == 79.0)
    }

    @Test
    fun `only the first weigh-in of a day counts, whatever order they arrive in`() {
        val evening = w(1, 81.4, hour = 21)
        val morning = w(1, 80.2, hour = 7)
        val counted = TrendCalculator.counted(listOf(w(0, 80.0), evening, morning), HOME_SCALE)
        assertEquals(listOf(80_000, 80_200), counted.map { it.weightG })
        assertEquals(80.0 + 0.1 * 0.2, trend(w(0, 80.0), evening, morning).last().trendKg, eps)
    }

    @Test
    fun `no weigh-ins means no trend`() {
        assertEquals(emptyList<TrendPoint>(), trend())
        assertEquals(emptyList<TrendPoint>(), trend(w(0, 80.0, GYM_SCALE)))
        assertNull(TrendCalculator.weeklyRate(emptyList()))
    }

    @Test
    fun `trend between points is interpolated, and holds after the last`() {
        val points = trend(w(0, 80.0), w(4, 76.0))
        val end = points.last().trendKg
        assertNull(TrendCalculator.trendAt(points, start.minusDays(1)))
        assertEquals(80.0, TrendCalculator.trendAt(points, start)!!, eps)
        assertEquals(80.0 + (end - 80.0) / 4, TrendCalculator.trendAt(points, start.plusDays(1))!!, eps)
        assertEquals(end, TrendCalculator.trendAt(points, start.plusDays(4))!!, eps)
        assertEquals(end, TrendCalculator.trendAt(points, start.plusDays(30))!!, eps)
    }

    @Test
    fun `the weekly rate needs a week of trend`() {
        assertNull(TrendCalculator.weeklyRate(trend(w(0, 80.0), w(6, 79.0))))
        val rate = TrendCalculator.weeklyRate(trend(w(0, 80.0), w(7, 79.0)))!!
        assertEquals(-TrendCalculator.alpha(7), rate.kgPerWeek, eps)
        assertEquals(start.plusDays(7), rate.asOf)
    }

    @Test
    fun `the weekly rate is anchored at the latest weigh-in and interpolates a week back`() {
        val points = trend(w(0, 80.0), w(3, 80.0), w(10, 79.0), w(12, 79.0))
        val last = points.last()
        val rate = TrendCalculator.weeklyRate(points)!!
        // A week before day 12 is day 5, between the points on days 3 and 10.
        val weekAgo = points[1].trendKg + (points[2].trendKg - points[1].trendKg) * 2 / 7
        assertEquals(last.trendKg - weekAgo, rate.kgPerWeek, eps)
        assertEquals(rate.kgPerWeek / last.trendKg * 100, rate.pctPerWeek, eps)
        assertEquals(start.plusDays(12), rate.asOf)
    }

    @Test
    fun `daily weigh-ins losing steadily give a rate a little behind the real one`() {
        // 0.1 kg a day for four weeks: the trend lags but settles near 0.7 kg a week.
        val points = trend(*(0L..28L).map { w(it, 90.0 - 0.1 * it) }.toTypedArray())
        val rate = TrendCalculator.weeklyRate(points)!!
        assertTrue(rate.kgPerWeek in -0.72..-0.6)
        assertTrue(rate.pctPerWeek < 0)
    }

    @Test
    fun `the goal's pace comes from its policy`() {
        assertEquals(WeightPace(-0.75, -0.75), WeightPace.of(GoalPolicy.defaultFor(Goal.FAT_LOSS)))
        assertEquals(WeightPace(-0.25, -0.25), WeightPace.of(GoalPolicy.defaultFor(Goal.RECOMP, Pace.GENTLE)))
        assertEquals(WeightPace(0.25, 0.5), WeightPace.of(GoalPolicy.defaultFor(Goal.LEAN_BULK, Pace.AGGRESSIVE)))
        assertTrue(WeightPace.of(GoalPolicy.defaultFor(Goal.MAINTAIN)).steady)
        assertFalse(WeightPace.of(GoalPolicy.defaultFor(Goal.RECOMP)).steady)
        // A custom rate under Advanced is used as is.
        assertEquals(WeightPace(-1.2, -1.2), WeightPace.of(GoalPolicy(Goal.FAT_LOSS, lossRatePctPerWeek = 1.2, proteinGPerKg = 2.0)))
    }
}
