package app.recomp.data

import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import app.recomp.data.food.PhotoStore
import app.recomp.data.db.FoodEntity
import app.recomp.data.db.FoodLogEntity
import app.recomp.data.db.FoodUnitEntity
import app.recomp.data.repo.CustomFood
import app.recomp.data.repo.DescribeResult
import app.recomp.data.repo.DescribedMeal
import app.recomp.data.repo.ItemFrom
import app.recomp.data.repo.OnboardingResult
import app.recomp.data.repo.UnmatchedPart
import app.recomp.domain.ai.AiKind
import app.recomp.domain.ai.AiState
import app.recomp.domain.ai.AiStatus
import app.recomp.domain.ai.Confidence
import app.recomp.domain.ai.EstimatedItem
import app.recomp.domain.ai.MealEstimate
import app.recomp.domain.food.FoodDiet
import app.recomp.domain.food.FoodSource
import app.recomp.domain.food.LogSource
import app.recomp.domain.food.MealSlot
import app.recomp.domain.food.MealSlots
import app.recomp.domain.food.Origin
import app.recomp.domain.food.Per100g
import app.recomp.domain.food.PortionUnit
import app.recomp.domain.food.SnackAllowance
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.Pace
import app.recomp.domain.nutrition.Sex
import app.recomp.domain.persona.Diet
import app.recomp.domain.persona.FinishLine
import app.recomp.domain.persona.FoodRegion
import app.recomp.domain.persona.GoalPlan
import app.recomp.domain.persona.Presets
import app.recomp.domain.persona.Profile
import app.recomp.ui.food.DescribePhase
import app.recomp.ui.food.DescribeViewModel
import app.recomp.ui.food.FitPhase
import app.recomp.ui.food.FitText
import app.recomp.ui.food.FitViewModel
import app.recomp.ui.food.QuickAddValues
import app.recomp.ui.food.ReviewItem
import app.recomp.ui.food.reviewItem
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
 * M9 end to end over the real database (PRD §12.5): Describe it with AI off (AI-04), the parser going first with
 * AI on, *Will it fit?* (NU-18) and the snack allowance (NU-06). Every food and phrase is invented; the AI is a fake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ParserFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: TestApp
    private val date = LocalDate.of(2026, 10, 18)
    private var roti = 0L
    private var rajma = 0L

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = TestApp(File(tmp.root, "app").apply { mkdirs() })
        runBlocking {
            app.prefs.updatePersona { it.copy(diet = Diet.LACTO_VEGETARIAN, region = FoodRegion.INDIAN) }
            roti = database("Chapati/Roti", FoodDiet.VEGAN, 300.0, "roti" to 40.0)
            database("Dal makhani", FoodDiet.VEGETARIAN, 140.0, "bowl" to 260.0)
            database("Chicken curry", FoodDiet.MEAT, 180.0, "bowl" to 240.0)
            database("Paneer", FoodDiet.VEGETARIAN, 280.0, "piece" to 25.0)
            rajma = app.foods.createCustom(CustomFood("Rajma masala", null, Per100g(120.0, 6.0, 16.0, 3.0, 5.0), FoodDiet.VEGAN, null))
        }
    }

    @After
    fun tearDown() {
        app.close()
        Dispatchers.resetMain()
    }

    /** A database food, as the bundled import would leave it. */
    private suspend fun database(name: String, diet: FoodDiet, kcal: Double, unit: Pair<String, Double>): Long {
        val id = app.db.foodDao().insert(
            FoodEntity(name = name, source = FoodSource.INDB, diet = diet, region = FoodRegion.INDIAN, kcal100g = kcal, protein100g = 8.0, carbs100g = 20.0, fat100g = 4.0, fiber100g = 2.0, createdAt = 1),
        )
        app.db.foodDao().upsertUnits(listOf(FoodUnitEntity(foodId = id, label = unit.first, grams = unit.second)))
        return id
    }

    private suspend fun aiOn() {
        app.ai.setEnabled(true)
        app.ai.saveKey("test-key")
        app.ai.markNoticeSeen()
        app.ai.markNoticeSeen(AiKind.FIT_CHECK)
    }

    private fun salad(name: String = "Cucumber salad") = EstimatedItem(name, 1.0, "bowl", 120.0, 40.0, 2.0, 6.0, 1.0, 2.0, Confidence.MEDIUM, "no dressing")

    private fun replyWith(vararg items: EstimatedItem) {
        app.estimator.next = { FakeEstimator.reply(AiStatus.OK, MealEstimate(items.toList(), null)) }
    }

    private suspend fun describe(text: String, origin: Origin = Origin.HOME, kind: AiKind = AiKind.MEAL_TEXT, parserOnFailure: Boolean = false): DescribedMeal =
        (app.describer.describe(text, origin, kind, parserOnFailure = parserOnFailure) as DescribeResult.Described).meal

    private suspend fun rows(): List<FoodLogEntity> = app.db.foodLogDao().day(date)

    private suspend fun requestKinds(): List<String> = app.db.openHelper.readableDatabase.query("SELECT kind FROM ai_request_log ORDER BY id").use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }

    private suspend fun <T> eventually(block: suspend () -> T?): T = withTimeout(5_000) {
        var value = block()
        while (value == null) {
            kotlinx.coroutines.delay(10)
            value = block()
        }
        value
    }

    private fun describeVm() = DescribeViewModel(
        SavedStateHandle(mapOf("date" to date.toString(), "slot" to "lunch", "draftId" to 0L)), app.ai, app.foodLog, app.foods,
        PhotoStore(ApplicationProvider.getApplicationContext()), app.describer, app.scanImages,
    )

    // AI-04: Describe it with AI off

    @Test
    fun `with AI off the parser matches your foods and the database, and lists what it couldn't find`() = runBlocking {
        val meal = describe("2 roti, 1 katori rajma masala and blorp salad")
        assertEquals(AiState.OFF, meal.aiState)
        assertEquals(listOf("Chapati/Roti", "Rajma masala"), meal.items.map { it.item.name })
        assertEquals(listOf(ItemFrom.DATABASE, ItemFrom.YOUR_FOOD), meal.items.map { it.from })
        assertEquals(listOf(roti, rajma), meal.items.map { it.foodId })
        assertEquals(listOf(80.0, 150.0), meal.items.map { it.item.grams })
        assertEquals(240.0, meal.items[0].item.kcal, 1e-6)
        assertEquals(listOf(UnmatchedPart("blorp salad")), meal.unmatched)
        assertNull(meal.requestId)
        assertTrue("nothing is sent", app.estimator.requests.isEmpty())
        assertTrue(requestKinds().isEmpty())
    }

    @Test
    fun `the review saves parser items linked to their foods, and Undo takes back their use`() = runBlocking {
        val vm = describeVm()
        vm.setText("2 roti, 1 katori rajma masala and blorp salad")
        vm.estimate()
        val review = eventually { vm.phase.value as? DescribePhase.Review }
        assertNull(review.requestId)
        assertEquals(listOf("blorp salad"), review.unmatched.map { it.typed })
        assertEquals(listOf(ItemFrom.DATABASE, ItemFrom.YOUR_FOOD), review.items.map { it.from })

        // A part nothing found: quick add it, here in the review. Nothing is logged yet.
        vm.resolve("blorp salad", ReviewItem.quick(QuickAddValues(null, 45.0, 2.0, null, null, Origin.HOME), "blorp salad"))
        assertTrue(rows().isEmpty())
        assertTrue((vm.phase.value as DescribePhase.Review).unmatched.isEmpty())

        vm.save(null)
        val outcome = eventually { vm.outcome.value }
        val logged = rows()
        assertEquals(listOf(LogSource.LOCAL_PARSE, LogSource.LOCAL_PARSE, LogSource.QUICK_ADD), logged.map { it.source })
        assertEquals(listOf(roti, rajma, null), logged.map { it.foodId })
        assertEquals(listOf("roti", "katori", "serving"), logged.map { it.unitLabel })
        assertEquals(listOf(2.0, 1.0, 1.0), logged.map { it.quantity })
        assertEquals("blorp salad", logged[2].name)
        assertNull("a quick add's carbs are unknown", logged[2].carbsG)
        assertTrue(logged.all { it.aiRequestId == null && it.mealSlot == "lunch" })
        assertEquals(1, app.foods.food(roti)!!.useCount)
        assertTrue(app.estimator.requests.isEmpty())

        app.foodLog.undoInsert(outcome.undoIds)
        assertTrue(rows().isEmpty())
        assertEquals(0, app.foods.food(roti)!!.useCount)
    }

    @Test
    fun `a matched food changes its amount only, and a food you pick is logged as yours`() = runBlocking {
        val vm = describeVm()
        vm.setText("2 roti and mystery")
        vm.estimate()
        val review = eventually { vm.phase.value as? DescribePhase.Review }
        vm.update(0, review.items[0].copy(current = review.items[0].current.scaledTo(100.0)))
        val paneer = app.foods.search("paneer").single().food
        vm.resolve("mystery", ReviewItem.picked(paneer, 2.0, PortionUnit("piece", 25.0, PortionUnit.Kind.OWN)))
        vm.save(null)
        eventually { vm.outcome.value }

        val logged = rows()
        assertEquals(listOf(LogSource.LOCAL_PARSE, LogSource.MANUAL), logged.map { it.source })
        assertEquals(100.0, logged[0].grams!!, 1e-6)
        assertEquals("the food's own numbers at the new weight", 300.0, logged[0].kcal, 1e-6)
        assertEquals(50.0, logged[1].grams!!, 1e-6)
        // The learning loop is for AI items: no AI-learned copy of a food you already have.
        assertTrue(app.db.foodDao().bySource(FoodSource.AI.name).isEmpty())
    }

    @Test
    fun `the diet filter and whole words hold over the real search index`() = runBlocking {
        val vegetarian = app.foods.parseMeal("1 bowl chicken curry, 1 bowl dal makhani, pan")
        assertEquals(listOf("Dal makhani"), vegetarian.items.map { it.food.name })
        assertEquals(listOf("1 bowl chicken curry", "pan"), vegetarian.unmatched)

        app.prefs.updatePersona { it.copy(diet = Diet.NON_VEGETARIAN) }
        assertEquals(listOf("Chicken curry", "Dal makhani"), app.foods.parseMeal("1 bowl chicken curry, 1 bowl dal makhani").items.map { it.food.name })

        app.prefs.updatePersona { it.copy(diet = Diet.VEGAN) }
        assertEquals(listOf("1 bowl dal makhani"), app.foods.parseMeal("1 bowl dal makhani").unmatched)

        // A hidden food is never matched.
        app.foods.hide(roti)
        assertEquals(listOf("2 roti"), app.foods.parseMeal("2 roti").unmatched)
    }

    @Test
    fun `the parser uses the unit you logged a food in last, and your calibrated measures`() = runBlocking {
        app.foodLog.log(date, "lunch", app.foods.food(rajma)!!, 1.0, PortionUnit("katori", 150.0, PortionUnit.Kind.HOUSEHOLD), Origin.HOME)
        assertEquals(150.0, app.foods.parseMeal("rajma masala").items.single().grams, 1e-6)
        app.foods.setHouseholdMeasure("katori", 180.0)
        assertEquals(180.0, app.foods.parseMeal("rajma masala").items.single().grams, 1e-6)
        assertEquals(360.0, app.foods.parseMeal("2 bowls rajma masala").items.single().grams, 1e-6)
    }

    // PRD §12.5: with AI on, the parser goes first

    @Test
    fun `when everything matches nothing is sent and nothing is spent`() = runBlocking {
        aiOn()
        val meal = describe("2 roti, 1 bowl dal makhani")
        assertEquals(2, meal.items.size)
        assertNull(meal.requestId)
        assertTrue(app.estimator.requests.isEmpty())
        assertTrue(requestKinds().isEmpty())
    }

    @Test
    fun `the AI estimates only the rest, gets the whole meal with the matched items marked, and nothing is counted twice`() = runBlocking {
        aiOn()
        // The model repeats the roti anyway: it's dropped, by either of the food's names.
        replyWith(salad(), salad("Roti"))
        val meal = describe("2 roti, 1 bowl dal makhani and cucumber salad")

        val sent = app.estimator.requests.single().user
        assertTrue(sent, "Meal: 2 roti, 1 bowl dal makhani and cucumber salad" in sent)
        assertTrue(sent, "Already counted, do not return these:\n- 2 roti = Chapati/Roti, 80 g\n- 1 bowl dal makhani = Dal makhani, 260 g" in sent)

        assertEquals(listOf("Chapati/Roti", "Dal makhani", "Cucumber salad"), meal.items.map { it.item.name })
        assertEquals(listOf(ItemFrom.DATABASE, ItemFrom.DATABASE, ItemFrom.AI), meal.items.map { it.from })
        assertEquals(listOf("Roti"), meal.alreadyCounted.map { it.name })
        assertTrue(meal.unmatched.isEmpty())
        assertNotNull(meal.requestId)

        val ids = app.foodLog.saveDescribedMeal(date, "lunch", meal.items.map { ReviewItem(it.item, it.item, it.from, it.foodId).toLog() }, Origin.HOME, meal.requestId, FoodDiet.VEGETARIAN, "Usual lunch", null)
        val logged = rows()
        assertEquals(3, ids.size)
        assertEquals(listOf(LogSource.LOCAL_PARSE, LogSource.LOCAL_PARSE, LogSource.AI_TEXT), logged.map { it.source })
        assertEquals(listOf(null, null, meal.requestId), logged.map { it.aiRequestId })
        assertEquals(3, app.foodLog.savedMealNamed("Usual lunch")?.let { app.db.savedMealDao().get(it.id) }!!.items.size)
    }

    @Test
    fun `a reply with nothing new is an answer, not an unreadable one`() = runBlocking {
        aiOn()
        replyWith()
        val meal = describe("2 roti and a sprinkle of nothing")
        assertEquals(listOf("Chapati/Roti"), meal.items.map { it.item.name })
        assertNull(meal.aiFailure)
        assertEquals("OK", app.db.openHelper.readableDatabase.query("SELECT status FROM ai_request_log").use { it.moveToFirst(); it.getString(0) })

        // Without anything counted, an empty reply is still unreadable, as before.
        val failed = app.describer.describe("a sprinkle of nothing", Origin.HOME)
        assertEquals(AiStatus.INVALID, (failed as DescribeResult.Failed).status)
    }

    @Test
    fun `an Ordered meal goes straight to the AI`() = runBlocking {
        aiOn()
        replyWith(salad("Butter roti"), salad("Dal makhani (restaurant)"))
        val meal = describe("2 roti, 1 bowl dal makhani", Origin.ORDERED)
        assertEquals(listOf(ItemFrom.AI, ItemFrom.AI), meal.items.map { it.from })
        val sent = app.estimator.requests.single().user
        assertTrue(sent, sent.startsWith("Origin: Ordered"))
        assertFalse(sent, "Already counted" in sent)
    }

    @Test
    fun `if the AI fails after the parser matched something, the matched items are reviewed and no draft is kept`() = runBlocking {
        aiOn()
        app.estimator.next = { FakeEstimator.reply(AiStatus.TIMEOUT, attempts = emptyList()) }
        val vm = describeVm()
        vm.setText("2 roti and cucumber salad")
        vm.estimate()
        val review = eventually { vm.phase.value as? DescribePhase.Review }
        assertEquals(listOf("Chapati/Roti"), review.items.map { it.current.name })
        assertEquals(listOf("cucumber salad"), review.unmatched.map { it.typed })
        assertTrue(review.notice!!, "didn't answer in time" in review.notice!!)
        assertNull("a failed request is not what the rows came from", review.requestId)
        assertTrue(app.ai.observeDrafts(date).first().isEmpty())
        assertEquals(listOf("MEAL_TEXT"), requestKinds())

        // With nothing matched it's as before: the description is kept as a draft.
        val none = describeVm()
        none.setText("cucumber salad")
        none.estimate()
        eventually { none.outcome.value }
        assertEquals(listOf("cucumber salad"), app.ai.observeDrafts(date).first().map { it.text })
    }

    @Test
    fun `AI on but not ready, the parser answers and Save draft still keeps the description`() = runBlocking {
        aiOn()
        app.connectivity.online.value = false
        val vm = describeVm()
        vm.setText("2 roti and cucumber salad")
        vm.estimate()
        val review = eventually { vm.phase.value as? DescribePhase.Review }
        assertEquals(listOf("Chapati/Roti"), review.items.map { it.current.name })
        assertTrue(review.notice!!, "without AI" in review.notice!!)
        assertTrue(app.estimator.requests.isEmpty())

        vm.backToText()
        vm.saveDraft()
        eventually { vm.outcome.value }
        assertEquals(1, app.ai.observeDrafts(date).first().size)
    }

    // NU-18: Will it fit?

    private suspend fun onboard(goal: Goal = Goal.RECOMP): LocalDate {
        val today = app.days.today()
        val profile = Profile(Sex.MALE, today.minusYears(30), heightCm = 175.0, weightKg = 80.0)
        val plan = GoalPlan.of(goal, Pace.STANDARD, if (goal == Goal.LEAN_BULK) 84.0 else 76.0, FinishLine.GoalWeight, today)
        app.personas.completeOnboarding(
            OnboardingResult(Presets.REFERENCE.draft.copy(goal = plan, diet = Diet.LACTO_VEGETARIAN, proteinRamp = false), Presets.REFERENCE.key, emptyList(), profile, LocalTime.of(4, 0)),
        )
        return today
    }

    private fun fitVm(slot: String = "") =
        FitViewModel(SavedStateHandle(mapOf("slot" to slot)), app.describer, app.ai, app.foodLog, app.foods, app.nutrition, app.days, app.clock)

    @Test
    fun `Will it fit shows before and after without logging, and Log it adds it to the meal for the time of day`() = runBlocking {
        val today = onboard()
        aiOn()
        replyWith(salad("Paneer butter masala").copy(kcal = 420.0, proteinG = 18.0))
        val vm = fitVm()
        assertEquals("a dish you'd order", Origin.ORDERED, vm.origin.value)
        vm.setText("1 plate paneer butter masala")
        vm.check()
        val result = eventually { vm.phase.value as? FitPhase.Result }
        assertEquals(listOf("Paneer butter masala"), result.items.map { it.current.name })
        val fit = eventually { vm.fit.value }
        assertEquals(0, fit.kcalBefore)
        assertEquals(420, fit.kcalAfter)
        assertEquals(18, fit.proteinAfterG)
        assertEquals(fit.kcalTarget - 420, fit.kcalLeftAfter)
        assertTrue(FitText.line(fit), FitText.line(fit).startsWith("Protein left ${fit.proteinTargetG} g → ${fit.proteinTargetG!! - 18} g · calories "))
        assertTrue("nothing is logged by checking", app.db.foodLogDao().day(today).isEmpty())
        assertEquals(listOf("FIT_CHECK"), requestKinds())

        // 06:30 on the test clock is breakfast; 13:00 is lunch.
        assertEquals("breakfast", vm.slot.value)
        vm.logIt()
        val outcome = eventually { vm.outcome.value }
        val logged = app.db.foodLogDao().day(today)
        assertEquals(listOf("breakfast"), logged.map { it.mealSlot })
        assertEquals(listOf(LogSource.AI_TEXT), logged.map { it.source })
        assertEquals("a logged fit check keeps its request", listOf(result.requestId), logged.map { it.aiRequestId })
        assertEquals(Origin.ORDERED, logged.single().origin)
        assertEquals(logged.map { it.id }, outcome.undoIds)
        assertTrue(app.ai.observeDrafts(today).first().isEmpty())

        app.clock.instant = Instant.parse("2026-10-18T13:00:00Z")
        assertEquals("lunch", fitVm().slot.first { it != null })
        assertEquals("from a meal's add screen it starts on that meal", "dinner", fitVm("dinner").slot.first { it != null })
    }

    @Test
    fun `with AI off or failing, Will it fit answers from your foods and the database and keeps no draft`() = runBlocking {
        val today = onboard()
        val off = fitVm()
        off.setText("2 roti and 1 bowl dal makhani")
        off.setOrigin(Origin.HOME)
        off.check()
        val local = eventually { off.phase.value as? FitPhase.Result }
        assertEquals(listOf(ItemFrom.DATABASE, ItemFrom.DATABASE), local.items.map { it.from })
        assertEquals(240 + 364, eventually { off.fit.value }.kcalAfter)
        assertTrue(app.estimator.requests.isEmpty())

        aiOn()
        app.estimator.next = { FakeEstimator.reply(AiStatus.SERVER, attempts = emptyList()) }
        val failing = fitVm()
        failing.setText("2 roti and 1 bowl dal makhani")
        failing.check()
        val fallback = eventually { failing.phase.value as? FitPhase.Result }
        assertEquals("Ordered went to the AI, which failed, so the parser answered", listOf("Chapati/Roti", "Dal makhani"), fallback.items.map { it.current.name })
        assertTrue(fallback.notice!!, "unavailable" in fallback.notice!!)
        assertNull(fallback.requestId)
        assertTrue(app.ai.observeDrafts(today).first().isEmpty())
        assertTrue(app.db.foodLogDao().day(today).isEmpty())

        failing.logIt()
        eventually { failing.outcome.value }
        assertEquals(listOf(LogSource.LOCAL_PARSE, LogSource.LOCAL_PARSE), app.db.foodLogDao().day(today).map { it.source })
    }

    @Test
    fun `the meal for the time of day follows the slot times you set`() = runBlocking {
        onboard()
        app.prefs.edit { mealSlots = MealSlots.DEFAULT.map { if (it.key == "lunch") it.copy(startMinutes = 6 * 60) else it } + MealSlot("meal-1", "Pre-workout", startMinutes = 6 * 60 + 15) }
        assertEquals(6 * 60, app.prefs.mealSlots.first().first { it.key == "lunch" }.startMinutes)
        assertEquals("meal-1", fitVm().slot.first { it != null })
    }

    // NU-06: the snack allowance

    @Test
    fun `the snack allowance shows on the day and its meal, and the target is the same with it on or off`() = runBlocking {
        val today = onboard()
        val paneer = app.foods.search("paneer").single().food
        app.foodLog.log(today, "snacks", paneer, 1.0, PortionUnit("piece", 25.0, PortionUnit.Kind.OWN), Origin.HOME)
        val before = app.foodLog.observeDay(today).first()
        assertNull(before.allowance)
        assertTrue(before.meals.all { it.allowanceKcal == null })

        app.prefs.setSnackAllowance(SnackAllowance(150))
        val on = app.foodLog.observeDay(today).first()
        assertEquals(SnackAllowance(150, "snacks"), on.allowance)
        assertEquals(mapOf("snacks" to 150), on.meals.filter { it.allowanceKcal != null }.associate { it.slot.key to it.allowanceKcal })
        assertEquals("part of the target, not extra", before.remaining, on.remaining)
        assertEquals(before.targets!!.target.kcal, on.targets!!.target.kcal)
        assertEquals(70.0, on.meals.first { it.slot.key == "snacks" }.totals.kcal, 1e-6)

        app.prefs.setSnackAllowance(SnackAllowance(200, "lunch"))
        assertEquals(listOf("lunch"), app.foodLog.observeDay(today).first().meals.filter { it.allowanceKcal == 200 }.map { it.slot.key })
        app.prefs.setSnackAllowance(null)
        assertNull(app.foodLog.observeDay(today).first().allowance)
    }

    // PR #15's review

    @Test
    fun `S1 - with AI on, a guess goes to the AI and is never sent as already counted`() = runBlocking {
        database("Banana raita", FoodDiet.VEGETARIAN, 90.0, "bowl" to 200.0)
        aiOn()
        replyWith(salad("Banana").copy(kcal = 105.0))
        val meal = describe("2 roti and 2 bananas")
        val sent = app.estimator.requests.single().user
        assertTrue(sent, "- 2 roti = Chapati/Roti, 80 g" in sent)
        assertFalse(sent, "Banana raita" in sent)
        assertEquals(listOf("Chapati/Roti", "Banana"), meal.items.map { it.item.name })
        assertTrue(meal.unmatched.isEmpty())

        // Only a guess and nothing sure: still one request, never "fully matched".
        replyWith(salad("Banana"))
        assertEquals(listOf(ItemFrom.AI), describe("2 bananas").items.map { it.from })
        assertEquals(2, app.estimator.requests.size)
    }

    @Test
    fun `S1 - with AI off, a guess is offered under the unmatched parts, and Use it logs it`() = runBlocking {
        val raita = database("Banana raita", FoodDiet.VEGETARIAN, 90.0, "bowl" to 200.0)
        val meal = describe("2 roti and 2 bananas")
        assertEquals(listOf("Chapati/Roti"), meal.items.map { it.item.name })
        val part = meal.unmatched.single()
        assertEquals("2 bananas", part.typed)
        assertEquals("Banana raita", part.guess!!.item.name)
        assertEquals(raita, part.guess!!.foodId)

        val vm = describeVm()
        vm.setText("2 roti and 2 bananas")
        vm.estimate()
        val review = eventually { vm.phase.value as? DescribePhase.Review }
        assertEquals(listOf("Chapati/Roti"), review.items.map { it.current.name })
        val guess = review.unmatched.single().guess!!
        vm.resolve("2 bananas", guess.reviewItem())
        vm.save(null)
        eventually { vm.outcome.value }
        assertEquals(listOf(roti, raita), rows().map { it.foodId })
        assertEquals(listOf(LogSource.LOCAL_PARSE, LogSource.LOCAL_PARSE), rows().map { it.source })
    }

    @Test
    fun `S3 - an AI item renamed from a counted one is set aside, and Add anyway logs it`() = runBlocking {
        aiOn()
        replyWith(salad("Roti (2 pcs)"), salad())
        val vm = describeVm()
        vm.setText("2 roti and cucumber salad")
        vm.estimate()
        val review = eventually { vm.phase.value as? DescribePhase.Review }
        assertEquals(listOf("Chapati/Roti", "Cucumber salad"), review.items.map { it.current.name })
        assertEquals(listOf("Roti (2 pcs)"), review.alreadyCounted.map { it.current.name })
        vm.addAnyway(0)
        val after = vm.phase.value as DescribePhase.Review
        assertEquals(3, after.items.size)
        assertTrue(after.alreadyCounted.isEmpty())
    }

    @Test
    fun `N3 - a save that can't go through keeps the review and says why, instead of crashing`() = runBlocking {
        val vm = describeVm()
        vm.setText("2 roti")
        vm.estimate()
        eventually { vm.phase.value as? DescribePhase.Review }
        // The food disappears under the review, as a backup import can do.
        app.db.openHelper.writableDatabase.execSQL("DELETE FROM food_unit WHERE food_id = $roti")
        app.db.openHelper.writableDatabase.execSQL("DELETE FROM food WHERE id = $roti")
        vm.save(null)
        val notice = eventually { (vm.phase.value as? DescribePhase.Review)?.notice }
        assertTrue(notice, "no longer in your foods" in notice)
        assertNull(vm.outcome.value)
        assertFalse("the screen can try again", vm.busy.value)
        assertTrue(rows().isEmpty())
    }

    @Test
    fun `N3 - Log it that can't go through keeps the result and says why`() = runBlocking {
        val today = onboard()
        val vm = fitVm()
        vm.setText("2 roti")
        vm.setOrigin(Origin.HOME)
        vm.check()
        eventually { vm.phase.value as? FitPhase.Result }
        app.db.openHelper.writableDatabase.execSQL("DELETE FROM food_unit WHERE food_id = $roti")
        app.db.openHelper.writableDatabase.execSQL("DELETE FROM food WHERE id = $roti")
        vm.logIt()
        val notice = eventually { (vm.phase.value as? FitPhase.Result)?.notice }
        assertTrue(notice, "Couldn't save" in notice)
        assertNull(vm.outcome.value)
        assertTrue(app.db.foodLogDao().day(today).isEmpty())
    }

    @Test
    fun `N6 - removing the allowance's meal moves its line to the default meal`() = runBlocking {
        val today = onboard()
        app.prefs.setSnackAllowance(SnackAllowance(150, "lunch"))
        app.prefs.edit { mealSlots = mealSlots.map { if (it.key == "lunch") it.copy(removed = true) else it } }
        val day = app.foodLog.observeDay(today).first()
        assertEquals(SnackAllowance(150, "snacks"), day.allowance)
        assertEquals(listOf("snacks"), day.meals.filter { it.allowanceKcal != null }.map { it.slot.key })
    }

    @Test
    fun `the parser's index leaves no source out, so foods of a kind added later are matched`() = runBlocking {
        FoodSource.entries.forEach { source ->
            app.db.foodDao().insert(
                FoodEntity(name = "Zylo ${source.name.lowercase()}", source = source, diet = FoodDiet.VEGAN, kcal100g = 100.0, protein100g = 5.0, carbs100g = 10.0, fat100g = 2.0, fiber100g = 1.0, createdAt = 1),
            )
        }
        val found = app.foods.describeIndex().lookup(listOf("zylo")).map { it.source }.toSet()
        assertEquals(FoodSource.entries.toSet(), found)
    }
}
