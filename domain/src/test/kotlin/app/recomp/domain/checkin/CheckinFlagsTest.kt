package app.recomp.domain.checkin

import app.recomp.domain.checkin.CheckinFlag.DRIFT
import app.recomp.domain.checkin.CheckinFlag.FLAT
import app.recomp.domain.checkin.CheckinFlag.GAIN_FAST
import app.recomp.domain.checkin.CheckinFlag.GAIN_SLOW
import app.recomp.domain.checkin.CheckinFlag.LOSS_SLOW
import app.recomp.domain.checkin.CheckinStatus.FLAGGED
import app.recomp.domain.checkin.CheckinStatus.NEEDS_WAIST
import app.recomp.domain.checkin.CheckinStatus.NOT_ENOUGH_DATA
import app.recomp.domain.checkin.CheckinStatus.ON_TRACK
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.GoalPolicy
import app.recomp.domain.nutrition.Pace
import app.recomp.domain.persona.WeightSource
import app.recomp.domain.trend.TrendCalculator
import app.recomp.domain.trend.WeighIn
import java.time.LocalDate
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** GL-08's check-in flags (PRD §12.1, TRD §5.4), at their edges. Weights are invented. */
class CheckinFlagsTest {
    private val cut = GoalPolicy.defaultFor(Goal.FAT_LOSS, Pace.STANDARD)
    private val recomp = GoalPolicy.defaultFor(Goal.RECOMP)
    private val maintain = GoalPolicy.defaultFor(Goal.MAINTAIN)
    private val bulk = GoalPolicy.defaultFor(Goal.LEAN_BULK)

    private fun judge(policy: GoalPolicy, rate: Double? = null, previous: Double? = null, fourWeeks: Double? = null, waist: Double? = null) =
        CheckinFlags.judge(policy, rate, previous, fourWeeks, waist)

    @Test
    fun `fat loss fires when both weeks lose under half the pace`() {
        // Standard pace is 0.75% a week, so half is 0.375%.
        assertEquals(ON_TRACK to null, judge(cut, -0.375, -0.375))
        assertEquals(FLAGGED to LOSS_SLOW, judge(cut, -0.374, -0.374))
        assertEquals(FLAGGED to LOSS_SLOW, judge(cut, -0.2, 0.1))
        // One slow week isn't two in a row.
        assertEquals(ON_TRACK to null, judge(cut, -0.2, -0.5))
        assertEquals(ON_TRACK to null, judge(cut, -0.5, -0.2))
        assertEquals(NOT_ENOUGH_DATA to null, judge(cut, -0.2, null))
        assertEquals(NOT_ENOUGH_DATA to null, judge(cut, null, -0.2))
        // The pace is the policy's own, so a custom rate moves the edge.
        val gentle = GoalPolicy.defaultFor(Goal.FAT_LOSS, Pace.GENTLE)
        assertEquals(ON_TRACK to null, judge(gentle, -0.25, -0.3))
        assertEquals(FLAGGED to LOSS_SLOW, judge(gentle, -0.24, -0.24))
    }

    @Test
    fun `recomp fires when trend and waist are both flat over 4 weeks`() {
        assertEquals(FLAGGED to FLAT, judge(recomp, fourWeeks = 0.49, waist = 0.9))
        assertEquals(FLAGGED to FLAT, judge(recomp, fourWeeks = -0.49, waist = -0.99))
        assertEquals(ON_TRACK to null, judge(recomp, fourWeeks = 0.5, waist = 0.0))
        assertEquals(ON_TRACK to null, judge(recomp, fourWeeks = -0.5, waist = 0.0))
        assertEquals(ON_TRACK to null, judge(recomp, fourWeeks = 0.2, waist = 1.0))
        assertEquals(ON_TRACK to null, judge(recomp, fourWeeks = 0.2, waist = -1.0))
    }

    @Test
    fun `recomp asks for a waist only when the trend is flat`() {
        assertEquals(NEEDS_WAIST to null, judge(recomp, fourWeeks = 0.2))
        // A trend that moved can't be flat, whatever the waist did.
        assertEquals(ON_TRACK to null, judge(recomp, fourWeeks = -0.8))
        assertEquals(NOT_ENOUGH_DATA to null, judge(recomp, waist = 0.0))
        // Weekly rates don't matter to a recomp.
        assertEquals(FLAGGED to FLAT, judge(recomp, rate = -1.0, previous = -1.0, fourWeeks = 0.0, waist = 0.0))
    }

    @Test
    fun `maintain fires on more than 1 percent drift over 4 weeks`() {
        assertEquals(ON_TRACK to null, judge(maintain, fourWeeks = 1.0))
        assertEquals(ON_TRACK to null, judge(maintain, fourWeeks = -1.0))
        assertEquals(FLAGGED to DRIFT, judge(maintain, fourWeeks = 1.01))
        assertEquals(FLAGGED to DRIFT, judge(maintain, fourWeeks = -1.01))
        assertEquals(NOT_ENOUGH_DATA to null, judge(maintain, rate = 0.0, previous = 0.0))
    }

    @Test
    fun `lean bulk fires on two slow or two fast weeks`() {
        assertEquals(FLAGGED to GAIN_SLOW, judge(bulk, 0.099, 0.05))
        assertEquals(FLAGGED to GAIN_SLOW, judge(bulk, -0.2, 0.0))
        assertEquals(ON_TRACK to null, judge(bulk, 0.1, 0.05))
        assertEquals(ON_TRACK to null, judge(bulk, 0.05, 0.1))
        assertEquals(FLAGGED to GAIN_FAST, judge(bulk, 0.51, 0.6))
        assertEquals(ON_TRACK to null, judge(bulk, 0.5, 0.6))
        assertEquals(ON_TRACK to null, judge(bulk, 0.6, 0.5))
        // One slow and one fast week is neither.
        assertEquals(ON_TRACK to null, judge(bulk, 0.05, 0.6))
        assertEquals(ON_TRACK to null, judge(bulk, 0.3, 0.3))
        assertEquals(NOT_ENOUGH_DATA to null, judge(bulk, 0.05, null))
    }

    // From weigh-ins

    private val day0 = LocalDate.of(2026, 6, 1)

    /** Daily weigh-ins of [kg] on each day in [days]. */
    private fun points(days: IntRange, kg: (Int) -> Double) = TrendCalculator.trend(
        days.map { d -> WeighIn(day0.plusDays(d.toLong()), day0.plusDays(d.toLong()).toEpochDay() * 86_400_000L, (kg(d) * 1000).roundToInt(), WeightSource.HOME_SCALE) },
        WeightSource.HOME_SCALE,
    )

    @Test
    fun `the weekly rates and the 4-week change come from the trend`() {
        // A steady 0.1 kg a day loss, long enough for the trend to settle.
        val trend = points(0..90) { 90.0 - 0.1 * it }
        val end = day0.plusDays(90)
        val e = CheckinFlags.evaluate(cut, trend, emptyList(), end)
        val rate = TrendCalculator.weeklyRate(trend)!!.pctPerWeek
        assertEquals(rate, e.ratePct!!, 1e-9)
        assertEquals(-0.7 / e.trendKg!! * 100, e.ratePct!!, 0.01)
        assertEquals(-0.7 / e.trendKg!! * 100, e.previousRatePct!!, 0.02)
        assertEquals(-2.8, e.fourWeekKg!!, 0.01)
        assertEquals(e.fourWeekKg!! / e.trendKg!! * 100, e.fourWeekPct!!, 1e-9)
        // About 0.85% a week against a 0.75% pace: on track.
        assertEquals(ON_TRACK, e.status)
    }

    @Test
    fun `a slow cut flags only after two slow weeks`() {
        // 0.1 kg a day, then 0.02 kg a day from day 70.
        val kg = { d: Int -> if (d < 70) 90.0 - 0.1 * d else 83.0 - 0.02 * (d - 70) }
        val oneWeek = CheckinFlags.evaluate(cut, points(0..77, kg), emptyList(), day0.plusDays(77))
        assertEquals(ON_TRACK, oneWeek.status)
        val later = CheckinFlags.evaluate(cut, points(0..100, kg), emptyList(), day0.plusDays(100))
        assertEquals(FLAGGED, later.status)
        assertEquals(LOSS_SLOW, later.flag)
    }

    @Test
    fun `a rate needs a weigh-in in its week and a trend reaching a week back`() {
        val trend = points(0..30) { 80.0 }
        // Weighed until day 30: on day 40 the rate's week has no weigh-in.
        val e = CheckinFlags.evaluate(maintain, trend, emptyList(), day0.plusDays(40))
        assertNull(e.ratePct)
        assertNull(e.fourWeekPct)
        assertEquals(NOT_ENOUGH_DATA, e.status)
        // A trend under 4 weeks old has rates but no 4-week change.
        val young = CheckinFlags.evaluate(maintain, points(10..30) { 80.0 }, emptyList(), day0.plusDays(30))
        assertEquals(0.0, young.ratePct!!, 1e-9)
        assertNull(young.fourWeekPct)
        assertEquals(NOT_ENOUGH_DATA, CheckinFlags.evaluate(cut, points(25..30) { 80.0 }, emptyList(), day0.plusDays(30)).status)
    }

    @Test
    fun `weigh-ins after the week's end are ignored`() {
        val trend = points(0..60) { if (it <= 50) 80.0 else 70.0 }
        val e = CheckinFlags.evaluate(maintain, trend, emptyList(), day0.plusDays(50))
        assertEquals(0.0, e.fourWeekPct!!, 1e-9)
        assertEquals(80.0, e.trendKg!!, 1e-9)
    }

    @Test
    fun `waist uses the readings nearest each end, within a week of it`() {
        val end = day0.plusDays(60)
        fun w(day: Int, cm: Double) = WaistReading(day0.plusDays(day.toLong()), cm)
        // The 4 weeks run from day 32 to day 60.
        assertEquals(-1.5, CheckinFlags.waistChange(listOf(w(25, 95.0), w(31, 92.0), w(54, 91.0), w(60, 90.5)), end)!!, 1e-9)
        assertEquals(-0.5, CheckinFlags.waistChange(listOf(w(39, 91.0), w(54, 90.5)), end)!!, 1e-9)
        // Nothing within a week of the start, or of the end.
        assertNull(CheckinFlags.waistChange(listOf(w(24, 92.0), w(60, 90.5)), end))
        assertNull(CheckinFlags.waistChange(listOf(w(40, 92.0), w(60, 90.5)), end))
        assertNull(CheckinFlags.waistChange(listOf(w(32, 92.0), w(53, 90.5)), end))
        assertNull(CheckinFlags.waistChange(emptyList(), end))
    }

    @Test
    fun `a flat recomp with waist readings fires, and without them asks for one`() {
        val trend = points(0..60) { 80.0 }
        val end = day0.plusDays(60)
        val waist = listOf(WaistReading(day0.plusDays(32), 90.0), WaistReading(day0.plusDays(59), 89.5))
        assertEquals(FLAT, CheckinFlags.evaluate(recomp, trend, waist, end).flag)
        assertEquals(NEEDS_WAIST, CheckinFlags.evaluate(recomp, trend, waist.take(1), end).status)
    }
}
