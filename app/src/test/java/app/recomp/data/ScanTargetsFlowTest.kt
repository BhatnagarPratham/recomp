package app.recomp.data

import app.recomp.data.body.ScanImages
import app.recomp.data.repo.ExtraDraft
import app.recomp.data.repo.OnboardingResult
import app.recomp.data.repo.ScanInput
import app.recomp.data.db.WeeklyCheckinEntity
import app.recomp.domain.model.SessionKind
import app.recomp.domain.nutrition.BmrFormula
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.NutritionCalculator
import app.recomp.domain.nutrition.Pace
import app.recomp.domain.nutrition.Sex
import app.recomp.domain.persona.FinishLine
import app.recomp.domain.persona.GoalPlan
import app.recomp.domain.persona.Presets
import app.recomp.domain.persona.Profile
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
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

/** IB-03, IB-05, TRD §10 M15: scan-informed targets end to end, and a shared image kept and deleted with its scan. Invented data. */
@RunWith(RobolectricTestRunner::class)
class ScanTargetsFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: TestApp
    private val personas get() = app.personas

    @Before
    fun setUp() {
        app = TestApp(File(tmp.root, "app").apply { mkdirs() })
    }

    @After
    fun tearDown() = app.close()

    private suspend fun today() = app.days.today()

    private fun at(day: LocalDate, hour: Int = 7): Long = day.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** TRD §5.3 vector B's plan: vector A's body (30 today, 175 cm, 95 kg, goal 78 kg) at the Aggressive recomp pace. */
    private suspend fun onboardVectorB() {
        val today = today()
        val profile = Profile(Sex.MALE, today.minusYears(30), heightCm = 175.0, weightKg = 95.0)
        val plan = GoalPlan.of(Goal.RECOMP, Pace.AGGRESSIVE, 78.0, FinishLine.GoalWeight, today).copy(aggressiveAcknowledged = true)
        val yoga = ExtraDraft(null, "Yoga class", SessionKind.YOGA, setOf(6))
        app.personas.completeOnboarding(OnboardingResult(Presets.REFERENCE.draft.copy(goal = plan), Presets.REFERENCE.key, listOf(yoga), profile, LocalTime.of(4, 0)))
    }

    /** An on-protocol scan [daysAgo] days back: 95 kg with [fatG] of fat (66 kg of lean mass by default). */
    private suspend fun scan(daysAgo: Long, fatG: Int = 29_000, mealMins: Int = 180, image: String? = null): Long {
        val day = today().minusDays(daysAgo)
        return app.body.saveScan(ScanInput(at(day), 95_000, 33_000, fatG, fatG / 950.0, 8.0, minsSinceMeal = mealMins, workoutBefore = false, imagePath = image)).id
    }

    @Test
    fun `golden vector B end to end - a recent scan's lean mass sets BMR, and saving takes the targets`() = runBlocking {
        onboardVectorB()
        // Onboarding had no scan: Mifflin-St Jeor, as vector A's BMR.
        val before = app.db.nutritionDao().targetOn(today())!!
        assertEquals(1899, before.bmrKcal)
        assertNull(personas.scanOffer.first())

        scan(daysAgo = 2)
        assertEquals(66.0, personas.leanMassKg()!!, 1e-9)
        // Targets don't change on their own...
        assertEquals(before, app.db.nutritionDao().targetOn(today()))
        // ...but Body offers them, since BMR moves by more than 20 kcal.
        val offer = personas.scanOffer.first()!!
        assertEquals(1899, offer.targetBmrKcal)
        assertEquals(1796, offer.scanBmrKcal)

        val preview = personas.preview()
        val b = preview.after
        assertEquals(BmrFormula.KATCH_MCARDLE, b.bmrFormula)
        assertEquals(1796, b.bmrKcal.roundToInt())
        assertEquals(2469, b.tdeeKcal.roundToInt())
        assertEquals(-617, b.energyAdjustmentKcal.roundToInt())
        assertEquals(listOf(1850, 125, 51, 222), listOf(b.kcal, b.proteinG, b.fatG, b.carbsG))

        personas.save()
        val saved = app.db.nutritionDao().targetOn(today())!!
        assertEquals(listOf(1850, 125, 51, 222, 1796), listOf(saved.kcal, saved.proteinG, saved.fatG, saved.carbsG, saved.bmrKcal))
        assertNull(personas.scanOffer.first())
    }

    @Test
    fun `only an on-protocol scan from the last 60 days counts`() = runBlocking {
        onboardVectorB()
        // Off protocol: eaten an hour before.
        scan(daysAgo = 1, mealMins = 60)
        assertNull(personas.leanMassKg())
        assertEquals(BmrFormula.MIFFLIN_ST_JEOR, personas.preview().after.bmrFormula)
        // 61 days old: too old.
        scan(daysAgo = 61, fatG = 28_000)
        assertNull(personas.leanMassKg())
        // 60 days: counts.
        scan(daysAgo = 60, fatG = 28_500)
        assertEquals(66.5, personas.leanMassKg()!!, 1e-9)
    }

    @Test
    fun `Not now hides the offer until a newer scan`() = runBlocking {
        onboardVectorB()
        scan(daysAgo = 3)
        val offer = personas.scanOffer.first()!!
        personas.dismissScanOffer(offer.measuredAt)
        assertNull(personas.scanOffer.first())
        scan(daysAgo = 1, fatG = 27_000)
        assertEquals(NutritionCalculator.katchMcArdle(68.0).roundToInt(), personas.scanOffer.first()!!.scanBmrKcal)
    }

    @Test
    fun `with a check-in estimate, it sets calories and the scan's BMR sets the floor`() = runBlocking {
        onboardVectorB()
        scan(daysAgo = 2)
        app.db.checkinDao().upsert(
            WeeklyCheckinEntity(today().minusWeeks(1), computedAt = 1, windowDays = 21, loggedDays = 20, weighIns = 20, tdeeObs = 2_300, tdeePrev = 2_611, tdeeNew = 2_300),
        )
        val after = personas.preview().after
        assertEquals(2300.0, after.tdeeKcal, 0.0)
        assertTrue(after.adaptiveTdee)
        assertEquals(1796, after.bmrKcal.roundToInt())
        assertEquals(NutritionCalculator.floor(Sex.MALE, after.bmrKcal).let { Math.ceil(it / 10).toInt() * 10 }, after.floorKcal)
        // The formula line the check-in shows ("the formula said …") is the scan's BMR × activity.
        assertEquals((NutritionCalculator.katchMcArdle(66.0) * 1.375).roundToInt(), (after.formulaTdeeKcal).roundToInt())
    }

    @Test
    fun `a shared image is kept with its scan and deleted with it`() = runBlocking {
        onboardVectorB()
        val name = app.scanImages.stage(ByteArrayInputStream(ByteArray(2_048) { 7 }), "image/png")!!
        assertNotNull(app.scanImages.file(name))
        val id = scan(daysAgo = 1, image = name)
        assertEquals(name, app.body.scan(id)!!.imagePath)
        // An edit without an image keeps it.
        app.body.saveScan(ScanInput(at(today().minusDays(1)), 95_000, 33_200, 29_000, 30.5, 8.0, minsSinceMeal = 180, workoutBefore = false), id)
        assertEquals(name, app.body.scan(id)!!.imagePath)
        app.body.deleteScan(id)
        assertNull(app.scanImages.file(name))
    }

    @Test
    fun `a share you back out of is cleaned up after a day, and only images the app keeps are staged`() = runBlocking {
        onboardVectorB()
        val kept = app.scanImages.stage(ByteArrayInputStream(ByteArray(100) { 1 }), "image/jpeg")!!
        scan(daysAgo = 1, image = kept)
        val stray = app.scanImages.stage(ByteArrayInputStream(ByteArray(100) { 2 }), "image/webp")!!
        app.body.cleanImages(now = System.currentTimeMillis())
        assertNotNull("a fresh stray waits a day", app.scanImages.file(stray))
        app.body.cleanImages(now = System.currentTimeMillis() + 25 * 3_600_000L)
        assertNull(app.scanImages.file(stray))
        assertNotNull(app.scanImages.file(kept))
        // Not an image it keeps, empty, or over 20 MB: nothing is kept.
        assertNull(app.scanImages.stage(ByteArrayInputStream(ByteArray(10)), "image/gif"))
        assertNull(app.scanImages.stage(ByteArrayInputStream(ByteArray(0)), "image/png"))
        val big = object : java.io.InputStream() {
            var left = ScanImages.MAX_BYTES + 1
            override fun read(): Int = if (left-- > 0) 0 else -1
        }
        assertNull(app.scanImages.stage(big, "image/jpeg"))
        // A name that isn't one of ours never resolves.
        assertNull(app.scanImages.file("../recomp.db"))
        assertFalse(File(tmp.root, "app/scan_images").listFiles().orEmpty().any { it.length() == 0L })
    }
}
