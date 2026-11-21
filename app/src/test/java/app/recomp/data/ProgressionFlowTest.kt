package app.recomp.data

import app.recomp.data.repo.ProgressionRepository
import app.recomp.data.repo.TrainingSetup
import app.recomp.domain.persona.Experience
import app.recomp.domain.model.SetType
import app.recomp.domain.persona.SplitTemplate
import app.recomp.domain.persona.Units
import app.recomp.domain.progression.Action
import app.recomp.domain.progression.PrType
import app.recomp.domain.progression.Recommendation
import app.recomp.domain.progression.Targets
import app.recomp.domain.training.Loads
import app.recomp.domain.training.LoggedSet
import app.recomp.domain.units.LoadUnits
import app.recomp.domain.units.UnitMath
import app.recomp.domain.units.UnitStyle
import java.io.File
import java.math.BigDecimal
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
    fun `a top working load other than the target records an override, and only that`() = runBlocking {
        benchSession(40_000, 12, 12, 12) // next target: 42.5 × 8
        val id = app.workouts.startRoutine(push().id, now = clock + 86_400_000)
        val sets = app.workouts.observeSessionExercises(id).first().first().sets.sortedBy { it.setIndex }
        suspend fun overrode() = app.workouts.observeSessionExercises(id).first().first().entry.userOverrode
        assertEquals(42_500, benchRecommendation(id)!!.loadG)

        app.workouts.completeSet(sets[0].id, 40_000, 10, null) // stayed at 40
        assertTrue(overrode())
        app.workouts.uncompleteSet(sets[0].id) // it was a typo
        assertFalse(overrode())

        app.workouts.completeSet(sets[0].id, 42_500, 8, null)
        app.workouts.completeSet(sets[1].id, 40_000, 10, null) // a back-off set below the top
        assertFalse(overrode())
    }

    @Test
    fun `history as of a session ignores later sessions`() = runBlocking {
        val first = benchSession(40_000, 12, 11, 10)
        benchSession(40_000, 12, 12, 12)
        val bench = app.workouts.observeSessionExercises(first).first().first().exercise
        val startedAt = app.db.workoutDao().session(first)!!.startedAt
        assertEquals(1, app.progression.history(bench.id, asOf = startedAt).size)
        assertEquals(Action.ADD_REPS, app.progression.recommend(bench, 3, 8, 12, asOf = startedAt).action)
        assertEquals(Action.INCREASE, app.progression.recommend(bench, 3, 8, 12).action)
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
        assertEquals("42.5 × 8", Targets.describe(lines[0].rec, UnitStyle.METRIC))
        assertTrue(lines[0].weightUp)
        assertEquals(Action.BASELINE, lines[1].rec.action)
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
        assertEquals("42.5 × 10/9/9", Loads.describe(history[0].sets, UnitStyle.METRIC, history[0].loadType))
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

    /** PR-05: a lb install, with bench on a 5 lb (2,268 g) step. */
    private suspend fun inLb(benchId: Long) {
        app.prefs.updatePersona { it.copy(units = Units.IMPERIAL) }
        app.db.exerciseDao().update(app.db.exerciseDao().get(benchId)!!.copy(incrementG = 2_268))
    }

    @Test
    fun `in lb an increase is frozen on the exercise's lb grid, so typing it hits the target`() = runBlocking {
        val first = benchSession(100_000, 12, 12, 12) // a kg-era 100 kg, which is 220.46 lb
        inLb(app.workouts.observeSessionExercises(first).first().first().exercise.id)
        val id = app.workouts.startRoutine(push().id, now = clock + 86_400_000)
        val rec = benchRecommendation(id)!!
        // 220.46 + 5 lb goes to 225 lb (102,058 g), not 225.46 lb or the 102,268 g the engine adds in grams.
        assertEquals(Action.INCREASE, rec.action)
        assertEquals(102_058, rec.loadG)
        assertEquals("225 × 8", Targets.describe(rec, UnitStyle.IMPERIAL))
        val typed = LoadUnits.parse("225", Units.IMPERIAL)!!
        assertEquals(rec.loadG, typed)
        val sets = app.workouts.observeSessionExercises(id).first().first().sets.sortedBy { it.setIndex }
        sets.forEach { app.workouts.completeSet(it.id, typed, 8, null) }
        assertFalse(app.workouts.observeSessionExercises(id).first().first().entry.userOverrode)
        assertTrue(Targets.hit(rec, sets.map { LoggedSet(SetType.WORKING, typed, 8) }))
    }

    @Test
    fun `in lb a deload rounds down to the lb grid`() = runBlocking {
        val last = (1..4).map { benchSession(100_000, 10, 10, 9) }.last()
        inLb(app.workouts.observeSessionExercises(last).first().first().exercise.id)
        val id = app.workouts.startRoutine(push().id, now = clock + 86_400_000)
        assertTrue(benchRecommendation(id)!!.stalled)
        app.progression.deloadToday(app.workouts.observeSessionExercises(id).first().first().entry.id)
        // 90% of 220.46 lb is 198.4 lb; the engine's 39 steps of 2,268 g (195.0 lb) land on 195 lb exactly.
        val deload = benchRecommendation(id)!!
        assertEquals(Action.DELOAD, deload.action)
        assertEquals(UnitMath.lbToG(BigDecimal(195)), deload.loadG)
        assertEquals("195 × 8", Targets.describe(deload, UnitStyle.IMPERIAL))
    }

    @Test
    fun `on the lb grid, increases stay past what you lifted and repeats keep it`() {
        val five = 2_268
        fun lb(n: Int) = UnitMath.lbToG(BigDecimal(n))
        val up = ProgressionRepository.onGrid(Recommendation(Action.INCREASE, lb(225) + five, listOf(8)), lb(225), five, Units.IMPERIAL)
        assertEquals(lb(230), up.loadG)
        // Assistance comes off: 25 lb less 5 is 20 lb, and a deload adds it on the easier side.
        val less = ProgressionRepository.onGrid(Recommendation(Action.INCREASE, 11_340 - five, listOf(8), assisted = true), 11_340, five, Units.IMPERIAL)
        assertEquals(lb(20), less.loadG)
        val more = ProgressionRepository.onGrid(Recommendation(Action.DELOAD, 12_000, listOf(8), assisted = true), 11_340, five, Units.IMPERIAL)
        assertEquals(lb(30), more.loadG)
        // A repeat keeps a kg-era 100 kg to the nearest 0.01 lb (220.46 lb, 99,999 g), so the field types back exactly; kg changes nothing.
        assertEquals(99_999, ProgressionRepository.onGrid(Recommendation(Action.REPEAT, 100_000, listOf(8)), 100_000, five, Units.IMPERIAL).loadG)
        val kg = Recommendation(Action.INCREASE, 102_500, listOf(8))
        assertEquals(kg, ProgressionRepository.onGrid(kg, 100_000, 2_500, Units.METRIC))
    }
}
