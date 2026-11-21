package app.recomp.domain.nutrition

import app.recomp.domain.food.Macros
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** NU-18: Will it fit's before and after, for each goal's main number. */
class FitCheckTest {
    private val dish = Macros(kcal = 420.4, proteinG = 31.6)
    private val eaten = Intake(1_720, 58)

    @Test
    fun `recomp shows protein left before and after`() {
        val fit = FitCheck.of(Goal.RECOMP, 2_100, ProteinGoal.Full(120), eaten, dish)
        assertEquals(MainNumber.ProteinLeft(62, 120, 58), fit.before)
        assertEquals(MainNumber.ProteinLeft(30, 120, 90), fit.after)
        assertEquals(62, fit.proteinLeftBeforeG)
        assertEquals(30, fit.proteinLeftAfterG)
        assertEquals(380, fit.kcalLeftBefore)
        assertEquals(-40, fit.kcalLeftAfter)
    }

    @Test
    fun `fat loss shows calories left, and over is a plain negative`() {
        val fit = FitCheck.of(Goal.FAT_LOSS, 2_100, ProteinGoal.Full(120), eaten, dish)
        assertEquals(MainNumber.CaloriesLeft(380, 2_100), fit.before)
        assertEquals(MainNumber.CaloriesLeft(-40, 2_100), fit.after)
    }

    @Test
    fun `maintain shows calories against the band`() {
        val fit = FitCheck.of(Goal.MAINTAIN, 2_200, ProteinGoal.Full(110), Intake(1_850, 70), Macros(380.0, 20.0))
        val before = fit.before as MainNumber.CaloriesInRange
        val after = fit.after as MainNumber.CaloriesInRange
        assertEquals(1_850, before.eaten)
        assertEquals(2_230, after.eaten)
        assertEquals(before.band, after.band)
        assertEquals(true, after.inRange)
        assertEquals(false, before.inRange)
    }

    @Test
    fun `lean bulk shows calories still to eat`() {
        val fit = FitCheck.of(Goal.LEAN_BULK, 2_800, ProteinGoal.Full(140), Intake(2_200, 100), Macros(380.0, 20.0))
        assertEquals(MainNumber.CaloriesToEat(600, 2_800), fit.before)
        assertEquals(MainNumber.CaloriesToEat(220, 2_800), fit.after)
    }

    @Test
    fun `the ramp's target is the protein target, and the baseline week has none`() {
        val ramping = FitCheck.of(Goal.RECOMP, 2_100, ProteinGoal.Ramping(120, 90, 2), eaten, dish)
        assertEquals(90, ramping.proteinTargetG)
        assertEquals(0, ramping.proteinLeftAfterG)
        val measuring = FitCheck.of(Goal.RECOMP, 2_100, ProteinGoal.Measuring(120, 3), eaten, dish)
        assertNull(measuring.proteinTargetG)
        assertNull(measuring.proteinLeftAfterG)
        assertEquals(58, measuring.proteinBeforeG)
        assertEquals(90, measuring.proteinAfterG)
    }

    @Test
    fun `past the protein target the line goes negative while the main number stops at zero`() {
        val fit = FitCheck.of(Goal.RECOMP, 2_100, ProteinGoal.Full(80), eaten, dish)
        assertEquals(-10, fit.proteinLeftAfterG)
        assertEquals(MainNumber.ProteinLeft(0, 80, 90), fit.after)
    }
}
