package app.recomp.data

import app.recomp.data.db.WorkoutSessionEntity
import app.recomp.data.repo.BodyRepository
import app.recomp.data.repo.OnboardingResult
import app.recomp.data.repo.ScanInput
import app.recomp.data.repo.SegmentalLean
import app.recomp.domain.body.ProtocolIssue
import app.recomp.domain.body.ScanSlot
import app.recomp.domain.body.SinceMeal
import app.recomp.domain.model.SessionKind
import app.recomp.domain.model.SessionStatus
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.Pace
import app.recomp.domain.nutrition.Sex
import app.recomp.domain.persona.FinishLine
import app.recomp.domain.persona.GoalPlan
import app.recomp.domain.persona.Presets
import app.recomp.domain.persona.Profile
import app.recomp.domain.persona.WeightSource
import app.recomp.domain.trend.TrendCalculator
import app.recomp.domain.trend.WeightPace
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

/** Weigh-ins and the trend, waist, scans with the conditions check, and the trend weight in targets (BM-01, BM-02, IB-01, IB-02). */
@RunWith(RobolectricTestRunner::class)
class BodyFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: TestApp
    private val body get() = app.body

    /** A Monday well in the past, so every timestamp is a complete day under the 4 AM day start. */
    private val monday: LocalDate = LocalDate.of(2026, 9, 7).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    @Before
    fun setUp() {
        app = TestApp(File(tmp.root, "app").apply { mkdirs() })
    }

    @After
    fun tearDown() = app.close()

    private fun at(day: Long, time: String): Long =
        LocalDateTime.of(monday.plusDays(day), LocalTime.parse(time)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Invented body data (TRD §5.3 vector A's inputs), recomp at the standard pace. */
    private suspend fun onboard() {
        val today = app.days.today()
        val profile = Profile(Sex.MALE, today.minusYears(30), heightCm = 175.0, weightKg = 95.0)
        val plan = GoalPlan.of(Goal.RECOMP, Pace.STANDARD, 78.0, FinishLine.GoalWeight, today)
        app.personas.completeOnboarding(OnboardingResult(Presets.REFERENCE.draft.copy(goal = plan), Presets.REFERENCE.key, emptyList(), profile, LocalTime.of(4, 0)))
    }

    private fun scan(at: Long, weightG: Int = 80_000, sinceMeal: SinceMeal = SinceMeal.OVER_3H, workout: Boolean = false) = ScanInput(
        measuredAt = at,
        weightG = weightG,
        skeletalMuscleG = 33_000,
        bodyFatG = 20_000,
        bodyFatPct = 25.0,
        visceralFatLevel = 9.0,
        minsSinceMeal = sinceMeal.minutes,
        workoutBefore = workout,
    )

    @Test
    fun `weigh-ins keep their source, and the trend uses the primary source's first of each day`() = runBlocking {
        onboard()
        body.logWeighIn(80_000, WeightSource.HOME_SCALE, at(0, "07:00"))
        body.logWeighIn(82_500, WeightSource.GYM_SCALE, at(1, "18:00"))
        val evening = body.logWeighIn(79_000, WeightSource.HOME_SCALE, at(1, "21:00"))
        val morning = body.logWeighIn(81_000, WeightSource.HOME_SCALE, at(1, "07:30"))

        val trend = body.observeTrend().first()
        assertEquals(WeightSource.HOME_SCALE, trend.primary)
        assertEquals(4, trend.weighIns.size)
        assertEquals(listOf(monday, monday.plusDays(1)), trend.points.map { it.date })
        assertEquals(80.0 + TrendCalculator.alpha(1) * 1.0, trend.latest!!.trendKg, 1e-9)
        assertEquals(80.1, trend.trendKg)
        assertTrue(morning in trend.countedIds)
        assertFalse(evening in trend.countedIds)
        assertEquals(2, trend.countedIds.size)

        // Switching the primary source recomputes from that source alone.
        app.personas.setWeightSource(WeightSource.GYM_SCALE)
        assertEquals(82.5, body.observeTrend().first().trendKg)
        assertEquals(82.5, body.trendKg())
    }

    @Test
    fun `the weekly rate appears once the trend spans a week, next to the goal's pace`() = runBlocking {
        onboard()
        body.logWeighIn(80_000, WeightSource.HOME_SCALE, at(0, "07:00"))
        body.logWeighIn(79_600, WeightSource.HOME_SCALE, at(6, "07:00"))
        val early = body.observeTrend().first()
        assertNull(early.rate)
        assertEquals(WeightPace(-0.5, -0.5), early.pace)

        body.logWeighIn(79_400, WeightSource.HOME_SCALE, at(7, "07:00"))
        val rate = body.observeTrend().first().rate!!
        assertTrue(rate.kgPerWeek < 0)
        assertEquals(monday.plusDays(7), rate.asOf)
    }

    @Test
    fun `editing, deleting and undoing a weigh-in`() = runBlocking {
        onboard()
        val id = body.logWeighIn(80_000, WeightSource.HOME_SCALE, at(0, "07:00"))
        assertEquals(80_000, body.lastWeightG(WeightSource.HOME_SCALE))
        // The gym scale has none yet, so its sheet prefills from the last of any source.
        assertEquals(80_000, body.lastWeightG(WeightSource.GYM_SCALE))

        body.updateWeighIn(id, 80_400, WeightSource.GYM_SCALE, at(1, "07:00"))
        val edited = app.db.bodyDao().weighIn(id)!!
        assertEquals(80_400, edited.weightG)
        assertEquals(WeightSource.GYM_SCALE, edited.source)
        assertEquals(monday.plusDays(1), edited.logDate)

        val deleted = body.deleteWeighIn(id)!!
        assertNull(body.trendKg())
        body.restoreWeighIn(deleted)
        assertEquals(edited, app.db.bodyDao().weighIn(id))
    }

    @Test
    fun `the trend weight replaces the typed weight in targets, but only when targets are recomputed`() = runBlocking {
        onboard()
        assertEquals(2090, app.nutrition.targetOn(app.days.today())!!.kcal)
        assertEquals(95.0, app.personas.currentProfile()!!.weightKg, 0.0)

        body.logWeighIn(90_000, WeightSource.HOME_SCALE, at(0, "07:00"))
        // Weigh-ins never write a target version on their own.
        assertEquals(1, app.db.nutritionDao().all().size)
        assertEquals(90.0, app.personas.currentProfile()!!.weightKg, 0.0)

        val preview = app.personas.preview()
        assertEquals(90.0, preview.profile.weightKg, 0.0)
        assertTrue(preview.changes)
        assertTrue(preview.after.kcal < 2090)
        // Protein follows the goal weight, which hasn't moved.
        assertEquals(125, preview.after.proteinG)

        app.personas.save()
        assertEquals(preview.after.kcal, app.nutrition.targetOn(app.days.today())!!.kcal)
        assertEquals(90.0, app.personas.profile.first()!!.weightKg, 0.0)
    }

    @Test
    fun `a scan records a scan weigh-in, sets the usual slot the first time and checks the protocol`() = runBlocking {
        onboard()
        assertNull(body.scanSlot.first())

        val first = body.saveScan(scan(at(0, "10:00")).copy(segmental = SegmentalLean(3_500, 3_400, 26_000, 9_000, 8_900)))
        assertEquals(ScanSlot(DayOfWeek.MONDAY, LocalTime.of(10, 0)), first.slotSet)
        assertEquals(first.slotSet, body.scanSlot.first())
        assertTrue(first.check.ok)
        val saved = body.scan(first.id)!!
        assertTrue(saved.protocolOk)
        assertEquals(0, saved.minsFromSlot)
        assertEquals(SegmentalLean(3_500, 3_400, 26_000, 9_000, 8_900), BodyRepository.segmental(saved.segmentalLeanJson))

        val weighIn = app.db.bodyDao().weighInForScan(first.id)!!
        assertEquals(WeightSource.SCAN, weighIn.source)
        assertEquals(80_000, weighIn.weightG)
        assertEquals(at(0, "10:00"), weighIn.measuredAt)

        // A week later, three hours late and just after eating: off protocol, for both reasons.
        val late = body.saveScan(scan(at(7, "13:00"), weightG = 79_500, sinceMeal = SinceMeal.UNDER_2H))
        assertNull(late.slotSet)
        assertEquals(setOf(ProtocolIssue.TIME, ProtocolIssue.MEAL), late.check.issues)
        val lateRow = body.scan(late.id)!!
        assertFalse(lateRow.protocolOk)
        assertEquals(180, lateRow.minsFromSlot)
        assertEquals(setOf(ProtocolIssue.TIME, ProtocolIssue.MEAL), lateRow.issues)

        // Editing recomputes the check and moves its weigh-in with it.
        val fixed = body.saveScan(scan(at(7, "10:30"), weightG = 79_200), id = late.id)
        assertEquals(late.id, fixed.id)
        assertTrue(body.scan(late.id)!!.protocolOk)
        val moved = app.db.bodyDao().weighInForScan(late.id)!!
        assertEquals(79_200, moved.weightG)
        assertEquals(at(7, "10:30"), moved.measuredAt)
        assertEquals(2, app.db.bodyDao().weighIns().size)

        // With scans as the primary source, they feed the trend.
        app.personas.setWeightSource(WeightSource.SCAN)
        assertEquals(listOf(monday, monday.plusDays(7)), body.observeTrend().first().points.map { it.date })

        // Deleting a scan takes its weigh-in with it.
        body.deleteScan(late.id)
        assertNull(body.scan(late.id))
        assertNull(app.db.bodyDao().weighInForScan(late.id))
        assertEquals(1, app.db.bodyDao().weighIns().size)
        assertEquals(listOf(first.id), body.observeScans().first().map { it.id })
    }

    @Test
    fun `a first scan cancelled mid-save still saves with its usual slot`() = runBlocking {
        onboard()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { body.saveScan(scan(at(0, "10:00"))) }
        job.cancel()
        job.join()
        assertEquals(1, body.observeScans().first().size)
        assertEquals(ScanSlot(DayOfWeek.MONDAY, LocalTime.of(10, 0)), body.scanSlot.first())
    }

    @Test
    fun `a weigh-in after midnight but before the day starts belongs to the day before`() = runBlocking {
        onboard() // "day starts at" 4 AM
        body.logWeighIn(80_000, WeightSource.HOME_SCALE, at(0, "07:00"))
        val night = body.logWeighIn(81_500, WeightSource.HOME_SCALE, at(1, "02:30"))
        assertEquals(monday, app.db.bodyDao().weighIn(night)!!.logDate)
        // It's a later weigh-in on Monday's log day, so the trend keeps Monday's morning one.
        val trend = body.observeTrend().first()
        assertEquals(listOf(monday), trend.points.map { it.date })
        assertFalse(night in trend.countedIds)
        assertEquals(80.0, trend.trendKg)

        body.logWeighIn(80_400, WeightSource.HOME_SCALE, at(1, "07:00"))
        assertEquals(listOf(monday, monday.plusDays(1)), body.observeTrend().first().points.map { it.date })
    }

    @Test
    fun `trained before the scan comes from that day's finished sessions`() = runBlocking {
        onboard()
        app.db.workoutDao().insertSession(
            WorkoutSessionEntity(
                routineId = null, kind = SessionKind.STRENGTH, status = SessionStatus.COMPLETED,
                startedAt = at(2, "07:00"), endedAt = at(2, "08:00"), logDate = monday.plusDays(2),
            ),
        )
        assertFalse(body.trainedBefore(at(2, "07:30")))
        assertTrue(body.trainedBefore(at(2, "10:00")))
        assertFalse(body.trainedBefore(at(3, "10:00")))
        assertTrue(body.saveScan(scan(at(2, "10:00"), workout = body.trainedBefore(at(2, "10:00")))).check.issues.contains(ProtocolIssue.WORKOUT))
    }

    @Test
    fun `waist is logged, edited and deleted with undo`() = runBlocking {
        onboard()
        assertNull(body.lastWaistCm())
        val id = body.logWaist(92.5, at(0, "07:00"))
        body.logWaist(91.5, at(7, "07:00"))
        assertEquals(91.5, body.lastWaistCm()!!, 0.0)
        assertEquals(listOf(92.5, 91.5), body.observeWaist().first().map { it.value })

        body.updateMeasurement(id, 93.0, at(0, "07:00"))
        assertEquals(listOf(93.0, 91.5), body.observeWaist().first().map { it.value })
        val gone = body.deleteMeasurement(id)!!
        assertEquals(1, body.observeWaist().first().size)
        body.restoreMeasurement(gone)
        assertNotNull(app.db.bodyDao().measurement(id))
    }
}
