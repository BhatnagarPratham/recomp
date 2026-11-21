package app.recomp.data

import app.recomp.data.repo.UnitSwitch
import app.recomp.domain.model.SessionKind
import app.recomp.data.db.SetEntryEntity
import app.recomp.data.db.SessionExerciseEntity
import app.recomp.domain.model.SessionStatus
import app.recomp.data.db.WorkoutSessionEntity
import app.recomp.domain.model.LoadType
import app.recomp.domain.model.SetType
import app.recomp.domain.persona.Units
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Settings → Units (PR-05, PRD §12.16): the switch moves weight steps and nothing else. */
@RunWith(RobolectricTestRunner::class)
class UnitSwitchTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: TestApp
    private lateinit var switch: UnitSwitch

    @Before
    fun setUp() {
        app = TestApp(File(tmp.root, "app").apply { mkdirs() })
        switch = UnitSwitch(app.db, app.db.exerciseDao(), app.prefs)
    }

    @After
    fun tearDown() = app.close()

    /** A finished set of 42.5 kg on the first weighted exercise, to show history isn't touched. */
    private suspend fun logOneSet(): Pair<Long, Long> {
        val exercise = app.db.exerciseDao().observeActive().first().first { it.loadType == LoadType.WEIGHTED }
        val session = app.db.workoutDao().insertSession(
            WorkoutSessionEntity(
                routineId = null, kind = SessionKind.STRENGTH, status = SessionStatus.COMPLETED,
                startedAt = 0, endedAt = 1, logDate = java.time.LocalDate.of(2026, 11, 2),
            ),
        )
        val row = app.db.workoutDao().insertSessionExercise(
            SessionExerciseEntity(sessionId = session, exerciseId = exercise.id, position = 0, targetSets = 3, repMin = 8, repMax = 12, restSec = 90),
        )
        val set = app.db.workoutDao().insertSet(SetEntryEntity(sessionExerciseId = row, setIndex = 0, setType = SetType.WORKING, loadG = 42_500, reps = 10, completedAt = 1))
        return exercise.id to set
    }

    @Test
    fun `moving steps changes only exercises' weight steps, and history stays exact`() = runBlocking {
        app.training.ensureLibrarySeeded()
        val (_, setId) = logOneSet()
        val before = app.db.exerciseDao().observeActive().first().associate { it.id to it }

        val moves = switch.preview(Units.IMPERIAL)
        assertTrue(moves.isNotEmpty())
        assertTrue(moves.any { it.fromG == 2_500 && it.toG == 2_268 })
        assertTrue(moves.none { it.fromG == it.toG })
        switch.switch(Units.IMPERIAL, moveSteps = true)

        assertEquals(Units.IMPERIAL, app.prefs.persona.first().units)
        val after = app.db.exerciseDao().observeActive().first()
        after.forEach { e ->
            val was = before.getValue(e.id)
            if (e.loadType == LoadType.BODYWEIGHT) assertEquals(e.name, was.incrementG, e.incrementG)
            else assertTrue("${e.name}: ${e.incrementG} g", e.incrementG in listOf(454, 1_134, 2_268, 4_536))
            assertEquals(was.copy(incrementG = e.incrementG), e)
        }
        assertEquals(42_500, app.db.workoutDao().set(setId)!!.loadG)

        // And back: every step returns to a kg step.
        switch.switch(Units.METRIC, moveSteps = true)
        app.db.exerciseDao().observeActive().first().filter { it.loadType != LoadType.BODYWEIGHT }.forEach {
            assertTrue("${it.name}: ${it.incrementG} g", it.incrementG in listOf(1_000, 1_250, 2_000, 2_500, 4_000, 5_000))
        }
        assertEquals(42_500, app.db.workoutDao().set(setId)!!.loadG)
    }

    @Test
    fun `keeping steps switches the units alone`() = runBlocking {
        app.training.ensureLibrarySeeded()
        val before = app.db.exerciseDao().observeActive().first()
        switch.switch(Units.IMPERIAL, moveSteps = false)
        assertEquals(Units.IMPERIAL, switch.current())
        assertEquals(before, app.db.exerciseDao().observeActive().first())
        // Steps already on the lb list aren't offered as a move.
        switch.switch(Units.IMPERIAL, moveSteps = true)
        assertTrue(switch.preview(Units.IMPERIAL).isEmpty())
    }

    @Test
    fun `onboarding in lb moves the library's kg default steps, but not the walkthrough's`() = runBlocking {
        app.training.ensureLibrarySeeded()
        val all = app.db.exerciseDao().observeActive().first().filter { it.loadType != LoadType.BODYWEIGHT }
        val chosen = all.first()
        app.db.exerciseDao().update(chosen.copy(incrementG = 1_134))
        app.training.moveDefaultSteps(Units.IMPERIAL, keep = setOf(chosen.id))
        val after = app.db.exerciseDao().observeActive().first().filter { it.loadType != LoadType.BODYWEIGHT }
        assertEquals(1_134, after.single { it.id == chosen.id }.incrementG)
        after.filter { it.id != chosen.id }.forEach { assertTrue("${it.name}: ${it.incrementG} g", it.incrementG in listOf(454, 1_134, 2_268, 4_536)) }
    }
}
