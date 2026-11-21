package app.recomp.data

import app.recomp.data.health.HealthWriter
import app.recomp.data.repo.ExtraDraft
import app.recomp.data.repo.ExtraEdit
import app.recomp.data.repo.HealthWriteRepository
import app.recomp.data.repo.OnboardingResult
import app.recomp.data.repo.ScanInput
import app.recomp.data.repo.ShareResult
import app.recomp.data.repo.WorkoutRepository.ExtraEntry
import app.recomp.domain.export.OutKind
import app.recomp.domain.export.OutMeal
import app.recomp.domain.export.OutRecord
import app.recomp.domain.export.OutWorkout
import app.recomp.domain.food.Origin
import app.recomp.domain.model.SessionKind
import app.recomp.domain.model.SetType
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.Pace
import app.recomp.domain.nutrition.Sex
import app.recomp.domain.persona.FinishLine
import app.recomp.domain.persona.GoalPlan
import app.recomp.domain.persona.Presets
import app.recomp.domain.persona.Profile
import app.recomp.domain.persona.WeightSource
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.TimeZone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

/** Health Connect, as a list: what was written, with its version, and what was deleted. */
class FakeHealthWriter : HealthWriter {
    var allowed = true
    var failNext: Exception? = null
    val records = linkedMapOf<String, Pair<OutRecord, Long>>()
    val upserts = mutableListOf<List<String>>()
    val deletes = mutableListOf<String>()
    val deletedAll = mutableListOf<OutKind>()

    override suspend fun canWrite() = allowed

    override suspend fun upsert(records: List<OutRecord>, version: Long) {
        failNext?.let { failNext = null; throw it }
        upserts += records.map { it.id }
        records.forEach { r ->
            val existing = this.records[r.id]
            // Health Connect keeps the higher version.
            if (existing == null || version > existing.second) this.records[r.id] = r to version
        }
    }

    override suspend fun delete(kind: OutKind, ids: List<String>) {
        deletes += ids
        ids.forEach { records.remove(it) }
    }

    override suspend fun deleteAll(kind: OutKind) {
        deletedAll += kind
        records.keys.filter { records[it]!!.first.kind == kind }.forEach { records.remove(it) }
    }
}

/**
 * DA-04 over real tables (PRD §12.12, TRD §10 M16): off by default; on, the last 30 days are written; edits and
 * deletes follow; other apps' workouts and scan weights never go. Invented data, in UTC.
 */
@RunWith(RobolectricTestRunner::class)
class HealthWriteFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: TestApp
    private lateinit var savedZone: TimeZone
    private val hc = FakeHealthWriter()
    private lateinit var writes: HealthWriteRepository
    private val today = LocalDate.of(2026, 11, 20)

    private fun at(hour: Int, minute: Int = 0, date: LocalDate = today): Long = date.atTime(hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Before
    fun setUp() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        app = TestApp(File(tmp.root, "app").apply { mkdirs() })
        app.clock.instant = Instant.ofEpochMilli(at(21))
        writes = HealthWriteRepository(app.db, app.prefs, app.device, app.days, hc, app.clock)
        runBlocking {
            val profile = Profile(Sex.MALE, LocalDate.of(1996, 1, 1), heightCm = 175.0, weightKg = 90.0)
            val plan = GoalPlan.of(Goal.RECOMP, Pace.STANDARD, 80.0, FinishLine.GoalWeight, today.minusDays(60))
            val yoga = ExtraDraft(null, "Yoga class", SessionKind.YOGA, setOf(6))
            app.personas.completeOnboarding(
                OnboardingResult(Presets.REFERENCE.draft.copy(goal = plan), Presets.REFERENCE.key, listOf(yoga), profile, LocalTime.of(4, 0)),
                now = at(6, date = today.minusDays(60)),
            )
        }
    }

    @After
    fun tearDown() {
        app.close()
        TimeZone.setDefault(savedZone)
    }

    private suspend fun workout(date: LocalDate = today, hour: Int = 18): Long {
        val routine = app.training.observeNextRoutine().first()!!
        val session = app.workouts.startRoutine(routine.id, at(hour, date = date))
        val set = app.db.workoutDao().sessionExercises(session).first().sets.first { it.setType == SetType.WORKING }
        app.workouts.completeSet(set.id, 40_000, 10, 2, at(hour, 5, date))
        app.workouts.finish(session, at(hour + 1, date = date))
        return session
    }

    private suspend fun lunch(date: LocalDate = today, kcal: Double = 500.0) =
        app.foodLog.quickAdd(date, "lunch", "Test lunch", kcal, 30.0, null, null, Origin.HOME, now = at(13, date = date))

    @Test
    fun `nothing is written until sharing is turned on`() = runBlocking {
        workout()
        lunch()
        assertFalse(app.prefs.healthShare.first())
        assertEquals(ShareResult.Off, writes.sync())
        assertTrue(hc.records.isEmpty())
    }

    @Test
    fun `turning it on writes the last 30 days of workouts, meals and typed weigh-ins`() = runBlocking {
        val recent = workout()
        workout(today.minusDays(29))
        workout(today.minusDays(30)) // outside the window
        lunch()
        lunch(today.minusDays(45))
        app.body.logWeighIn(80_100, WeightSource.HOME_SCALE, at(7))
        writes.setShare(true)
        val result = writes.sync() as ShareResult.Done
        assertEquals(4, result.written)
        val ids = hc.records.keys
        assertTrue("recomp-workout-$recent" in ids)
        assertEquals(2, ids.count { it.startsWith("recomp-workout-") })
        assertEquals(listOf("recomp-meal-$today-lunch"), ids.filter { it.startsWith("recomp-meal-") })
        assertEquals(1, ids.count { it.startsWith("recomp-weight-") })
        // Each record's version is the time it was written.
        assertTrue(hc.records.values.all { it.second == app.clock.millis() })
        val w = hc.records.getValue("recomp-workout-$recent").first as OutWorkout
        assertEquals(SessionKind.STRENGTH, w.sessionKind)
        assertTrue(w.notes!!.contains("40 kg × 10"))
        assertNull(app.device.healthShare.first().error)
        assertEquals(app.clock.millis(), app.device.healthShare.first().lastWriteAt)
    }

    @Test
    fun `a second sync writes nothing, and edits and deletes follow`() = runBlocking {
        val session = workout()
        lunch()
        app.body.logWeighIn(80_100, WeightSource.HOME_SCALE, at(7))
        writes.setShare(true)
        writes.sync()
        hc.upserts.clear()

        app.clock.instant = app.clock.instant.plusSeconds(60)
        assertEquals(ShareResult.Done(0, 0), writes.sync())
        assertTrue(hc.upserts.isEmpty())

        // An edited set (M10 edits through the same table): the workout is written again, at a later version.
        app.db.openHelper.writableDatabase.execSQL(
            "UPDATE set_entry SET load_g = 45000 WHERE session_exercise_id IN (SELECT id FROM session_exercise WHERE session_id = $session) AND completed_at IS NOT NULL",
        )
        // Another entry in the same meal: the meal's one record grows.
        lunch(kcal = 200.0)
        app.clock.instant = app.clock.instant.plusSeconds(60)
        assertEquals(ShareResult.Done(2, 0), writes.sync())
        val (record, version) = hc.records.getValue("recomp-workout-$session")
        assertTrue((record as OutWorkout).notes!!.contains("45 kg × 10"))
        assertEquals(app.clock.millis(), version)
        assertEquals(700.0, (hc.records.getValue("recomp-meal-$today-lunch").first as OutMeal).kcal, 1e-9)

        // The weigh-in and the workout deleted: they go from Health Connect by their ids.
        app.db.openHelper.writableDatabase.execSQL("DELETE FROM body_weight")
        // As M10's delete does: the session and, by its foreign keys, its exercises and sets.
        app.db.openHelper.writableDatabase.execSQL("DELETE FROM workout_session WHERE id = $session")
        app.clock.instant = app.clock.instant.plusSeconds(60)
        val done = writes.sync() as ShareResult.Done
        assertEquals(2, done.deleted)
        assertEquals(listOf("recomp-meal-$today-lunch"), hc.records.keys.toList())
    }

    @Test
    fun `other apps' workouts and scan weights are never written`() = runBlocking {
        val classRoutine = app.training.observeExtras().first().single()
        val mine = app.workouts.logExtra(classRoutine, ExtraEntry("Hatha", 60, 3, null), at(8))
        // A walk M13 read from another app, and a scan with its weight.
        app.db.openHelper.writableDatabase.execSQL(
            "INSERT INTO workout_session (routine_id, kind, status, started_at, ended_at, log_date, notes, hc_record_id) " +
                "VALUES (${classRoutine.id}, 'CARDIO', 'COMPLETED', ${at(9)}, ${at(10)}, '$today', 'from Test Fit', 'hc-walk-1')",
        )
        app.body.saveScan(
            ScanInput(
                measuredAt = at(10), weightG = 80_300, skeletalMuscleG = 33_100, bodyFatG = 20_050, bodyFatPct = 25.0, visceralFatLevel = 8.5,
                minsSinceMeal = 180, workoutBefore = false, bmrKcal = 1700, ecwTbwRatio = 0.381, score = 74, segmental = null, notes = null,
            ),
        )
        writes.setShare(true)
        writes.sync()
        assertEquals(listOf("recomp-workout-$mine"), hc.records.keys.toList())
        assertEquals(SessionKind.YOGA, (hc.records.values.single().first as OutWorkout).sessionKind)
        assertEquals("Hatha", (hc.records.values.single().first as OutWorkout).title)
    }

    @Test
    fun `without Health Connect's permission nothing is written and Settings says why`() = runBlocking {
        workout()
        writes.setShare(true)
        hc.allowed = false
        assertEquals(ShareResult.NotAllowed, writes.sync())
        assertTrue(hc.records.isEmpty())
        assertEquals(HealthWriteRepository.NOT_ALLOWED, app.device.healthShare.first().error)
    }

    @Test
    fun `a failed write is reported, never thrown, and the next sync writes it all`() = runBlocking {
        workout()
        writes.setShare(true)
        hc.failNext = java.io.IOException("Health Connect is busy")
        val failed = writes.sync()
        assertTrue(failed is ShareResult.Failed)
        assertEquals("Health Connect is busy", app.device.healthShare.first().error)
        assertTrue(hc.records.isEmpty())
        assertEquals(1, (writes.sync() as ShareResult.Done).written)
        assertNull(app.device.healthShare.first().error)
    }

    @Test
    fun `resync everything replaces all of Recomp's records, the old ones included`() = runBlocking {
        workout(today.minusDays(45))
        lunch(today.minusDays(45))
        workout()
        writes.setShare(true)
        writes.sync()
        assertEquals(1, hc.records.size)
        val result = writes.sync(everything = true) as ShareResult.Done
        assertEquals(setOf(OutKind.WORKOUT, OutKind.MEAL, OutKind.WEIGHT), hc.deletedAll.toSet())
        assertEquals(3, result.written)
        assertEquals(3, hc.records.size)
        // Afterwards only the window is remembered, so the next sync is quiet.
        app.clock.instant = app.clock.instant.plusSeconds(60)
        assertEquals(ShareResult.Done(0, 0), writes.sync())
    }

    @Test
    fun `turning it off stops writing and forgets what was written`() = runBlocking {
        workout()
        writes.setShare(true)
        writes.sync()
        writes.setShare(false)
        assertTrue(app.device.written().isEmpty())
        lunch()
        assertEquals(ShareResult.Off, writes.sync())
        assertFalse(hc.records.keys.any { it.startsWith("recomp-meal-") })
    }

    @Test
    fun `the share switch is a setting that's exported, and what was written is device state that isn't`() = runBlocking {
        writes.setShare(true)
        workout()
        writes.sync()
        assertTrue(app.prefs.exportable().asMap().keys.any { it.name == "health_share" })
        assertTrue(app.device.written().isNotEmpty())
        assertFalse(File(tmp.root, "app/settings.preferences_pb").readBytes().decodeToString().contains("recomp-workout-"))
    }

    @Test
    fun `M10's history edits follow, a class moved to another day and a deleted workout`() = runBlocking {
        val classRoutine = app.training.observeExtras().first().single()
        val yoga = app.workouts.logExtra(classRoutine, ExtraEntry("Hatha", 60, 3, null), at(8))
        val strength = workout()
        writes.setShare(true)
        writes.sync()
        assertTrue("recomp-workout-$yoga" in hc.records && "recomp-workout-$strength" in hc.records)

        // Edited in History: a day earlier and longer. Same id, written again at a later version.
        assertNull(app.history.saveExtra(yoga, ExtraEdit(today.minusDays(1), 75, 3, "moved")))
        app.clock.instant = app.clock.instant.plusSeconds(60)
        val edited = writes.sync() as ShareResult.Done
        assertEquals(1, edited.written)
        val (record, version) = hc.records.getValue("recomp-workout-$yoga")
        assertEquals(today.minusDays(1), (record as OutWorkout).date)
        assertEquals(75L * 60, (record.end.epochSecond - record.start.epochSecond))
        assertEquals(app.clock.millis(), version)

        // Deleted in History: gone from Health Connect too.
        assertTrue(app.history.deleteSession(strength))
        app.clock.instant = app.clock.instant.plusSeconds(60)
        assertEquals(1, (writes.sync() as ShareResult.Done).deleted)
        assertFalse("recomp-workout-$strength" in hc.records)
    }
}
