package app.recomp.domain.nutrition

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GL-07 and GL-10 (TRD §5.9), plus the maintenance band behind Maintain and "in a deficit". */
class TodayNumbersTest {
    private val today = LocalDate.of(2026, 10, 17)

    @Test
    fun `fat loss shows calories left, going negative once over`() {
        assertEquals(MainNumber.CaloriesLeft(2090, 2090), TodayNumbers.main(Goal.FAT_LOSS, 2090, ProteinGoal.Full(125), Intake.NONE))
        assertEquals(MainNumber.CaloriesLeft(-110, 2090), TodayNumbers.main(Goal.FAT_LOSS, 2090, ProteinGoal.Full(125), Intake(2200, 90)))
    }

    @Test
    fun `recomp shows protein left against the ramped target`() {
        assertEquals(MainNumber.ProteinLeft(55, 95, 40), TodayNumbers.main(Goal.RECOMP, 2090, ProteinGoal.Ramping(125, 95, 1), Intake(900, 40)))
        assertEquals(MainNumber.ProteinLeft(125, 125, 0), TodayNumbers.main(Goal.RECOMP, 2090, ProteinGoal.NotStarted(125), Intake.NONE))
        assertEquals(MainNumber.ProteinLeft(0, 125, 140), TodayNumbers.main(Goal.RECOMP, 2090, ProteinGoal.Full(125), Intake(2000, 140)))
        // The baseline week has no target, only what you ate.
        assertEquals(MainNumber.ProteinLeft(null, null, 40), TodayNumbers.main(Goal.RECOMP, 2090, ProteinGoal.Measuring(125, 3), Intake(900, 40)))
    }

    @Test
    fun `maintain shows calories against the 5 percent band`() {
        val none = TodayNumbers.main(Goal.MAINTAIN, 2610, ProteinGoal.Full(125), Intake.NONE) as MainNumber.CaloriesInRange
        assertEquals(2480..2740, none.band)
        assertEquals(2480, none.belowBy)
        assertFalse(none.inRange)

        val inside = TodayNumbers.main(Goal.MAINTAIN, 2610, ProteinGoal.Full(125), Intake(2500, 120)) as MainNumber.CaloriesInRange
        assertTrue(inside.inRange)
        assertEquals(0, inside.belowBy)

        val over = TodayNumbers.main(Goal.MAINTAIN, 2610, ProteinGoal.Full(125), Intake(2800, 120)) as MainNumber.CaloriesInRange
        assertEquals(60, over.aboveBy)
    }

    @Test
    fun `lean bulk shows calories still to eat, never negative`() {
        assertEquals(MainNumber.CaloriesToEat(2810, 2810), TodayNumbers.main(Goal.LEAN_BULK, 2810, ProteinGoal.Full(112), Intake.NONE))
        assertEquals(MainNumber.CaloriesToEat(0, 2810), TodayNumbers.main(Goal.LEAN_BULK, 2810, ProteinGoal.Full(112), Intake(3000, 130)))
    }

    @Test
    fun `the 7-day average covers logged days in the last 7 complete days`() {
        val days = listOf(
            LoggedDay(today.minusDays(8), Intake(5000, 10), 2090, 125), // too old
            LoggedDay(today.minusDays(7), Intake(2000, 100), 2090, 125),
            LoggedDay(today.minusDays(1), Intake(2200, 120), 2050, 125),
            LoggedDay(today, Intake(400, 30), 2050, 125), // today is still in progress
        )
        assertEquals(WeekAverage(kcal = 2100, proteinG = 110, kcalTarget = 2070, proteinTargetG = 125, daysLogged = 2), TodayNumbers.weekAverage(today, days))
    }

    @Test
    fun `no logged days means no average`() {
        assertNull(TodayNumbers.weekAverage(today, emptyList()))
        assertNull(TodayNumbers.weekAverage(today, listOf(LoggedDay(today, Intake(400, 30), 2050, 125))))
    }

    @Test
    fun `measuring days carry no protein target into the average`() {
        val days = listOf(LoggedDay(today.minusDays(2), Intake(2000, 70), 2090, null))
        assertNull(TodayNumbers.weekAverage(today, days)!!.proteinTargetG)
    }

    @Test
    fun `the maintenance band rounds to 10 and decides what counts as a deficit`() {
        assertEquals(2480..2740, EnergyBalance.band(2610))
        assertEquals(2480..2740, EnergyBalance.band(2611.4))
        // Maintain's target is TDEE rounded to 10: never a deficit.
        assertFalse(EnergyBalance.inDeficit(2610, 2611.4))
        assertFalse(EnergyBalance.inDeficit(2480, 2611.4))
        assertTrue(EnergyBalance.inDeficit(2470, 2611.4))
        // Vector A's recomp and vector C's bulk.
        assertTrue(EnergyBalance.inDeficit(2090, 2611.4))
        assertFalse(EnergyBalance.inDeficit(2810, 2556.0))
    }
}
