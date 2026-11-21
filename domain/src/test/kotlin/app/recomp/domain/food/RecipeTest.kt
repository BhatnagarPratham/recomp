package app.recomp.domain.food

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NU-22: a recipe's values, its diet tag and the no-recipe-inside-itself rule. */
class RecipeTest {
    private val rice = RecipeIngredient(Per100g(360.0, 7.0, 79.0, 0.5, 1.0), FoodDiet.VEGAN, 200.0)
    private val dal = RecipeIngredient(Per100g(340.0, 24.0, 60.0, 1.0, 10.0), FoodDiet.VEGAN, 100.0)
    private val ghee = RecipeIngredient(Per100g(900.0, 0.0, 0.0, 100.0, 0.0), FoodDiet.VEGETARIAN, 10.0)
    private val egg = RecipeIngredient(Per100g(143.0, 12.6, 0.7, 9.5, 0.0), FoodDiet.EGG, 50.0)

    @Test
    fun `with a cooked weight, the total is spread over what the pot weighs`() {
        // 720 + 340 + 90 = 1,150 kcal and 14 + 24 + 0 = 38 g protein, in a pot that weighs 920 g cooked.
        val v = Recipes.values(listOf(rice, dal, ghee), cookedWeightG = 920.0, servings = 4.0)!!
        assertEquals(1150.0, v.total.kcal, 1e-9)
        assertEquals(38.0, v.total.proteinG, 1e-9)
        assertEquals(310.0, v.rawWeightG, 1e-9)
        assertEquals(920.0, v.weightG, 1e-9)
        assertTrue(v.cooked)
        assertEquals(125.0, v.per100g.kcal, 1e-9)
        assertEquals(38.0 / 9.2, v.per100g.proteinG, 1e-9)
        assertEquals(218.0 / 9.2, v.per100g.carbsG, 1e-9)
        assertEquals(12.0 / 9.2, v.per100g.fatG, 1e-9)
        assertEquals(12.0 / 9.2, v.per100g.fiberG, 1e-9)
        // A serving is the cooked weight ÷ servings, and four servings are the whole pot.
        assertEquals(230.0, v.servingG!!, 1e-9)
        assertEquals(287.5, v.perServing!!.kcal, 1e-9)
        assertEquals(9.5, v.perServing!!.proteinG, 1e-9)
    }

    @Test
    fun `without a cooked weight, the dish weighs what its ingredients do`() {
        val v = Recipes.values(listOf(rice, dal, ghee), cookedWeightG = null, servings = 2.0)!!
        assertFalse(v.cooked)
        assertEquals(310.0, v.weightG, 1e-9)
        assertEquals(1150.0 / 3.1, v.per100g.kcal, 1e-9)
        assertEquals(155.0, v.servingG!!, 1e-9)
        // A serving's values don't depend on the pot's weight: half the pot either way.
        assertEquals(575.0, v.perServing!!.kcal, 1e-9)
        assertEquals(575.0, Recipes.values(listOf(rice, dal, ghee), 920.0, 2.0)!!.perServing!!.kcal, 1e-9)
    }

    @Test
    fun `a cooked weight alone gives no serving unit`() {
        val v = Recipes.values(listOf(rice), cookedWeightG = 600.0, servings = null)!!
        assertNull(v.servingG)
        assertNull(v.perServing)
        assertEquals(120.0, v.per100g.kcal, 1e-9)
    }

    @Test
    fun `the diet tag is the strictest ingredient's`() {
        assertEquals(FoodDiet.VEGAN, Recipes.diet(listOf(rice, dal)))
        assertEquals(FoodDiet.VEGETARIAN, Recipes.diet(listOf(rice, ghee, dal)))
        assertEquals(FoodDiet.EGG, Recipes.diet(listOf(egg, rice, ghee)))
        assertEquals(FoodDiet.MEAT, Recipes.diet(listOf(rice, egg.copy(diet = FoodDiet.FISH), egg.copy(diet = FoodDiet.MEAT))))
        assertEquals(FoodDiet.EGG, Recipes.values(listOf(rice, egg), null, 1.0)!!.diet)
    }

    @Test
    fun `amounts that can't be right are named, and give no values`() {
        assertEquals(RecipeProblem.NO_INGREDIENTS, Recipes.problem(emptyList(), 500.0, 2.0))
        assertEquals(RecipeProblem.BAD_AMOUNT, Recipes.problem(listOf(rice.copy(grams = 0.0)), 500.0, 2.0))
        assertEquals(RecipeProblem.BAD_AMOUNT, Recipes.problem(listOf(rice.copy(grams = Double.NaN)), 500.0, 2.0))
        assertEquals(RecipeProblem.BAD_COOKED_WEIGHT, Recipes.problem(listOf(rice), 0.0, 2.0))
        assertEquals(RecipeProblem.BAD_COOKED_WEIGHT, Recipes.problem(listOf(rice), 60_000.0, null))
        assertEquals(RecipeProblem.BAD_SERVINGS, Recipes.problem(listOf(rice), 500.0, 0.0))
        assertEquals(RecipeProblem.NO_YIELD, Recipes.problem(listOf(rice), null, null))
        assertNull(Recipes.problem(listOf(rice), null, 1.0))
        assertNull(Recipes.problem(listOf(rice), 500.0, null))
        assertNull(Recipes.values(listOf(rice), null, null))
    }

    @Test
    fun `a recipe can't contain itself, directly or through another recipe`() {
        // 10 = dal rice (rice 1, dal 2); 11 = thali (dal rice 10, curd 3); 12 = feast (thali 11).
        val contents = mapOf(10L to listOf(1L, 2L), 11L to listOf(10L, 3L), 12L to listOf(11L))
        val of = { id: Long -> contents[id].orEmpty() }
        assertTrue(Recipes.containsItself(10, listOf(1, 10), of))
        assertTrue("through one recipe", Recipes.containsItself(10, listOf(1, 11), of))
        assertTrue("through two", Recipes.containsItself(10, listOf(12), of))
        assertFalse(Recipes.containsItself(11, listOf(10, 3), of))
        assertFalse(Recipes.containsItself(12, listOf(10, 11, 1), of))
        assertFalse("a new recipe is in nothing yet", Recipes.containsItself(null, listOf(10, 11, 12), of))
        // Data that already loops (it can't be saved, but a damaged backup could hold it) still ends.
        val loop = mapOf(20L to listOf(21L), 21L to listOf(20L))
        assertFalse(Recipes.containsItself(30, listOf(20), { loop[it].orEmpty() }))

        val containers = mapOf(1L to listOf(10L), 10L to listOf(11L), 11L to listOf(12L))
        assertEquals(setOf(11L, 12L), Recipes.holders(10, { containers[it].orEmpty() }))
        assertEquals(emptySet<Long>(), Recipes.holders(12, { containers[it].orEmpty() }))
    }
}
