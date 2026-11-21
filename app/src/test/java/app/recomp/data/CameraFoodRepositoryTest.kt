package app.recomp.data

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import app.recomp.data.ai.AiResult
import app.recomp.data.food.LabelReader
import app.recomp.data.food.OpenFoodFactsClient
import app.recomp.data.food.PhotoStore
import app.recomp.data.repo.BarcodeLookup
import app.recomp.data.repo.CameraFoodRepository
import app.recomp.data.repo.CustomFood
import app.recomp.data.repo.OffSearch
import app.recomp.domain.ai.AiImage
import app.recomp.domain.ai.AiStatus
import app.recomp.domain.food.FoodDiet
import app.recomp.domain.food.FoodSource
import app.recomp.domain.food.LabelBasis
import app.recomp.domain.food.LabelField
import app.recomp.domain.food.Origin
import app.recomp.domain.food.Per100g
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

/** NU-19 and NU-21 through the repository: barcodes, Open Food Facts search and label scans. Invented products and labels. */
@RunWith(RobolectricTestRunner::class)
class CameraFoodRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: TestApp
    private val server = MockWebServer()
    private var ocrText = ""
    private lateinit var repo: CameraFoodRepository
    private val bitmap: Bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
    private val image = AiImage("image/jpeg", byteArrayOf(1))

    @Before
    fun setUp() {
        app = TestApp(File(tmp.root, "app").apply { mkdirs() })
        server.start()
        repo = CameraFoodRepository(
            app.foods, app.ai, OpenFoodFactsClient("Recomp/test", server.url("/").toString()), LabelReader { ocrText },
            PhotoStore(ApplicationProvider.getApplicationContext()), app.db.aiDao(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        app.close()
    }

    private fun product(code: String = "4006381333931") = MockResponse().setHeader("Content-Type", "application/json").setBody(
        """{"status":1,"product":{"code":"$code","product_name":"Oat crunch","brands":"Testbrand",
        "nutriments":{"energy-kcal_100g":402,"proteins_100g":10,"carbohydrates_100g":64},"ingredients_analysis_tags":["en:vegan"]}}""",
    )

    @Test
    fun `a barcode you already have opens your food, without asking Open Food Facts`() = runBlocking {
        val id = app.foods.createCustom(
            CustomFood("Oat crunch", "Testbrand", Per100g(402.0, 10.0, 64.0, 9.5), FoodDiet.VEGAN, null, FoodSource.OFF, "4006381333931"),
        )
        assertEquals(id, (repo.barcode("4006381333931") as BarcodeLookup.Yours).food.id)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an unknown barcode asks Open Food Facts once, and a misread is never sent`() = runBlocking {
        server.enqueue(product())
        val found = repo.barcode("4006381333931") as BarcodeLookup.Found
        assertEquals("Oat crunch", found.prefill.name)
        assertEquals(FoodSource.OFF, found.prefill.source)
        assertEquals(FoodDiet.VEGAN, found.prefill.diet)
        assertEquals(setOf(LabelField.KCAL, LabelField.PROTEIN, LabelField.CARBS), found.prefill.filled)
        assertTrue(found.prefill.note!!.contains("fill in the blanks")) // no fat value
        // The session remembers it.
        assertTrue(repo.barcode("4006381333931") is BarcodeLookup.Found)
        assertEquals(1, server.requestCount)
        assertEquals(BarcodeLookup.Invalid, repo.barcode("4006381333932"))
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"status":0}"""))
        assertEquals(BarcodeLookup.NotFound, repo.barcode("96385074"))
    }

    @Test
    fun `saving a product makes it your food, and saving it again keeps one row`() = runBlocking {
        server.enqueue(product())
        val p = (repo.barcode("4006381333931") as BarcodeLookup.Found).prefill
        val food = CustomFood(p.name, p.brand, Per100g(402.0, 10.0, 64.0, 9.5), FoodDiet.VEGAN, null, p.source, p.barcode)
        val id = app.foods.createCustom(food)
        assertEquals(id, app.foods.createCustom(food.copy(per100g = Per100g(400.0, 10.0, 64.0, 9.5))))
        val row = app.foods.food(id)!!
        assertEquals(FoodSource.OFF, row.source)
        assertEquals("off:4006381333931", row.sourceKey)
        assertEquals(400.0, row.kcal100g, 0.0)
        assertEquals(food.copy(per100g = Per100g(400.0, 10.0, 64.0, 9.5)), app.foods.editable(id))
    }

    @Test
    fun `a search is asked once per session`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"products":[{"code":"1","product_name":"Oat bar"}]}"""))
        assertEquals(listOf("Oat bar"), (repo.search("Oat  bar") as OffSearch.Results).products.map { it.name })
        assertEquals(listOf("Oat bar"), (repo.search("oat bar") as OffSearch.Results).products.map { it.name })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `with AI off a label is read on the phone, and the form is told to check it`() = runBlocking {
        ocrText = "Nutrient Per 100 g\nEnergy (kcal) 456\nProtein (g) 12.5\nCarbohydrate (g) 61\nTotal Fat (g) 17.9"
        val scan = repo.scanLabel(bitmap, image, "Choco bites")
        assertFalse(scan.aiUsed)
        assertEquals("Choco bites", scan.prefill.name)
        assertEquals(FoodSource.LABEL_OCR, scan.prefill.source)
        assertEquals(456.0, scan.prefill.values.kcal)
        assertTrue(scan.prefill.note!!.startsWith("Read on the phone"))
        assertTrue(app.sender.calls.isEmpty())
    }

    @Test
    fun `with AI on its reading replaces the phone's where it found a value`() = runBlocking {
        app.ai.setEnabled(true)
        app.ai.saveKey("test-key")
        ocrText = "Nutrient Per 100 g\nEnergy (kcal) 45\nProtein (g) 12.5\nTotal Fat (g) 17.9"
        app.sender.next = { call ->
            val json = """{"name":"Choco bites","brand":"Testbrand","kcal_100g":456,"protein_100g":12.5,"carbs_100g":61,"fat_100g":-1,"fiber_100g":-1,"serving_g":30,"diet":"vegetarian"}"""
            AiResult(AiStatus.OK, Json.parseToJsonElement(json) as JsonObject, json, call.target.model, emptyList(), 10)
        }
        val scan = repo.scanLabel(bitmap, image, "")
        assertTrue(scan.aiUsed)
        val v = scan.prefill.values
        assertEquals(LabelBasis.PER_100G, v.basis)
        assertEquals(listOf(456.0, 12.5, 61.0, 17.9, 30.0), listOf(v.kcal, v.proteinG, v.carbsG, v.fatG, v.servingG))
        assertNull(v.fiberG)
        assertEquals("Choco bites", scan.prefill.name)
        assertEquals(FoodDiet.VEGETARIAN, scan.prefill.diet)
        assertTrue(app.sender.calls.single().user.contains("Energy (kcal) 45"))
    }

    @Test
    fun `when AI fails the phone's reading stands and says so`() = runBlocking {
        app.ai.setEnabled(true)
        app.ai.saveKey("test-key")
        ocrText = "Per 100 g\nEnergy (kcal) 456"
        app.sender.next = { call -> AiResult(AiStatus.TIMEOUT, null, null, call.target.model, emptyList(), 10) }
        val scan = repo.scanLabel(bitmap, image, "Choco bites")
        assertFalse(scan.aiUsed)
        assertEquals(456.0, scan.prefill.values.kcal)
        assertTrue(scan.prefill.note!!.startsWith("AI couldn't check this reading"))
    }

    @Test
    fun `kept meal photos no draft points at are swept after a day`() = runBlocking {
        val store = PhotoStore(ApplicationProvider.getApplicationContext())
        val kept = store.keep(bitmap)
        val orphan = store.keep(bitmap)
        app.ai.keepDraft(java.time.LocalDate.of(2026, 11, 21), "lunch", null, "", Origin.HOME, null, kept)
        store.sweep(setOf(kept), now = System.currentTimeMillis() + PhotoStore.GRACE_MS + 1)
        assertTrue(File(kept).isFile)
        assertFalse(File(orphan).isFile)
        store.delete(kept)
        assertFalse(File(kept).isFile)
    }
}
