package app.recomp.data

import androidx.datastore.preferences.core.edit
import app.recomp.data.ai.AiKeyStore
import app.recomp.data.db.FoodEntity
import app.recomp.data.repo.AiItemToLog
import app.recomp.data.repo.CustomFood
import app.recomp.data.repo.EstimateResult
import app.recomp.domain.ai.AiKind
import app.recomp.domain.ai.AiModel
import app.recomp.domain.ai.AiProviderId
import app.recomp.domain.ai.AiState
import app.recomp.domain.ai.AiStatus
import app.recomp.domain.ai.AiUrl
import app.recomp.domain.ai.Clarification
import app.recomp.domain.ai.Confidence
import app.recomp.domain.ai.EstimatedItem
import app.recomp.domain.ai.MealEstimate
import app.recomp.domain.ai.TokenUsage
import app.recomp.domain.food.FoodDiet
import app.recomp.domain.food.FoodSource
import app.recomp.domain.food.LogSource
import app.recomp.domain.food.Origin
import app.recomp.domain.food.Per100g
import app.recomp.domain.persona.Diet
import app.recomp.domain.persona.FoodRegion
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
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
 * AI logging (AI-01 to AI-03, NU-17, TRD §6.2): the gate and every fallback path, spend and the cap, the prompt's
 * own foods, saving with the learning loop, drafts and the key store. Claude is a fake; the values are invented.
 */
@RunWith(RobolectricTestRunner::class)
class AiFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: TestApp
    private val ai get() = app.ai
    private val date = LocalDate.of(2026, 10, 18)

    @Before
    fun setUp() {
        app = TestApp(File(tmp.root, "app").apply { mkdirs() })
        runBlocking { app.prefs.updatePersona { it.copy(diet = Diet.LACTO_VEGETARIAN, region = FoodRegion.INDIAN) } }
    }

    @After
    fun tearDown() = app.close()

    private suspend fun ready() {
        ai.setEnabled(true)
        ai.saveKey("test-key")
    }

    private fun item(name: String = "Phulka", grams: Double = 70.0, kcal: Double = 210.0, protein: Double = 6.5, quantity: Double = 2.0, unit: String = "piece") =
        EstimatedItem(name, quantity, unit, grams, kcal, protein, carbsG = 42.0, fatG = 1.5, fiberG = 4.0, Confidence.HIGH, "no ghee")

    private fun estimate(vararg items: EstimatedItem, question: String? = null) = MealEstimate(items.toList(), question)

    private suspend fun requests() = app.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM ai_request_log").use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun `the gate is off by default, then needs a key, the cap and a connection`() = runBlocking {
        assertEquals(AiState.OFF, ai.gate.first().state)
        assertEquals(AiState.OFF, ai.state())

        ai.setEnabled(true)
        assertEquals(AiState.NO_KEY, ai.gate.first().state)

        ai.saveKey("  test-key  ")
        assertEquals(AiState.READY, ai.gate.first().state)
        assertEquals(AiState.READY, ai.state())

        app.connectivity.online.value = false
        assertEquals(AiState.OFFLINE, ai.gate.first().state)
        assertEquals(AiState.OFFLINE, ai.state())
        app.connectivity.online.value = true

        ai.setMonthlyCap(0.05)
        assertEquals(AiState.READY, ai.state())
        app.estimator.next = { FakeEstimator.reply(AiStatus.OK, estimate(item())) }
        ai.estimate("2 phulka", Origin.HOME)
        // 2,000 in and 1,000 out on Opus 5.5 is $0.028: under the cap. A second one crosses it.
        assertEquals(AiState.READY, ai.state())
        ai.estimate("2 phulka", Origin.HOME)
        val gate = ai.gate.first()
        assertEquals(AiState.OVER_CAP, gate.state)
        assertEquals(LocalDate.of(2026, 11, 1), gate.resumesOn)
        assertEquals(0.05, gate.capUsd!!, 0.0)

        ai.setMonthlyCap(null)
        assertEquals(AiState.READY, ai.state())
    }

    @Test
    fun `when the gate isn't ready nothing is sent or logged`() = runBlocking {
        suspend fun tryIt(expected: AiState) {
            assertEquals(EstimateResult.NotReady(expected), ai.estimate("2 phulka", Origin.HOME))
        }
        tryIt(AiState.OFF)
        ai.setEnabled(true)
        tryIt(AiState.NO_KEY)
        ai.saveKey("test-key")
        app.connectivity.online.value = false
        tryIt(AiState.OFFLINE)
        app.connectivity.online.value = true
        ai.setMonthlyCap(0.0)
        tryIt(AiState.OVER_CAP)
        assertTrue(app.estimator.requests.isEmpty())
        assertEquals(0, requests())
    }

    @Test
    fun `requests made at the same time can't get past the cap`() = runBlocking {
        ready()
        ai.setMonthlyCap(0.05)
        val hold = CompletableDeferred<Unit>()
        app.estimator.hold = hold
        app.estimator.next = { FakeEstimator.reply(AiStatus.OK, estimate(item())) }
        val results = (1..3).map { async(Dispatchers.Default) { ai.estimate("dal", Origin.HOME) } }
        // One goes out; its reservation already counts, so the other two find the cap reached.
        withTimeout(5_000) { while (results.count { it.isCompleted } < 2) delay(10) }
        assertEquals(1, app.estimator.requests.size)

        val pending = app.db.openHelper.readableDatabase.query("SELECT status, cost_usd FROM ai_request_log").use { c ->
            c.moveToFirst(); c.getString(0) to c.getDouble(1)
        }
        assertEquals(AiStatus.PENDING.name, pending.first)
        assertTrue("reserved at the upper bound", pending.second > 0.1)
        assertEquals(AiState.OVER_CAP, ai.state())

        hold.complete(Unit)
        val all = results.awaitAll()
        assertEquals(1, all.count { it is EstimateResult.Estimated })
        assertEquals(2, all.count { it == EstimateResult.NotReady(AiState.OVER_CAP) })
        // Settled at what it really cost: 2,000 in and 1,000 out on Opus 5.5.
        assertEquals(0.028, ai.spend.first().costUsd, 1e-9)
        assertEquals(1, requests())
    }

    @Test
    fun `a request that timed out keeps the cost the server may have billed`() = runBlocking {
        ready()
        app.estimator.next = { request -> FakeEstimator.reply(AiStatus.TIMEOUT, attempts = listOf(request.upperBound)) }
        val result = ai.estimate("dal", Origin.HOME) as EstimateResult.Failed
        val row = ai.request(result.requestId!!)!!
        assertEquals(AiStatus.TIMEOUT, row.status)
        assertEquals(8_000L, row.outputTokens)
        assertTrue(row.costUsd > 0.16)
    }

    @Test
    fun `a reached cap lifts on the 1st of next month`() = runBlocking {
        ready()
        ai.setMonthlyCap(0.01)
        app.estimator.next = { FakeEstimator.reply(AiStatus.OK, estimate(item())) }
        ai.estimate("dal", Origin.HOME)
        assertEquals(AiState.OVER_CAP, ai.state())
        assertEquals(1, ai.spend.first().requests)

        app.clock.instant = Instant.parse("2026-11-01T00:00:01Z")
        assertEquals(AiState.READY, ai.state())
        val spend = ai.spend.first()
        assertEquals(LocalDate.of(2026, 11, 1), spend.month)
        assertEquals(0.0, spend.costUsd, 0.0)
        assertEquals(0, spend.requests)
    }

    @Test
    fun `every failure is logged with its status and comes back as failed`() = runBlocking {
        ready()
        val failures = listOf(AiStatus.TIMEOUT, AiStatus.RATE_LIMIT, AiStatus.NETWORK, AiStatus.SERVER, AiStatus.AUTH, AiStatus.BILLING, AiStatus.REFUSAL, AiStatus.MAX_TOKENS, AiStatus.INVALID, AiStatus.BAD_REQUEST)
        failures.forEach { status ->
            val attempts = if (status in setOf(AiStatus.REFUSAL, AiStatus.MAX_TOKENS)) listOf(TokenUsage("claude-opus-5-5", 1_000, 200)) else emptyList()
            app.estimator.next = { FakeEstimator.reply(status, attempts = attempts) }
            val result = ai.estimate("dal", Origin.HOME)
            assertTrue("$status", result is EstimateResult.Failed && result.status == status)
            val row = ai.request((result as EstimateResult.Failed).requestId!!)!!
            assertEquals(status, row.status)
            assertEquals(1_234L, row.latencyMs)
        }
        assertEquals(failures.size, requests())
        // A refusal still costs what it used: 1,000 in and 200 out at Opus 5.5.
        assertEquals(2 * (0.004 + 0.004), ai.spend.first().costUsd, 1e-9)
    }

    @Test
    fun `a reply with no usable item is invalid`() = runBlocking {
        ready()
        app.estimator.next = { FakeEstimator.reply(AiStatus.OK, estimate(item(grams = 0.0), item(name = "Salad", grams = -1.0))) }
        val result = ai.estimate("salad", Origin.HOME)
        assertTrue(result is EstimateResult.Failed && result.status == AiStatus.INVALID)
        assertEquals(AiStatus.INVALID, ai.request((result as EstimateResult.Failed).requestId!!)!!.status)
    }

    @Test
    fun `an estimate is validated, logged and costed over every attempt`() = runBlocking {
        ready()
        ai.setModel(AiModel.SONNET_5_5)
        app.estimator.next = {
            FakeEstimator.reply(
                AiStatus.OK,
                estimate(item(), item(name = "Pickle", grams = 0.0), question = "Ghee on the phulka?"),
                attempts = listOf(TokenUsage("claude-sonnet-5-5", 1_000, 0), TokenUsage("claude-opus-5", 1_000, 1_000, cacheReadTokens = 1_000)),
                servedBy = "claude-opus-5",
            )
        }
        val result = ai.estimate("2 phulka and pickle", Origin.ORDERED) as EstimateResult.Estimated
        assertEquals(listOf("Phulka"), result.meal.items.map { it.name })
        assertEquals(listOf("Pickle"), result.meal.rejected)
        assertEquals("Ghee on the phulka?", result.meal.clarifyingQuestion)

        val sent = app.estimator.requests.single()
        assertEquals(AiModel.SONNET_5_5.id, sent.model)
        assertEquals("test-key", sent.target.apiKey)
        assertTrue(sent.user.startsWith("Origin: Ordered\nMeal: 2 phulka and pickle"))
        assertTrue(sent.system.contains("lacto-vegetarian"))

        val row = ai.request(result.requestId)!!
        assertEquals("claude-opus-5", row.model)
        assertEquals(2_000L, row.inputTokens)
        assertEquals(1_000L, row.outputTokens)
        assertEquals(1_000L, row.cacheReadTokens)
        assertEquals(0.002 + 0.005 + 0.025 + 0.0005, row.costUsd, 1e-12)
        assertEquals(AiStatus.OK, row.status)
        assertNotNull(row.responseJson)
    }

    @Test
    fun `a re-estimate sends the question and your answer`() = runBlocking {
        ready()
        app.estimator.next = { FakeEstimator.reply(AiStatus.OK, estimate(item())) }
        ai.estimate("dal", Origin.HOME, Clarification("Tempered with ghee?", "a spoon"))
        assertTrue(app.estimator.requests.single().user.contains("You asked: Tempered with ghee?\nThe user answered: a spoon"))
    }

    @Test
    fun `the prompt lists your own foods for your diet and region, most used first`() = runBlocking {
        val shake = app.foods.createCustom(CustomFood("Protein shake", null, Per100g(80.0, 15.0, 3.0, 1.0), FoodDiet.VEGETARIAN, "glass" to 300.0))
        app.foods.createCustom(CustomFood("Hidden bar", null, Per100g(400.0, 20.0, 40.0, 15.0), FoodDiet.VEGETARIAN, null)).also { app.foods.hide(it) }
        app.foods.createCustom(CustomFood("Omelette", null, Per100g(150.0, 10.0, 1.0, 11.0), FoodDiet.EGG, null))
        app.foods.createCustom(CustomFood("Tofu bowl", null, Per100g(120.0, 9.0, 10.0, 5.0), FoodDiet.VEGAN, null))
        app.db.foodDao().markUsed(shake, 3, 1)

        val system = ai.systemPrompt()
        assertTrue(system.contains("- Protein shake: per 100 g 80 kcal, 15 g protein, 3 g carbohydrate, 1 g fat, 0 g fibre; 1 glass = 300 g"))
        assertTrue(system.indexOf("Protein shake") < system.indexOf("Tofu bowl"))
        assertFalse("hidden", system.contains("Hidden bar"))
        assertFalse("egg isn't in a lacto-vegetarian diet", system.contains("Omelette"))
        assertTrue(system.contains("1 katori = 150 g"))
        assertFalse("seed foods aren't yours", system.contains("Paneer"))
    }

    @Test
    fun `a saved estimate logs one AI row per item and teaches only the edited ones`() = runBlocking {
        val phulka = item()
        val dal = item(name = "Dal", grams = 150.0, kcal = 170.0, protein = 9.0, quantity = 1.0, unit = "katori")
        val bigDal = dal.scaledTo(200.0)
        val ids = app.foodLog.logAiMeal(
            date, "lunch",
            listOf(AiItemToLog(phulka, edited = false), AiItemToLog(bigDal, edited = true), AiItemToLog(item(name = "Rice", grams = 150.0, unit = "grams", quantity = 150.0), edited = false)),
            Origin.ORDERED, requestId = 7, dietTag = FoodDiet.VEGETARIAN, now = 1_000,
        )
        val rows = app.db.foodLogDao().get(ids)
        assertEquals(listOf("Phulka", "Dal", "Rice"), rows.map { it.name })
        assertTrue(rows.all { it.source == LogSource.AI_TEXT && it.aiRequestId == 7L && it.origin == Origin.ORDERED && it.logDate == date })
        assertNull("unedited items keep their snapshot only", rows[0].foodId)
        assertEquals("g", rows[2].unitLabel)
        assertEquals(150.0, rows[2].quantity, 0.0)
        assertEquals(226.67, rows[1].kcal, 0.01)

        val learned = app.db.foodDao().get(rows[1].foodId!!)!!
        assertEquals(FoodSource.AI, learned.source)
        assertEquals(FoodDiet.VEGETARIAN, learned.diet)
        assertNull(learned.region)
        assertEquals(170.0 / 1.5, learned.kcal100g, 1e-9)
        assertEquals(1, learned.useCount)
        assertEquals(200.0, app.db.foodDao().units(learned.id).single { it.label == "katori" }.grams, 1e-9)
        // It shows in search and in later prompts, ahead of the bundled foods.
        assertEquals("Dal", app.foods.search("dal").first().food.name)
        assertTrue(ai.systemPrompt().contains("- Dal: per 100 g"))

        // The same dish edited again updates that food instead of adding another.
        val again = app.foodLog.logAiMeal(date, "dinner", listOf(AiItemToLog(dal.copy(name = "dal", kcal = 150.0), edited = true)), Origin.HOME, 8, FoodDiet.VEGETARIAN, now = 2_000)
        val sameFood = app.db.foodLogDao().get(again).single().foodId
        assertEquals(learned.id, sameFood)
        assertEquals(1, app.db.foodDao().bySource(FoodSource.AI.name).size)
        assertEquals(100.0, app.db.foodDao().get(learned.id)!!.kcal100g, 1e-9)
        assertEquals(150.0, app.db.foodDao().units(learned.id).single { it.label == "katori" }.grams, 1e-9)

        // Undo takes the rows and their use back.
        app.foodLog.undoInsert(again)
        assertEquals(1, app.db.foodDao().get(learned.id)!!.useCount)
    }

    @Test
    fun `a learned food is tagged by what it contains, not by your diet`() = runBlocking {
        // A lacto-vegetarian's edited chicken biryani is meat: it stays out of their search and their prompts.
        val biryani = item(name = "Chicken biryani", grams = 300.0, quantity = 1.0, unit = "plate").copy(diet = FoodDiet.MEAT)
        val rows = app.db.foodLogDao().get(app.foodLog.logAiMeal(date, "dinner", listOf(AiItemToLog(biryani, true)), Origin.ORDERED, 1, FoodDiet.VEGETARIAN))
        assertEquals(FoodDiet.MEAT, app.db.foodDao().get(rows.single().foodId!!)!!.diet)
        assertTrue(app.foods.search("biryani").isEmpty())
        assertFalse(ai.systemPrompt().contains("Chicken biryani"))

        // A meat eater's edited dal is vegan, so it would still show after a change of diet.
        val dal = item(name = "Dal tadka", grams = 150.0).copy(diet = FoodDiet.VEGAN)
        val dalId = app.db.foodLogDao().get(app.foodLog.logAiMeal(date, "lunch", listOf(AiItemToLog(dal, true)), Origin.HOME, 2, FoodDiet.MEAT)).single().foodId!!
        assertEquals(FoodDiet.VEGAN, app.db.foodDao().get(dalId)!!.diet)

        // Learned again with ghee, it's re-tagged; with no tag from the model, it keeps the one it had.
        app.foodLog.logAiMeal(date, "lunch", listOf(AiItemToLog(dal.copy(diet = FoodDiet.VEGETARIAN, kcal = 250.0), true)), Origin.HOME, 3, FoodDiet.MEAT)
        assertEquals(FoodDiet.VEGETARIAN, app.db.foodDao().get(dalId)!!.diet)
        app.foodLog.logAiMeal(date, "lunch", listOf(AiItemToLog(dal.copy(diet = null, kcal = 260.0), true)), Origin.HOME, 4, FoodDiet.MEAT)
        assertEquals(FoodDiet.VEGETARIAN, app.db.foodDao().get(dalId)!!.diet)

        // With no tag at all, a new food falls back to your own-food tag.
        val untagged = app.db.foodLogDao().get(app.foodLog.logAiMeal(date, "lunch", listOf(AiItemToLog(item(name = "Sprout salad"), true)), Origin.HOME, 5, FoodDiet.VEGETARIAN))
        assertEquals(FoodDiet.VEGETARIAN, app.db.foodDao().get(untagged.single().foodId!!)!!.diet)
    }

    @Test
    fun `a learned dish updates your own food of that name, and never a bundled one`() = runBlocking {
        val shake = app.foods.createCustom(CustomFood("Protein shake", null, Per100g(80.0, 15.0, 3.0, 1.0), FoodDiet.VEGETARIAN, "glass" to 300.0))
        val seedDal = app.db.foodDao().insert(
            FoodEntity(
                name = "Moong dal", source = FoodSource.SEED, sourceKey = "seed:test-dal", diet = FoodDiet.VEGAN,
                kcal100g = 100.0, protein100g = 7.0, carbs100g = 15.0, fat100g = 1.0, fiber100g = 3.0, createdAt = 1,
            ),
        )
        val shakeItem = item(name = "protein SHAKE", grams = 350.0, kcal = 300.0, protein = 52.0, quantity = 1.0, unit = "glass").copy(diet = FoodDiet.VEGAN)
        val dalItem = item(name = "Moong dal", grams = 200.0, kcal = 240.0, quantity = 1.0, unit = "katori")
        val rows = app.db.foodLogDao().get(
            app.foodLog.logAiMeal(date, "lunch", listOf(AiItemToLog(shakeItem, true), AiItemToLog(dalItem, true)), Origin.HOME, 1, FoodDiet.VEGETARIAN),
        )

        // Your custom food takes your numbers, keeps its name and the tag you gave it, and gets no AI twin.
        assertEquals(shake, rows[0].foodId)
        val updated = app.db.foodDao().get(shake)!!
        assertEquals("Protein shake", updated.name)
        assertEquals(FoodSource.CUSTOM, updated.source)
        assertEquals(FoodDiet.VEGETARIAN, updated.diet)
        assertEquals(300.0 / 3.5, updated.kcal100g, 1e-9)
        assertEquals(350.0, app.db.foodDao().units(shake).single { it.label == "glass" }.grams, 1e-9)

        // The bundled dal is untouched; your version sits beside it as AI-learned.
        assertEquals(100.0, app.db.foodDao().get(seedDal)!!.kcal100g, 0.0)
        val learned = app.db.foodDao().get(rows[1].foodId!!)!!
        assertEquals(FoodSource.AI, learned.source)
        assertEquals(listOf(FoodSource.AI, FoodSource.SEED), app.foods.search("moong").map { it.food.source })
        assertEquals(listOf(learned.id), app.db.foodDao().bySource(FoodSource.AI.name).map { it.id })
    }

    @Test
    fun `saving an estimate is all or nothing`() = runBlocking {
        val draft = ai.saveDraft(date, "lunch", "2 phulka", Origin.HOME, AiStatus.TIMEOUT)
        try {
            app.foodLog.saveAiMeal(date, "lunch", listOf(AiItemToLog(item(), true)), Origin.HOME, 1, FoodDiet.VEGETARIAN, mealName = " ", draftId = draft)
            throw AssertionError("saved a meal without a name")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertTrue("no rows", app.foodLog.day(date).isEmpty())
        assertTrue("no learned food", app.db.foodDao().bySource(FoodSource.AI.name).isEmpty())
        assertNotNull("the draft is still there", ai.draft(draft))

        val ids = app.foodLog.saveAiMeal(date, "lunch", listOf(AiItemToLog(item(), false)), Origin.HOME, 1, FoodDiet.VEGETARIAN, mealName = "Phulka plate", draftId = draft)
        assertEquals(1, ids.size)
        assertNotNull(app.foodLog.savedMealNamed("Phulka plate"))
        assertNull(ai.draft(draft))
    }

    @Test
    fun `save plus My meals saves just the estimate's items`() = runBlocking {
        app.foodLog.quickAdd(date, "lunch", "Earlier snack", 100.0, 2.0, null, null, Origin.HOME)
        val ids = app.foodLog.logAiMeal(date, "lunch", listOf(AiItemToLog(item(), false), AiItemToLog(item(name = "Dal", grams = 150.0), true)), Origin.HOME, 1, FoodDiet.VEGETARIAN)
        val mealId = app.foodLog.saveMealOf("Usual lunch", ids, "lunch")
        val meal = app.db.savedMealDao().get(mealId)!!
        assertEquals(listOf("Phulka", "Dal"), meal.items.sortedBy { it.position }.map { it.name })
        assertNull(meal.items.first { it.name == "Phulka" }.foodId)
        assertNotNull(meal.items.first { it.name == "Dal" }.foodId)

        // One tap logs it again, the learned dal from its food and the phulka from its snapshot.
        val logged = app.foodLog.logSavedMeal(mealId, date.plusDays(1), "lunch")
        assertEquals(listOf(210.0, 210.0), app.db.foodLogDao().get(logged).map { it.kcal })
    }

    @Test
    fun `drafts show in their meal on the day, and delete with undo`() = runBlocking {
        val id = ai.saveDraft(date, "dinner", "  paneer wrap and lassi ", Origin.ORDERED, AiStatus.TIMEOUT)
        ai.saveDraft(date.minusDays(1), "dinner", "other day", Origin.HOME, null)
        val day = app.foodLog.observeDay(date).first()
        val dinner = day.meals.first { it.slot.key == "dinner" }
        val draft = dinner.drafts.single()
        assertEquals("paneer wrap and lassi", draft.text)
        assertEquals(AiStatus.TIMEOUT, draft.reason)
        assertEquals(Origin.ORDERED, draft.origin)
        assertTrue(day.meals.filter { it.slot.key != "dinner" }.all { it.drafts.isEmpty() })
        assertEquals("a draft isn't logged food", 0, day.totals.entries)

        val gone = ai.deleteDraft(id)!!
        assertTrue(app.foodLog.observeDay(date).first().meals.all { it.drafts.isEmpty() })
        ai.restoreDraft(gone)
        assertEquals(id, ai.observeDrafts(date).first().single().id)
    }

    @Test
    fun `a draft in a meal you've since removed still shows`() = runBlocking {
        app.prefs.edit { mealSlots = mealSlots.map { if (it.key == "snacks") it.copy(removed = true) else it } }
        ai.saveDraft(date, "snacks", "chai and biscuits", Origin.HOME, null)
        val snacks = app.foodLog.observeDay(date).first().meals.single { it.slot.key == "snacks" }
        assertTrue(snacks.slot.removed)
        assertEquals(1, snacks.drafts.size)
    }

    @Test
    fun `the key is stored encrypted, never in the settings, and a key that won't decrypt reads as none`() = runBlocking {
        assertFalse(app.keys.hasKey(AiProviderId.ANTHROPIC).first())
        ai.saveKey(" test-key ")
        assertTrue(app.keys.hasKey(AiProviderId.ANTHROPIC).first())
        assertEquals("test-key", app.keys.key(AiProviderId.ANTHROPIC))

        val raw = File(tmp.root, "app/secrets.preferences_pb").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse("ciphertext only", raw.contains("test-key"))
        val settings = File(tmp.root, "app/settings.preferences_pb").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(settings.contains("test-key"))

        app.cipher.broken = true
        assertFalse(app.keys.hasKey(AiProviderId.ANTHROPIC).first())
        assertNull(app.keys.key(AiProviderId.ANTHROPIC))
        ai.setEnabled(true)
        assertEquals(AiState.NO_KEY, ai.state())
        app.cipher.broken = false

        ai.removeKey()
        assertFalse(app.keys.hasKey(AiProviderId.ANTHROPIC).first())
        try {
            ai.saveKey("two words")
            throw AssertionError("a key with spaces was saved")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `a key with anything but printable ASCII is refused`() = runBlocking {
        // A zero-width space, a BOM, an en dash, a space, an accent: OkHttp would reject any of them in the request
        // header, with the whole key in its error message.
        listOf("test\u200Bkey", "\uFEFFtest-key", "test\u2013key", "test key", "t\u00E9st-key", " ").forEach { bad ->
            try {
                ai.saveKey(bad)
                throw AssertionError("saved a key with ${bad.map { it.code }}")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        assertFalse(app.keys.hasKey(AiProviderId.ANTHROPIC).first())
        assertTrue(AiKeyStore.looksLikeKey("test-KEY_09.~"))
    }

    @Test
    fun `an estimator that throws comes back as a logged failure, never an exception`() = runBlocking {
        ready()
        app.estimator.next = { throw IllegalStateException("not the key") }
        val result = ai.estimate("dal", Origin.HOME)
        assertTrue(result is EstimateResult.Failed && result.status == AiStatus.UNEXPECTED)
        assertEquals(AiStatus.UNEXPECTED, ai.request((result as EstimateResult.Failed).requestId!!)!!.status)
    }

    @Test
    fun `AI settings default to off, Opus 5_5 and no cap`() = runBlocking {
        val s = ai.settings.first()
        assertFalse(s.enabled)
        assertEquals(AiModel.OPUS_5_5, s.model)
        assertNull(s.monthlyCapUsd)
        assertFalse(s.noticeSeen(AiKind.MEAL_TEXT))

        ai.setEnabled(true)
        ai.setModel(AiModel.HAIKU_5_5)
        ai.setMonthlyCap(5.0)
        ai.markNoticeSeen()
        val after = ai.settings.first()
        assertTrue(after.enabled)
        assertEquals(AiModel.HAIKU_5_5, after.model)
        assertEquals(5.0, after.monthlyCapUsd!!, 0.0)
        assertTrue(after.noticeSeen(AiKind.MEAL_TEXT))
    }

    // AI providers (AI-05)

    private suspend fun providerOf(id: Long) = ai.request(id)!!.provider

    @Test
    fun `switching provider keeps both keys, and each provider needs its own`() = runBlocking {
        ready()
        assertEquals(AiState.READY, ai.state())
        assertEquals(setOf(AiProviderId.ANTHROPIC), ai.storedKeys.first())

        ai.setProvider(AiProviderId.OPENAI)
        assertEquals("Anthropic's key isn't OpenAI's", AiState.NO_KEY, ai.state())
        assertEquals(AiProviderId.OPENAI, ai.gate.first().provider)
        assertFalse(ai.hasKey.first())
        assertEquals(EstimateResult.NotReady(AiState.NO_KEY), ai.estimate("dal", Origin.HOME))
        assertTrue(app.estimator.requests.isEmpty())

        ai.saveKey("openai-test-key")
        assertEquals(AiState.READY, ai.state())
        assertEquals(setOf(AiProviderId.ANTHROPIC, AiProviderId.OPENAI), ai.storedKeys.first())
        assertEquals("test-key", app.keys.key(AiProviderId.ANTHROPIC))
        assertEquals("openai-test-key", app.keys.key(AiProviderId.OPENAI))

        // Each request carries the key of the provider in use, and its model.
        app.estimator.next = { FakeEstimator.reply(AiStatus.OK, estimate(item())) }
        ai.setModel(AiModel.GPT_6_LUNA)
        val openAi = ai.estimate("dal", Origin.HOME) as EstimateResult.Estimated
        ai.setProvider(AiProviderId.ANTHROPIC)
        assertEquals("back on Anthropic with its key and model, nothing re-entered", AiState.READY, ai.state())
        val anthropic = ai.estimate("dal", Origin.HOME) as EstimateResult.Estimated
        val (first, second) = app.estimator.requests
        assertEquals(listOf(AiProviderId.OPENAI, "openai-test-key", "gpt-6-luna"), listOf(first.target.provider, first.target.apiKey, first.model))
        assertEquals(listOf(AiProviderId.ANTHROPIC, "test-key", "claude-opus-5-5"), listOf(second.target.provider, second.target.apiKey, second.model))
        assertFalse("the refusal fallback is Anthropic's alone", first.target.serverFallback)
        assertTrue(second.target.serverFallback)
        assertEquals(AiProviderId.OPENAI, providerOf(openAi.requestId))
        assertEquals(AiProviderId.ANTHROPIC, providerOf(anthropic.requestId))

        // Removing one provider's key leaves the other's.
        ai.removeKey(AiProviderId.OPENAI)
        assertEquals(setOf(AiProviderId.ANTHROPIC), ai.storedKeys.first())
        assertEquals(AiState.READY, ai.state())

        // The stored ciphertext is bound to its provider: one provider's entry can't stand in for another's.
        val raw = File(tmp.root, "app/secrets.preferences_pb").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("test-key"))
        assertNull(app.keys.key(AiProviderId.GEMINI))
    }

    @Test
    fun `one monthly cap counts every provider's requests together`() = runBlocking {
        ready()
        ai.saveKey("gemini-test-key", AiProviderId.GEMINI)
        ai.setMonthlyCap(0.05)
        // $0.028 on Anthropic, then the same again logged against Gemini: together over the cap.
        app.estimator.next = { FakeEstimator.reply(AiStatus.OK, estimate(item())) }
        ai.estimate("dal", Origin.HOME)
        ai.setProvider(AiProviderId.GEMINI)
        assertEquals(AiState.READY, ai.state())
        ai.estimate("dal", Origin.HOME)
        assertEquals(AiState.OVER_CAP, ai.state())
        ai.setProvider(AiProviderId.ANTHROPIC)
        assertEquals("the cap isn't per provider", AiState.OVER_CAP, ai.state())
        val spend = ai.spend.first()
        assertEquals(2, spend.requests)
        assertEquals(0.056, spend.costUsd, 1e-9)
    }

    @Test
    fun `the reservation is each provider's own upper bound, logged against it`() = runBlocking {
        ready()
        ai.saveKey("gemini-test-key", AiProviderId.GEMINI)
        ai.setProvider(AiProviderId.GEMINI)
        ai.setModel(AiModel.GEMINI_3_8_FLASH)
        val hold = CompletableDeferred<Unit>()
        app.estimator.hold = hold
        app.estimator.next = { FakeEstimator.reply(AiStatus.OK, estimate(item()), attempts = listOf(TokenUsage("gemini-3.8-flash", 1_000, 500)), servedBy = "gemini-3.8-flash-001") }
        val running = async(Dispatchers.Default) { ai.estimate("dal", Origin.HOME) }
        withTimeout(5_000) { while (app.estimator.requests.isEmpty()) delay(10) }
        val bound = app.estimator.requests.single().upperBound
        val pending = app.db.openHelper.readableDatabase.query("SELECT provider, model, status, cost_usd FROM ai_request_log").use {
            it.moveToFirst()
            listOf(it.getString(0), it.getString(1), it.getString(2)) to it.getDouble(3)
        }
        assertEquals(listOf("GEMINI", "gemini-3.8-flash", "PENDING"), pending.first)
        assertEquals((bound.inputTokens * 0.75 + 8_000 * 3.75) / 1_000_000, pending.second, 1e-12)
        hold.complete(Unit)
        val done = running.await() as EstimateResult.Estimated
        val row = ai.request(done.requestId)!!
        assertEquals(AiProviderId.GEMINI, row.provider)
        assertEquals("gemini-3.8-flash-001", row.model)
        assertEquals((1_000 * 0.75 + 500 * 3.75) / 1_000_000, row.costUsd, 1e-12)
    }

    @Test
    fun `a compatible server needs its address and model, and its typed prices are what's charged`() = runBlocking {
        ai.setEnabled(true)
        ai.setProvider(AiProviderId.COMPATIBLE)
        assertEquals(AiState.NO_URL, ai.state())
        assertEquals(EstimateResult.NotReady(AiState.NO_URL), ai.estimate("dal", Origin.HOME))

        // The URL rules are enforced on the way in: http only to this phone or the local network.
        for (bad in listOf("http://example.com/v1", "example.com", "https://user:pw@example.com/v1", "")) {
            assertTrue(bad, runCatching { ai.setCompatible(bad, "m", 0.0, 0.0) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(runCatching { ai.setCompatible("https://example.com/v1", " ", 0.0, 0.0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { ai.setCompatible("https://example.com/v1", "m", -1.0, 0.0) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(AiState.NO_URL, ai.state())

        ai.setCompatible("https://openrouter.ai/api/v1/", " vendor/test-model ", 0.5, 1.5)
        assertEquals("over https it needs its key", AiState.NO_KEY, ai.state())
        ai.saveKey("router-test-key")
        assertEquals(AiState.READY, ai.state())

        app.estimator.next = { request -> FakeEstimator.reply(AiStatus.OK, estimate(item()), attempts = listOf(TokenUsage(request.model, 2_000, 1_000, rates = request.target.rates))) }
        val result = ai.estimate("dal", Origin.HOME) as EstimateResult.Estimated
        val sent = app.estimator.requests.single()
        assertEquals("vendor/test-model", sent.model)
        assertEquals("https://openrouter.ai/api/v1", sent.target.baseUrl!!.base)
        assertEquals("router-test-key", sent.target.apiKey)
        val row = ai.request(result.requestId)!!
        assertEquals(AiProviderId.COMPATIBLE, row.provider)
        assertEquals((2_000 * 0.5 + 1_000 * 1.5) / 1_000_000, row.costUsd, 1e-12)

        // A stored URL that no longer passes the rules (an edited backup) reads as not set up: nothing is sent.
        app.prefs.setAiCompatible("http://example.com/v1", "m", 0.0, 0.0)
        assertEquals(AiState.NO_URL, ai.state())
        assertEquals(EstimateResult.NotReady(AiState.NO_URL), ai.estimate("dal", Origin.HOME))
        assertEquals(1, app.estimator.requests.size)
    }

    @Test
    fun `a server over plain http is never given the key, and a free one costs nothing`() = runBlocking {
        ai.setEnabled(true)
        ai.setProvider(AiProviderId.COMPATIBLE)
        // A key saved earlier, for another server.
        ai.setCompatible("https://openrouter.ai/api/v1", "m", 0.0, 0.0)
        ai.saveKey("router-test-key")
        ai.setCompatible("http://localhost:11434/v1", "local-model", 0.0, 0.0)
        assertEquals(AiState.READY, ai.state())
        app.estimator.next = { request -> FakeEstimator.reply(AiStatus.OK, estimate(item()), attempts = listOf(TokenUsage(request.model, 2_000, 1_000, rates = request.target.rates))) }
        val result = ai.estimate("dal", Origin.HOME) as EstimateResult.Estimated
        assertNull("no key over http", app.estimator.requests.single().target.apiKey)
        assertEquals(0.0, ai.request(result.requestId)!!.costUsd, 0.0)
        assertEquals(0.0, ai.spend.first().costUsd, 0.0)

        // With no key stored at all it's still ready: it needs none.
        ai.removeKey()
        assertEquals(AiState.READY, ai.state())
        assertEquals(AiState.READY, ai.gate.first().state)

        // This phone needs no internet and no local-network permission.
        app.connectivity.online.value = false
        app.localNetwork.allowed.value = false
        assertEquals(AiState.READY, ai.state())
    }

    @Test
    fun `a refused local-network permission has its own state, and nothing is sent`() = runBlocking {
        ai.setEnabled(true)
        ai.setProvider(AiProviderId.COMPATIBLE)
        ai.setCompatible("http://192.168.1.20:11434/v1", "local-model", 0.0, 0.0)
        assertEquals(AiState.READY, ai.state())

        app.localNetwork.allowed.value = false
        assertEquals(AiState.NO_LOCAL_NETWORK, ai.state())
        assertEquals(AiState.NO_LOCAL_NETWORK, ai.gate.first().state)
        assertEquals(EstimateResult.NotReady(AiState.NO_LOCAL_NETWORK), ai.estimate("dal", Origin.HOME))
        assertTrue(app.estimator.requests.isEmpty())
        assertEquals(0, requests())

        // A server on your own network needs no internet, only the permission.
        app.localNetwork.allowed.value = true
        app.connectivity.online.value = false
        assertEquals(AiState.READY, ai.state())

        // The permission is about the local network only: the same refusal doesn't stop a server on the internet.
        app.connectivity.online.value = true
        app.localNetwork.allowed.value = false
        ai.setCompatible("https://openrouter.ai/api/v1", "m", 0.0, 0.0)
        ai.saveKey("router-test-key")
        assertEquals(AiState.READY, ai.state())
        ai.setProvider(AiProviderId.ANTHROPIC)
        ai.saveKey("test-key")
        assertEquals(AiState.READY, ai.state())
        // An https address on the local network still needs it.
        ai.setProvider(AiProviderId.COMPATIBLE)
        ai.setCompatible("https://10.0.0.5/v1", "m", 0.0, 0.0)
        assertEquals("a new server has no key yet", AiState.NO_KEY, ai.state())
        ai.saveKey("lan-test-key")
        assertEquals(AiState.NO_LOCAL_NETWORK, ai.state())
    }

    @Test
    fun `the privacy notice is shown once per provider, and again for another server`() = runBlocking {
        ai.markNoticeSeen()
        assertTrue(ai.settings.first().noticeSeen(AiKind.MEAL_TEXT))
        assertFalse("another kind of request has its own notice", ai.settings.first().noticeSeen(AiKind.COACH))

        ai.setProvider(AiProviderId.OPENAI)
        assertFalse("changing provider shows it again", ai.settings.first().noticeSeen(AiKind.MEAL_TEXT))
        ai.markNoticeSeen()
        assertTrue(ai.settings.first().noticeSeen(AiKind.MEAL_TEXT))
        ai.setProvider(AiProviderId.ANTHROPIC)
        assertTrue("once", ai.settings.first().noticeSeen(AiKind.MEAL_TEXT))

        ai.setProvider(AiProviderId.COMPATIBLE)
        ai.setCompatible("https://openrouter.ai/api/v1", "m", 0.0, 0.0)
        assertFalse(ai.settings.first().noticeSeen(AiKind.MEAL_TEXT))
        ai.markNoticeSeen()
        assertTrue(ai.settings.first().noticeSeen(AiKind.MEAL_TEXT))
        ai.setCompatible("http://192.168.1.20:11434/v1", "m", 0.0, 0.0)
        assertFalse("another server hasn't been told about", ai.settings.first().noticeSeen(AiKind.MEAL_TEXT))
        ai.setCompatible("https://openrouter.ai/api/v2", "other", 1.0, 1.0)
        assertTrue("the same server, whatever the path or model", ai.settings.first().noticeSeen(AiKind.MEAL_TEXT))
    }

    @Test
    fun `an install from before providers keeps its key, model and the notice it saw`() = runBlocking {
        // What M3c to M6 stored: the one notice flag, Anthropic's model under ai_model, and the key under its old name.
        app.store.edit {
            it[androidx.datastore.preferences.core.booleanPreferencesKey("ai_notice_seen")] = true
            it[androidx.datastore.preferences.core.stringPreferencesKey("ai_model")] = "claude-haiku-5-5"
            it[androidx.datastore.preferences.core.stringPreferencesKey("ai_mode")] = "ON"
        }
        assertEquals("the key's entry keeps its name, so an upgrade still finds it", "anthropic_api_key", AiKeyStore.name(AiProviderId.ANTHROPIC))
        ai.saveKey("test-key")
        val s = ai.settings.first()
        assertEquals(AiProviderId.ANTHROPIC, s.provider)
        assertEquals(AiModel.HAIKU_5_5, s.model)
        assertTrue(s.noticeSeen(AiKind.MEAL_TEXT))
        assertFalse(s.copy(provider = AiProviderId.GEMINI).noticeSeen(AiKind.MEAL_TEXT))
        assertEquals(AiState.READY, ai.state())
        // The other providers start on their defaults.
        assertEquals(AiModel.GPT_6_1_SOL, s.models[AiProviderId.OPENAI])
        assertEquals(AiModel.GEMINI_4_HYDROGEN, s.models[AiProviderId.GEMINI])
    }

    @Test
    fun `a compatible server's key is only ever sent to the server it was entered for`() = runBlocking {
        ai.setEnabled(true)
        ai.setProvider(AiProviderId.COMPATIBLE)
        // No server yet: there is nothing to keep a key for.
        assertTrue(runCatching { ai.saveKey("router-test-key") }.isFailure)
        ai.setCompatible("https://openrouter.ai/api/v1", "m", 0.0, 0.0)
        ai.saveKey("router-test-key")
        ai.markNoticeSeen()
        assertEquals(AiState.READY, ai.state())
        assertTrue(ai.hasKey.first())

        // You edit the address to try another service: the first server's key doesn't go with it.
        ai.setCompatible("https://another-host.example/v1", "m", 0.0, 0.0)
        assertEquals(AiState.NO_KEY, ai.state())
        assertEquals(AiState.NO_KEY, ai.gate.first().state)
        assertFalse(ai.hasKey.first())
        assertFalse(AiProviderId.COMPATIBLE in ai.storedKeys.first())
        assertEquals(EstimateResult.NotReady(AiState.NO_KEY), ai.estimate("dal", Origin.HOME))
        assertTrue(app.estimator.requests.isEmpty())
        assertNull(app.keys.key(AiProviderId.COMPATIBLE, AiUrl.ok("https://another-host.example/v1")))
        // The same host on another port or scheme is another server too.
        for (other in listOf("https://openrouter.ai:8443/api/v1", "http://openrouter.ai.local/v1")) assertNull(other, app.keys.key(AiProviderId.COMPATIBLE, AiUrl.ok(other)))
        assertNull("never without saying which server", app.keys.key(AiProviderId.COMPATIBLE))

        // Back on the first server (any path on it), the key is there again.
        ai.setCompatible("https://openrouter.ai/api/v2/", "other", 0.0, 0.0)
        assertEquals(AiState.READY, ai.state())
        app.estimator.next = { FakeEstimator.reply(AiStatus.OK, estimate(item())) }
        ai.estimate("dal", Origin.HOME)
        assertEquals("router-test-key", app.estimator.requests.single().target.apiKey)

        // A key entered for the new server replaces the old one, and is then that server's alone.
        ai.setCompatible("https://another-host.example/v1", "m", 0.0, 0.0)
        ai.saveKey("another-test-key")
        assertEquals(AiState.READY, ai.state())
        ai.setCompatible("https://openrouter.ai/api/v1", "m", 0.0, 0.0)
        assertEquals(AiState.NO_KEY, ai.state())

        // Editing the stored server name doesn't move the key: the ciphertext is bound to the server it was entered for.
        app.secrets.edit { it[androidx.datastore.preferences.core.stringPreferencesKey("compatible_api_key_server")] = "https://openrouter.ai" }
        assertNull(app.keys.key(AiProviderId.COMPATIBLE, AiUrl.ok("https://openrouter.ai/api/v1")))
        assertEquals(AiState.NO_KEY, ai.state())
        // Removing the key removes what it was for.
        ai.removeKey()
        assertEquals(null, app.keys.stored.first().compatibleServer)
    }

    @Test
    fun `imported settings that name another server don't get this phone's key`() = runBlocking {
        ai.setEnabled(true)
        ai.setProvider(AiProviderId.COMPATIBLE)
        ai.setCompatible("https://openrouter.ai/api/v1", "m", 0.0, 0.0)
        ai.saveKey("router-test-key")
        assertEquals(AiState.READY, ai.state())

        // A backup from another install, or an edited one: the provider, another https host, and its notice already seen.
        app.prefs.replaceAll(
            androidx.datastore.preferences.core.mutablePreferencesOf(
                androidx.datastore.preferences.core.stringPreferencesKey("ai_mode") to "ON",
                androidx.datastore.preferences.core.stringPreferencesKey("ai_provider") to "COMPATIBLE",
                androidx.datastore.preferences.core.stringPreferencesKey("ai_base_url") to "https://elsewhere.example/v1",
                androidx.datastore.preferences.core.stringPreferencesKey("ai_model_compatible") to "m",
                androidx.datastore.preferences.core.stringSetPreferencesKey("ai_notices_seen") to setOf("COMPATIBLE/elsewhere.example:MEAL_TEXT"),
            ),
        )
        val s = ai.settings.first()
        assertEquals("elsewhere.example", s.url!!.host)
        assertTrue("its notice reads as seen, so nothing else would stop a send", s.noticeSeen(AiKind.MEAL_TEXT))
        assertEquals(AiState.NO_KEY, ai.state())
        assertEquals(EstimateResult.NotReady(AiState.NO_KEY), ai.estimate("dal", Origin.HOME))
        assertTrue(app.estimator.requests.isEmpty())
        assertEquals(0, requests())
        // The key itself is untouched, for the server it belongs to.
        assertEquals("router-test-key", app.keys.key(AiProviderId.COMPATIBLE, AiUrl.ok("https://openrouter.ai/api/v1")))
    }

    @Test
    fun `a server whose name leads to the local network needs the permission too`() = runBlocking {
        ai.setEnabled(true)
        ai.setProvider(AiProviderId.COMPATIBLE)
        app.resolver.hosts["box.tailnet.example"] = listOf("100.101.102.103")
        app.resolver.hosts["ai.home.example"] = listOf("2001:db8::1", "192.168.1.20")
        app.resolver.hosts["openrouter.ai"] = listOf("104.18.2.115")

        // With the permission in hand (or before Android 17) nothing is looked up.
        assertFalse(ai.setCompatible("https://box.tailnet.example/v1", "m", 0.0, 0.0))
        ai.saveKey("tailnet-test-key")
        assertEquals(AiState.READY, ai.state())
        assertTrue(app.resolver.lookups.isEmpty())

        // Refused: the name is looked up, found on the local network, and the gate says so instead of timing out.
        app.localNetwork.allowed.value = false
        assertEquals(AiState.NO_LOCAL_NETWORK, ai.state())
        assertEquals(AiState.NO_LOCAL_NETWORK, ai.gate.first().state)
        assertEquals(EstimateResult.NotReady(AiState.NO_LOCAL_NETWORK), ai.estimate("dal", Origin.HOME))
        assertTrue(app.estimator.requests.isEmpty())
        assertEquals(listOf("box.tailnet.example"), app.resolver.lookups.distinct())

        // Saving such a server says it's local, so Settings can ask for the permission. Any local address counts.
        assertTrue(ai.setCompatible("https://ai.home.example/v1", "m", 0.0, 0.0))
        ai.saveKey("home-test-key")
        assertEquals(AiState.NO_LOCAL_NETWORK, ai.state())
        app.localNetwork.allowed.value = true
        assertEquals(AiState.READY, ai.state())

        // A public name, and one that doesn't resolve, aren't held back; an address or a .local name isn't looked up.
        app.localNetwork.allowed.value = false
        app.resolver.lookups.clear()
        assertFalse(ai.setCompatible("https://openrouter.ai/api/v1", "m", 0.0, 0.0))
        ai.saveKey("router-test-key")
        assertEquals(AiState.READY, ai.state())
        assertFalse(ai.setCompatible("https://unknown.example/v1", "m", 0.0, 0.0))
        assertTrue(ai.setCompatible("http://desktop.local:11434/v1", "m", 0.0, 0.0))
        assertTrue(ai.setCompatible("http://192.168.1.20:11434/v1", "m", 0.0, 0.0))
        assertFalse(ai.setCompatible("http://localhost:11434/v1", "m", 0.0, 0.0))
        assertEquals(listOf("openrouter.ai", "unknown.example"), app.resolver.lookups.distinct())
    }

    @Test
    fun `a request is priced at the rates of the day it's made`() = runBlocking {
        ready()
        ai.saveKey("gemini-test-key", AiProviderId.GEMINI)
        ai.setProvider(AiProviderId.GEMINI)
        ai.setModel(AiModel.GEMINI_3_8_FLASH)
        app.estimator.next = { FakeEstimator.reply(AiStatus.OK, estimate(item()), attempts = listOf(TokenUsage("gemini-3.8-flash", 1_000_000, 1_000_000))) }
        // 18 October 2026: the price before the announced change.
        val before = ai.estimate("dal", Origin.HOME) as EstimateResult.Estimated
        assertEquals(0.75 + 3.75, ai.request(before.requestId)!!.costUsd, 1e-9)
        // 2 January 2027: doubled, with no new build.
        app.clock.instant = Instant.parse("2027-01-02T06:30:00Z")
        val after = ai.estimate("dal", Origin.HOME) as EstimateResult.Estimated
        assertEquals(1.50 + 7.50, ai.request(after.requestId)!!.costUsd, 1e-9)
    }
}
