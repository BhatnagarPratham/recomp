package app.recomp.data

import app.recomp.data.db.FoodEntity
import app.recomp.data.food.FoodFile
import app.recomp.data.food.FoodFileEntry
import app.recomp.data.repo.AiItemToLog
import app.recomp.domain.ai.Confidence
import app.recomp.domain.ai.EstimatedItem
import app.recomp.domain.food.FoodCaveat
import app.recomp.domain.persona.FoodRegion
import kotlinx.serialization.json.Json
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
        assertEquals(RecipeProblem.MISSING_INGREDIENT, refused(null, RecipeDraft("Test", listOf(9_999L to 100.0), null, 1.0)))
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

    @Test
    fun `a saved meal's serving of a recipe follows the recipe's latest serving`() = runBlocking {
        // 1,000 g of dal rice for 4: a serving is 250 g.
        val id = save(null, "Test dal rice", listOf(rice to 200.0, dal to 100.0), cooked = 1000.0, servings = 4.0)
        app.foodLog.log(day, "lunch", food(id), 1.0, foods.units(id).first(), Origin.HOME)
        app.foodLog.log(day, "lunch", food(rice), 50.0, foods.units(rice).last(), Origin.HOME)
        val meal = app.foodLog.saveMeal("Test lunch", day, "lunch")

        // Next time the pot weighs 900 g and feeds 5, so a serving is 180 g.
        save(id, "Test dal rice", listOf(rice to 200.0, dal to 100.0), cooked = 900.0, servings = 5.0)
        app.foodLog.logSavedMeal(meal, day.plusDays(1), "lunch")
        val rows = app.foodLog.day(day.plusDays(1))
        val serving = rows.single { it.foodId == id }
        assertEquals(1.0 to "serving", serving.quantity to serving.unitLabel)
        assertEquals(180.0, serving.grams!!, 1e-9)
        assertEquals(1060.0 / 5, serving.kcal, 1e-6)
        // Grams stay grams.
        assertEquals(50.0, rows.single { it.foodId == rice }.grams!!, 1e-9)

        // With no servings any more, the unit is gone, and the meal keeps the weight it was saved with.
        save(id, "Test dal rice", listOf(rice to 200.0, dal to 100.0), cooked = 900.0, servings = null)
        app.foodLog.logSavedMeal(meal, day.plusDays(2), "lunch")
        assertEquals(250.0, app.foodLog.day(day.plusDays(2)).single { it.foodId == id }.grams!!, 1e-9)
        // The day it was first logged is untouched throughout.
        assertEquals(250.0, app.foodLog.day(day).single { it.foodId == id }.grams!!, 1e-9)
    }

    @Test
    fun `a recalibrated unit of any food is what a saved meal logs`() = runBlocking {
        app.foodLog.log(day, "lunch", food(dal), 2.0, foods.units(dal).first(), Origin.HOME)
        val meal = app.foodLog.saveMeal("Test dal", day, "lunch")
        foods.calibrate(dal, "katori", 55.0)
        app.foodLog.logSavedMeal(meal, day.plusDays(1), "lunch")
        assertEquals(110.0, app.foodLog.day(day.plusDays(1)).single().grams!!, 1e-9)
        assertEquals(80.0, app.foodLog.day(day).single().grams!!, 1e-9)
    }

    @Test
    fun `a recipe with a frying-oil ingredient carries the caveat, and loses it when that's taken out`() = runBlocking {
        val poori = app.db.foodDao().insert(
            FoodEntity(name = "Test poori", source = FoodSource.INDB, sourceKey = "indb:TEST1", diet = FoodDiet.VEGAN, kcal100g = 400.0, protein100g = 7.0, carbs100g = 45.0, fat100g = 22.0, fiber100g = 3.0, caveat = FoodCaveat.FRYING_OIL, createdAt = 1),
        )
        val id = save(null, "Test poori bhaji", listOf(poori to 120.0, dal to 100.0), null, 2.0)
        assertEquals(FoodCaveat.FRYING_OIL, food(id).caveat)
        // It passes up through a recipe inside a recipe too.
        val outer = save(null, "Test thali", listOf(id to 200.0, rice to 50.0), null, 1.0)
        assertEquals(FoodCaveat.FRYING_OIL, food(outer).caveat)
        save(id, "Test poori bhaji", listOf(dal to 100.0), null, 2.0)
        assertNull(food(id).caveat)
    }

    @Test
    fun `a recipe's diet tag follows an ingredient whose tag is corrected, through nested recipes`() = runBlocking {
        val mayo = foods.createCustom(CustomFood("Test mayo", null, Per100g(680.0, 1.0, 1.0, 75.0, 0.0), FoodDiet.VEGETARIAN, null))
        val sandwich = save(null, "Test sandwich", listOf(rice to 100.0, mayo to 20.0), null, 1.0)
        val platter = save(null, "Test platter", listOf(sandwich to 200.0, dal to 50.0), null, 2.0)
        val kcal = food(sandwich).kcal100g
        assertEquals(FoodDiet.VEGETARIAN, food(platter).diet)

        // The mayo turns out to have egg: both recipes are tagged egg at once, and their values are unchanged.
        foods.updateCustom(mayo, CustomFood("Test mayo", null, Per100g(700.0, 1.0, 1.0, 77.0, 0.0), FoodDiet.EGG, null))
        assertEquals(FoodDiet.EGG, food(sandwich).diet)
        assertEquals(FoodDiet.EGG, food(platter).diet)
        assertEquals(kcal, food(sandwich).kcal100g, 1e-9)
        app.prefs.edit { persona = persona.copy(diet = Diet.LACTO_VEGETARIAN) }
        assertEquals(emptyList<String>(), foods.search("test sandwich").map { it.food.name })

        // And back, when it's corrected again.
        foods.updateCustom(mayo, CustomFood("Test mayo", null, Per100g(700.0, 1.0, 1.0, 77.0, 0.0), FoodDiet.VEGAN, null))
        assertEquals(FoodDiet.VEGAN, food(platter).diet)
    }

    @Test
    fun `a re-tagged AI-learned food or a re-imported database food re-tags the recipes that hold it`() = runBlocking {
        fun estimate(diet: FoodDiet) = EstimatedItem("Test sauce", 1.0, "tbsp", 15.0, 60.0, 0.5, 2.0, 5.5, 0.0, Confidence.HIGH, "", diet)
        val ids = app.foodLog.logAiMeal(day, "lunch", listOf(AiItemToLog(estimate(FoodDiet.VEGAN), edited = true)), Origin.HOME, requestId = 1, dietTag = FoodDiet.VEGAN, now = 1)
        val sauce = app.foodLog.day(day).single { it.id == ids.single() }.foodId!!
        val bowl = save(null, "Test sauce bowl", listOf(rice to 100.0, sauce to 15.0), null, 1.0)
        assertEquals(FoodDiet.VEGAN, food(bowl).diet)
        app.foodLog.logAiMeal(day, "dinner", listOf(AiItemToLog(estimate(FoodDiet.FISH), edited = true)), Origin.HOME, requestId = 2, dietTag = FoodDiet.VEGAN, now = 2)
        assertEquals(sauce, app.db.foodDao().ownFoodNamed("Test sauce")!!.id)
        assertEquals(FoodDiet.FISH, food(bowl).diet)

        // A database food whose tag changes in a new data file.
        fun file(version: String, diet: FoodDiet) =
            Json.encodeToString(FoodFile(version, listOf(FoodFileEntry("indb:TEST2", "Test stock", diet, FoodRegion.INDIAN, 20.0, 1.0, 2.0, 0.5, 0.0))))
        app.foodFiles.indbText = file("t1", FoodDiet.VEGAN)
        app.foodSeeder.ensureSeeded()
        val stock = foods.search("test stock").single().food.id
        val soup = save(null, "Test soup", listOf(stock to 300.0, dal to 50.0), null, 2.0)
        assertEquals(FoodDiet.VEGAN, food(soup).diet)
        app.foodFiles.indbText = file("t2", FoodDiet.MEAT)
        app.foodSeeder.ensureSeeded()
        assertEquals(FoodDiet.MEAT, food(soup).diet)
    }

    @Test
    fun `amounts reopen exactly as they were saved`() = runBlocking {
        val id = save(null, "Test dal rice", listOf(rice to 82.35294117647059, dal to 100.0), cooked = 912.5, servings = 2.25)
        val d = recipes.recipe(id)!!
        val form = RecipeForm(d.food.name, d.lines, RecipeText.number(d.cookedWeightG), RecipeText.number(d.servings))
        assertEquals("2.25" to "912.5", form.servings to form.cooked)
        // Saving the reopened form untouched stores the same numbers.
        save(id, form.name, form.draft.ingredients, form.draft.cookedWeightG, form.draft.servingCountOrNull())
        val again = recipes.recipe(id)!!
        assertEquals(2.25 to 912.5, again.servings to again.cookedWeightG)
        assertEquals(82.35294117647059, again.lines.first().grams, 0.0)
        assertEquals(listOf("0.333", "1.5", "2.125"), listOf(1.0 / 3, 1.5, 2.125).map(RecipeText::number))
    }

    private fun RecipeDraft.servingCountOrNull() = servings
}
