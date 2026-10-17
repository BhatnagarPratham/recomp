package app.recomp.domain.nutrition

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/** NU-03, TRD §5.3: the baseline window, its slide, and the weekly steps. Illustrative intakes. */
class ProteinRampTest {
    private val from = LocalDate.of(2026, 10, 1)
    private fun day(d: Int): LocalDate = LocalDate.of(2026, 10, d)
    private fun logs(vararg entries: Pair<Int, Int>) = entries.associate { (d, g) -> day(d) to g }

    @Test
    fun `nothing logged yet has not started and aims at the full target`() {
        assertEquals(Baseline.NotStarted, ProteinRamp.baseline(from, day(10), emptyMap()))
        val goal = ProteinRamp.goal(125, enabled = true, RampState(from), day(10), emptyMap())
        assertEquals(ProteinGoal.NotStarted(125), goal)
        assertEquals(125, goal.todayG)
    }

    @Test
    fun `the window starts at the first logged day and measures until it finishes`() {
        val partial = logs(2 to 60, 3 to 70, 4 to 80, 5 to 90)
        assertEquals(Baseline.Measuring(day(2), 4), ProteinRamp.baseline(from, day(6), partial))
        // The window's last day is today: still measuring, and no target meanwhile.
        val goal = ProteinRamp.goal(125, true, RampState(from), day(8), partial + logs(6 to 100))
        assertEquals(ProteinGoal.Measuring(125, 5), goal)
        assertEquals(null, goal.todayG)
    }

    @Test
    fun `a finished window with 5 logged days is the baseline`() {
        val week = logs(2 to 60, 3 to 70, 4 to 80, 5 to 90, 6 to 100)
        assertEquals(Baseline.Done(day(8), 80), ProteinRamp.baseline(from, day(9), week))
    }

    @Test
    fun `a finished window with fewer than 5 logged days slides to the next logged day`() {
        val sparse = logs(2 to 50, 3 to 50, 4 to 50, 10 to 70, 11 to 80, 12 to 90, 13 to 100, 14 to 110)
        assertEquals(Baseline.Done(day(16), 90), ProteinRamp.baseline(from, day(20), sparse))
        // Before the second window finishes it's measuring from its own start.
        assertEquals(Baseline.Measuring(day(10), 5), ProteinRamp.baseline(from, day(16), sparse))
    }

    @Test
    fun `days before the ramp was turned on don't count`() {
        val before = (24..30).associate { LocalDate.of(2026, 9, it) to 80 }
        assertEquals(Baseline.NotStarted, ProteinRamp.baseline(from, day(10), before))
    }

    @Test
    fun `the target rises 15 g a week from the day after the baseline`() {
        val locked = RampState(from, baselineEnd = day(8), baselineMeanG = 80)
        fun on(d: Int) = ProteinRamp.goal(125, true, locked, day(d), emptyMap())
        assertEquals(ProteinGoal.Ramping(125, 95, 1), on(9))
        assertEquals(ProteinGoal.Ramping(125, 95, 1), on(15))
        assertEquals(ProteinGoal.Ramping(125, 110, 2), on(16))
        // Week 3 would be 125: the full target.
        assertEquals(ProteinGoal.Full(125), on(23))
        assertEquals(ProteinGoal.Full(125), on(30))
    }

    @Test
    fun `an unlocked baseline is found from the logs`() {
        val week = logs(2 to 60, 3 to 70, 4 to 80, 5 to 90, 6 to 100)
        assertEquals(ProteinGoal.Ramping(125, 95, 1), ProteinRamp.goal(125, true, RampState(from), day(9), week))
    }

    @Test
    fun `a locked baseline ignores later log edits`() {
        val locked = RampState(from, baselineEnd = day(8), baselineMeanG = 80)
        val edited = logs(2 to 200, 3 to 200, 4 to 200, 5 to 200, 6 to 200)
        assertEquals(ProteinGoal.Ramping(125, 95, 1), ProteinRamp.goal(125, true, locked, day(9), edited))
    }

    @Test
    fun `editing the full target keeps the ramp's week`() {
        val locked = RampState(from, baselineEnd = day(8), baselineMeanG = 80)
        assertEquals(ProteinGoal.Ramping(150, 140, 4), ProteinRamp.goal(150, true, locked, day(30), emptyMap()))
    }

    @Test
    fun `a baseline already at the full target skips the ramp`() {
        val locked = RampState(from, baselineEnd = day(8), baselineMeanG = 130)
        assertEquals(ProteinGoal.Full(125), ProteinRamp.goal(125, true, locked, day(9), emptyMap()))
    }

    @Test
    fun `turning the ramp off jumps to the full target`() {
        val locked = RampState(from, baselineEnd = day(8), baselineMeanG = 80)
        assertEquals(ProteinGoal.Full(125), ProteinRamp.goal(125, enabled = false, locked, day(9), emptyMap()))
        assertEquals(ProteinGoal.Full(125), ProteinRamp.goal(125, enabled = true, state = null, day(9), emptyMap()))
    }
}
