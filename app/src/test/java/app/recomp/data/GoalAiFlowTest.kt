package app.recomp.data

import app.recomp.data.ai.ProviderSender
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.recomp.data.ai.GoalSchema
import app.recomp.data.repo.GoalReading
import app.recomp.data.repo.OnboardingResult
import app.recomp.domain.ai.AiKind
import app.recomp.domain.ai.AiModel
import app.recomp.domain.ai.AiProviderId
import app.recomp.domain.ai.AiState
import app.recomp.domain.ai.AiStatus
import app.recomp.domain.ai.Clarification
import app.recomp.domain.ai.GoalInterpretation
import app.recomp.domain.ai.GoalPrompt
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.Pace
import app.recomp.domain.nutrition.Sex
import app.recomp.domain.persona.Diet
import app.recomp.domain.persona.Experience
import app.recomp.domain.persona.FoodRegion
import app.recomp.domain.persona.GoalPlan
import app.recomp.domain.persona.Intent
import app.recomp.domain.persona.Presets
import app.recomp.domain.persona.Profile
import app.recomp.ui.food.AiText
import app.recomp.ui.onboarding.GoalText
import app.recomp.ui.onboarding.OnboardingUi
import app.recomp.ui.onboarding.OnboardingViewModel
import app.recomp.ui.onboarding.Step
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
 * AI goal interpretation (GL-03, TRD §5.7) against a local server standing in for the provider: the request
 * carries only the typed text, the reply is an intent and at most one follow-up question, and any failure falls
 * back to the three questions. No real key or network; the body data is invented.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GoalAiFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: TestApp
    private lateinit var server: MockWebServer
    private val key = "test-key-0451"

    /** Invented body data with numbers that would stand out in a request. */
    private val profile = Profile(Sex.FEMALE, LocalDate.of(1993, 7, 9), heightCm = 163.5, weightKg = 71.25)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = TestApp(File(tmp.root, "app").apply { mkdirs() })
        server = MockWebServer().apply { start(java.net.InetAddress.getByName("127.0.0.1"), 0) }
        app.openAi.baseUrl = "http://127.0.0.1:${server.port}/v1"
        app.openAi.timeout = Duration.ofSeconds(2)
        app.sender.through = ProviderSender(app.providers)
        runBlocking {
            app.ai.setProvider(AiProviderId.OPENAI)
            app.ai.setEnabled(true)
            app.ai.saveKey(key)
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
        app.close()
        Dispatchers.resetMain()
    }

    private fun reply(intent: String, question: String = "") = chat("""{"intent":"$intent","follow_up_question":${Json.encodeToString(question)}}""")

    private fun chat(content: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(
        """{"id":"c1","object":"chat.completion","model":"${AiModel.GPT_6_1_SOL.id}","choices":[{"index":0,"message":{"role":"assistant","content":${Json.encodeToString(content)},"refusal":null},"finish_reason":"stop"}],"usage":{"prompt_tokens":300,"completion_tokens":40,"total_tokens":340}}""",
    )

    private fun error(code: Int) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setHeader("retry-after", "0")
        .setBody("""{"error":{"message":"no","type":"server_error","param":null,"code":null}}""")

    private fun take(): JsonObject = Json.parseToJsonElement(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject

    private fun messages(body: JsonObject) = body.getValue("messages").jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content to it.jsonObject.getValue("content").jsonPrimitive.content }

    private suspend fun log() = app.db.openHelper.readableDatabase.query("SELECT kind, status, provider, cost_usd FROM ai_request_log ORDER BY id").use { c ->
        buildList { while (c.moveToNext()) add(listOf(c.getString(0), c.getString(1), c.getString(2), c.getDouble(3).toString())) }
    }

    private suspend fun onboard() {
        val plan = GoalPlan.of(Goal.MAINTAIN, Pace.STANDARD, null, null, app.days.today())
        app.personas.completeOnboarding(OnboardingResult(Presets.LEAN_BULK.draft.copy(goal = plan, diet = Diet.EGGETARIAN, region = FoodRegion.INDIAN), Presets.LEAN_BULK.key, emptyList(), profile, LocalTime.of(4, 0)))
    }

    // The request and its reply

    @Test
    fun `the request carries the typed text, the schema and nothing about the body`() = runBlocking {
        onboard()
        server.enqueue(reply("both"))
        val reading = app.ai.interpretGoal("  I want to look leaner but keep my strength  ")
        assertEquals(GoalReading.Read(GoalInterpretation(Intent.BOTH, null)), reading)

        val body = take()
        assertEquals(1, server.requestCount)
        val messages = messages(body)
        assertEquals(2, messages.size)
        assertEquals(GoalPrompt.SYSTEM, messages[0].second)
        assertEquals("user" to "Goal, in their words: I want to look leaner but keep my strength", messages[1])
        // Nothing of the profile, the targets or the persona is anywhere in the request.
        val sent = body.toString()
        listOf("163.5", "71.25", "1993", "FEMALE", "female", "EGGETARIAN", "Eggetarian", "INDIAN", "kcal").forEach { assertFalse(it, sent.contains(it)) }
        val schema = body.getValue("response_format").jsonObject.getValue("json_schema").jsonObject
        assertEquals("goal_intent", schema.getValue("name").jsonPrimitive.content)
        assertEquals(GoalSchema.schema.element, schema.getValue("schema").jsonObject)
        assertEquals(setOf("intent", "follow_up_question"), schema.getValue("schema").jsonObject.getValue("properties").jsonObject.keys)

        // Logged and costed as its own kind, with the provider it went to.
        val row = log().single()
        assertEquals(listOf("GOAL", "OK", "OPENAI"), row.take(3))
        assertTrue(row[3].toDouble() > 0)
        assertFalse(app.db.aiDao().request(1)!!.responseJson.orEmpty().contains(key))
    }

    @Test
    fun `at most one follow-up question, sent back with its answer`() = runBlocking {
        server.enqueue(reply("unclear", "  Leaner, bigger,\n or both? "))
        val first = app.ai.interpretGoal("look better")
        assertEquals(GoalReading.Read(GoalInterpretation(null, "Leaner, bigger, or both?")), first)
        take()

        // The second reply asks again: there's no second question, and its intent stands.
        server.enqueue(reply("lose_fat", "By when?"))
        val second = app.ai.interpretGoal("look better", Clarification("Leaner, bigger, or both?", "leaner"))
        assertEquals(GoalReading.Read(GoalInterpretation(Intent.LOSE_FAT, null)), second)
        assertEquals("Goal, in their words: look better\nYou asked: Leaner, bigger, or both?\nThey answered: leaner", messages(take())[1].second)

        // Still unclear after the answer: no intent and no question, which the flow turns into the three questions.
        server.enqueue(reply("unclear", "What do you mean?"))
        assertEquals(GoalReading.Read(GoalInterpretation(null, null)), app.ai.interpretGoal("hmm", Clarification("Leaner, bigger, or both?", "dunno")))
        assertEquals(listOf("OK", "OK", "OK"), log().map { it[1] })
    }

    @Test
    fun `every failure comes back as one, still logged`() = runBlocking {
        // A server that keeps failing: one retry, then the status.
        server.enqueue(error(500))
        server.enqueue(error(500))
        assertEquals(GoalReading.Failed(AiStatus.SERVER), app.ai.interpretGoal("get stronger"))
        assertEquals(2, server.requestCount)
        // A rejected key.
        server.enqueue(error(401))
        assertEquals(GoalReading.Failed(AiStatus.AUTH), app.ai.interpretGoal("get stronger"))
        // A goal instead of an intent, an answer that isn't JSON, and a reply with no intent at all.
        server.enqueue(reply("recomp"))
        assertEquals(GoalReading.Failed(AiStatus.INVALID), app.ai.interpretGoal("get stronger"))
        server.enqueue(chat("You should do a recomp."))
        assertEquals(GoalReading.Failed(AiStatus.INVALID), app.ai.interpretGoal("get stronger"))
        server.enqueue(chat("""{"follow_up_question":"Why?"}"""))
        assertEquals(GoalReading.Failed(AiStatus.INVALID), app.ai.interpretGoal("get stronger"))
        // No answer in time.
        server.enqueue(reply("both").setBodyDelay(5, TimeUnit.SECONDS))
        assertEquals(GoalReading.Failed(AiStatus.TIMEOUT), app.ai.interpretGoal("get stronger"))
        assertEquals(listOf("SERVER", "AUTH", "INVALID", "INVALID", "INVALID", "TIMEOUT"), log().map { it[1] })
        assertTrue(log().all { it[0] == "GOAL" })
    }

    @Test
    fun `nothing is sent unless the gate is ready`() = runBlocking {
        app.connectivity.online.value = false
        assertEquals(GoalReading.NotReady(AiState.OFFLINE), app.ai.interpretGoal("get stronger"))
        app.connectivity.online.value = true
        app.ai.setEnabled(false)
        assertEquals(GoalReading.NotReady(AiState.OFF), app.ai.interpretGoal("get stronger"))
        app.ai.setEnabled(true)
        app.ai.removeKey()
        assertEquals(GoalReading.NotReady(AiState.NO_KEY), app.ai.interpretGoal("get stronger"))
        app.ai.saveKey(key)
        app.ai.setMonthlyCap(0.0)
        assertEquals(GoalReading.NotReady(AiState.OVER_CAP), app.ai.interpretGoal("get stronger"))
        assertEquals(0, server.requestCount)
        assertTrue(log().isEmpty())
    }

    @Test
    fun `the goal description has its own first-use notice, per provider`() = runBlocking {
        app.ai.markNoticeSeen(AiKind.MEAL_TEXT)
        assertFalse(app.ai.settings.first().noticeSeen(AiKind.GOAL))
        app.ai.markNoticeSeen(AiKind.GOAL)
        assertTrue(app.ai.settings.first().noticeSeen(AiKind.GOAL))
        app.ai.setProvider(AiProviderId.GEMINI)
        assertFalse(app.ai.settings.first().noticeSeen(AiKind.GOAL))
        // The notice says what goes and what doesn't, and to whom.
        val text = AiText.goalPrivacy(AiProviderId.OPENAI)
        assertTrue(text.contains("the text you type to OpenAI"))
        assertTrue(text.contains("not your name, body data, targets or history"))
        assertTrue(AiText.goalPrivacy(AiProviderId.COMPATIBLE, "ai.example.org").contains("the server at ai.example.org"))
    }

    // The goal flow (PRD §4.6)

    private fun flow(goalOnly: Boolean) = OnboardingViewModel(SavedStateHandle(mapOf("goalOnly" to goalOnly)), app.personas, app.training, app.nutrition, FilePresetSource, app.ai)

    private suspend fun OnboardingViewModel.until(done: (OnboardingUi) -> Boolean): OnboardingUi = withTimeout(10_000) {
        while (!done(ui.value)) kotlinx.coroutines.delay(10)
        ui.value
    }

    @Test
    fun `from Settings, the intent goes through the same rules as the questions, after the notice`() = runBlocking {
        onboard()
        val vm = flow(goalOnly = true)
        val ready = vm.until { it.loaded && it.aiReady }
        assertEquals(Step.GOAL, ready.step)

        // The first description to this provider shows the notice, and nothing is sent until it's confirmed.
        vm.setDescription("lose the belly and get some muscle")
        vm.describeGoal()
        val asked = vm.until { it.askGoalNotice }
        assertTrue(asked.goalNotice.contains("OpenAI"))
        assertEquals(0, server.requestCount)
        vm.dismissGoalNotice()
        assertEquals(0, server.requestCount)

        server.enqueue(reply("both"))
        vm.describeGoal()
        vm.until { it.askGoalNotice }
        vm.confirmGoalNotice()
        val suggested = vm.until { it.step == Step.SUGGESTION }
        // Both, and an intermediate lifter at this BMI (26.7, under 27): a lean bulk, by the rules, not by the AI.
        assertEquals(Intent.BOTH, suggested.goal.intent)
        assertEquals(Experience.INTERMEDIATE, suggested.goal.liftingAge)
        assertEquals(Goal.LEAN_BULK, suggested.goal.advice!!.goal)
        assertEquals(GoalText.aiRead(Intent.BOTH), suggested.goal.aiNote)
        // Back shows the questions with the intent filled in, to change if it's wrong.
        assertTrue(vm.back())
        assertEquals(Step.QUESTIONS, vm.ui.value.step)

        // The notice isn't shown again.
        assertTrue(vm.back())
        server.enqueue(reply("maintain"))
        vm.describeGoal()
        assertEquals(Goal.MAINTAIN, vm.until { it.step == Step.SUGGESTION && it.goal.intent == Intent.MAINTAIN }.goal.advice!!.goal)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `a follow-up question is asked once, with an answer box`() = runBlocking {
        onboard()
        app.ai.markNoticeSeen(AiKind.GOAL)
        val vm = flow(goalOnly = true)
        vm.until { it.loaded && it.aiReady }
        vm.setDescription("look better")
        server.enqueue(reply("unclear", "Leaner, bigger, or both?"))
        vm.describeGoal()
        val asked = vm.until { it.goal.aiQuestion != null }
        assertEquals("Leaner, bigger, or both?", asked.goal.aiQuestion)
        assertEquals(Step.GOAL, asked.step)
        // Nothing goes without an answer.
        vm.describeGoal()
        assertEquals(1, server.requestCount)

        vm.setFollowUpAnswer("bigger")
        server.enqueue(reply("build_muscle"))
        vm.describeGoal()
        val suggested = vm.until { it.step == Step.SUGGESTION }
        assertEquals(Goal.LEAN_BULK, suggested.goal.advice!!.goal)
        assertEquals(2, server.requestCount)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `any failure falls back to the three questions, saying why`() = runBlocking {
        onboard()
        app.ai.markNoticeSeen(AiKind.GOAL)
        val vm = flow(goalOnly = true)
        vm.until { it.loaded && it.aiReady }
        vm.setDescription("get in shape")

        // The provider is down.
        server.enqueue(error(500))
        server.enqueue(error(500))
        vm.describeGoal()
        val failed = vm.until { it.step == Step.QUESTIONS }
        assertTrue(failed.goal.notSure)
        assertNull(failed.goal.intent)
        assertEquals(GoalText.aiFailed(AiText.reason(AiStatus.SERVER, AiProviderId.OPENAI)), failed.goal.aiNote)
        assertFalse(failed.aiBusy)
        assertFalse(failed.canContinue)
        // The questions work as they always did.
        vm.answer { it.copy(intent = Intent.LOSE_FAT) }
        vm.next()
        assertEquals(Goal.FAT_LOSS, vm.until { it.step == Step.SUGGESTION }.goal.advice!!.goal)

        // A reply that names no intent, and one that isn't an intent at all.
        for ((response, note) in listOf(reply("unclear") to GoalText.AI_UNCLEAR, reply("cut") to GoalText.aiFailed(AiText.reason(AiStatus.INVALID, AiProviderId.OPENAI)))) {
            vm.back()
            vm.back()
            assertEquals(Step.GOAL, vm.ui.value.step)
            server.enqueue(response)
            vm.describeGoal()
            assertEquals(note, vm.until { it.step == Step.QUESTIONS && it.goal.aiNote == note }.goal.aiNote)
        }

        // AI stops being ready between the card and the tap.
        vm.back()
        app.connectivity.online.value = false
        val before = server.requestCount
        vm.describeGoal()
        assertEquals(GoalText.AI_NOT_READY, vm.until { it.step == Step.QUESTIONS && it.goal.aiNote == GoalText.AI_NOT_READY }.goal.aiNote)
        assertEquals(before, server.requestCount)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `in onboarding the lifting question is still asked, and with AI off there's no text box`() = runBlocking {
        app.ai.markNoticeSeen(AiKind.GOAL)
        val vm = flow(goalOnly = false)
        vm.until { it.loaded && it.aiReady }
        assertEquals(5, vm.ui.value.presets.size)
        vm.pickPreset("custom")
        vm.updateAbout { it.copy(sex = profile.sex, birthDate = profile.birthDate, height = "163.5", weight = "71.25") }
        vm.next()
        vm.next()
        assertEquals(Step.GOAL, vm.ui.value.step)

        vm.setDescription("lose fat")
        server.enqueue(reply("lose_fat"))
        vm.describeGoal()
        val questions = vm.until { it.step == Step.QUESTIONS }
        assertEquals(Intent.LOSE_FAT, questions.goal.intent)
        assertNull(questions.goal.liftingAge)
        vm.answer { it.copy(liftingAge = Experience.BEGINNER) }
        vm.next()
        assertEquals(Goal.FAT_LOSS, vm.until { it.step == Step.SUGGESTION }.goal.advice!!.goal)

        app.ai.setEnabled(false)
        assertFalse(vm.until { !it.aiReady }.aiReady)
        vm.viewModelScope.cancel()
    }
}
