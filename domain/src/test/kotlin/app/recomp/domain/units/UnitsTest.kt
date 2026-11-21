package app.recomp.domain.units

import app.recomp.domain.model.Equipment
import app.recomp.domain.persona.Units
import java.math.BigDecimal
import java.math.RoundingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnitsTest {
    private val lb = UnitStyle.IMPERIAL
    private val kg = UnitStyle.METRIC

    @Test
    fun `225 lb is 102,058 g and shows as 225 lb, and every hundredth of a pound round-trips`() {
        assertEquals(102_058, UnitMath.lbToG(BigDecimal("225")))
        assertEquals("225", LoadUnits.format(102_058, lb))
        assertEquals(BigDecimal("225.00"), UnitMath.gToLb(102_058))
        for (hundredths in 0..100_000) {
            val value = BigDecimal(hundredths).movePointLeft(2)
            assertEquals(value.setScale(2), UnitMath.gToLb(UnitMath.lbToG(value)))
        }
    }

    @Test
    fun `steps in lb land on integer grams without drifting`() {
        val five = UnitMath.lbToG(BigDecimal(5))
        var load = 0
        repeat(45) { load = LoadUnits.step(load, five, up = true, Units.IMPERIAL) }
        assertEquals(102_058, load)
        assertEquals(102_060, 45 * five)
        repeat(45) { load = LoadUnits.step(load, five, up = false, Units.IMPERIAL) }
        assertEquals(0, load)
        assertEquals(0, LoadUnits.step(1_000, five, up = false, Units.IMPERIAL))
        // In kg, steps are plain grams, as before.
        assertEquals(42_500, LoadUnits.step(40_000, 2_500, up = true, Units.METRIC))
    }

    @Test
    fun `a kg-era load shows rounded in lb, and is only rewritten when it's snapped`() {
        assertEquals("93.7", LoadUnits.format(42_500, lb))
        assertEquals("42.5", LoadUnits.format(42_500, kg))
        assertEquals(UnitMath.lbToG(BigDecimal("93.70")), LoadUnits.snap(42_500, Units.IMPERIAL))
        assertEquals(42_500, LoadUnits.snap(42_500, Units.METRIC))
    }

    @Test
    fun `engine targets snap to the exercise's lb grid in the right direction`() {
        val five = UnitMath.lbToG(BigDecimal(5))
        // 100 kg is 220.46 lb: an increase goes up to 225 lb, a deload down to 220 lb.
        assertEquals(UnitMath.lbToG(BigDecimal(225)), LoadUnits.increase(100_000, 100_000 + five, five, Units.IMPERIAL))
        // From a point on the grid, one step up; a target that rounds down to what you lifted still goes up a step.
        assertEquals(UnitMath.lbToG(BigDecimal(230)), LoadUnits.increase(102_058, 102_058 + five, five, Units.IMPERIAL))
        assertEquals(UnitMath.lbToG(BigDecimal(225)), LoadUnits.increase(101_500, 101_600, five, Units.IMPERIAL))
        assertEquals(102_500, LoadUnits.increase(100_000, 102_500, 2_500, Units.METRIC))
        assertEquals(UnitMath.lbToG(BigDecimal(220)), LoadUnits.toGrid(100_000, five, Units.IMPERIAL, RoundingMode.DOWN))
        // A point already on the grid stays.
        assertEquals(102_058, LoadUnits.toGrid(102_058, five, Units.IMPERIAL, RoundingMode.UP))
        assertEquals(102_058, LoadUnits.toGrid(102_058, five, Units.IMPERIAL, RoundingMode.DOWN))
        // kg is untouched.
        assertEquals(101_234, LoadUnits.toGrid(101_234, 2_500, Units.METRIC, RoundingMode.UP))
    }

    @Test
    fun `typed loads parse in either unit and either decimal mark`() {
        assertEquals(102_058, LoadUnits.parse("225", Units.IMPERIAL))
        assertEquals(UnitMath.lbToG(BigDecimal("225.5")), LoadUnits.parse(" 225,5 ", Units.IMPERIAL))
        assertEquals(42_500, LoadUnits.parse("42,5", Units.METRIC))
        listOf("", " ", "-5", "abc", "1e9", "2205.0").forEach { assertNull(it, LoadUnits.parse(it, Units.IMPERIAL)) }
        assertNull(LoadUnits.parse("1000.1", Units.METRIC))
        assertEquals(UnitMath.lbToG(BigDecimal("2204.62")), LoadUnits.parse("2204.62", Units.IMPERIAL))
    }

    @Test
    fun `weight steps and defaults in lb`() {
        assertEquals(listOf(454, 1134, 2268, 4536), LoadUnits.incrementChoicesG(Units.IMPERIAL))
        assertEquals(2268, LoadUnits.defaultIncrementG(Equipment.BARBELL, Units.IMPERIAL))
        assertEquals(2268, LoadUnits.defaultIncrementG(Equipment.KETTLEBELL, Units.IMPERIAL))
        assertEquals(4536, LoadUnits.defaultIncrementG(Equipment.CABLE, Units.IMPERIAL))
        assertEquals(4000, LoadUnits.defaultIncrementG(Equipment.KETTLEBELL, Units.METRIC))
        assertEquals("2.5", LoadUnits.formatStep(1134, lb))
        assertEquals("5.51", LoadUnits.formatStep(2500, lb))
        assertEquals("1.25", LoadUnits.formatStep(1250, kg))
    }

    @Test
    fun `the switch moves each step to the nearest one offered in the other unit`() {
        val toLb = mapOf(1000 to 1134, 1250 to 1134, 2000 to 2268, 2500 to 2268, 4000 to 4536, 5000 to 4536, 3000 to 2268, 7500 to 4536)
        toLb.forEach { (from, to) -> assertEquals("$from g", to, LoadUnits.nearestIncrementG(from, Units.IMPERIAL)) }
        val toKg = mapOf(454 to 1000, 1134 to 1250, 2268 to 2500, 4536 to 5000)
        toKg.forEach { (from, to) -> assertEquals("$from g", to, LoadUnits.nearestIncrementG(from, Units.METRIC)) }
    }

    @Test
    fun `lengths and height`() {
        assertEquals(175.26, UnitMath.heightCm(5, 9.0)!!, 1e-9)
        assertEquals(5 to 9, UnitMath.feetAndInches(175.0))
        assertEquals(6 to 0, UnitMath.feetAndInches(182.5))
        assertEquals(181.61, UnitMath.heightCm(5, 11.5)!!, 1e-9)
        assertNull(UnitMath.heightCm(5, 12.0))
        assertNull(UnitMath.heightCm(0, 0.0))
        assertEquals(32.25, UnitMath.cmToIn(UnitMath.inToCm(32.25)), 1e-12)
        assertEquals(-2.5, UnitMath.lengthOf(-6.35, Units.IMPERIAL), 1e-12)
        assertEquals(80.0, UnitMath.lengthOf(80.0, Units.METRIC), 0.0)
    }

    @Test
    fun `the decimal mark follows the style`() {
        val german = UnitStyle(Units.METRIC, ',')
        assertEquals("42,5", LoadUnits.format(42_500, german))
        assertEquals("93,7", LoadUnits.format(42_500, UnitStyle(Units.IMPERIAL, ',')))
        assertEquals("kg" to "cm", kg.weight to kg.length)
        assertEquals("lb" to "in", lb.weight to lb.length)
    }

    @Test
    fun `onboarding's seeded kg steps follow a choice of lb, and typed steps stay`() {
        assertEquals(2268, LoadUnits.defaultIn(2500, Units.IMPERIAL))
        assertEquals(4536, LoadUnits.defaultIn(5000, Units.IMPERIAL))
        assertEquals(2268, LoadUnits.defaultIn(2268, Units.IMPERIAL))
        assertEquals(3000, LoadUnits.defaultIn(3000, Units.IMPERIAL))
        assertEquals(2500, LoadUnits.defaultIn(2268, Units.METRIC))
        assertEquals(2500, LoadUnits.defaultIn(2500, Units.METRIC))
    }

    @Test
    fun `an exercise still on its kg default gets its equipment's lb default`() {
        assertEquals(2268, LoadUnits.defaultIn(4000, Equipment.KETTLEBELL, Units.IMPERIAL))
        assertEquals(4536, LoadUnits.defaultIn(5000, Equipment.CABLE, Units.IMPERIAL))
        assertEquals(2268, LoadUnits.defaultIn(2500, Equipment.BARBELL, Units.IMPERIAL))
        // Not the default: the nearest step, as before.
        assertEquals(4536, LoadUnits.defaultIn(4000, Equipment.BARBELL, Units.IMPERIAL))
        assertEquals(4000, LoadUnits.defaultIn(2268, Equipment.KETTLEBELL, Units.METRIC))
    }
}
