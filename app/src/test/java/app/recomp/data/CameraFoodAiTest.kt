package app.recomp.data

import app.recomp.data.ai.AiResult
import app.recomp.data.repo.LabelRead
import app.recomp.domain.ai.AiImage
import app.recomp.domain.ai.AiKind
import app.recomp.domain.ai.AiStatus
import app.recomp.domain.ai.Confidence
import app.recomp.domain.ai.EstimatedItem
import app.recomp.domain.ai.LabelEstimate
import app.recomp.domain.ai.MealEstimate
import app.recomp.domain.food.FoodDiet
import app.recomp.domain.food.Origin
import app.recomp.data.repo.EstimateResult
import app.recomp.data.repo.DraftPlan
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** NU-19's AI label reading and NU-20's meal photos, through the repository with fake providers. Invented values. */
@RunWith(RobolectricTestRunner::class)
class CameraFoodAiTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: TestApp
    private val date = LocalDate.of(2026, 11, 21)
    private val photo = AiImage("image/jpeg", byteArrayOf(1, 2, 3))

    @Before
    fun setUp() {
        app = TestApp(File(tmp.root, "app").apply { mkdirs() })
        runBlocking {
            app.ai.setEnabled(true)
            app.ai.saveKey("test-key")
        }
    }

    @After
    fun tearDown() = app.close()

    private fun reply(json: String) = { call: app.recomp.data.ai.AiCall ->
        AiResult(AiStatus.OK, Json.parseToJsonElement(json) as JsonObject, json, call.target.model, emptyList(), 10)
    }

    @Test
    fun `a label read sends the photo and the recogniser's text, and its values come back checked`() = runBlocking {
        app.sender.next = reply(
            """{"name":"Oat crunch","brand":"","kcal_100g":402,"protein_100g":10,"carbs_100g":-1,"fat_100g":140,
            "fiber_100g":7.8,"serving_g":30,"diet":"vegan"}""",
        )
        val read = app.ai.readLabel(photo, "Energy (kcal) 402") as LabelRead.Read
        assertEquals(LabelEstimate("Oat crunch", null, 402.0, 10.0, null, null, 7.8, 30.0, FoodDiet.VEGAN), read.estimate)
        val call = app.sender.calls.single()
        assertEquals("nutrition_label", call.schema.name)
        assertEquals(listOf(photo), call.images)
        assertTrue(call.user.contains("Energy (kcal) 402"))
        // No persona: a label is the same for everyone.
        assertTrue(!call.system.contains("lacto"))
        val row = app.ai.request(read.requestId)!!
        assertEquals(AiKind.LABEL, row.kind)
        assertEquals(AiStatus.OK, row.status)
    }

    @Test
    fun `a label reply that can't be used is logged as invalid`() = runBlocking {
        app.sender.next = reply("""{"items":[]}""")
        val failed = app.ai.readLabel(photo, "") as LabelRead.Failed
        assertEquals(AiStatus.INVALID, failed.status)
        assertEquals(AiStatus.INVALID, app.ai.request(failed.requestId!!)!!.status)
    }

    @Test
    fun `with AI off a label is read on the phone only`() = runBlocking {
        app.ai.setEnabled(false)
        assertTrue(app.ai.readLabel(photo, "Energy (kcal) 402") is LabelRead.NotReady)
        assertTrue(app.sender.calls.isEmpty())
    }

    @Test
    fun `a meal photo goes with the estimate, logged as a photo request`() = runBlocking {
        app.estimator.next = { FakeEstimator.reply(AiStatus.OK, MealEstimate(listOf(EstimatedItem("Dal", 1.0, "katori", 150.0, 170.0, 9.0, 22.0, 5.0, 4.0, Confidence.HIGH, "home style")), null)) }
        val result = app.ai.estimate("", Origin.HOME, photo = photo) as EstimateResult.Estimated
        val request = app.estimator.requests.single()
        assertEquals(listOf(photo), request.images)
        assertTrue(request.user.contains("Photo: the attached photo shows the meal"))
        assertEquals(AiKind.MEAL_PHOTO, app.ai.request(result.requestId)!!.kind)
    }

    @Test
    fun `a photo estimate that fails keeps a draft with its photo, words or not`() = runBlocking {
        app.estimator.next = { FakeEstimator.reply(AiStatus.TIMEOUT) }
        val plan = DraftPlan(date, "lunch", replacing = null)
        val failed = app.ai.estimate("", Origin.HOME, draft = plan, photo = photo, photoPath = "/data/meal_photos/a.jpg") as EstimateResult.Failed
        val draft = app.ai.draft(failed.draftId!!)!!
        assertEquals("", draft.text)
        assertEquals("/data/meal_photos/a.jpg", draft.photoPath)
        assertEquals(listOf(draft), app.ai.observeDrafts(date).first())
    }
}
