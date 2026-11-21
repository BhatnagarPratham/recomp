package app.recomp.ui

import app.recomp.domain.body.TapeSite
import app.recomp.ui.body.BodyText
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
}
