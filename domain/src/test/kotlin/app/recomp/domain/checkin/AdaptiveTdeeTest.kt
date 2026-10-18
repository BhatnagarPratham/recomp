package app.recomp.domain.checkin

import app.recomp.domain.persona.WeightSource
import app.recomp.domain.trend.TrendCalculator
import app.recomp.domain.trend.WeighIn
import java.time.LocalDate
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NU-05, TRD §5.4, on synthetic data with a known TDEE: a simulated body whose weight moves by
 * (intake − TDEE) / 7700 kg a day, weighed and logged on chosen days. Every number is invented.
 */
class AdaptiveTdeeTest {
    private val day0 = LocalDate.of(2026, 6, 1)

    /** A simulated person. [eaten] is real intake per day; [logShare] is the share of it that gets logged. */
    private class Body(
        val tdee: Double = 2500.0,
        val startKg: Double = 90.0,
        val days: Int = 70,
        val eaten: (Int) -> Double = { 2000.0 },
        /** Extra weight change on a day that isn't fat, e.g. water in a diet's first days. */
        val water: (Int) -> Double = { 0.0 },
        val logShare: Double = 1.0,
        val weighedOn: (Int) -> Boolean = { true },
        val loggedOn: (Int) -> Boolean = { true },
    ) {
        /** The morning weight on day [d], before that day's food. */
        val weights: List<Double> = buildList {
            var kg = startKg
            repeat(days + 1) { d ->
                add(kg)
                kg += (eaten(d) - tdee) / 7700.0 + water(d)
            }
        }
    }

    private fun date(d: Int) = day0.plusDays(d.toLong())

    private fun points(b: Body) = TrendCalculator.trend(
        (0..b.days).filter(b.weighedOn).map { d -> WeighIn(date(d), date(d).toEpochDay() * 86_400_000L, (b.weights[d] * 1000).roundToInt(), WeightSource.HOME_SCALE) },
        WeightSource.HOME_SCALE,
    )

    private fun intake(b: Body) = (0 until b.days).filter(b.loggedOn).associate { date(it) to (b.eaten(it) * b.logShare).roundToInt() }

    /** The check-in for the week ending on day [end]. */
    private fun estimate(b: Body, end: Int = b.days - 1, previous: Double = 2611.0, incomplete: Set<LocalDate> = emptySet()) =
        AdaptiveTdee.estimate(date(end), points(b), intake(b), incomplete, previous)

    @Test
    fun `daily weighing finds the known TDEE over 21 days`() {
        val e = estimate(Body())
        assertEquals(21, e.windowDays)
        assertEquals(21, e.loggedDays)
        assertEquals(21, e.weighIns)
        assertEquals(2000.0, e.kcalMean!!, 0.0)
        assertEquals(2500.0, e.observed!!, 10.0)
        // Halfway from the formula's 2,611 towards it.
        assertEquals(2611 + 0.5 * (e.observed!! - 2611), e.new, 1e-9)
        assertNull(e.gap)
    }

    @Test
    fun `weekly weighing uses 28 days and finds the same TDEE`() {
        val e = estimate(Body(weighedOn = { it % 7 == 0 }))
        assertEquals(28, e.windowDays)
        assertEquals(4, e.weighIns)
        assertEquals(28, e.loggedDays)
        assertEquals(2500.0, e.observed!!, 10.0)
    }

    @Test
    fun `a maintenance diet reads as its intake`() {
        val e = estimate(Body(eaten = { 2500.0 }))
        assertEquals(2500.0, e.observed!!, 1.0)
        assertEquals(e.trendStartKg!!, e.trendEndKg!!, 0.001)
    }

    @Test
    fun `gaps in weighing and logging still find the TDEE`() {
        // Five days without weighing, four days without food (17 of 21 logged), and the last weigh-in on Friday.
        val b = Body(weighedOn = { it !in 52..56 && it <= 67 }, loggedOn = { it !in setOf(50, 55, 60, 65) })
        val e = estimate(b)
        assertEquals(21, e.windowDays)
        assertEquals(17, e.loggedDays)
        assertEquals(2000.0, e.kcalMean!!, 0.0)
        assertEquals(2500.0, e.observed!!, 20.0)
    }

    @Test
    fun `an early water drop overstates the first estimates, the half step damps it, and it washes out`() {
        // Maintenance for 30 days, then a 500 kcal deficit with 1.5 kg of water lost over its first 3 days.
        val b = Body(days = 30 + 12 * 7, eaten = { if (it < 30) 2500.0 else 2000.0 }, water = { if (it in 30..32) -0.5 else 0.0 })
        var previous = 2500.0
        val weeks = (1..12).map { week ->
            estimate(b, end = 29 + week * 7, previous = previous).also { previous = it.new }
        }
        val worst = weeks.maxOf { it.observed!! }
        assertTrue("The water reads as extra burn: $worst", worst > 2500 + 200)
        // About +260 kcal at worst; the estimate peaks lower and later.
        assertTrue("The estimate is damped", weeks.maxOf { it.new } - 2500 < (worst - 2500) * 0.8)
        weeks.forEach { assertTrue(kotlin.math.abs(it.new - it.previous) <= AdaptiveTdee.MAX_STEP_KCAL) }
        // Once the drop has left the window, the observation is the real TDEE again and the estimate follows.
        assertEquals(2500.0, weeks.last().observed!!, 5.0)
        assertEquals(2500.0, weeks.last().new, 10.0)
    }

    @Test
    fun `steady under-logging shifts the estimate by the unlogged calories, so the deficit you see is real`() {
        val e = estimate(Body(logShare = 0.85))
        assertEquals(1700.0, e.kcalMean!!, 0.0)
        // 2,500 real TDEE minus the 15% of 2,000 kcal that went unlogged.
        assertEquals(2500.0 - 0.15 * 2000, e.observed!!, 10.0)
        // Against what you log, the estimate shows the real 500 kcal deficit.
        assertEquals(500.0, e.observed!! - e.kcalMean!!, 10.0)
    }

    @Test
    fun `17 of 21 days logged is enough and 16 isn't`() {
        val enough = estimate(Body(loggedOn = { it !in 49..52 }))
        assertEquals(17, enough.loggedDays)
        assertNull(enough.gap)

        val short = estimate(Body(loggedOn = { it !in 49..53 }))
        assertEquals(16, short.loggedDays)
        assertEquals(EstimateGap.FOOD_DAYS, short.gap)
        assertNull(short.observed)
        assertEquals(2611.0, short.new, 0.0)
    }

    @Test
    fun `a day marked incomplete is left out like a day without food`() {
        val b = Body(loggedOn = { it !in 49..52 })
        assertNull(estimate(b).gap)
        val e = estimate(b, incomplete = setOf(date(60)))
        assertEquals(16, e.loggedDays)
        assertEquals(EstimateGap.FOOD_DAYS, e.gap)

        // An incomplete day isn't in the mean either.
        val low = Body(eaten = { if (it == 60) 800.0 else 2000.0 })
        assertEquals(2000.0, estimate(low, incomplete = setOf(date(60))).kcalMean!!, 0.0)
    }

    @Test
    fun `a 28-day window needs 23 days logged`() {
        val weekly = { d: Int -> d % 7 == 0 }
        assertNull(estimate(Body(weighedOn = weekly, loggedOn = { it !in 42..46 })).gap)
        val short = estimate(Body(weighedOn = weekly, loggedOn = { it !in 42..47 }))
        assertEquals(22, short.loggedDays)
        assertEquals(EstimateGap.FOOD_DAYS, short.gap)
    }

    @Test
    fun `10 weigh-ins in 21 days make the daily window, 9 don't`() {
        // Every other day: 10 weigh-ins in 21 days.
        assertEquals(21, estimate(Body(weighedOn = { it % 2 == 0 })).windowDays)
        val ten = (49..69).filter { it % 2 == 0 }.take(10).toSet()
        assertEquals(21, estimate(Body(weighedOn = { it < 42 || it in ten })).windowDays)
        val nine = ten.take(9).toSet()
        val e = estimate(Body(weighedOn = { it < 42 || it in nine }))
        assertEquals(28, e.windowDays)
        assertNull(e.gap)
    }

    @Test
    fun `under 4 weigh-ins in 28 days gives no estimate`() {
        val e = estimate(Body(weighedOn = { it < 30 || it in setOf(45, 55, 65) }))
        assertEquals(28, e.windowDays)
        assertEquals(3, e.weighIns)
        assertEquals(EstimateGap.WEIGH_INS, e.gap)
        assertEquals(2611.0, e.new, 0.0)
    }

    @Test
    fun `a trend that starts inside the window isn't enough yet`() {
        // Weighing and logging from day 50 only: 20 days so far, and the window starts on day 49.
        val e = estimate(Body(weighedOn = { it >= 50 }, loggedOn = { it >= 50 }))
        assertEquals(EstimateGap.HISTORY, e.gap)
        assertEquals(20, e.loggedDays)
        // A week later the trend covers the window. A trend only 4 weeks old still lags behind the loss (TRD §10's
        // M5 risks), so the first estimate reads low; the half step and the clamp limit what it does.
        val later = estimate(Body(days = 77, weighedOn = { it >= 50 }, loggedOn = { it >= 50 }))
        assertNull(later.gap)
        assertTrue(later.observed!! in 2350.0..2500.0)
    }

    @Test
    fun `nothing logged or weighed gives no estimate`() {
        val e = AdaptiveTdee.estimate(date(20), emptyList(), emptyMap(), emptySet(), 2611.0)
        assertEquals(EstimateGap.WEIGH_INS, e.gap)
        assertNull(e.kcalMean)
        assertNull(e.trendEndKg)
        assertEquals(2611.0, e.new, 0.0)
    }

    @Test
    fun `weigh-ins and food after the week's end don't count`() {
        val b = Body(days = 80)
        val atEnd = estimate(b, end = 69)
        val cut = Body(days = 70)
        assertEquals(estimate(cut, end = 69), atEnd)
    }

    @Test
    fun `a far-off previous estimate moves 150 kcal a week`() {
        assertEquals(2850.0, estimate(Body(), previous = 3000.0).new, 0.0)
        assertEquals(2300.0, estimate(Body(), previous = 2150.0).new, 0.0)
    }

    @Test
    fun `each week moves halfway, by at most 150 kcal`() {
        assertEquals(2461.0, AdaptiveTdee.step(2611.0, 2000.0), 0.0)
        assertEquals(2761.0, AdaptiveTdee.step(2611.0, 3200.0), 0.0)
        assertEquals(2461.0, AdaptiveTdee.step(2611.0, 2311.0), 0.0)
        assertEquals(2462.0, AdaptiveTdee.step(2611.0, 2313.0), 0.0)
        assertEquals(2655.5, AdaptiveTdee.step(2611.0, 2700.0), 0.0)
        assertEquals(2611.0, AdaptiveTdee.step(2611.0, 2611.0), 0.0)
    }

    @Test
    fun `the minimum logged days round up`() {
        assertEquals(17, AdaptiveTdee.minLoggedDays(21))
        assertEquals(23, AdaptiveTdee.minLoggedDays(28))
        assertEquals(16, AdaptiveTdee.minLoggedDays(20))
    }

    @Test
    fun `a stored week's counts tell which check it failed`() {
        listOf(
            estimate(Body()),
            estimate(Body(weighedOn = { it < 30 || it in setOf(45, 55, 65) })),
            estimate(Body(loggedOn = { it !in 49..53 })),
            estimate(Body(weighedOn = { it >= 50 }, loggedOn = { it >= 50 })),
        ).forEach { e -> assertEquals(e.gap, AdaptiveTdee.gapOf(e.windowDays, e.loggedDays, e.weighIns, e.observed != null)) }
    }
}
