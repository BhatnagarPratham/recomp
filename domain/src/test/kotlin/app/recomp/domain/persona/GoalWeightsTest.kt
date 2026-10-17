package app.recomp.domain.persona

import app.recomp.domain.nutrition.Goal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Suggested goal weight, default finish lines and the pace projection (TRD §5.8). Illustrative inputs. */
class GoalWeightsTest {
    private val today = LocalDate.of(2026, 10, 17)

    @Test
    fun `above BMI 25 the suggestion is the BMI-25 weight`() {
        // 25 × 1.75² = 76.6
        assertEquals(77.0, GoalWeights.suggest(heightCm = 175.0, weightKg = 95.0), 0.0)
    }

    @Test
    fun `at BMI 25 or less the suggestion is 5 percent below current`() {
        assertEquals(68.0, GoalWeights.suggest(175.0, 72.0), 0.0)
        // Exactly BMI 25 counts as "already 25 or less".
        assertEquals(73.0, GoalWeights.suggest(175.0, 76.5625), 0.0)
    }

    @Test
    fun `the lean suggestion never goes below BMI 20 or above current weight`() {
        // 5% below 63 would be BMI 19.5, so it stops at BMI 20 (61.25 kg).
        assertEquals(61.0, GoalWeights.suggest(175.0, 63.0), 0.0)
        // Already below BMI 20: the suggestion is the current weight.
        assertEquals(60.0, GoalWeights.suggest(175.0, 60.0), 0.0)
    }

    @Test
    fun `default finish lines follow the goal`() {
        assertEquals(FinishLine.GoalWeight, GoalWeights.defaultFinishLine(Goal.FAT_LOSS, today))
        assertEquals(FinishLine.GoalWeight, GoalWeights.defaultFinishLine(Goal.RECOMP, today))
        assertEquals(FinishLine.ByDate(LocalDate.of(2027, 2, 6)), GoalWeights.defaultFinishLine(Goal.LEAN_BULK, today))
        assertNull(GoalWeights.defaultFinishLine(Goal.MAINTAIN, today))
        assertTrue(GoalWeights.suggestsGoalWeight(Goal.RECOMP))
        assertFalse(GoalWeights.suggestsGoalWeight(Goal.LEAN_BULK))
    }

    @Test
    fun `projection turns the daily adjustment into kg a week`() {
        assertEquals(-0.5, Projection.kgPerWeek(-550.0), 1e-9)
        assertEquals(0.25, Projection.kgPerWeek(275.0), 1e-9)
        assertEquals(0.0, Projection.kgPerWeek(0.0), 0.0)
    }

    @Test
    fun `weeks to the goal weight, and none when moving away or standing still`() {
        assertEquals(4.0, Projection.weeksTo(80.0, 78.0, -0.5)!!, 1e-9)
        assertEquals(8.0, Projection.weeksTo(70.0, 72.0, 0.25)!!, 1e-9)
        assertEquals(0.0, Projection.weeksTo(78.0, 78.0, -0.5)!!, 0.0)
        assertNull(Projection.weeksTo(80.0, 78.0, 0.25))
        assertNull(Projection.weeksTo(80.0, 78.0, 0.0))
    }

    @Test
    fun `finish date and change by a date`() {
        assertEquals(today.plusDays(28), Projection.finishDate(today, 4.0))
        assertEquals(today.plusDays(25), Projection.finishDate(today, 3.5))
        assertEquals(4.0, Projection.changeBy(today, today.plusWeeks(16), 0.25), 1e-9)
    }
}
