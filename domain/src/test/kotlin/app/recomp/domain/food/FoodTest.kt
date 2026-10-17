package app.recomp.domain.food

import app.recomp.domain.persona.Diet
import app.recomp.domain.persona.FoodRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NU-11's diet filter, NU-12's units and NU-16's totals. */
class FoodTest {
    @Test
    fun `each diet allows one more food tag than the one before`() {
        assertEquals(setOf(FoodDiet.VEGAN), Diet.VEGAN.allowedFoods)
        assertEquals(setOf(FoodDiet.VEGAN, FoodDiet.VEGETARIAN), Diet.LACTO_VEGETARIAN.allowedFoods)
        assertEquals(setOf(FoodDiet.VEGAN, FoodDiet.VEGETARIAN, FoodDiet.EGG), Diet.EGGETARIAN.allowedFoods)
        assertEquals(setOf(FoodDiet.VEGAN, FoodDiet.VEGETARIAN, FoodDiet.EGG, FoodDiet.FISH), Diet.PESCATARIAN.allowedFoods)
        assertEquals(FoodDiet.entries.toSet(), Diet.NON_VEGETARIAN.allowedFoods)

        assertFalse(Diet.LACTO_VEGETARIAN.allows(FoodDiet.EGG))
        assertFalse(Diet.PESCATARIAN.allows(FoodDiet.MEAT))
        assertTrue(Diet.PESCATARIAN.allows(FoodDiet.FISH))
        Diet.entries.forEach { assertTrue("$it allows its own tag", it.allows(it.ownFoodTag)) }
        assertEquals(FoodDiet.VEGETARIAN, Diet.LACTO_VEGETARIAN.ownFoodTag)
    }

    @Test
    fun `macros scale from per 100 g, and a serving converts to per 100 g`() {
        val paneer = Per100g(kcal = 265.0, proteinG = 18.0, carbsG = 3.0, fatG = 20.0, fiberG = 0.0)
        val m = paneer.forGrams(50.0)
        assertEquals(132.5, m.kcal, 1e-9)
        assertEquals(9.0, m.proteinG, 1e-9)
        assertEquals(10.0, m.fatG!!, 1e-9)

        val bar = Per100g.fromServing(60.0, Macros(210.0, 20.0, 18.0, 7.0, 3.0))
        assertEquals(350.0, bar.kcal, 1e-9)
        assertEquals(210.0, bar.forGrams(60.0).kcal, 1e-9)
    }

    @Test
    fun `a food's own units come first and beat the household measure of the same name`() {
        val household = Portions.household(FoodRegion.INDIAN, calibrated = mapOf("katori" to 180.0, "cup" to 999.0))
        assertEquals(mapOf("katori" to 180.0, "glass" to 250.0, "tbsp" to 15.0), household)

        val units = Portions.unitsFor(own = listOf("bowl" to 258.0, "tbsp" to 19.0, "g" to 1.0), household = household)
        assertEquals(listOf("bowl", "tbsp", "katori", "glass", "g"), units.map { it.label })
        assertEquals(19.0, units.first { it.label == "tbsp" }.grams, 1e-9)
        assertEquals(PortionUnit.Kind.OWN, units.first { it.label == "tbsp" }.kind)
        assertEquals(PortionUnit.Kind.HOUSEHOLD, units.first { it.label == "katori" }.kind)

        assertEquals(listOf("cup", "tbsp"), Portions.householdDefaults(FoodRegion.INTERNATIONAL).keys.toList())
        assertEquals(listOf("cup", "slice", "piece", "tbsp"), Portions.servingUnits(FoodRegion.INTERNATIONAL))
    }

    @Test
    fun `amounts step by a half, or 10 g, snapping to the grid`() {
        assertEquals(1.5, Portions.up(1.0, "katori"), 1e-9)
        assertEquals(1.5, Portions.up(1.3, "katori"), 1e-9)
        assertEquals(1.0, Portions.down(1.3, "katori"), 1e-9)
        assertEquals(0.5, Portions.down(1.0, "katori"), 1e-9)
        assertEquals(0.5, Portions.down(0.5, "katori"), 1e-9)
        assertEquals(110.0, Portions.up(100.0, "g"), 1e-9)
        assertEquals(10.0, Portions.down(10.0, "g"), 1e-9)

        assertEquals("½", Portions.format(0.5))
        assertEquals("2", Portions.format(2.0))
        assertEquals("1½", Portions.format(1.5))
        assertEquals("1.25", Portions.format(1.25))
    }

    @Test
    fun `totals split only what's known, and over is a negative number`() {
        val entries = listOf(
            Macros(300.0, 20.0, 30.0, 10.0, 4.0),
            Macros(150.0, 10.0, null, null, null), // quick add
        )
        val t = DayTotals.of(entries)
        assertEquals(450.0, t.kcal, 1e-9)
        assertEquals(30.0, t.proteinG, 1e-9)
        assertEquals(30.0, t.carbsG, 1e-9)
        assertEquals(10.0, t.fatG, 1e-9)
        assertEquals(150.0, t.unsplitKcal, 1e-9)
        assertEquals(2, t.entries)

        assertEquals(Remaining(-50, 95), Remaining.of(DayTotals.of(listOf(Macros(2140.4, 30.0))), 2090, 125))
        assertNull(Remaining.of(t, 2090, null).proteinLeftG)
    }

    @Test
    fun `search matches every word as a prefix and ignores punctuation`() {
        assertEquals("paneer* tik*", FoodQuery.match("Paneer  tik"))
        assertEquals("dal* makhani*", FoodQuery.match("dal-makhani\""))
        assertEquals("aloo* 2*", FoodQuery.match("aloo (2)"))
        assertNull(FoodQuery.match("  *  "))
        assertNull(FoodQuery.match(""))
    }

    @Test
    fun `a day shows active meals plus removed ones that have entries`() {
        val slots = MealSlots.DEFAULT.map { if (it.key == "snacks") it.copy(removed = true) else it } + MealSlot("meal-1", "Pre-workout")
        assertEquals(listOf("breakfast", "lunch", "dinner", "meal-1"), MealSlots.forDay(slots, emptySet()).map { it.key })
        assertEquals(listOf("breakfast", "lunch", "snacks", "dinner", "meal-1", "supper"), MealSlots.forDay(slots, setOf("snacks", "supper")).map { it.key })
        assertEquals("meal-2", MealSlots.newKey(slots))
    }
}
