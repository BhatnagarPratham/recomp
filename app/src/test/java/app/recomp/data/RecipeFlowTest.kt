package app.recomp.data

import app.recomp.data.db.FoodEntity
import app.recomp.data.repo.CustomFood
import app.recomp.data.repo.RecipeDraft
import app.recomp.data.repo.RecipeSaved
import app.recomp.domain.food.FoodDiet
import app.recomp.domain.food.FoodSource
import app.recomp.domain.food.Origin
import app.recomp.domain.food.Per100g
import app.recomp.domain.food.PortionUnit
import app.recomp.domain.food.RecipeProblem
import app.recomp.domain.persona.Diet
import app.recomp.ui.food.RecipeForm
import app.recomp.ui.food.RecipeText
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Recipes (NU-22, TRD §10 M14): a recipe saved as a food with its serving unit and diet tag, edits that
 * change future logs only, and the refusal of a recipe inside itself. Every food here is invented.
 */
@RunWith(RobolectricTestRunner::class)
class RecipeFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: TestApp
    private val recipes get() = app.recipes
    private val foods get() = app.foods
    private val day = LocalDate.of(2026, 11, 20)

    private var rice = 0L
    private var dal = 0L
    private var ghee = 0L
    private var egg = 0L

    @Before
    fun setUp() = runBlocking {
        app = TestApp(File(tmp.root, "app").apply { mkdirs() })
        rice = foods.createCustom(CustomFood("Test rice", null, Per100g(360.0, 7.0, 79.0, 0.5, 1.0), FoodDiet.VEGAN, null))
        dal = foods.createCustom(CustomFood("Test dal", null, Per100g(340.0, 24.0, 60.0, 1.0, 10.0), FoodDiet.VEGAN, "katori" to 40.0))
        ghee = foods.createCustom(CustomFood("Test ghee", null, Per100g(900.0, 0.0, 0.0, 100.0, 0.0), FoodDiet.VEGETARIAN, null))
        egg = foods.createCustom(CustomFood("Test egg", null, Per100g(143.0, 12.6, 0.7, 9.5, 0.0), FoodDiet.EGG, "piece" to 50.0))
    }

    @After
    fun tearDown() = app.close()

    private suspend fun save(id: Long?, name: String, ingredients: List<Pair<Long, Double>>, cooked: Double?, servings: Double?): Long =
        (recipes.save(id, RecipeDraft(name, ingredients, cooked, servings)) as RecipeSaved.Ok).foodId

    private suspend fun refused(id: Long?, draft: RecipeDraft): RecipeProblem = (recipes.save(id, draft) as RecipeSaved.Refused).problem

    private suspend fun food(id: Long): FoodEntity = foods.food(id)!!

    @Test
    fun `a recipe with a cooked weight is a food per 100 g of the cooked dish, with a serving unit`() = runBlocking {
        // 720 + 340 + 90 = 1,150 kcal in a pot that weighs 920 g cooked, for 4.
        val id = save(null, " Test dal rice ", listOf(rice to 200.0, dal to 100.0, ghee to 10.0), cooked = 920.0, servings = 4.0)
        val f = food(id)
        assertEquals("Test dal rice", f.name)
        assertEquals(FoodSource.RECIPE, f.source)
        assertEquals(125.0, f.kcal100g, 1e-9)
        assertEquals(38.0 / 9.2, f.protein100g, 1e-9)
        assertEquals(218.0 / 9.2, f.carbs100g, 1e-9)
        assertEquals(12.0 / 9.2, f.fat100g, 1e-9)
        assertEquals(12.0 / 9.2, f.fiber100g, 1e-9)
        assertNull("every region sees it", f.region)

        // Its serving comes first, next to the usual measures, and is what the portion sheet prefills.
        val units = foods.units(id)
        assertEquals(listOf("serving", "katori", "glass", "tbsp", "g"), units.map { it.label })
        assertEquals(230.0, units.first().grams, 1e-9)
        val portion = foods.portion(id)!!
        assertEquals(1.0 to "serving", portion.quantity to portion.unit.label)

        // It's in search like any food, ranked with your own, and among your own foods for Describe it.
        val hit = foods.search("dal rice").single()
        assertEquals(id, hit.food.id)
        assertEquals("serving", hit.serving!!.label)
        assertEquals(listOf("Test dal", "Test dal rice"), foods.search("test dal").map { it.food.name })
        val filter = foods.filter.first()
        assertTrue(id in app.db.foodDao().ownFoods(filter.diets, filter.region.name, 50).map { it.id })

        // What the editor reopens with.
        val detail = recipes.recipe(id)!!
        assertEquals(listOf(rice to 200.0, dal to 100.0, ghee to 10.0), detail.lines.map { it.food.id to it.grams })
        assertEquals(920.0 to 4.0, detail.cookedWeightG to detail.servings)
        assertNull("a plain food is no recipe", recipes.recipe(rice))
    }

    @Test
    fun `without a cooked weight the dish weighs what its ingredients do, and a big serving still prefills`() = runBlocking {
        val id = save(null, "Test khichdi", listOf(rice to 400.0, dal to 200.0, ghee to 20.0), cooked = null, servings = 1.0)
        val f = food(id)
        assertEquals(2300.0 / 6.2, f.kcal100g, 1e-9)
        // One 620 g serving: over the 500 g limit that makes a database unit implausible, but it's yours.
        val portion = foods.portion(id)!!
        assertEquals("serving", portion.unit.label)
        assertEquals(620.0, portion.unit.grams, 1e-9)
        assertEquals(2300.0, f.per100g.forGrams(portion.quantity * portion.unit.grams).kcal, 1e-6)

        // A cooked weight with no servings: logged by weight, with no serving unit.
        val byWeight = save(null, "Test plain rice", listOf(rice to 100.0), cooked = 300.0, servings = null)
        assertEquals(120.0, food(byWeight).kcal100g, 1e-9)
        assertFalse("serving" in foods.units(byWeight).map { it.label })
        assertEquals(100.0 to "g", foods.portion(byWeight)!!.let { it.quantity to it.unit.label })
    }

    @Test
    fun `the diet tag is the strictest ingredient's, and follows the ingredients on each save`() = runBlocking {
        val id = save(null, "Test fried rice", listOf(rice to 200.0, ghee to 10.0), null, 2.0)
        assertEquals(FoodDiet.VEGETARIAN, food(id).diet)
        save(id, "Test fried rice", listOf(rice to 200.0, ghee to 10.0, egg to 100.0), null, 2.0)
        assertEquals(FoodDiet.EGG, food(id).diet)
        save(id, "Test fried rice", listOf(rice to 200.0), null, 2.0)
        assertEquals(FoodDiet.VEGAN, food(id).diet)

        // The tag filters it like any food: an egg recipe is hidden from a vegetarian.
        save(id, "Test fried rice", listOf(rice to 200.0, egg to 100.0), null, 2.0)
        assertEquals(1, foods.search("fried rice").size)
        app.prefs.edit { persona = persona.copy(diet = Diet.LACTO_VEGETARIAN) }
        assertEquals(0, foods.search("fried rice").size)

        // A recipe inside a recipe passes its tag up.
        app.prefs.edit { persona = persona.copy(diet = Diet.NON_VEGETARIAN) }
        val outer = save(null, "Test rice bowl", listOf(id to 150.0, dal to 50.0), null, 1.0)
        assertEquals(FoodDiet.EGG, food(outer).diet)
    }

    @Test
    fun `editing a recipe changes future logs only`() = runBlocking {
        val id = save(null, "Test dal rice", listOf(rice to 200.0, dal to 100.0), cooked = 900.0, servings = 3.0)
        val serving = foods.units(id).first()
        val past = app.foodLog.log(day, "lunch", food(id), 1.0, serving, Origin.HOME)
        val meal = app.foodLog.saveMeal("Test lunch", day, "lunch")
        val before = app.foodLog.day(day).single()
        assertEquals(1060.0 / 3, before.kcal, 1e-6)
        assertEquals(300.0, before.grams!!, 1e-9)

        // More ghee, a different cooked weight and fewer servings.
        save(id, "Test dal rice, richer", listOf(rice to 200.0, dal to 100.0, ghee to 30.0), cooked = 880.0, servings = 2.0)
        assertEquals(before, app.foodLog.day(day).single { it.id == past })
        assertEquals("Test dal rice", app.foodLog.day(day).single().name)

        // A new log, and the saved meal logged again, use the recipe as it is now.
        val now = food(id)
        assertEquals(1330.0 / 8.8, now.kcal100g, 1e-9)
        assertEquals(1, now.useCount)
        val units = foods.units(id)
        assertEquals(440.0, units.first { it.label == "serving" }.grams, 1e-9)
        app.foodLog.log(day.plusDays(1), "lunch", now, 1.0, units.first(), Origin.HOME)
        assertEquals(665.0, app.foodLog.day(day.plusDays(1)).single().kcal, 1e-6)
        app.foodLog.logSavedMeal(meal, day.plusDays(2), "lunch")
        val again = app.foodLog.day(day.plusDays(2)).single()
        assertEquals(now.per100g.forGrams(again.grams!!).kcal, again.kcal, 1e-6)
        assertEquals(before, app.foodLog.day(day).single())
    }

    @Test
    fun `a change to an ingredient reaches the recipe when it's next saved`() = runBlocking {
        val id = save(null, "Test dal rice", listOf(rice to 100.0, dal to 100.0), null, 2.0)
        assertEquals(350.0, food(id).kcal100g, 1e-9)
        foods.updateCustom(dal, CustomFood("Test dal", null, Per100g(300.0, 24.0, 60.0, 1.0, 10.0), FoodDiet.VEGAN, "katori" to 40.0))
        assertEquals("fixed when saved", 350.0, food(id).kcal100g, 1e-9)
        // The editor opens with the ingredient as it is now, so saving picks it up.
        val detail = recipes.recipe(id)!!
        assertEquals(300.0, detail.lines[1].food.kcal100g, 1e-9)
        save(id, detail.food.name, detail.lines.map { it.food.id to it.grams }, detail.cookedWeightG, detail.servings)
        assertEquals(330.0, food(id).kcal100g, 1e-9)
    }

    @Test
    fun `a recipe inside itself is refused, directly or through another recipe`() = runBlocking {
        val base = save(null, "Test base", listOf(rice to 100.0), null, 1.0)
        val middle = save(null, "Test middle", listOf(base to 100.0, dal to 50.0), null, 1.0)
        val top = save(null, "Test top", listOf(middle to 100.0), null, 1.0)
        val before = recipes.recipe(base)!!

        assertEquals(RecipeProblem.CONTAINS_ITSELF, refused(base, RecipeDraft("Test base", listOf(rice to 100.0, base to 50.0), null, 1.0)))
        assertEquals(RecipeProblem.CONTAINS_ITSELF, refused(base, RecipeDraft("Test base", listOf(middle to 50.0), null, 1.0)))
        assertEquals(RecipeProblem.CONTAINS_ITSELF, refused(base, RecipeDraft("Test base", listOf(rice to 100.0, top to 50.0), null, 1.0)))
        assertEquals("a refused save writes nothing", before, recipes.recipe(base))

        // The editor never offers them: the recipe itself and everything that holds it.
        assertEquals(setOf(base, middle, top), recipes.blockedIngredients(base))
        assertEquals(setOf(top), recipes.blockedIngredients(top))
        assertEquals(emptySet<Long>(), recipes.blockedIngredients(null))
        // Going the other way is fine: the top recipe can take the base directly too.
        assertEquals(top, save(top, "Test top", listOf(middle to 100.0, base to 20.0), null, 1.0))
    }

    @Test
    fun `a recipe that can't be right is refused with nothing written`() = runBlocking {
        assertEquals(RecipeProblem.NO_NAME, refused(null, RecipeDraft("  ", listOf(rice to 100.0), null, 1.0)))
        assertEquals(RecipeProblem.NO_INGREDIENTS, refused(null, RecipeDraft("Test", emptyList(), null, 1.0)))
        assertEquals(RecipeProblem.NO_INGREDIENTS, refused(null, RecipeDraft("Test", listOf(9_999L to 100.0), null, 1.0)))
        assertEquals(RecipeProblem.BAD_AMOUNT, refused(null, RecipeDraft("Test", listOf(rice to 0.0), null, 1.0)))
        assertEquals(RecipeProblem.NO_YIELD, refused(null, RecipeDraft("Test", listOf(rice to 100.0), null, null)))
        assertEquals(RecipeProblem.BAD_SERVINGS, refused(null, RecipeDraft("Test", listOf(rice to 100.0), null, -1.0)))
        assertEquals(emptyList<FoodEntity>(), app.db.foodDao().bySource(FoodSource.RECIPE.name))
        assertEquals(emptyList<Any>(), app.db.backupCheckDao().recipe())
        // A plain food can't be saved over as a recipe.
        try {
            recipes.save(rice, RecipeDraft("Test", listOf(dal to 100.0), null, 1.0))
            org.junit.Assert.fail("a custom food isn't a recipe")
        } catch (e: IllegalArgumentException) {
            assertEquals(FoodSource.CUSTOM, food(rice).source)
        }
    }

    @Test
    fun `saving again keeps the units you calibrated, and dropping the servings drops the serving`() = runBlocking {
        val id = save(null, "Test dal rice", listOf(rice to 200.0, dal to 100.0), 900.0, 3.0)
        foods.calibrate(id, "katori", 210.0)
        save(id, "Test dal rice", listOf(rice to 200.0, dal to 100.0), 900.0, 4.0)
        var own = foods.units(id).filter { it.kind == PortionUnit.Kind.OWN }
        assertEquals(listOf("serving" to 225.0, "katori" to 210.0), own.map { it.label to it.grams })
        save(id, "Test dal rice", listOf(rice to 200.0, dal to 100.0), 900.0, null)
        own = foods.units(id).filter { it.kind == PortionUnit.Kind.OWN }
        assertEquals(listOf("katori" to 210.0), own.map { it.label to it.grams })
        // Hiding works as for any food, and the recipe stays for the logs that point at it.
        foods.hide(id)
        assertEquals(emptyList<String>(), foods.search("dal rice").map { it.food.name })
        assertEquals(2, recipes.recipe(id)!!.lines.size)
    }

    @Test
    fun `the editor says which weight its numbers assume`() = runBlocking {
        val lines = recipes.recipe(save(null, "Test dal rice", listOf(rice to 200.0, dal to 100.0, ghee to 10.0), null, 4.0))!!.lines
        // As typed: nothing to show before an ingredient, totals as soon as there is one.
        assertNull(RecipeForm().values)
        assertEquals(RecipeProblem.NO_INGREDIENTS, RecipeForm(name = "Test").problem)
        val typing = RecipeForm("Test", lines)
        assertEquals(RecipeProblem.NO_YIELD, typing.problem)
        assertEquals(1150.0, typing.values!!.total.kcal, 1e-9)
        assertNull("no serving until you say how many", typing.values!!.servingG)

        val raw = typing.copy(servings = "4")
        assertNull(raw.problem)
        assertEquals(77.5, raw.values!!.servingG!!, 1e-9)
        val rawNote = RecipeText.weightNote(raw.values!!)
        assertTrue(rawNote, rawNote.startsWith("No cooked weight, so per 100 g assumes the dish weighs what its ingredients do raw (310 g)."))

        val cooked = raw.copy(cooked = "920")
        assertEquals(230.0, cooked.values!!.servingG!!, 1e-9)
        val cookedNote = RecipeText.weightNote(cooked.values!!)
        assertTrue(cookedNote, "raw, as you weighed them (310 g in all)" in cookedNote && "cooked dish, using the 920 g you weighed" in cookedNote)
        assertEquals(RecipeDraft("Test", listOf(rice to 200.0, dal to 100.0, ghee to 10.0), 920.0, 4.0), cooked.draft)

        // Text that isn't a number stops the save and names the field.
        assertEquals(RecipeProblem.BAD_COOKED_WEIGHT, raw.copy(cooked = "lots").problem)
        assertEquals(RecipeProblem.BAD_SERVINGS, typing.copy(servings = "0").problem)
        assertEquals(RecipeProblem.NO_NAME, raw.copy(name = " ").problem)
        assertNull(raw.copy(cooked = "lots").values)
        assertEquals("Vegetarian, from its strictest ingredient.", RecipeText.diet(raw.values!!))
        assertEquals(listOf("920", "2.5", ""), listOf(RecipeText.number(920.0), RecipeText.number(2.5), RecipeText.number(null)))
    }
}
