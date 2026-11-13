package app.recomp.domain.body

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** IB-04, TRD §5.6: deltas between on-protocol scans, noise labels at their edges, and the slope. Invented scans. */
class ScanTrendsTest {
    private val day0 = LocalDate.of(2026, 9, 7)

    private fun scan(day: Int, muscle: Double, fat: Double, pct: Double, ok: Boolean = true) =
        ScanPoint(day0.plusDays(day.toLong()), day0.plusDays(day.toLong()).toEpochDay() * 86_400_000L + 7 * 3_600_000L, muscle, fat, pct, ok)

    @Test
    fun `noise labels sit at 1 kg of muscle and 1 point of body fat`() {
        fun change(dMuscle: Double, dPct: Double) = ScanChange(scan(0, 33.0, 20.0, 25.0), scan(7, 33.0 + dMuscle, 20.0, 25.0 + dPct))
        assertTrue(change(0.99, 0.99).muscleWithinNoise)
        assertTrue(change(0.99, 0.99).bodyFatWithinNoise)
        assertTrue(change(-0.99, -0.99).bodyFatWithinNoise)
        assertFalse(change(1.0, 0.0).muscleWithinNoise)
        assertFalse(change(-1.0, 0.0).muscleWithinNoise)
        assertFalse(change(0.0, 1.0).bodyFatWithinNoise)
        assertFalse(change(0.0, -1.0).bodyFatWithinNoise)
        assertEquals(0.4, change(0.4, 0.0).muscleKg, 1e-9)
    }

    @Test
    fun `off-protocol scans are left out of the changes`() {
        val scans = listOf(scan(0, 33.0, 20.0, 25.0), scan(7, 36.0, 17.0, 20.0, ok = false), scan(14, 33.5, 19.5, 24.5))
        val changes = ScanTrends.changes(scans)
        assertEquals(1, changes.size)
        assertEquals(0.5, changes.single().muscleKg, 1e-9)
        assertEquals(-0.5, changes.single().bodyFatPct, 1e-9)
    }

    @Test
    fun `the slope is per 4 weeks, from three on-protocol scans, ignoring off-protocol ones`() {
        // Muscle +0.25 kg a week, fat −0.5 kg a week, body fat −0.5 points a week; an off-protocol outlier in between.
        val scans = listOf(
            scan(0, 33.0, 20.0, 25.0),
            scan(7, 33.25, 19.5, 24.5),
            scan(10, 40.0, 10.0, 15.0, ok = false),
            scan(14, 33.5, 19.0, 24.0),
            scan(21, 33.75, 18.5, 23.5),
        )
        val slopes = ScanTrends.slopes(scans)!!
        assertEquals(1.0, slopes.muscleKg, 1e-9)
        assertEquals(-2.0, slopes.fatKg, 1e-9)
        assertEquals(-2.0, slopes.bodyFatPct, 1e-9)
    }

    @Test
    fun `under three on-protocol scans, or all on one day, there's no slope`() {
        assertNull(ScanTrends.slopes(listOf(scan(0, 33.0, 20.0, 25.0), scan(7, 33.5, 19.5, 24.5), scan(14, 34.0, 19.0, 24.0, ok = false))))
        assertNull(ScanTrends.slopes(listOf(scan(3, 33.0, 20.0, 25.0), scan(3, 33.5, 19.5, 24.5), scan(3, 34.0, 19.0, 24.0))))
    }

    @Test
    fun `the week's scan is compared with the on-protocol scan before it`() {
        val scans = listOf(scan(0, 33.0, 20.0, 25.0), scan(7, 36.0, 17.0, 20.0, ok = false), scan(14, 33.5, 19.5, 24.5))
        val week = ScanTrends.weekScan(scans, day0.plusDays(14), day0.plusDays(20))!!
        assertEquals(scans[2], week.scan)
        assertEquals(scans[0], week.change!!.from)
        // An off-protocol scan is shown without a comparison.
        val off = ScanTrends.weekScan(scans, day0.plusDays(7), day0.plusDays(13))!!
        assertNull(off.change)
        // The first on-protocol scan has nothing to compare with, and a week without one has no scan.
        assertNull(ScanTrends.weekScan(scans, day0, day0.plusDays(6))!!.change)
        assertNull(ScanTrends.weekScan(scans, day0.plusDays(21), day0.plusDays(27)))
    }
}
