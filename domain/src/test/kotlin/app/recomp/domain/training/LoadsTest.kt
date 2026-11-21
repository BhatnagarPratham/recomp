package app.recomp.domain.training

import app.recomp.domain.model.Equipment
import app.recomp.domain.model.LoadType
import app.recomp.domain.model.SetType.WARMUP
import app.recomp.domain.model.SetType.WORKING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import app.recomp.domain.units.UnitStyle
import org.junit.Test

private val M = UnitStyle.METRIC
private val LB = UnitStyle.IMPERIAL

class LoadsTest {
    @Test
    fun `formats grams as kg without trailing zeros`() {
        assertEquals("42.5", Loads.formatKg(42_500))
        assertEquals("40", Loads.formatKg(40_000))
        assertEquals("1.25", Loads.formatKg(1_250))
        assertEquals("0", Loads.formatKg(0))
    }

    @Test
    fun `parses kg text into grams`() {
        assertEquals(42_500, Loads.parseKg("42.5"))
        assertEquals(42_500, Loads.parseKg(" 42,5 "))
        assertEquals(1_250, Loads.parseKg("1.25"))
        assertNull(Loads.parseKg(""))
        assertNull(Loads.parseKg("-5"))
        assertNull(Loads.parseKg("abc"))
        assertEquals(1_000_000, Loads.parseKg("1000"))
        assertNull(Loads.parseKg("1000.5"))
        assertNull(Loads.parseKg("3000000")) // would overflow Int as grams
    }

    @Test
    fun `stepper moves by the increment and stops at zero`() {
        assertEquals(47_500, Loads.step(45_000, 2_500, up = true))
        assertEquals(42_500, Loads.step(45_000, 2_500, up = false))
        assertEquals(0, Loads.step(1_000, 2_500, up = false))
    }

    @Test
    fun `every equipment type has a positive default increment`() {
        Equipment.entries.forEach { assertTrue(it.name, Loads.defaultIncrementG(it) > 0) }
        assertEquals(2_500, Loads.defaultIncrementG(Equipment.BARBELL))
        assertEquals(5_000, Loads.defaultIncrementG(Equipment.MACHINE))
    }

    @Test
    fun `only pure bodyweight exercises skip the load`() {
        assertFalse(Loads.logsLoad(LoadType.BODYWEIGHT))
        assertTrue(Loads.logsLoad(LoadType.BODYWEIGHT_PLUS))
        assertTrue(Loads.logsLoad(LoadType.WEIGHTED))
    }

    @Test
    fun `describes history compactly and skips warm-ups`() {
        val sets = listOf(
            LoggedSet(WARMUP, 20_000, 10),
            LoggedSet(WORKING, 45_000, 12), LoggedSet(WORKING, 45_000, 11), LoggedSet(WORKING, 47_500, 8),
        )
        assertEquals("45 × 12/11, 47.5 × 8", Loads.describe(sets, M))
        assertEquals("BW × 15/12", Loads.describe(listOf(LoggedSet(WORKING, null, 15), LoggedSet(WORKING, null, 12)), M))
        assertEquals("", Loads.describe(listOf(LoggedSet(WARMUP, 20_000, 10)), M))
    }

    @Test
    fun `describes history in lb, to a tenth of a lb (PR-05)`() {
        // 225 lb is 102,058 g; a kg-era 42.5 kg shows as 93.7 lb.
        val sets = listOf(LoggedSet(WORKING, 102_058, 5), LoggedSet(WORKING, 102_058, 4), LoggedSet(WORKING, 42_500, 12))
        assertEquals("225 × 5/4, 93.7 × 12", Loads.describe(sets, LB))
        assertEquals("225 × 5/4, 93,7 × 12", Loads.describe(sets, UnitStyle(LB.units, ',')))
        assertEquals("BW × 15", Loads.describe(listOf(LoggedSet(WORKING, null, 15)), LB))
    }
}
