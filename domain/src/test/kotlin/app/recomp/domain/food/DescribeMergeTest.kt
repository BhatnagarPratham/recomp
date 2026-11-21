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
        assertEquals(emptyList<EstimatedItem>(), merged.alreadyCounted)
    }

    @Test
    fun `an item the parser already counted is dropped, whichever of its names the AI uses`() {
        val counted = listOf(parsed("Chapati/Roti", "2 phulkas"), parsed("Pea paneer curry (Matar paneer)", "1 katori matar paneer"))
        val merged = DescribeMerge.merge(counted, listOf(ai("Roti"), ai("Chapatis"), ai("Phulka"), ai("Matar Paneer"), ai("Pea paneer curry"), ai("Salad")))
        assertEquals(listOf("Salad"), merged.items.map { it.name })
        assertEquals(listOf("Roti", "Chapatis", "Phulka", "Matar Paneer", "Pea paneer curry"), merged.alreadyCounted.map { it.name })
    }

    @Test
    fun `S3 - a counted dish the AI renames with descriptors is still counted`() {
        val counted = listOf(parsed("Chapati/Roti", "2 roti"), parsed("Dal makhani", "1 katori dal makhani"))
        val names = listOf(
            "Roti", "Chapati", "Rotis", "2 Roti", "Dal Makhani", "Makhani dal",
            "Roti (2 pcs)", "Whole wheat roti", "Butter roti", "Dal makhani (restaurant style)", "Black dal makhani",
        )
        val merged = DescribeMerge.merge(counted, names.map(::ai) + ai("Cucumber salad"))
        assertEquals(names, merged.alreadyCounted.map { it.name })
        assertEquals(listOf("Cucumber salad"), merged.items.map { it.name })
    }

    @Test
    fun `an item that answers a part sent to the AI is kept, even when it shares a word with a counted one`() {
        val counted = listOf(parsed("Dal", "1 katori dal"))
        // "dal makhani" was sent to be estimated: the AI's dal makhani is that, not the counted dal again.
        val asked = DescribeMerge.merge(counted, listOf(ai("Dal makhani"), ai("Paneer")), unsure = listOf("1 bowl dal makhani"))
        assertEquals(listOf("Dal makhani", "Paneer"), asked.items.map { it.name })
        // Nothing like it was sent, so it's the counted dal described again, set aside with Add anyway.
        val repeat = DescribeMerge.merge(counted, listOf(ai("Dal makhani"), ai("Paneer")), unsure = listOf("paneer"))
        assertEquals(listOf("Paneer"), repeat.items.map { it.name })
        assertEquals(listOf("Dal makhani"), repeat.alreadyCounted.map { it.name })
    }

    @Test
    fun `an egg dish isn't counted as the roti just because egg is also a unit word`() {
        val merged = DescribeMerge.merge(listOf(parsed("Chapati/Roti", "2 roti")), listOf(ai("Egg curry"), ai("2 eggs")))
        assertEquals(listOf("Egg curry", "2 eggs"), merged.items.map { it.name })
    }

    @Test
    fun `with nothing counted, everything is kept`() {
        assertEquals(2, DescribeMerge.merge(emptyList(), listOf(ai("Roti"), ai("Dal"))).items.size)
    }
}
