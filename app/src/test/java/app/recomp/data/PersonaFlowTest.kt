package app.recomp.data

import app.recomp.data.db.CheckinDecision
import app.recomp.data.db.TargetReason
import app.recomp.data.db.WeeklyCheckinEntity
import app.recomp.data.repo.ExtraDraft
import app.recomp.data.repo.MaintenanceEstimate
import app.recomp.data.repo.OnboardingResult
import app.recomp.data.repo.TrainingSetup
import app.recomp.domain.model.SessionKind
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.MainNumber
import app.recomp.domain.nutrition.Pace
import app.recomp.domain.nutrition.ProteinGoal
import app.recomp.domain.nutrition.RampState
import app.recomp.domain.nutrition.Sex
import app.recomp.domain.nutrition.TargetOverrides
import app.recomp.domain.persona.Diet
import app.recomp.domain.persona.Experience
import app.recomp.domain.persona.FinishLine
import app.recomp.domain.persona.GoalPlan
import app.recomp.domain.persona.GuardrailNote
import app.recomp.domain.persona.PersonaDraft
import app.recomp.domain.persona.Presets
import app.recomp.domain.persona.Profile
import app.recomp.domain.persona.SplitTemplate
import java.io.File
import java.time.DayOfWeek
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

/** Onboarding, Settings saves, target versions, the deficit flag and Today's numbers (PS-01, PS-02, PR-01 to PR-03). */
@RunWith(RobolectricTestRunner::class)
class PersonaFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: TestApp
    private val personas get() = app.personas
    private val dao get() = app.db.nutritionDao()
    private val dayMs = 86_400_000L

    @Before
    fun setUp() {
        app = TestApp(File(tmp.root, "app").apply { mkdirs() })
    }

    @After
    fun tearDown() = app.close()

    private suspend fun today() = app.days.today()

    /** TRD §5.3 vector A's inputs (illustrative): 30 today, so the targets match the golden vector. */
    private suspend fun vectorA() = Profile(Sex.MALE, today().minusYears(30), heightCm = 175.0, weightKg = 95.0)

    private suspend fun recomp() = GoalPlan.of(Goal.RECOMP, Pace.STANDARD, 78.0, FinishLine.GoalWeight, today())

    private val yoga = ExtraDraft(null, "Yoga class", SessionKind.YOGA, setOf(6))

    private suspend fun onboard(
        draft: PersonaDraft = Presets.REFERENCE.draft,
        plan: GoalPlan? = null,
        extras: List<ExtraDraft> = listOf(yoga),
        profile: Profile? = null,
    ) = personas.completeOnboarding(
        OnboardingResult(draft.copy(goal = plan ?: recomp()), Presets.REFERENCE.key, extras, profile ?: vectorA(), LocalTime.of(4, 0)),
    )

    @Test
    fun `fresh onboarding builds the split, writes the first target and completes the persona`() = runBlocking {
        assertFalse(personas.onboarded.first())
        onboard()

        assertTrue(personas.onboarded.first())
        assertEquals(listOf("Push", "Pull", "Legs"), app.training.observeRotation().first().map { it.name })
        val row = dao.all().single()
        assertEquals(today(), row.effectiveFrom)
        assertEquals(TargetReason.INITIAL, row.reason)
        assertEquals(Goal.RECOMP, row.goal)
        assertEquals(listOf(2090, 125, 58, 267, 2611, 1899), listOf(row.kcal, row.proteinG, row.fatG, row.carbsG, row.tdeeEstimate, row.bmrKcal))

        val persona = personas.persona.first()!!
        assertEquals(Diet.LACTO_VEGETARIAN, persona.diet)
        assertEquals(78.0, persona.goal.goalWeightKg)
        assertEquals("Yoga class", persona.extras.single().name)
        assertEquals(setOf(DayOfWeek.SATURDAY), persona.extras.single().days)
        // Lacto-vegetarian: the ramp is on and its baseline counts from today.
        assertTrue(persona.proteinRamp)
        assertEquals(RampState(today()), app.prefs.ramp.first())
    }

    @Test
    fun `an upgraded install keeps its routines and only re-applies rep ranges`() = runBlocking {
        // What M1's interim setup left behind.
        app.training.applySetup(TrainingSetup(SplitTemplate.PPL, Experience.BEGINNER, 3, listOf(yoga), emptyMap(), emptyMap()))
        val routineIds = app.training.observeRotation().first().map { it.id }
        val yogaId = app.training.observeExtras().first().single().id
        assertFalse(personas.onboarded.first())

        val draft = Presets.REFERENCE.draft.copy(experience = Experience.INTERMEDIATE)
        onboard(draft, extras = listOf(yoga.copy(routineId = yogaId)))

        assertEquals(routineIds, app.training.observeRotation().first().map { it.id })
        assertEquals(yogaId, app.training.observeExtras().first().single().id)
        val bench = app.training.observeLibrary().first().first { it.name == "Bench press" }
        assertEquals(6 to 10, bench.defaultRepMin to bench.defaultRepMax)
    }

    @Test
    fun `a settings change versions the targets from today and the same day replaces it`() = runBlocking {
        onboard()
        val now = System.currentTimeMillis()
        personas.save(profile = vectorA().copy(weightKg = 93.0), now = now)
        val sameDay = dao.all().single()
        assertEquals(TargetReason.MANUAL, sameDay.reason)
        assertTrue(sameDay.kcal < 2090)

        personas.save(profile = vectorA().copy(weightKg = 91.0), now = now + dayMs)
        val rows = dao.all()
        assertEquals(2, rows.size)
        // The earlier version is never rewritten.
        assertEquals(sameDay, rows[0])
        assertEquals(today().plusDays(1), rows[1].effectiveFrom)
        assertEquals(91.0, personas.profile.first()!!.weightKg, 0.0)
    }

    @Test
    fun `a change that leaves the numbers alone writes no version`() = runBlocking {
        onboard()
        personas.save(plan = recomp())
        assertEquals(TargetReason.INITIAL, dao.all().single().reason)
    }

    @Test
    fun `overrides replace the computed numbers until reset and carbs fill the rest`() = runBlocking {
        onboard()
        personas.save(overrides = TargetOverrides(proteinG = 150))
        val row = dao.all().single()
        assertEquals(150 to 242, row.proteinG to row.carbsG)
        assertEquals(TargetOverrides(proteinG = 150), personas.overrides.first())
        // A later body-data change keeps the override.
        personas.save(profile = vectorA().copy(weightKg = 94.0))
        assertEquals(150, dao.all().single().proteinG)

        personas.save(overrides = TargetOverrides())
        assertEquals(125, dao.all().single().proteinG)
        assertTrue(personas.overrides.first().isEmpty)
    }

    @Test
    fun `a weight below BMI 18_5 switches a deficit goal to maintain, and the preview says why`() = runBlocking {
        onboard()
        val low = vectorA().copy(weightKg = 55.0)
        val preview = personas.preview(profile = low)
        assertEquals(listOf(GuardrailNote.LOW_BMI_MAINTAIN), preview.notes)
        assertEquals(Goal.MAINTAIN, preview.plan.goal)
        assertTrue(preview.changes)

        personas.save(profile = low)
        assertEquals(Goal.MAINTAIN, personas.persona.first()!!.goal.goal)
        assertEquals(Goal.MAINTAIN, dao.all().single().goal)
    }

    @Test
    fun `in a deficit follows the target in force`() = runBlocking {
        assertFalse(app.progression.inDeficit())
        onboard()
        assertTrue(app.progression.inDeficit())
        personas.save(plan = GoalPlan.of(Goal.MAINTAIN, Pace.STANDARD, null, null, today()))
        assertFalse(app.progression.inDeficit())
    }

    @Test
    fun `day starts at moves the log date of new entries`() = runBlocking {
        val at530 = LocalDate.now().atTime(5, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(LocalDate.now(), app.workouts.today(now = at530))
        personas.setDayStart(LocalTime.of(6, 0))
        assertEquals(LocalTime.of(6, 0), personas.dayStart.first())
        assertEquals(LocalDate.now().minusDays(1), app.workouts.today(now = at530))
    }

    @Test
    fun `today follows the goal with nothing logged yet`() = runBlocking {
        assertNull(app.nutrition.observeToday().first())
        onboard()
        val recompDay = app.nutrition.observeToday().first()!!
        assertEquals(ProteinGoal.NotStarted(125), recompDay.protein)
        assertEquals(MainNumber.ProteinLeft(125, 125, 0), recompDay.main)
        assertNull(recompDay.week)

        personas.setProteinRamp(false)
        assertEquals(ProteinGoal.Full(125), app.nutrition.observeToday().first()!!.protein)

        personas.save(plan = GoalPlan.of(Goal.MAINTAIN, Pace.STANDARD, null, null, today()))
        val maintain = app.nutrition.observeToday().first()!!.main as MainNumber.CaloriesInRange
        assertEquals(2480..2740, maintain.band)
    }

    @Test
    fun `turning the ramp back on starts a fresh baseline`() = runBlocking {
        onboard(Presets.REFERENCE.draft.copy(diet = Diet.NON_VEGETARIAN))
        assertNull(app.prefs.ramp.first())
        assertFalse(personas.persona.first()!!.proteinRamp)

        personas.setProteinRamp(true)
        assertEquals(RampState(today()), app.prefs.ramp.first())
        // Nothing is logged before M3b, so there's no baseline to lock.
        app.nutrition.lockRampBaseline()
        assertEquals(RampState(today()), app.prefs.ramp.first())
        assertNotNull(app.nutrition.observeToday().first())
    }

    @Test
    fun `once a check-in has an estimate, Settings' targets use it as maintenance, whatever was decided`() = runBlocking {
        onboard()
        assertNull(personas.maintenance())
        assertFalse(personas.preview(profile = vectorA().copy(weightKg = 93.0)).after.adaptiveTdee)

        // A kept week with an estimate, then a week without enough data, which carries it forward.
        val week = today().minusWeeks(2).with(DayOfWeek.MONDAY)
        val checkins = app.db.checkinDao()
        checkins.upsert(
            WeeklyCheckinEntity(
                weekStart = week, computedAt = 1, windowDays = 21, loggedDays = 20, weighIns = 21, kcalMean = 2100.0,
                tdeeObs = 2300, tdeePrev = 2611, tdeeNew = 2461, decision = CheckinDecision.KEPT, decidedAt = 2,
            ),
        )
        checkins.upsert(WeeklyCheckinEntity(weekStart = week.plusWeeks(1), computedAt = 3, windowDays = 21, loggedDays = 9, weighIns = 12, tdeePrev = 2461, tdeeNew = 2461))
        assertEquals(MaintenanceEstimate(2461, week), personas.maintenance())
        assertEquals(MaintenanceEstimate(2461, week), personas.maintenance.first())

        // 93 kg: BMR 1,879 stays the formula's, and the floor with it; TDEE is the estimate.
        val preview = personas.preview(profile = vectorA().copy(weightKg = 93.0))
        assertEquals(MaintenanceEstimate(2461, week), preview.maintenance)
        assertTrue(preview.after.adaptiveTdee)
        assertEquals(2461.0, preview.after.tdeeKcal, 0.0)
        assertEquals(1879, preview.after.bmrKcal.roundToInt())
        assertEquals(2583, preview.after.formulaTdeeKcal.roundToInt())
        // 2,461 − 511.5 for 0.5% of 93 kg a week.
        assertEquals(1950, preview.after.kcal)

        personas.save(profile = vectorA().copy(weightKg = 93.0))
        val row = dao.targetOn(today())!!
        assertEquals(TargetReason.MANUAL, row.reason)
        assertEquals(1950, row.kcal)
        assertEquals(2461, row.tdeeEstimate)
        assertEquals(1879, row.bmrKcal)
        // The display uses it too, and the formula stays the default elsewhere.
        val p = app.prefs.profile.first()!!
        val plan = app.prefs.currentPersona().goal!!.toPlan()
        assertEquals(1950, personas.targetsFor(p, plan, TargetOverrides(), today(), personas.maintenance()).kcal)
        assertEquals(2583, personas.targetsFor(p, plan, TargetOverrides(), today()).tdeeKcal.roundToInt())
    }
}
