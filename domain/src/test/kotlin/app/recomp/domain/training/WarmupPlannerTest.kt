package app.recomp.domain.training

import org.junit.Assert.assertEquals
import org.junit.Test

/** WL-11: about 40%, 60% and 80% of today's target, rounded down to the step, nothing under two steps. */
class WarmupPlannerTest {
    private fun plan(targetKg: Double, stepKg: Double) =
        WarmupPlanner.plan((targetKg * 1000).toInt(), (stepKg * 1000).toInt()).map { Loads.formatKg(it.loadG!!) to it.reps }

    @Test
    fun `a 50 kg target warms up at 20, 30 and 40`() {
        assertEquals(listOf("20" to 8, "30" to 5, "40" to 3), plan(50.0, 2.5))
        assertEquals("Warm-up: 20 × 8 · 30 × 5 · 40 × 3", WarmupPlanner.describe(WarmupPlanner.plan(50_000, 2_500)))
    }

    @Test
    fun `loads round down to the exercise's step`() {
        // 40% of 42.5 is 17, 60% is 25.5, 80% is 34.
        assertEquals(listOf("15" to 8, "25" to 5, "32.5" to 3), plan(42.5, 2.5))
        // A 5 kg machine step: 40% of 72.5 is 29, 60% is 43.5, 80% is 58.
        assertEquals(listOf("25" to 8, "40" to 5, "55" to 3), plan(72.5, 5.0))
    }

    @Test
    fun `sets under two steps are left out, so light lifts get fewer or none`() {
        // 40% of 10 is 4 → 2.5, under two steps (5): dropped. 60% → 6 → 5, 80% → 8 → 7.5.
        assertEquals(listOf("5" to 5, "7.5" to 3), plan(10.0, 2.5))
        // 80% of 12.5 is exactly two steps, so it stays.
        assertEquals(listOf("10" to 3), plan(12.5, 5.0))
        assertEquals(emptyList<Pair<String, Int>>(), plan(10.0, 5.0))
        assertEquals(emptyList<Pair<String, Int>>(), plan(5.0, 2.5))
    }

    @Test
    fun `two warm-ups that round to the same load become one`() {
        // 60% of 17.5 is 10.5 and 80% is 14, both 10 with a 5 kg step.
        assertEquals(listOf("10" to 5), plan(17.5, 5.0))
    }

    @Test
    fun `no target load or no step means no warm-ups`() {
        assertEquals(emptyList<SetValues>(), WarmupPlanner.plan(0, 2_500))
        assertEquals(emptyList<SetValues>(), WarmupPlanner.plan(50_000, 0))
    }
}
