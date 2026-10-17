package app.recomp.domain.food.indb

import app.recomp.domain.food.FoodDiet
import org.junit.Assert.assertEquals
import org.junit.Test

/** Diet tags from INDB ingredients (TRD §6.4). A wrong tag shows meat to a vegetarian, so every rule is pinned. */
class DietTagsTest {
    private fun code(c: String) = DietTags.ofIngredient(Ingredient(c, "anything"))

    @Test
    fun `IFCT food groups decide coded ingredients`() {
        assertEquals(FoodDiet.VEGAN, code("A018")) // wheat flour
        assertEquals(FoodDiet.VEGAN, code("B001")) // bengal gram
        assertEquals(FoodDiet.VEGAN, code("T508")) // sunflower oil
        assertEquals(FoodDiet.VEGETARIAN, code("L003")) // paneer
        assertEquals(FoodDiet.EGG, code("M001"))
        assertEquals(FoodDiet.MEAT, code("N003")) // chicken
        assertEquals(FoodDiet.MEAT, code("O064")) // mutton
        listOf("P034", "Q007", "R001", "S006").forEach { assertEquals(it, FoodDiet.FISH, code(it)) }
    }

    @Test
    fun `hand-listed codes outside their group`() {
        listOf("T500", "T501", "T506", "I501", "I507", "I509", "V504", "X500", "X506", "X508", "U500", "U509").forEach {
            assertEquals(it, FoodDiet.VEGETARIAN, code(it))
        }
        assertEquals(FoodDiet.EGG, code("L507")) // mayonnaise, in the milk group
        assertEquals(FoodDiet.EGG, code("X509")) // thousand island
        assertEquals(FoodDiet.MEAT, code("K504")) // gelatine
        assertEquals(FoodDiet.MEAT, code("K506")) // chicken stock cubes
        assertEquals(FoodDiet.MEAT, code("W501")) // jelly
        assertEquals(FoodDiet.FISH, code("X507")) // Worcestershire sauce
        assertEquals(FoodDiet.VEGAN, code("K507")) // vegetable stock cubes
    }

    @Test
    fun `uncoded ingredients go by name, and a blank code is not meat`() {
        assertEquals(FoodDiet.VEGAN, DietTags.ofIngredient(Ingredient("", "KEWRA ESSENCE")))
        assertEquals(FoodDiet.VEGAN, DietTags.ofIngredient(Ingredient(null, "Yellow colour")))
        assertEquals(FoodDiet.MEAT, DietTags.ofIngredient(Ingredient("", "LEMON JELLY CRYSTALS")))
        assertEquals(FoodDiet.VEGETARIAN, DietTags.ofIngredient(Ingredient(null, "Fresh cream")))
        assertEquals(FoodDiet.VEGAN, DietTags.ofIngredient(Ingredient(null, "Coconut milk")))
        assertEquals(FoodDiet.VEGAN, DietTags.ofIngredient(Ingredient(null, "Peanut butter")))
        assertEquals(FoodDiet.VEGAN, DietTags.ofIngredient(Ingredient(null, "Cream of tartar")))
        assertEquals(FoodDiet.VEGAN, DietTags.ofIngredient(Ingredient(null, "Egg plant")))
        assertEquals(FoodDiet.EGG, DietTags.ofIngredient(Ingredient(null, "Eggs, beaten")))
        assertEquals(FoodDiet.FISH, DietTags.ofIngredient(Ingredient(null, "Fish sauce")))
        // A sub-recipe or foreign code isn't an IFCT group letter.
        assertEquals(FoodDiet.VEGETARIAN, DietTags.ofIngredient(Ingredient("TST266X", "White sauce with milk")))
    }

    @Test
    fun `the recipe's own wording lifts a coded ingredient to egg, fish or meat, but never adds dairy`() {
        // INDB codes "chicken stock" as vegetable stock, and an egg wash as milk.
        assertEquals(FoodDiet.MEAT, DietTags.ofIngredient(Ingredient("K507", "Stock cubes, vegetable", "Vegetable/chicken stock")))
        assertEquals(FoodDiet.MEAT, DietTags.ofIngredient(Ingredient("K505", "Water, distilled", "Water or meat stock")))
        assertEquals(FoodDiet.EGG, DietTags.ofIngredient(Ingredient("L002", "Milk, whole", "Egg/Milk")))
        assertEquals(FoodDiet.FISH, DietTags.ofIngredient(Ingredient("X501", "Soy sauce", "Fish sauce")))
        assertEquals(FoodDiet.MEAT, DietTags.ofIngredient(Ingredient("W504", "Jam", "Lemon jelly")))
        // Coded vegan foods stay vegan whatever dairy-sounding words the recipe uses.
        assertEquals(FoodDiet.VEGAN, DietTags.ofIngredient(Ingredient("H007", "Coconut, fresh", "Coconut cream")))
        assertEquals(FoodDiet.VEGAN, DietTags.ofIngredient(Ingredient("D031", "Brinjal", "Brinjal (egg plant)")))
        assertEquals(FoodDiet.VEGAN, DietTags.ofIngredient(Ingredient("K507", "Stock cubes, vegetable", "Vegetable stock")))
        // Uncoded rows read both names.
        assertEquals(FoodDiet.VEGETARIAN, DietTags.ofIngredient(Ingredient("", "Essence", "Malai essence")))
    }

    @Test
    fun `a recipe takes its strictest ingredient`() {
        val dal = listOf(Ingredient("B001", "Bengal gram"), Ingredient("T506", "Ghee"))
        assertEquals(FoodDiet.VEGETARIAN, DietTags.ofRecipe("Chana dal", dal))
        assertEquals(FoodDiet.EGG, DietTags.ofRecipe("Cake", dal + Ingredient("M001", "Egg")))
        assertEquals(FoodDiet.MEAT, DietTags.ofRecipe("Biryani", dal + Ingredient("M001", "Egg") + Ingredient("N003", "Chicken")))
        assertEquals(FoodDiet.VEGAN, DietTags.ofRecipe("Plain rice", listOf(Ingredient("A014", "Rice"))))
    }

    @Test
    fun `a meat or fish name lifts a recipe unless it says it's the vegetarian version`() {
        val veg = listOf(Ingredient("D036", "Cauliflower"))
        assertEquals(FoodDiet.MEAT, DietTags.ofRecipe("Chicken stew", veg))
        assertEquals(FoodDiet.FISH, DietTags.ofRecipe("Tomato fish", veg))
        assertEquals(FoodDiet.VEGAN, DietTags.ofRecipe("Soya keema", veg))
        assertEquals(FoodDiet.VEGAN, DietTags.ofRecipe("Vegetarian egg kofta curry", veg))
        assertEquals(FoodDiet.VEGAN, DietTags.ofRecipe("Eggplant rice (Vangi bhat)", veg))
        assertEquals(FoodDiet.VEGAN, DietTags.ofRecipe("Chickpea salad", veg))
        // Fruit jellies are preserves; only a gelatine ingredient makes a dish meat.
        assertEquals(FoodDiet.VEGAN, DietTags.ofRecipe("Guava jelly (Amrood ki jelly)", listOf(Ingredient("", "Citric Acid"))))
        assertEquals(FoodDiet.MEAT, DietTags.ofRecipe("Jelly biscuit", listOf(Ingredient("W501", "Jelly, made with water"))))
        // The name never lowers a tag the ingredients set.
        assertEquals(FoodDiet.MEAT, DietTags.ofRecipe("Vegetarian-style keema", listOf(Ingredient("O502", "Lamb mince"))))
    }
}
