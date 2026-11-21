package app.recomp.domain.body

import app.recomp.domain.nutrition.Sex
import app.recomp.domain.persona.GoalWeights
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** IB-05, TRD §5.3: which scan sets BMR, the preview threshold, and the body-fat goal weight. Invented scans. */
class ScanTargetsTest {
    private val today = LocalDate.of(2026, 11, 21)
    private fun scan(daysAgo: Long, ok: Boolean = true, hour: Int = 7, fatG: Int = 29_000) =
        LeanScan(today.minusDays(daysAgo), today.minusDays(daysAgo).toEpochDay() * 86_400_000 + hour * 3_600_000L, 95_000, fatG, ok)

    @Test
    fun `the latest on-protocol scan from the last 60 days counts`() {
        val a = scan(10)
        val b = scan(3, ok = false)
        val c = scan(60, fatG = 28_000)
        assertEquals(a, ScanTargets.counting(listOf(c, a, b), today))
        // 60 days counts; 61 doesn't; off protocol never does; nor one dated after today.
        assertEquals(c, ScanTargets.counting(listOf(c, b), today))
        assertNull(ScanTargets.counting(listOf(scan(61), b), today))
        assertNull(ScanTargets.counting(listOf(scan(-1)), today))
        // Two the same day: the later one.
        val late = scan(2, hour = 19, fatG = 28_500)
        assertEquals(late, ScanTargets.counting(listOf(scan(2, hour = 7), late), today))
    }

    @Test
    fun `lean mass is weight minus fat mass, and BMR is Katch-McArdle on it`() {
        // Golden vector B's 66 kg of lean mass: 95 kg with 29 kg of fat.
        assertEquals(66.0, scan(1).leanKg, 1e-9)
        assertEquals(1795.6, ScanTargets.bmr(scan(1)), 1e-9)
        assertTrue(ScanTargets.worthPreview(1899.0, 1795.6))
        assertTrue(ScanTargets.worthPreview(1800.0, 1820.0))
        assertFalse(ScanTargets.worthPreview(1800.0, 1819.9))
    }

    @Test
    fun `a body-fat goal weight is lean mass over one minus the target`() {
        assertEquals(77.6, GoalWeights.forBodyFat(66.0, 15.0), 1e-9)
        assertEquals(82.5, GoalWeights.forBodyFat(66.0, 20.0), 1e-9)
        // The scan-based suggestion, rounded to a kg, and the BMI rule when there's no scan or you're already lean.
        assertEquals(78.0, GoalWeights.suggest(175.0, 95.0, 66.0, Sex.MALE), 0.0)
        assertEquals(GoalWeights.suggest(175.0, 95.0), GoalWeights.suggest(175.0, 95.0, null, Sex.MALE), 0.0)
        assertEquals(GoalWeights.suggest(175.0, 75.0), GoalWeights.suggest(175.0, 75.0, 66.0, Sex.MALE), 0.0)
        assertEquals(86.0, GoalWeights.suggest(165.0, 95.0, 66.0, Sex.FEMALE), 0.0)
    }
}
