package app.recomp.ui

import app.recomp.domain.model.SetType
import app.recomp.domain.persona.Units
import app.recomp.domain.progression.PrType
import app.recomp.domain.units.UnitStyle
import app.recomp.ui.components.Labels
import app.recomp.ui.components.LoadEntry
import app.recomp.ui.components.unitStyle
import app.recomp.ui.history.EditSetUi
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** Lift loads, steps and records in kg and lb (PR-05, PRD §12.16). Invented lift loads. */
class LoadTextTest {
    private val kg = UnitStyle.METRIC
    private val lb = UnitStyle.IMPERIAL
    private val germanLb = unitStyle(Units.IMPERIAL, Locale.GERMANY)

    @Test
    fun `loads show to a tenth, without trailing zeros, in either unit and decimal mark`() {
        assertEquals("42.5 kg", Labels.load(42_500, kg))
        assertEquals("93.7 lb", Labels.load(42_500, lb))
        assertEquals("225 lb", Labels.load(102_058, lb))
        assertEquals("93,7 lb", Labels.load(42_500, germanLb))
        assertEquals("42,5 kg", Labels.load(42_500, unitStyle(Units.METRIC, Locale.GERMANY)))
        assertEquals("assist 20 lb", Labels.assist(9_072, lb))
        assertEquals("no assistance", Labels.assist(0, lb))
    }

    @Test
    fun `weight steps show to two decimals`() {
        assertEquals("1.25 kg", Labels.step(1_250, kg))
        assertEquals("5 lb", Labels.step(2_268, lb))
        assertEquals("2.5 lb", Labels.step(1_134, lb))
        // A kg step kept after switching shows converted.
        assertEquals("5.51 lb", Labels.step(2_500, lb))
        assertEquals("5,51 lb", Labels.step(2_500, germanLb))
    }

    @Test
    fun `records read in the persona's unit, with volume and estimated 1RM in whole units`() {
        assertEquals("62.5 kg", Labels.recordValue(PrType.MAX_LOAD, 62_500, 62_500, false, kg))
        assertEquals("225 lb", Labels.recordValue(PrType.MAX_LOAD, 102_058, 102_058, false, lb))
        assertEquals("8 reps at 225 lb", Labels.recordValue(PrType.REPS_AT_LOAD, 8, 102_058, false, lb))
        // 300 lb is 136,078 g.
        assertEquals("about 300 lb", Labels.recordValue(PrType.E1RM, 136_078, null, false, lb))
        assertEquals("about 136 kg", Labels.recordValue(PrType.E1RM, 136_078, null, false, kg))
        // 10,000 lb is 4,535,924 g.
        assertEquals("10,000 lb", Labels.recordValue(PrType.SESSION_VOLUME, 4_535_924, null, false, lb))
        assertEquals("assist 20 lb", Labels.recordValue(PrType.MAX_LOAD, 0, 9_072, true, lb))
        assertEquals("8,5 lb", Labels.load(3_856, germanLb))
    }

    @Test
    fun `a type-in left as shown keeps the stored grams, and a changed one is read in the unit`() {
        // A kg-era 42.5 kg shows as 93.7 lb; reading "93.7" back would write 42,502 g.
        assertEquals("93.7", LoadEntry.shown(42_500, lb))
        assertEquals(42_500, LoadEntry.parse("93.7", 42_500, lb))
        assertEquals(42_500, LoadEntry.parse(" 93.7 ", 42_500, lb))
        assertEquals(42_502, LoadEntry.parse("93.7", null, lb))
        assertEquals(42_547, LoadEntry.parse("93.8", 42_500, lb))
        assertEquals(42_500, LoadEntry.parse("93,7", 42_500, germanLb))
        assertEquals(102_058, LoadEntry.parse("225", null, lb))
        assertEquals(42_500, LoadEntry.parse("42.5", null, kg))
        assertEquals(null, LoadEntry.parse("abc", 42_500, lb))
        assertEquals("", LoadEntry.shown(null, lb))
    }

    @Test
    fun `an unedited set in a past workout keeps its grams, and so does a copy of it`() {
        val shown = EditSetUi(1, 10, SetType.WORKING, LoadEntry.shown(42_500, lb), "8", null, storedG = 42_500)
        assertEquals(42_500, LoadEntry.parse(shown.load, shown.storedG, lb))
        val edited = shown.copy(load = "95")
        assertEquals(43_091, LoadEntry.parse(edited.load, edited.storedG, lb))
    }
}
