package app.recomp.data

import androidx.lifecycle.SavedStateHandle
import app.recomp.domain.ai.AiStatus
import app.recomp.domain.ai.Confidence
import app.recomp.domain.ai.EstimatedItem
import app.recomp.domain.ai.MealEstimate
import app.recomp.domain.food.FoodSource
import app.recomp.domain.food.LogSource
import app.recomp.domain.food.Origin
import app.recomp.domain.persona.Diet
import app.recomp.domain.persona.FoodRegion
import app.recomp.ui.food.DescribePhase
import app.recomp.ui.food.DescribeViewModel
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Describe it's screen logic (PRD §7.7, AI-02): a failure keeps the description as a draft, *Cancel* keeps nothing,
 * *Save draft* when AI isn't ready, and *Estimate now* on a draft that then saves. Claude is a fake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DescribeFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: TestApp
    private val date = LocalDate.of(2026, 10, 18)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = TestApp(File(tmp.root, "app").apply { mkdirs() })
        runBlocking {
            app.prefs.updatePersona { it.copy(diet = Diet.LACTO_VEGETARIAN, region = FoodRegion.INDIAN) }
            app.ai.setEnabled(true)
            app.ai.saveKey("test-key")
            app.ai.markNoticeSeen()
        }
    }

    @After
    fun tearDown() {
        app.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(draftId: Long = 0) = DescribeViewModel(
        SavedStateHandle(mapOf("date" to date.toString(), "slot" to "lunch", "draftId" to draftId)),
        app.ai,
        app.foodLog,
        app.foods,
    )

    private val phulka = EstimatedItem("Phulka", 2.0, "piece", 70.0, 210.0, 6.5, 42.0, 1.5, 4.0, Confidence.HIGH, "no ghee")

    private suspend fun drafts() = app.ai.observeDrafts(date).first()

    private suspend fun requests() = app.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM ai_request_log").use { it.moveToFirst(); it.getInt(0) }

    private suspend fun <T> eventually(block: suspend () -> T?): T = withTimeout(5_000) {
        var value = block()
        while (value == null) {
            kotlinx.coroutines.delay(10)
            value = block()
        }
        value
    }

    @Test
    fun `a failed estimate keeps the description as a draft in its meal, with the reason`() = runBlocking {
        listOf(AiStatus.TIMEOUT, AiStatus.RATE_LIMIT).forEach { status ->
            app.estimator.next = { FakeEstimator.reply(status, attempts = emptyList()) }
            val vm = viewModel()
            vm.setText("2 phulka and dal")
            vm.setOrigin(Origin.ORDERED)
            vm.estimate()
            val outcome = eventually { vm.outcome.value }
            assertTrue(outcome.message, outcome.message.contains("Kept as a draft in Lunch"))
            assertTrue(outcome.undoIds.isEmpty())
            val draft = drafts().last()
            assertEquals("2 phulka and dal", draft.text)
            assertEquals(Origin.ORDERED, draft.origin)
            assertEquals(status, draft.reason)
        }
        assertEquals(2, drafts().size)
    }

    @Test
    fun `an estimate that throws still keeps the description as a draft`() = runBlocking {
        app.estimator.next = { throw IllegalStateException("boom") }
        val vm = viewModel()
        vm.setText("2 phulka")
        vm.estimate()
        eventually { vm.outcome.value }
        assertEquals(AiStatus.UNEXPECTED, drafts().single().reason)
    }

    @Test
    fun `Cancel goes back to the text and keeps nothing, but the request is still logged`() = runBlocking {
        val hold = CompletableDeferred<Unit>()
        app.estimator.hold = hold
        app.estimator.next = { FakeEstimator.reply(AiStatus.TIMEOUT, attempts = emptyList()) }
        val vm = viewModel()
        vm.setText("dal")
        vm.estimate()
        assertEquals(DescribePhase.Estimating, vm.phase.value)
        vm.cancel()
        assertEquals(DescribePhase.Input, vm.phase.value)
        assertEquals("dal", vm.text.value)
        hold.complete(Unit)
        eventually { if (requests() == 1) true else null }
        assertTrue(drafts().isEmpty())
        assertNull(vm.outcome.value)
        assertEquals(DescribePhase.Input, vm.phase.value)
    }

    @Test
    fun `when AI isn't ready, Save draft keeps the description and nothing is sent`() = runBlocking {
        app.connectivity.online.value = false
        val vm = viewModel()
        vm.setText("chai and biscuits")
        vm.estimate()
        eventually { if (vm.phase.value == DescribePhase.Input) true else null }
        vm.saveDraft()
        val outcome = eventually { vm.outcome.value }
        assertTrue(outcome.message.contains("Kept as a draft"))
        assertNull(drafts().single().reason)
        assertTrue(app.estimator.requests.isEmpty())
    }

    @Test
    fun `Estimate now on a draft reviews, edits and saves it, and the draft is done`() = runBlocking {
        val draftId = app.ai.saveDraft(date, "lunch", "2 phulka", Origin.HOME, AiStatus.NETWORK)
        app.estimator.next = { FakeEstimator.reply(AiStatus.OK, MealEstimate(listOf(phulka), null)) }
        val vm = viewModel(draftId)
        eventually { if (vm.autoStart.value) true else null }
        assertEquals("2 phulka", vm.text.value)
        vm.autoStarted()
        vm.estimate()
        val review = eventually { vm.phase.value as? DescribePhase.Review }
        assertEquals(listOf("Phulka"), review.items.map { it.current.name })

        vm.update(0, review.items[0].current.scaledTo(80.0))
        assertTrue((vm.phase.value as DescribePhase.Review).items[0].edited)
        vm.save(null)
        val outcome = eventually { vm.outcome.value }
        assertEquals(1, outcome.undoIds.size)
        assertTrue(outcome.message, outcome.message.startsWith("Added 1 item · 240 kcal"))

        val row = app.db.foodLogDao().get(outcome.undoIds).single()
        assertEquals(LogSource.AI_TEXT, row.source)
        assertEquals(review.requestId, row.aiRequestId)
        assertEquals(FoodSource.AI, app.db.foodDao().get(row.foodId!!)!!.source)
        assertTrue("the draft is done", drafts().isEmpty())
    }

    @Test
    fun `estimating a draft that fails again replaces it rather than adding another`() = runBlocking {
        val draftId = app.ai.saveDraft(date, "lunch", "2 phulka", Origin.HOME, AiStatus.NETWORK)
        app.estimator.next = { FakeEstimator.reply(AiStatus.SERVER, attempts = emptyList()) }
        val vm = viewModel(draftId)
        eventually { if (vm.autoStart.value) true else null }
        vm.setText("2 phulka, dal")
        vm.estimate()
        eventually { vm.outcome.value }
        val draft = drafts().single()
        assertEquals("2 phulka, dal", draft.text)
        assertEquals(AiStatus.SERVER, draft.reason)
    }
}
