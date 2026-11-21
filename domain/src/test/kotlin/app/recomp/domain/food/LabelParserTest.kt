package app.recomp.domain.food

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NU-19's label parsing, on invented label texts in the shapes OCR returns them. No real product's label. */
class LabelParserTest {
    @Test
    fun `an FSSAI table gives the per-100 g column`() {
        val text = """
            NUTRITIONAL INFORMATION (Approx.)
            Nutrient Per 100 g Per Serve (30 g)
            Energy (kcal) 456 137
            Protein (g) 12.5 3.8
            Carbohydrate (g) 61.0 18.3
            of which Total Sugars (g) 18.2 5.5
            Total Fat (g) 17.9 5.4
            Saturated Fat (g) 8.1 2.4
            Dietary Fibre (g) 4.2 1.3
            Sodium (mg) 410 123
        """.trimIndent()
        val v = LabelParser.parse(text)
        assertEquals(LabelValues(LabelBasis.PER_100G, 456.0, 12.5, 61.0, 17.9, 4.2, 30.0), v)
        assertEquals(LabelField.entries.toSet(), v.filled)
    }

    @Test
    fun `columns in the other order still give per 100 g`() {
        val text = """
            Nutrition Information Per Serve (25 g) Per 100 g
            Energy (kcal) 120 480
            Protein (g) 2.5 10.0
            Carbohydrate (g) 15.0 60.0
            Total Fat (g) 5.5 22.0
        """.trimIndent()
        val v = LabelParser.parse(text)
        assertEquals(LabelBasis.PER_100G, v.basis)
        assertEquals(listOf(480.0, 10.0, 60.0, 22.0), listOf(v.kcal, v.proteinG, v.carbsG, v.fatG))
        assertEquals(25.0, v.servingG)
    }

    @Test
    fun `kJ and kcal on one line give kcal, and commas are decimal points`() {
        val text = """
            NUTRITION
            Typical values per 100 g per 30 g portion
            Energy 1680 kJ / 402 kcal 504 kJ / 121 kcal
            Fat 9,5 g 2,9 g
            of which saturates 1,2 g 0,4 g
            Carbohydrate 64 g 19 g
            of which sugars 21 g 6,3 g
            Fibre 7,8 g 2,3 g
            Protein 10 g 3,0 g
            Salt 0,61 g 0,18 g
        """.trimIndent()
        val v = LabelParser.parse(text)
        assertEquals(LabelValues(LabelBasis.PER_100G, 402.0, 10.0, 64.0, 9.5, 7.8, 30.0), v)
    }

    @Test
    fun `energy in kJ only is converted`() {
        val text = """
            Per 100 g
            Energy (kJ) 1674
            Protein (g) 8
        """.trimIndent()
        assertEquals(400.1, LabelParser.parse(text).kcal!!, 0.05)
    }

    @Test
    fun `separate kJ and kcal lines use the kcal one`() {
        val text = """
            Nutrient Per 100 g
            Energy (kJ) 1904
            Energy (kcal) 455
            Protein (g) 7.1
        """.trimIndent()
        assertEquals(455.0, LabelParser.parse(text).kcal)
    }

    @Test
    fun `a US Nutrition Facts panel is per serving, with its weight`() {
        val text = """
            Nutrition Facts
            8 servings per container
            Serving size 2/3 cup (55g)
            Amount per serving
            Calories 230
            Total Fat 8g 10%
            Saturated Fat 1g 5%
            Trans Fat 0g
            Sodium 160mg 7%
            Total Carbohydrate 37g 13%
            Dietary Fiber 4g 14%
            Total Sugars 12g
            Protein 3g
        """.trimIndent()
        val v = LabelParser.parse(text)
        assertEquals(LabelValues(LabelBasis.PER_SERVING, 230.0, 3.0, 37.0, 8.0, 4.0, 55.0), v)
    }

    @Test
    fun `OCR slips inside numbers are mended`() {
        val text = """
            Nutrient Per 100 g
            Energy (kcal) 45O
            Protein (g) l2.5
            Carbohydrate (g) 6l. 0
            Total Fat (g) 1O,4
        """.trimIndent()
        val v = LabelParser.parse(text)
        assertEquals(listOf(450.0, 12.5, 61.0, 10.4), listOf(v.kcal, v.proteinG, v.carbsG, v.fatG))
    }

    @Test
    fun `impossible values are left blank rather than filled wrong`() {
        val text = """
            Per 100 g
            Energy (kcal) 4560
            Protein (g) 125
            Total Fat (g) 17.9
        """.trimIndent()
        val v = LabelParser.parse(text)
        assertNull(v.kcal)
        assertNull(v.proteinG)
        assertEquals(17.9, v.fatG)
        assertEquals(setOf(LabelField.FAT), v.filled)
    }

    @Test
    fun `text that isn't a label reads as empty`() {
        val v = LabelParser.parse("Best before 12 months\nBatch no. A12\nMRP Rs. 45")
        assertTrue(v.empty)
        assertEquals(emptySet<LabelField>(), v.filled)
    }
}
