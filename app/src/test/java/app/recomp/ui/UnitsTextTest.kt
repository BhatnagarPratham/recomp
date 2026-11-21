package app.recomp.ui

import app.recomp.data.repo.StepMove
import app.recomp.domain.persona.Units
import app.recomp.domain.units.UnitStyle
import app.recomp.ui.settings.UnitsText
import org.junit.Assert.assertEquals
import org.junit.Test

class UnitsTextTest {
    @Test
    fun `the switch dialog names each step it would move`() {
        val bench = StepMove(2_500, 2_268, listOf("Bench press") + List(11) { "Lift $it" })
        assertEquals("Bench press and 11 more: 2.5 kg → 5 lb", UnitsText.move(bench, UnitStyle.METRIC, UnitStyle.IMPERIAL))
        assertEquals("Leg press: 10 lb → 5 kg", UnitsText.move(StepMove(4_536, 5_000, listOf("Leg press")), UnitStyle.IMPERIAL, UnitStyle.METRIC))
        assertEquals("Move your weight steps to lb plates?", UnitsText.title(Units.IMPERIAL))
    }
}
