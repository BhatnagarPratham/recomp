package app.recomp.domain.food

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI-04: amounts as people type them. Every phrase here is invented. */
class QuantitiesTest {
    private fun read(text: String) = Quantities.leading(Quantities.tokens(Quantities.normalise(text)))

    private fun value(text: String) = read(text)?.value

    @Test
    fun `digits, decimals, fractions and ranges`() {
        assertEquals(2.0, value("2 roti")!!, 1e-9)
        assertEquals(1.5, value("1.5 cup rice")!!, 1e-9)
        assertEquals(0.5, value("1/2 bowl rajma")!!, 1e-9)
        assertEquals(1.5, value("1 1/2 cup rice")!!, 1e-9)
        assertEquals(2.5, value("2-3 biscuits")!!, 1e-9)
        assertEquals(0.75, value("3/4 katori")!!, 1e-9)
    }

    @Test
    fun `fraction glyphs read as fractions, alone or after a whole number`() {
        assertEquals(0.5, value("½ katori rajma")!!, 1e-9)
        assertEquals(2.5, value("2½ roti")!!, 1e-9)
        assertEquals(0.25, value("¼ cup")!!, 1e-9)
        assertEquals(2.0 / 3, value("⅔ cup")!!, 1e-9)
    }

    @Test
    fun `numbers in words`() {
        assertEquals(1.0, value("a glass of milk")!!, 1e-9)
        assertEquals(1.0, value("an apple")!!, 1e-9)
        assertEquals(2.0, value("two eggs")!!, 1e-9)
        assertEquals(12.0, value("twelve almonds")!!, 1e-9)
        assertEquals(0.5, value("half a bowl of rajma")!!, 1e-9)
        assertEquals(0.5, value("half bowl")!!, 1e-9)
        assertEquals(0.5, value("a half cup")!!, 1e-9)
        assertEquals(0.25, value("quarter plate")!!, 1e-9)
        assertEquals(2.0, value("a couple of idlis")!!, 1e-9)
    }

    @Test
    fun `and joins an amount to its half`() {
        assertEquals(Quantities.Read(1.5, 4), read("one and a half cups rice"))
        assertEquals(Quantities.Read(2.5, 4), read("2 and a half roti"))
        assertEquals(Quantities.Read(1.5, 3), read("1 and 1/2 cup"))
        assertEquals(Quantities.Read(2.5, 3), read("two and half"))
        val tokens = Quantities.tokens("one and a half cups rice and dal")
        assertTrue(Quantities.joinsAmount(tokens, 1))
        assertFalse("the second and joins two foods", Quantities.joinsAmount(tokens, 6))
        assertFalse(Quantities.joinsAmount(Quantities.tokens("two eggs and a glass of milk"), 2))
    }

    @Test
    fun `how many tokens an amount takes`() {
        assertEquals(1, read("2 roti")!!.used)
        assertEquals(2, read("1 1/2 cup")!!.used)
        assertEquals(2, read("half a bowl")!!.used)
        assertEquals(2, read("a couple of idlis")!!.used)
        assertEquals(1, read("a glass")!!.used)
    }

    @Test
    fun `junk is not an amount`() {
        assertNull(read("roti"))
        assertNull(read(""))
        assertNull(read("x"))
        assertNull(Quantities.number("1/0"))
        assertNull(Quantities.number("0"))
        assertNull(Quantities.number("3-2"))
        assertNull(Quantities.number("99999999"))
        assertNull(Quantities.number("roti"))
        // "2." is a 2 followed by punctuation.
        assertEquals(2.0, value("2. roti")!!, 1e-9)
    }

    @Test
    fun `tokens split numbers from letters and drop punctuation`() {
        assertEquals(listOf("150", "g", "paneer"), Quantities.tokens("150g paneer"))
        assertEquals(listOf("roti", "x", "2"), Quantities.tokens("roti x2"))
        assertEquals(listOf("paneer", "low", "fat"), Quantities.tokens("paneer, low-fat"))
        assertEquals(listOf("eggs", "and", "toast"), Quantities.tokens(Quantities.normalise("Eggs & toast")))
    }
}
