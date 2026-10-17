package app.recomp.data

import app.recomp.data.repo.ProgressionRepository
import app.recomp.data.repo.TrainingSetup
import app.recomp.domain.persona.Experience
import app.recomp.domain.persona.SplitTemplate
import app.recomp.domain.progression.Action
import app.recomp.domain.progression.PrType
import app.recomp.domain.progression.Recommendation
import java.io.File
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

/** M2 on a real database: frozen targets, overrides, PRs, stall choices, preview and history. */
@RunWith(RobolectricTestRunner::class)
class ProgressionFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: TestApp
    private var clock = 1_000_000_000L

    @Before
    fun setUp() = runBlocking {
        app = TestApp(File(tmp.root, "app").apply { mkdirs() })
        app.training.applySetup(TrainingSetup(SplitTemplate.PPL, Experience.BEGINNER, 3, emptyList(), emptyMap(), emptyMap()))
    }

    @After
    fun tearDown() = app.close()

    private suspend fun push() = app.training.observeRotation().first().first()

    /** Runs a Push session where bench is logged at [load] × [reps] and everything else is skipped. */
    private suspend fun benchSession(load: Int, vararg reps: Int): Long {
        clock += 86_400_000
        val id = app.workouts.startRoutine(push().id, now = clock)
        val bench = app.workouts.observeSessionExercises(id).first().first()
        bench.sets.sortedBy { it.setIndex }.zip(reps.toList()).forEach { (set, r) -> app.workouts.completeSet(set.id, load, r, null, now = clock + 60_000) }
        app.workouts.finish(id, endedAt = clock + 3_600_000)
        return id
    }

    private suspend fun benchRecommendation(sessionId: Long): Recommendation? =
        ProgressionRepository.decode(app.workouts.observeSessionExercises(sessionId).first().first().entry.recommendationJson)

    @Test
    fun `starting a workout freezes each exercise's target`() = runBlocking {
        clock += 1
        val first = app.workouts.startRoutine(push().id, now = clock)
        assertEquals(Action.BASELINE, benchRecommendation(first)!!.action)
        app.workouts.discard(first)

        benchSession(40_000, 12, 11, 10)
        val next = app.workouts.startRoutine(push().id, now = clock + 86_400_000)
        assertEquals(Recommendation(Action.ADD_REPS, 40_000, listOf(12, 12, 10)), benchRecommendation(next))
    }

    @Test
    fun `round trip of the frozen JSON keeps every field`() {
        val rec = Recommendation(Action.INCREASE, null, listOf(15, 15, 15), stalled = true, harderVariation = true)
        assertEquals(rec, ProgressionRepository.decode(ProgressionRepository.encode(rec)))
        assertNull(ProgressionRepository.decode("not json"))
    }

    @Test
    fun `a working set at another load than the target records an override`() = runBlocking {
        benchSession(40_000, 12, 12, 12) // next target: 42.5 × 8
        val id = app.workouts.startRoutine(push().id, now = clock + 86_400_000)
        val bench = app.workouts.observeSessionExercises(id).first().first()
        assertEquals(42_500, benchRecommendation(id)!!.loadG)
        app.workouts.completeSet(bench.sets[0].id, 42_500, 8, null)
        assertFalse(app.workouts.observeSessionExercises(id).first().first().entry.userOverrode)
        app.workouts.completeSet(bench.sets[1].id, 40_000, 10, null)
        assertTrue(app.workouts.observeSessionExercises(id).first().first().entry.userOverrode)
    }

    @Test
    fun `PRs are written at finish, never on the first session`() = runBlocking {
        val first = benchSession(40_000, 12, 11, 10)
        assertTrue(app.progression.observeSessionRecords(first).first().isEmpty())
        val second = benchSession(42_500, 10, 9, 9)
        val types = app.progression.observeSessionRecords(second).first().map { it.type }.toSet()
        assertEquals(setOf(PrType.MAX_LOAD, PrType.E1RM), types)
        // 42.5 × 11/10/9 is 1,275 kg of volume, still under the first session's 1,320 kg.
        val third = benchSession(42_500, 11, 10, 9)
        val again = app.progression.observeSessionRecords(third).first().map { it.type }.toSet()
        assertEquals(setOf(PrType.REPS_AT_LOAD, PrType.E1RM), again)
        val fourth = benchSession(42_500, 12, 11, 10)
        assertTrue(PrType.SESSION_VOLUME in app.progression.observeSessionRecords(fourth).first().map { it.type })
    }

    @Test
    fun `deload today drops the target and restarts stall detection`() = runBlocking {
        repeat(4) { benchSession(50_000, 10, 10, 9) }
        val id = app.workouts.startRoutine(push().id, now = clock + 86_400_000)
        assertTrue(benchRecommendation(id)!!.stalled)
        val row = app.workouts.observeSessionExercises(id).first().first()
        app.progression.deloadToday(row.entry.id)
        assertEquals(Recommendation(Action.DELOAD, 45_000, listOf(8, 8, 8)), benchRecommendation(id))
        val bench = app.db.exerciseDao().get(row.exercise.id)!!
        assertEquals(clock + 86_400_000 - 1, bench.stallResetAt)
        assertFalse(bench.stallResetKeepsBaseline)
    }

    @Test
    fun `hold hides the card and keeps the old best as the baseline`() = runBlocking {
        repeat(4) { benchSession(50_000, 10, 10, 9) }
        val id = app.workouts.startRoutine(push().id, now = clock + 86_400_000)
        val row = app.workouts.observeSessionExercises(id).first().first()
        app.progression.holdStall(row.entry.id)
        assertFalse(benchRecommendation(id)!!.stalled)
        assertTrue(app.db.exerciseDao().get(row.exercise.id)!!.stallResetKeepsBaseline)
        app.workouts.discard(id)
        // The next session is the first after the hold: no card yet.
        val next = app.workouts.startRoutine(push().id, now = clock + 2 * 86_400_000)
        assertFalse(benchRecommendation(next)!!.stalled)
    }

    @Test
    fun `Today previews targets with an arrow where the weight goes up`() = runBlocking {
        benchSession(40_000, 12, 12, 12)
        val lines = app.progression.preview(push().id)
        assertEquals("Bench press", lines[0].name)
        assertEquals("42.5 × 8", lines[0].target)
        assertTrue(lines[0].weightUp)
        assertEquals("first time", lines[1].target)
        assertFalse(lines[1].weightUp)
    }

    @Test
    fun `exercise history lists sessions newest first with their PRs`() = runBlocking {
        benchSession(40_000, 12, 11, 10)
        val second = benchSession(42_500, 10, 9, 9)
        val bench = app.workouts.observeSessionExercises(second).first().first().exercise.id
        val history = app.progression.exerciseHistory(bench)
        assertEquals(2, history.size)
        assertEquals(second, history[0].sessionId)
        assertEquals("42.5 × 10/9/9", history[0].sets)
        assertTrue(PrType.MAX_LOAD in history[0].records)
        assertEquals(emptyList<PrType>(), history[1].records)
        assertNotNull(history[0].bestE1rmG)
        assertEquals("Push", history[0].routineName)
    }

    @Test
    fun `a stall swap also updates the routine`() = runBlocking {
        val routine = push()
        val rows = app.training.observeRoutineExercises(routine.id).first()
        val dumbbell = app.training.observeLibrary().first().first { it.name == "Dumbbell bench press" }
        app.training.swapInRoutine(routine.id, rows[0].exercise.id, dumbbell.id)
        val after = app.training.observeRoutineExercises(routine.id).first()
        assertEquals("Dumbbell bench press", after[0].exercise.name)
        assertEquals(rows.size, after.size)
        assertEquals(rows[0].entry.targetSets, after[0].entry.targetSets)
    }
}
