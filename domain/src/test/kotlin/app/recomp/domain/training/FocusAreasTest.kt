package app.recomp.domain.training

import org.junit.Assert.assertEquals
import org.junit.Test

class FocusAreasTest {
    @Test
    fun `areas keep one order however they were tapped, and tapping twice removes one`() {
        assertEquals(listOf("hips", "core"), FocusAreas.toggle(FocusAreas.toggle(emptyList(), "core"), "hips"))
        assertEquals(listOf("core"), FocusAreas.toggle(listOf("hips", "core"), "hips"))
        assertEquals(listOf("back", "full_body"), FocusAreas.normalize(listOf("full_body", "back", "back")))
    }

    @Test
    fun `an area this build doesn't know is kept, last`() {
        assertEquals(listOf("hips", "neck"), FocusAreas.normalize(listOf("neck", "hips")))
    }
}
