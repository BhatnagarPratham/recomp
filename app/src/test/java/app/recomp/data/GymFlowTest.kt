package app.recomp.data

import app.recomp.data.db.ExerciseEntity
import app.recomp.data.db.SessionExerciseWithSets
import app.recomp.data.repo.ProgressionRepository
import app.recomp.data.repo.TrainingSetup
import app.recomp.domain.model.Equipment
import app.recomp.domain.model.LoadType
import app.recomp.domain.model.Mechanic
import app.recomp.domain.model.SetType
import app.recomp.domain.persona.Experience
import app.recomp.domain.persona.SplitTemplate
import app.recomp.domain.persona.WeightSource
import app.recomp.domain.progression.Action
import app.recomp.domain.progression.PrType
import app.recomp.domain.progression.Recommendation
import app.recomp.domain.training.SetValues
import app.recomp.ui.components.sameMovement
import java.io.File
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

/** M6 against a real SQLite file: supersets, warm-ups, assisted lifts, the double step and substitutes. Data is invented. */
@RunWith(RobolectricTestRunner::class)
class GymFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: TestApp
    private val workouts get() = app.workouts
    private var clock = 1_000_000_000L

    @Before
    fun setUp() = runBlocking {
        app = TestApp(File(tmp.root, "app").apply { mkdirs() })
        app.training.applySetup(TrainingSetup(SplitTemplate.PPL, Experience.BEGINNER, 3, emptyList(), emptyMap(), emptyMap()))
    }

    @After
    fun tearDown() = app.close()

    private suspend fun push() = app.training.observeRotation().first().first()
    private suspend fun library() = app.training.observeLibrary().first()
    private suspend fun exercise(name: String) = library().first { it.name == name }
    private suspend fun rows(sessionId: Long): List<SessionExerciseWithSets> = workouts.observeSessionExercises(sessionId).first()
    private suspend fun groups(sessionId: Long) = rows(sessionId).map { it.entry.supersetGroup }

    /** A routine of exactly these exercises, three sets each. */
    private suspend fun routine(vararg exercises: ExerciseEntity): Long {
        val id = app.training.createRoutine("Test")
        app.training.saveRoutineExercises(id, exercises.map { app.training.planRowFor(it) })
        return id
    }

    // Supersets (RT-05)

    @Test
    fun `superset with next mid-workout leaves the routine alone until saved back`() = runBlocking {
        val routineId = push().id
        val session = workouts.startRoutine(routineId, now = clock)
        val first = rows(session)
        workouts.supersetWithNext(session, first[0].entry.id)
        workouts.supersetWithNext(session, first[1].entry.id)
        assertEquals(listOf(1, 1, 1, null, null, null), groups(session))
        // A fourth is refused.
        workouts.supersetWithNext(session, first[2].entry.id)
        assertEquals(listOf(1, 1, 1, null, null, null), groups(session))
        assertTrue(app.db.routineDao().exercises(routineId).all { it.entry.supersetGroup == null })
        assertTrue(workouts.differsFromTemplate(session))

        workouts.saveToTemplate(session)
        assertEquals(listOf(1, 1, 1, null, null, null), app.db.routineDao().exercises(routineId).map { it.entry.supersetGroup })
        assertFalse(workouts.differsFromTemplate(session))

        // The next workout starts with the groups, and each exercise keeps its own target.
        workouts.discard(session)
        val next = workouts.startRoutine(routineId, now = clock + 1)
        assertEquals(listOf(1, 1, 1, null, null, null), groups(next))
        assertTrue(rows(next).all { ProgressionRepository.decode(it.entry.recommendationJson)!!.action == Action.BASELINE })
    }

    @Test
    fun `split superset frees its exercises and a routine edit can undo a saved group`() = runBlocking {
        val routineId = push().id
        val rows = app.db.routineDao().exercises(routineId).map { it.entry }
        app.training.saveRoutineExercises(routineId, rows.mapIndexed { i, r -> r.copy(supersetGroup = if (i in 3..4) 9 else null) })
        // Saved groups are renumbered in order.
        assertEquals(listOf(null, null, null, 1, 1, null), app.db.routineDao().exercises(routineId).map { it.entry.supersetGroup })

        val session = workouts.startRoutine(routineId, now = clock)
        assertEquals(listOf(null, null, null, 1, 1, null), groups(session))
        workouts.splitSuperset(session, rows(session)[4].entry.id)
        assertEquals(List(6) { null }, groups(session))
        assertTrue(workouts.differsFromTemplate(session))
    }

    @Test
    fun `swap keeps the superset, removing one of two ends it, and moves keep groups together`() = runBlocking {
        val session = workouts.startRoutine(push().id, now = clock)
        val ids = rows(session).map { it.entry.id }
        workouts.supersetWithNext(session, ids[1])
        assertEquals(listOf(null, 1, 1, null, null, null), groups(session))

        workouts.swapExercise(ids[1], exercise("Machine shoulder press").id)
        assertEquals(listOf(null, 1, 1, null, null, null), groups(session))
        assertEquals("Machine shoulder press", rows(session)[1].exercise.name)

        // The superset moves up as one, then the bench jumps it.
        val names = rows(session).map { it.exercise.name }
        workouts.moveExercise(session, rows(session)[1].entry.id, up = true)
        assertEquals(listOf(names[1], names[2], names[0]) + names.drop(3), rows(session).map { it.exercise.name })
        assertEquals(listOf(1, 1, null, null, null, null), groups(session))
        workouts.moveExercise(session, rows(session)[2].entry.id, up = true)
        assertEquals(names, rows(session).map { it.exercise.name })

        workouts.removeExercise(rows(session)[2].entry.id)
        assertEquals(List(5) { null }, groups(session))
    }

    // Warm-ups (WL-11)

    @Test
    fun `adding warm-ups puts them before the working sets with their values, without changing the target`() = runBlocking {
        val session = workouts.startRoutine(push().id, now = clock)
        val bench = rows(session).first()
        val firstId = workouts.addWarmups(bench.entry.id, listOf(SetValues(20_000, 8), SetValues(30_000, 5), SetValues(40_000, 3)))
        val sets = rows(session).first().sets.sortedBy { it.setIndex }
        assertEquals(listOf(SetType.WARMUP, SetType.WARMUP, SetType.WARMUP, SetType.WORKING, SetType.WORKING, SetType.WORKING), sets.map { it.setType })
        assertEquals(listOf(20_000 to 8, 30_000 to 5, 40_000 to 3), sets.take(3).map { it.loadG to it.reps })
        assertEquals(sets.first().id, firstId)
        assertTrue(sets.none { it.completedAt != null })
        assertEquals(3, rows(session).first().entry.targetSets)
        assertNull(workouts.addWarmups(bench.entry.id, emptyList()))
    }

    // Assisted lifts (WL-09)

    @Test
    fun `assisted lifts are in the library and progress by taking assistance off`() = runBlocking {
        val pullUp = exercise("Assisted pull-up")
        assertEquals(LoadType.ASSISTED, pullUp.loadType)
        assertEquals(LoadType.ASSISTED, exercise("Assisted dip").loadType)

        val routineId = routine(pullUp)
        suspend fun session(assist: Int, reps: Int, rir: Int? = null): Long {
            clock += 86_400_000
            val id = workouts.startRoutine(routineId, now = clock)
            rows(id).single().sets.forEach { workouts.completeSet(it.id, assist, reps, rir, now = clock + 60_000) }
            workouts.finish(id, endedAt = clock + 3_600_000)
            return id
        }
        session(25_000, 10)
        session(25_000, 12)
        clock += 86_400_000
        val next = workouts.startRoutine(routineId, now = clock)
        assertEquals(Recommendation(Action.INCREASE, 20_000, listOf(8, 8, 8), assisted = true), ProgressionRepository.decode(rows(next).single().entry.recommendationJson))
        workouts.discard(next)
    }

    @Test
    fun `assisted PRs use the trend weight, and before a weigh-in less assistance is the record`() = runBlocking {
        val pullUp = exercise("Assisted pull-up")
        val routineId = routine(pullUp)
        suspend fun session(assist: Int, reps: Int): Long {
            clock += 86_400_000
            val id = workouts.startRoutine(routineId, now = clock)
            rows(id).single().sets.forEach { workouts.completeSet(it.id, assist, reps, null, now = clock + 60_000) }
            workouts.finish(id, endedAt = clock + 3_600_000)
            return id
        }
        session(30_000, 10)
        val noWeighIn = session(25_000, 10)
        val records = app.progression.observeSessionRecords(noWeighIn).first().associateBy { it.type }
        assertEquals(setOf(PrType.MAX_LOAD), records.keys)
        assertEquals(25_000, records.getValue(PrType.MAX_LOAD).loadG)
        assertNull(app.progression.exerciseHistory(pullUp.id).first().bestE1rmG)

        app.body.logWeighIn(80_000, WeightSource.HOME_SCALE, clock)
        val weighedIn = session(20_000, 10)
        val after = app.progression.observeSessionRecords(weighedIn).first().associateBy { it.type }
        // 80 − 20 = 60 kg lifted beats 55 kg, with the assistance kept.
        assertEquals(60_000L, after.getValue(PrType.MAX_LOAD).value)
        assertEquals(20_000, after.getValue(PrType.MAX_LOAD).loadG)
        assertTrue(PrType.E1RM in after)
        // History shows the assistance, and the e1RM of what you lifted: 60 × (1 + 10/30) = 80 kg.
        val history = app.progression.exerciseHistory(pullUp.id).first()
        assertEquals("assist 20 × 10/10/10", history.sets)
        assertEquals(80_000.0, history.bestE1rmG!!.toDouble(), 1.0)
    }

    @Test
    fun `an assisted override is a different least assistance`() = runBlocking {
        val routineId = routine(exercise("Assisted pull-up"))
        clock += 86_400_000
        val first = workouts.startRoutine(routineId, now = clock)
        rows(first).single().sets.forEach { workouts.completeSet(it.id, 25_000, 12, null, now = clock + 60_000) }
        workouts.finish(first, endedAt = clock + 3_600_000)
        clock += 86_400_000
        val id = workouts.startRoutine(routineId, now = clock) // target: assist 20 × 8
        val sets = rows(id).single().sets.sortedBy { it.setIndex }
        workouts.completeSet(sets[0].id, 20_000, 8, null, now = clock)
        workouts.completeSet(sets[1].id, 25_000, 8, null, now = clock) // more help on a later set isn't an override
        assertFalse(rows(id).single().entry.userOverrode)
        workouts.completeSet(sets[2].id, 15_000, 8, null, now = clock)
        assertTrue(rows(id).single().entry.userOverrode)
    }

    // RIR-aware jumps (PO-08)

    @Test
    fun `reps in reserve are kept with each set and earn a beginner the double step`() = runBlocking {
        val bench = push()
        suspend fun session(load: Int, rir: Int?): Long {
            clock += 86_400_000
            val id = workouts.startRoutine(bench.id, now = clock)
            rows(id).first().sets.forEach { workouts.completeSet(it.id, load, 12, rir, now = clock + 60_000) }
            workouts.finish(id, endedAt = clock + 3_600_000)
            return id
        }
        session(50_000, 3)
        clock += 86_400_000
        val next = workouts.startRoutine(bench.id, now = clock)
        val rec = ProgressionRepository.decode(rows(next).first().entry.recommendationJson)
        assertEquals(Recommendation(Action.INCREASE, 55_000, listOf(8, 8, 8), steps = 2), rec)
        assertEquals(listOf(3, 3, 3), app.progression.history(rows(next).first().exercise.id).first().working.map { it.rir })
    }

    @Test
    fun `the frozen JSON keeps the double step and assistance`() {
        val rec = Recommendation(Action.INCREASE, 20_000, listOf(8, 8, 8), steps = 2, assisted = true)
        assertEquals(rec, ProgressionRepository.decode(ProgressionRepository.encode(rec)))
        // An M2-era target reads as one step, weighted.
        assertEquals(Recommendation(Action.ADD_REPS, 40_000, listOf(12, 12, 10)), ProgressionRepository.decode("""{"action":"ADD_REPS","loadG":40000,"reps":[12,12,10]}"""))
    }

    // Substitutes (EX-04)

    @Test
    fun `same movement is the same pattern and primary muscle on other equipment`() = runBlocking {
        val library = library()
        val row = exercise("Seated cable row")
        val same = sameMovement(row, library)
        assertTrue("Barbell row" in same.map { it.name })
        assertTrue(same.all { it.movementPattern == row.movementPattern && it.primaryMuscle == row.primaryMuscle && it.equipment != Equipment.CABLE })
        assertTrue(same.none { it.id == row.id })
        val pullUp = sameMovement(exercise("Pull-up"), library).map { it.name }
        assertTrue("Assisted pull-up" in pullUp && "Lat pulldown" in pullUp)
        assertFalse("Chin-up" in pullUp) // same equipment
        // A custom exercise says nothing about its movement.
        val custom = app.training.exercise(app.training.createCustomExercise("My row", Mechanic.COMPOUND, Equipment.CABLE))!!
        assertEquals(emptyList<ExerciseEntity>(), sameMovement(custom, library))
    }
}
