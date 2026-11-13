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

    /** Masses typed in kg with one or two decimals, stored as grams the way the scan form stores them. */
    private fun scan(day: Int, muscle: Double, fat: Double, pct: Double, ok: Boolean = true, hour: Int = 7) =
        ScanPoint(
            day0.plusDays(day.toLong()),
            day0.plusDays(day.toLong()).toEpochDay() * 86_400_000L + hour * 3_600_000L,
            Math.round(muscle * 1000).toInt(),
            Math.round(fat * 1000).toInt(),
            pct,
            ok,
        )

    @Test
    fun `noise labels sit at 1 kg of muscle and 1 point of body fat`() {
        fun change(dMuscle: Double, dPct: Double) = ScanChange(scan(0, 33.0, 20.0, 25.0), scan(7, 33.0 + dMuscle, 20.0, 25.0 + dPct))
        // Body fat is typed to a tenth, so 0.9 is its largest change within noise.
        assertTrue(change(0.999, 0.9).muscleWithinNoise)
        assertTrue(change(0.999, 0.9).bodyFatWithinNoise)
        assertTrue(change(-0.999, -0.9).bodyFatWithinNoise)
        assertFalse(change(1.0, 0.0).muscleWithinNoise)
        assertFalse(change(-1.0, 0.0).muscleWithinNoise)
        assertFalse(change(0.0, 1.0).bodyFatWithinNoise)
        assertFalse(change(0.0, -1.0).bodyFatWithinNoise)
        assertEquals(0.4, change(0.4, 0.0).muscleKg, 1e-9)
    }

    @Test
    fun `a change of exactly 1 is never within noise, even when the doubles don't subtract cleanly`() {
        // Each of these differs by 0.999… as doubles.
        listOf(15.4 to 16.4, 7.2 to 8.2, 7.7 to 8.7, 15.9 to 16.9, 31.3 to 32.3, 31.8 to 32.8).forEach { (a, b) ->
            assertFalse("$a → $b", ScanChange(scan(0, 33.0, 20.0, a), scan(7, 33.0, 20.0, b)).bodyFatWithinNoise)
            assertFalse("$b → $a", ScanChange(scan(0, 33.0, 20.0, b), scan(7, 33.0, 20.0, a)).bodyFatWithinNoise)
        }
        listOf(31.3 to 32.3, 31.8 to 32.8).forEach { (a, b) ->
            assertFalse("$a → $b kg", ScanChange(scan(0, a, 20.0, 25.0), scan(7, b, 20.0, 25.0)).muscleWithinNoise)
            assertFalse("$b → $a kg", ScanChange(scan(0, b, 20.0, 25.0), scan(7, a, 20.0, 25.0)).muscleWithinNoise)
        }
        // 0.9 is still noise.
        assertTrue(ScanChange(scan(0, 31.3, 20.0, 15.4), scan(7, 32.2, 20.0, 16.3)).bodyFatWithinNoise)
        assertTrue(ScanChange(scan(0, 31.3, 20.0, 15.4), scan(7, 32.2, 20.0, 16.3)).muscleWithinNoise)
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

    @Test
    fun `a later off-protocol scan doesn't hide the week's on-protocol one`() {
        // Monday on protocol, Friday off it.
        val scans = listOf(scan(0, 33.0, 20.0, 25.0), scan(7, 33.5, 19.5, 24.5), scan(11, 36.0, 17.0, 20.0, ok = false))
        val week = ScanTrends.weekScan(scans, day0.plusDays(7), day0.plusDays(13))!!
        assertEquals(scans[1], week.scan)
        assertEquals(scans[0], week.change!!.from)
        // Two on-protocol scans in one week: the later one, against the earlier.
        val two = listOf(scan(0, 33.0, 20.0, 25.0), scan(7, 33.5, 19.5, 24.5, hour = 7), scan(7, 33.6, 19.4, 24.4, hour = 18))
        val same = ScanTrends.weekScan(two, day0.plusDays(7), day0.plusDays(13))!!
        assertEquals(two[2], same.scan)
        assertEquals(two[1], same.change!!.from)
    }

    @Test
    fun `a chart window keeps the scans from its first day on`() {
        val scans = listOf(scan(0, 33.0, 20.0, 25.0), scan(7, 33.5, 19.5, 24.5), scan(14, 34.0, 19.0, 24.0), scan(21, 34.5, 18.5, 23.5))
        assertEquals(scans.drop(1), ScanTrends.since(scans, day0.plusDays(7)))
        assertEquals(scans, ScanTrends.since(scans, day0.minusDays(1)))
        // The window's slope is over its own scans: three of them make one, two don't.
        assertEquals(2.0, ScanTrends.slopes(ScanTrends.since(scans, day0.plusDays(7)))!!.muscleKg, 1e-9)
        assertNull(ScanTrends.slopes(ScanTrends.since(scans, day0.plusDays(14))))
    }
}
