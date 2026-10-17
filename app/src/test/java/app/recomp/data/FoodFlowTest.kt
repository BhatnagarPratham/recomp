package app.recomp.data

import app.recomp.data.db.FoodEntity
import app.recomp.data.food.FoodFile
import app.recomp.data.food.FoodFileEntry
import app.recomp.data.food.FoodFileUnit
import app.recomp.data.repo.CustomFood
import app.recomp.data.repo.OnboardingResult
import app.recomp.domain.food.FoodCaveat
import app.recomp.domain.food.FoodDiet
import app.recomp.domain.food.FoodSource
import app.recomp.domain.food.Macros
import app.recomp.domain.food.MealSlot
import app.recomp.domain.food.MealSlots
import app.recomp.domain.food.Origin
import app.recomp.domain.food.Per100g
import app.recomp.domain.food.PortionUnit
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.Intake
import app.recomp.domain.nutrition.MainNumber
import app.recomp.domain.nutrition.Pace
import app.recomp.domain.nutrition.RampState
import app.recomp.domain.nutrition.Sex
import app.recomp.domain.persona.Diet
import app.recomp.domain.persona.FinishLine
import app.recomp.domain.persona.FoodRegion
import app.recomp.domain.persona.GoalPlan
import app.recomp.domain.persona.Presets
import app.recomp.domain.persona.Profile
import app.recomp.ui.food.FoodText
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Food data and logging (NU-10 to NU-16, TRD §6.4): the import, the diet and region filters, search
 * ranking, portions, the day view, saved meals, copy and Today's intake. INDB is a synthetic stand-in.
 */
@RunWith(RobolectricTestRunner::class)
class FoodFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: TestApp
    private val foods get() = app.foods
    private val log get() = app.foodLog
    private val dao get() = app.db.foodDao()

    @Before
    fun setUp() {
        app = TestApp(File(tmp.root, "app").apply { mkdirs() })
    }

    @After
    fun tearDown() = app.close()

    private fun entry(
        key: String,
        name: String,
        diet: FoodDiet,
        kcal: Double = 120.0,
        protein: Double = 5.0,
        units: List<FoodFileUnit> = emptyList(),
        caveat: FoodCaveat? = null,
    ) = FoodFileEntry(key, name, diet, FoodRegion.INDIAN, kcal, protein, carbs = 15.0, fat = 4.0, fiber = 2.0, units = units, caveat = caveat)

    private val curries = listOf(
        entry("indb:T1", "Dal curry", FoodDiet.VEGAN, units = listOf(FoodFileUnit("bowl", 250.0))),
        entry("indb:T2", "Paneer curry", FoodDiet.VEGETARIAN),
        entry("indb:T3", "Egg curry", FoodDiet.EGG),
        entry("indb:T4", "Fish curry", FoodDiet.FISH),
        entry("indb:T5", "Chicken curry", FoodDiet.MEAT),
    )

    private suspend fun seed(vararg indb: FoodFileEntry, version: String = "indb-test") {
        app.foodFiles.indbText = Json.encodeToString(FoodFile(version, indb.toList()))
        app.foodSeeder.ensureSeeded()
    }

    private suspend fun persona(diet: Diet, region: FoodRegion = FoodRegion.INDIAN) = app.prefs.updatePersona { it.copy(diet = diet, region = region) }

    private suspend fun named(name: String): FoodEntity =
        (dao.bySource(FoodSource.SEED.name) + dao.bySource(FoodSource.INDB.name) + dao.bySource(FoodSource.CUSTOM.name)).first { it.name == name }

    private suspend fun names(query: String) = foods.search(query).map { it.food.name }

    private suspend fun today() = app.days.today()

    /** The search row's subtitle, as the add-food screen shows it. */
    private suspend fun searchSubtitle(query: String): String = foods.search(query).first().let { FoodText.serving(it.food, it.serving) }

    private val g = PortionUnit("g", 1.0, PortionUnit.Kind.GRAMS)

    private suspend fun onboard(diet: Diet = Diet.LACTO_VEGETARIAN) {
        val profile = Profile(Sex.MALE, today().minusYears(30), heightCm = 175.0, weightKg = 95.0)
        val plan = GoalPlan.of(Goal.RECOMP, Pace.STANDARD, 78.0, FinishLine.GoalWeight, today())
        app.personas.completeOnboarding(
            OnboardingResult(Presets.REFERENCE.draft.copy(goal = plan, diet = diet), Presets.REFERENCE.key, emptyList(), profile, LocalTime.of(4, 0)),
        )
    }

    @Test
    fun `the import loads the basics and INDB, and re-imports keep what you changed`() = runBlocking {
        persona(Diet.NON_VEGETARIAN)
        seed(*curries.toTypedArray())
        assertEquals(5, dao.bySource(FoodSource.INDB.name).size)
        assertEquals(FoodFile.parse(app.foodFiles.seeds()).foods.size, dao.bySource(FoodSource.SEED.name).size)
        assertEquals("seed-1+indb-test", app.prefs.foodDataVersion())

        val dal = named("Dal curry")
        log.log(today(), "lunch", dal, 1.0, foods.units(dal.id).first { it.label == "bowl" }, Origin.HOME)
        foods.calibrate(dal.id, "bowl", 300.0)
        foods.hide(named("Paneer curry").id)

        // A new INDB version: dal's numbers and bowl change, egg curry is gone, a dish is new.
        val changed = curries.map { if (it.key == "indb:T1") it.copy(kcal = 130.0, units = listOf(FoodFileUnit("bowl", 260.0), FoodFileUnit("katori", 140.0))) else it }
            .filter { it.key != "indb:T3" } + entry("indb:T6", "Rajma curry", FoodDiet.VEGAN)
        seed(*changed.toTypedArray(), version = "indb-test-2")

        val after = dao.get(dal.id)!!
        assertEquals(130.0, after.kcal100g, 0.0)
        assertEquals(1, after.useCount)
        assertEquals(mapOf("bowl" to 300.0, "katori" to 140.0), dao.units(dal.id).associate { it.label to it.grams })
        assertTrue(named("Paneer curry").archived)
        assertTrue("dropped from the file, so archived", named("Egg curry").archived)
        assertFalse(named("Rajma curry").archived)
        // The logged entry still points at its food and kept its snapshot.
        assertEquals(120.0 * 2.5, log.day(today()).single().kcal, 1e-6)

        // A build without INDB leaves the INDB rows alone.
        app.foodFiles.indbText = null
        app.foodSeeder.ensureSeeded()
        assertEquals("seed-1", app.prefs.foodDataVersion())
        assertFalse(named("Rajma curry").archived)
    }

    @Test
    fun `the vegetarian-only filter holds over the imported database`() = runBlocking {
        seed(*curries.toTypedArray())

        persona(Diet.LACTO_VEGETARIAN)
        assertEquals(listOf("Dal curry", "Paneer curry"), names("curry"))
        val lacto = foods.suggestions()
        assertFalse(lacto.recent)
        assertTrue(lacto.hits.isNotEmpty())
        assertTrue(lacto.hits.all { it.food.diet in setOf(FoodDiet.VEGAN, FoodDiet.VEGETARIAN) })
        assertTrue(names("chicken").isEmpty())
        assertTrue(names("egg").isEmpty())

        persona(Diet.VEGAN)
        assertEquals(listOf("Dal curry"), names("curry"))
        assertTrue(foods.suggestions().hits.all { it.food.diet == FoodDiet.VEGAN })
        persona(Diet.EGGETARIAN)
        assertEquals(listOf("Dal curry", "Egg curry", "Paneer curry"), names("curry"))
        persona(Diet.PESCATARIAN)
        assertEquals(listOf("Dal curry", "Egg curry", "Fish curry", "Paneer curry"), names("curry"))
        persona(Diet.NON_VEGETARIAN)
        assertEquals(5, names("curry").size)

        // Food logged under one diet never leaks into another's recent list.
        val egg = named("Egg curry")
        log.log(today(), "dinner", egg, 100.0, g, Origin.HOME)
        persona(Diet.LACTO_VEGETARIAN)
        val recent = foods.suggestions()
        assertFalse("nothing this diet allows was logged", recent.recent)
        assertTrue(recent.hits.none { it.food.id == egg.id })
    }

    @Test
    fun `INDB shows for the Indian region only, the basics and your foods for both`() = runBlocking {
        seed(*curries.toTypedArray())
        persona(Diet.LACTO_VEGETARIAN)
        val mine = foods.createCustom(CustomFood("Office canteen dal", null, Per100g(110.0, 6.0, 15.0, 3.0), FoodDiet.VEGAN, null))
        assertEquals(listOf("Dal curry", "Office canteen dal").sorted(), names("dal").sorted())

        persona(Diet.LACTO_VEGETARIAN, FoodRegion.INTERNATIONAL)
        assertEquals(listOf("Office canteen dal"), names("dal"))
        assertEquals(listOf("Paneer", "Paneer, low-fat"), names("paneer"))
        assertEquals(mine, foods.search("canteen").single().food.id)
    }

    @Test
    fun `search ranks logged foods first, then custom, basics and INDB, with caveat foods last`() = runBlocking {
        persona(Diet.LACTO_VEGETARIAN)
        seed(
            entry("indb:P1", "Paneer tikka", FoodDiet.VEGETARIAN),
            entry("indb:P2", "Paneer bhurji", FoodDiet.VEGETARIAN),
            entry("indb:P3", "Paneer pakora", FoodDiet.VEGETARIAN, caveat = FoodCaveat.FRYING_OIL),
            entry("indb:P4", "Kadai paneer", FoodDiet.VEGETARIAN),
        )
        foods.createCustom(CustomFood("Paneer wrap (canteen)", null, Per100g(250.0, 12.0, 25.0, 11.0), FoodDiet.VEGETARIAN, null))

        assertEquals(
            listOf("Paneer wrap (canteen)", "Paneer", "Paneer, low-fat", "Paneer tikka", "Paneer bhurji", "Kadai paneer", "Paneer pakora"),
            names("paneer"),
        )
        // Every word is a prefix, anywhere in the name.
        assertEquals(listOf("Paneer tikka"), names("pan tik"))
        assertEquals(listOf("Paneer tikka"), names("TIKKA"))
        // A name that starts with the query beats one that only contains it.
        assertEquals(listOf("Curd (dahi), whole milk", "Hung curd"), names("curd"))

        val bhurji = named("Paneer bhurji")
        log.log(today(), "lunch", bhurji, 100.0, g, Origin.HOME, now = 1_000)
        log.log(today(), "lunch", bhurji, 100.0, g, Origin.HOME, now = 2_000)
        val pakora = named("Paneer pakora")
        log.log(today(), "snacks", pakora, 100.0, g, Origin.ORDERED, now = 3_000)
        assertEquals(listOf("Paneer bhurji", "Paneer pakora", "Paneer wrap (canteen)", "Paneer", "Paneer, low-fat", "Paneer tikka", "Kadai paneer"), names("paneer"))
        assertEquals(listOf("Paneer pakora", "Paneer bhurji"), foods.suggestions().hits.map { it.food.name })
    }

    @Test
    fun `hiding a food takes it out of search until it's restored`() = runBlocking {
        persona(Diet.LACTO_VEGETARIAN)
        seed(*curries.toTypedArray())
        val dal = named("Dal curry")
        foods.hide(dal.id)
        assertEquals(listOf("Paneer curry"), names("curry"))
        assertEquals(listOf("Dal curry"), foods.observeHidden().first().map { it.name })
        foods.restore(dal.id)
        assertEquals(listOf("Dal curry", "Paneer curry"), names("curry"))
    }

    @Test
    fun `the portion sheet prefills from last time, else a serving, else 100 g`() = runBlocking {
        persona(Diet.LACTO_VEGETARIAN)
        seed()
        val paneer = named("Paneer")
        val first = foods.portion(paneer.id)!!
        assertEquals(1.0, first.quantity, 0.0)
        assertEquals("piece", first.unit.label)
        assertEquals(Origin.HOME, first.origin)

        val curd = named("Curd (dahi), whole milk")
        val curdChoice = foods.portion(curd.id)!!
        assertEquals(listOf("katori", "glass", "tbsp", "g"), curdChoice.units.map { it.label })
        assertEquals(100.0, curdChoice.quantity, 0.0)
        assertEquals("g", curdChoice.unit.label)

        val katori = curdChoice.units.first { it.label == "katori" }
        log.log(today(), "lunch", curd, 1.5, katori, Origin.ORDERED)
        val again = foods.portion(curd.id)!!
        assertEquals(1.5, again.quantity, 0.0)
        assertEquals("katori", again.unit.label)
        assertEquals(Origin.ORDERED, again.origin)
        assertEquals(225.0, log.day(today()).single().grams!!, 1e-9)

        // Household calibration applies to every food; a per-food calibration beats it.
        foods.setHouseholdMeasure("katori", 180.0)
        assertEquals(180.0, foods.units(curd.id).first { it.label == "katori" }.grams, 0.0)
        foods.calibrate(curd.id, "katori", 200.0)
        val own = foods.units(curd.id).first { it.label == "katori" }
        assertEquals(200.0, own.grams, 0.0)
        assertEquals(PortionUnit.Kind.OWN, own.kind)
        foods.setHouseholdMeasure("katori", null)
        assertEquals(150.0, foods.units(paneer.id).first { it.label == "katori" }.grams, 0.0)

        val bar = foods.createCustom(CustomFood("Protein bar", "Some brand", Per100g.fromServing(60.0, Macros(210.0, 20.0, 18.0, 7.0, 3.0)), FoodDiet.VEGETARIAN, "bar" to 60.0))
        val barChoice = foods.portion(bar)!!
        assertEquals(Origin.PACKAGED, barChoice.origin)
        assertEquals("bar", barChoice.unit.label)
        assertEquals(210.0, barChoice.food.per100g.forGrams(60.0).kcal, 1e-9)
    }

    @Test
    fun `a database food whose unit is too big to be a serving prefills 100 g`() = runBlocking {
        persona(Diet.LACTO_VEGETARIAN)
        seed(entry("indb:B1", "Test pot curry", FoodDiet.VEGAN, units = listOf(FoodFileUnit("bowl", 700.0))))
        val choice = foods.portion(named("Test pot curry").id)!!
        assertEquals("g", choice.unit.label)
        assertEquals(100.0, choice.quantity, 0.0)
        assertEquals("bowl", choice.units.first().label)
    }

    @Test
    fun `the day view totals each meal and edits scale an entry's own snapshot`() = runBlocking {
        onboard()
        seed()
        val today = today()
        val shake = foods.createCustom(CustomFood("Shake", null, Per100g(400.0, 80.0, 8.0, 6.0), FoodDiet.VEGETARIAN, "scoop" to 30.0))
        val scoop = foods.units(shake).first { it.label == "scoop" }
        val id = log.log(today, "breakfast", foods.food(shake)!!, 1.0, scoop, Origin.HOME)
        log.quickAdd(today, "snacks", null, kcal = 300.0, proteinG = 10.0, carbsG = null, fatG = null, origin = Origin.PACKAGED)

        val day = log.observeDay(today).first()
        assertEquals(listOf("breakfast", "lunch", "snacks", "dinner"), day.meals.map { it.slot.key })
        assertEquals(120.0, day.meals[0].totals.kcal, 1e-9)
        assertEquals(300.0, day.meals[2].totals.kcal, 1e-9)
        assertEquals(420.0, day.totals.kcal, 1e-9)
        assertEquals(34.0, day.totals.proteinG, 1e-9)
        assertEquals(300.0, day.totals.unsplitKcal, 1e-9)
        assertEquals("Quick add", day.meals[2].entries.single().name)
        assertEquals(2090 - 420, day.remaining!!.kcalLeft)

        // Editing the food never rewrites the entry; editing the entry scales its own numbers.
        foods.updateCustom(shake, CustomFood("Shake", null, Per100g(500.0, 90.0, 8.0, 6.0), FoodDiet.VEGETARIAN, null))
        assertEquals(120.0, log.entry(id)!!.kcal, 1e-9)
        log.update(id, 2.0, scoop, Origin.HOME, "lunch")
        val edited = log.entry(id)!!
        assertEquals(240.0, edited.kcal, 1e-9)
        assertEquals(60.0, edited.grams!!, 1e-9)
        assertEquals("lunch", edited.mealSlot)

        val removed = log.delete(listOf(id))
        assertEquals(300.0, log.observeDay(today).first().totals.kcal, 1e-9)
        log.restore(removed)
        assertEquals(540.0, log.observeDay(today).first().totals.kcal, 1e-9)
    }

    @Test
    fun `editing an entry after its unit is recalibrated keeps the logged weight`() = runBlocking {
        persona(Diet.LACTO_VEGETARIAN)
        seed()
        val today = today()
        val curd = named("Curd (dahi), whole milk")
        val id = log.log(today, "lunch", curd, 1.0, foods.units(curd.id).first { it.label == "katori" }, Origin.HOME)
        val before = log.entry(id)!!
        assertEquals(150.0, before.grams!!, 0.0)

        foods.setHouseholdMeasure("katori", 200.0)
        val units = foods.unitsForEntry(before)
        assertEquals("katori", units.first().label)
        assertEquals(150.0, units.first().grams, 0.0)
        assertEquals(1, units.count { it.label == "katori" })

        // Only moving the entry leaves its amount and macros alone.
        log.update(id, 1.0, units.first(), Origin.HOME, "dinner")
        val moved = log.entry(id)!!
        assertEquals("dinner", moved.mealSlot)
        assertEquals(150.0, moved.grams!!, 0.0)
        assertEquals(before.kcal, moved.kcal, 1e-9)

        // A gram entry offers grams last, like every other food.
        val gid = log.log(today, "lunch", curd, 120.0, g, Origin.HOME)
        assertEquals("g", foods.unitsForEntry(log.entry(gid)!!).last().label)
    }

    @Test
    fun `changing a custom food's serving replaces the old one, and clearing it removes it`() = runBlocking {
        persona(Diet.LACTO_VEGETARIAN)
        seed()
        fun bar(serving: Pair<String, Double>?) = CustomFood("Test bar", null, Per100g(400.0, 30.0, 40.0, 15.0), FoodDiet.VEGETARIAN, serving)
        val id = foods.createCustom(bar("piece" to 30.0))
        foods.calibrate(id, "glass", 300.0)
        assertEquals(listOf("piece", "glass"), dao.units(id).map { it.label })

        foods.updateCustom(id, bar("katori" to 120.0))
        assertEquals(listOf("katori" to 120.0, "glass" to 300.0), dao.units(id).map { it.label to it.grams })
        assertEquals("katori", foods.serving(id)!!.label)
        val choice = foods.portion(id)!!
        assertEquals("katori", choice.unit.label)
        assertEquals(listOf("katori", "glass", "tbsp", "g"), choice.units.map { it.label })
        assertEquals("1 katori (120 g) · 480 kcal · 36 g protein", searchSubtitle("Test bar"))

        foods.updateCustom(id, bar(null))
        assertEquals(listOf("glass"), dao.units(id).map { it.label })
    }

    @Test
    fun `a renamed meal keeps its entries and a removed one still shows where it has them`() = runBlocking {
        seed()
        persona(Diet.LACTO_VEGETARIAN)
        val today = today()
        log.quickAdd(today, "snacks", "Biscuits", 150.0, 2.0, null, null, Origin.PACKAGED)
        app.prefs.edit { mealSlots = MealSlots.DEFAULT.map { if (it.key == "snacks") it.copy(name = "Evening snack") else it } + MealSlot("meal-1", "Pre-workout") }
        assertEquals(listOf("Breakfast", "Lunch", "Evening snack", "Dinner", "Pre-workout"), log.observeDay(today).first().meals.map { it.slot.name })

        app.prefs.edit { mealSlots = mealSlots.map { if (it.key == "snacks") it.copy(removed = true) else it } }
        val day = log.observeDay(today).first()
        assertEquals("Evening snack", day.meals.first { it.slot.key == "snacks" }.slot.name)
        assertEquals(listOf("breakfast", "lunch", "dinner", "meal-1"), log.observeDay(today.minusDays(1)).first().meals.map { it.slot.key })
    }

    @Test
    fun `a saved meal logs in one tap and follows edits to its foods`() = runBlocking {
        persona(Diet.LACTO_VEGETARIAN)
        seed()
        val today = today()
        val shake = foods.createCustom(CustomFood("Shake", null, Per100g(400.0, 80.0, 8.0, 6.0), FoodDiet.VEGETARIAN, "scoop" to 30.0))
        val paneer = named("Paneer")
        log.log(today, "breakfast", foods.food(shake)!!, 1.0, foods.units(shake).first { it.label == "scoop" }, Origin.HOME)
        log.log(today, "breakfast", paneer, 2.0, foods.units(paneer.id).first { it.label == "piece" }, Origin.HOME)
        log.quickAdd(today, "breakfast", "Chai", 80.0, 3.0, null, null, Origin.HOME)

        val mealId = log.saveMeal("My usual breakfast", today, "breakfast")
        foods.updateCustom(shake, CustomFood("Shake", null, Per100g(500.0, 90.0, 8.0, 6.0), FoodDiet.VEGETARIAN, null))

        val tomorrow = today.plusDays(1)
        val ids = log.logSavedMeal(mealId, tomorrow, "lunch")
        val rows = log.day(tomorrow)
        assertEquals(3, ids.size)
        assertEquals(listOf("Shake", "Paneer", "Chai"), rows.map { it.name })
        assertTrue(rows.all { it.mealSlot == "lunch" })
        assertEquals(150.0, rows[0].kcal, 1e-9) // 30 g at the shake's new 500 kcal per 100 g
        assertEquals(140.0, rows[1].kcal, 1e-9)
        assertEquals(80.0, rows[2].kcal, 1e-9) // the quick add's snapshot
        assertEquals(1, log.observeSavedMeals().first().single().meal.useCount)

        log.undoInsert(ids)
        assertTrue(log.day(tomorrow).isEmpty())

        // Saving under the same name saves over it.
        log.quickAdd(today, "dinner", "Khichdi", 400.0, 12.0, null, null, Origin.HOME)
        assertEquals(mealId, log.saveMeal("my usual BREAKFAST", today, "dinner"))
        val saved = log.observeSavedMeals().first().single()
        assertEquals(listOf("Khichdi"), saved.items.map { it.name })
        assertEquals("dinner", saved.meal.defaultSlot)
    }

    @Test
    fun `usage counts every portion, and Undo takes it back`() = runBlocking {
        persona(Diet.LACTO_VEGETARIAN)
        seed()
        val today = today()
        val paneer = named("Paneer")
        val piece = foods.units(paneer.id).first { it.label == "piece" }
        log.log(today, "breakfast", paneer, 2.0, piece, Origin.HOME, now = 1_000)
        log.log(today, "breakfast", paneer, 1.0, piece, Origin.HOME, now = 2_000)
        assertEquals(2, dao.get(paneer.id)!!.useCount)

        // A saved meal holding paneer twice counts twice.
        val mealId = log.saveMeal("Paneer twice", today, "breakfast")
        val ids = log.logSavedMeal(mealId, today, "dinner", now = 5_000)
        assertEquals(4, dao.get(paneer.id)!!.useCount)
        assertEquals(5_000L, dao.get(paneer.id)!!.lastUsedAt)
        assertEquals(1, log.observeSavedMeals().first().single().meal.useCount)

        log.undoSavedMeal(mealId, ids)
        val back = dao.get(paneer.id)!!
        assertEquals(2, back.useCount)
        assertEquals("last used falls back to the latest remaining entry", 2_000L, back.lastUsedAt)
        assertEquals(0, log.observeSavedMeals().first().single().meal.useCount)

        // An undone single log leaves a food as if it was never logged.
        val curd = named("Curd (dahi), whole milk")
        val id = log.log(today, "lunch", curd, 100.0, g, Origin.HOME)
        log.undoInsert(listOf(id))
        assertEquals(0, dao.get(curd.id)!!.useCount)
        assertNull(dao.get(curd.id)!!.lastUsedAt)
        assertEquals(listOf("Paneer"), foods.suggestions().hits.map { it.food.name })
    }

    @Test
    fun `copying a day or a meal adds beside what's there`() = runBlocking {
        persona(Diet.LACTO_VEGETARIAN)
        seed()
        val today = today()
        val yesterday = today.minusDays(1)
        log.quickAdd(yesterday, "breakfast", "Poha", 250.0, 5.0, null, null, Origin.HOME)
        log.quickAdd(yesterday, "lunch", "Thali", 700.0, 20.0, 90.0, 25.0, Origin.ORDERED)
        log.quickAdd(today, "breakfast", "Idli", 200.0, 6.0, null, null, Origin.HOME)

        val days = log.daysBefore(today)
        assertEquals(listOf(yesterday), days.map { it.logDate })
        assertEquals(2, days.single().entries)
        assertEquals(950.0, days.single().kcal, 1e-9)

        val ids = log.copy(yesterday, null, today, null)
        val copied = log.day(today)
        assertEquals(listOf("Idli", "Poha", "Thali"), copied.map { it.name })
        assertEquals(listOf("breakfast", "breakfast", "lunch"), copied.map { it.mealSlot })
        assertEquals(Origin.ORDERED, copied.last().origin)
        assertEquals(90.0, copied.last().carbsG!!, 0.0)
        log.undoInsert(ids)
        assertEquals(listOf("Idli"), log.day(today).map { it.name })

        log.copy(yesterday, "lunch", today, "dinner")
        assertEquals(listOf("Idli", "Thali"), log.day(today).map { it.name })
        assertEquals("dinner", log.day(today).last().mealSlot)
        assertEquals(2, log.day(yesterday).size)
    }

    @Test
    fun `Today counts logged food in the main number and the 7-day average`() = runBlocking {
        onboard()
        seed()
        app.personas.setProteinRamp(false)
        val today = today()
        log.quickAdd(today.minusDays(2), "lunch", null, 1800.0, 100.0, null, null, Origin.HOME)
        log.quickAdd(today.minusDays(1), "lunch", null, 2200.0, 120.0, null, null, Origin.HOME)
        log.quickAdd(today, "breakfast", null, 500.0, 40.0, null, null, Origin.HOME)
        log.quickAdd(today, "lunch", null, 600.0, 35.5, null, null, Origin.HOME)

        val n = app.nutrition.observeToday().first()!!
        assertEquals(Intake(1100, 76), n.eaten)
        assertEquals(MainNumber.ProteinLeft(125 - 76, 125, 76), n.main)
        val week = n.week!!
        assertEquals(2, week.daysLogged)
        assertEquals(2000, week.kcal)
        assertEquals(110, week.proteinG)
        assertEquals(2090, week.kcalTarget)
    }

    @Test
    fun `the protein ramp locks its baseline from logged food`() = runBlocking {
        onboard()
        seed()
        val today = today()
        val start = today.minusDays(10)
        app.prefs.edit { setRamp(RampState(start)) }
        // 5 logged days in the 7 from the first one: the baseline week is complete.
        listOf(0L, 1, 2, 4, 6).forEach { log.quickAdd(start.plusDays(it), "lunch", null, 1800.0, 60.0, null, null, Origin.HOME) }
        app.nutrition.lockRampBaseline()
        val ramp = app.prefs.ramp.first()!!
        assertEquals(start.plusDays(6), ramp.baselineEnd)
        assertEquals(60, ramp.baselineMeanG)
        // The day view's protein target now follows the ramp.
        assertNotNull(log.observeDay(today).first().targets!!.protein.todayG)
    }
}
