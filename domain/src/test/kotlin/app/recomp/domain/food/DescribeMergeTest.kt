package app.recomp.domain.food

import app.recomp.domain.ai.Confidence
import app.recomp.domain.ai.EstimatedItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** PRD §12.5: the parser's items and the AI's, with nothing counted twice. Invented foods. */
class DescribeMergeTest {
    private fun parsed(name: String, typed: String) =
        ParsedItem(IndexedFood(1, name, FoodSource.INDB, Per100g(100.0, 5.0, 15.0, 2.0)), typed, 2.0, "roti", 80.0, Confidence.HIGH, "")

    private fun ai(name: String) = EstimatedItem(name, 1.0, "bowl", 120.0, 40.0, 2.0, 6.0, 1.0, 2.0, Confidence.MEDIUM, "")

    @Test
    fun `the AI's new items are kept`() {
        val merged = DescribeMerge.merge(listOf(parsed("Chapati/Roti", "2 roti")), listOf(ai("Cucumber salad")))
        assertEquals(listOf("Cucumber salad"), merged.items.map { it.name })
        assertEquals(emptyList<String>(), merged.dropped)
    }

    @Test
    fun `an item the parser already counted is dropped, whichever of its names the AI uses`() {
        val counted = listOf(parsed("Chapati/Roti", "2 phulkas"), parsed("Pea paneer curry (Matar paneer)", "1 katori matar paneer"))
        val merged = DescribeMerge.merge(counted, listOf(ai("Roti"), ai("Chapatis"), ai("Phulka"), ai("Matar Paneer"), ai("Pea paneer curry"), ai("Salad")))
        assertEquals(listOf("Salad"), merged.items.map { it.name })
        assertEquals(listOf("Roti", "Chapatis", "Phulka", "Matar Paneer", "Pea paneer curry"), merged.dropped)
    }

    @Test
    fun `a different dish that shares a word is not dropped`() {
        val merged = DescribeMerge.merge(listOf(parsed("Dal", "1 katori dal")), listOf(ai("Dal makhani"), ai("Paneer")))
        assertEquals(listOf("Dal makhani", "Paneer"), merged.items.map { it.name })
    }

    @Test
    fun `with nothing counted, everything is kept`() {
        assertEquals(2, DescribeMerge.merge(emptyList(), listOf(ai("Roti"), ai("Dal"))).items.size)
    }
}
