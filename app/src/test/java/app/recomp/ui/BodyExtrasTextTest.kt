package app.recomp.ui

import app.recomp.domain.body.TapeSite
import app.recomp.domain.body.MobilityMarker
import app.recomp.ui.body.BodyText
import app.recomp.ui.body.MobilityDraft
import app.recomp.ui.body.MobilityText
import org.junit.Assert.assertEquals
import org.junit.Test

/** What Settings says about tape measurements (BM-03). */
class BodyExtrasTextTest {
    @Test
    fun `the tape row lists waist first, then the chosen sites in order`() {
        assertEquals("Waist only", BodyText.tapeSites(emptySet()))
        assertEquals("Waist and arm", BodyText.tapeSites(setOf(TapeSite.ARM)))
        assertEquals("Waist, chest, thigh and hip", BodyText.tapeSites(setOf(TapeSite.HIP, TapeSite.CHEST, TapeSite.THIGH)))
    }

    @Test
    fun `mobility values read the way the tests are described`() {
        assertEquals("3.5 cm past your toes", MobilityText.value(MobilityMarker.SIT_AND_REACH, 3.5))
        assertEquals("4.0 cm short", MobilityText.value(MobilityMarker.SIT_AND_REACH, -4.0))
        assertEquals("At your toes", MobilityText.value(MobilityMarker.SIT_AND_REACH, 0.0))
        assertEquals("45 s", MobilityText.value(MobilityMarker.SQUAT_HOLD, 45.0))
        assertEquals("1:05", MobilityText.value(MobilityMarker.SQUAT_HOLD, 65.0))
        assertEquals("Yes", MobilityText.value(MobilityMarker.TOE_TOUCH, 1.0))
        assertEquals("Not yet", MobilityText.value(MobilityMarker.TOE_TOUCH, 0.0))
        assertEquals(-4.5, BodyText.parseSigned("\u22124.5")!!, 1e-9)
    }

    @Test
    fun `only the tests you took are saved`() {
        val draft = MobilityDraft(reachTenths = -25, holdSeconds = null, toeTouch = false, at = 0)
        assertEquals(mapOf(MobilityMarker.SIT_AND_REACH to -2.5, MobilityMarker.TOE_TOUCH to 0.0), draft.values())
    }
}

