package app.recomp.data

import app.recomp.data.repo.ExtraDraft
import app.recomp.data.repo.TrainingSetup
import app.recomp.data.repo.WorkoutAlreadyActiveException
import app.recomp.data.repo.WorkoutRepository
import app.recomp.domain.model.SessionKind
import app.recomp.domain.model.SessionStatus
import app.recomp.domain.model.SetType
import app.recomp.domain.persona.Experience
import app.recomp.domain.persona.SplitTemplate
import app.recomp.domain.training.LoggedSet
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** DAOs and repositories against a real SQLite file: setup, rotation, logging, resume and finish. */
@RunWith(RobolectricTestRunner::class)
class TrainingFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: TestApp
    private val training get() = app.training
    private val workouts get() = app.workouts

    @Before
    fun setUp() {
        app = TestApp(File(tmp.root, "app").apply { mkdirs() })
    }

    @After
    fun tearDown() = app.close()

    private fun setUpPpl(
        experience: Experience = Experience.BEGINNER,
        choices: Map<String, Long> = emptyMap(),
        increments: Map<Long, Int> = emptyMap(),
        extras: List<ExtraDraft> = emptyList(),
    ) = runBlocking {
        training.applySetup(TrainingSetup(SplitTemplate.PPL, experience, 3, extras, choices, increments))
    }

    private suspend fun exerciseId(name: String) = training.observeLibrary().first().first { it.name == name }.id

    private suspend fun rotationNames() = training.observeRotation().first().map { it.name }

    private suspend fun nextName() = training.observeNextRoutine().first()?.name

    /** Ticks every set of the session with the given load and reps. */
    private suspend fun tickAll(sessionId: Long, loadG: Int?, reps: Int, now: Long) {
        workouts.observeSessionExercises(sessionId).first().forEach { ex ->
            ex.sets.forEach { workouts.completeSet(it.id, if (ex.exercise.loadType.name == "BODYWEIGHT") null else loadG, reps, rir = null, now = now) }
        }
    }

    @Test
    fun `seeding is idempotent`() = runBlocking {
        training.ensureLibrarySeeded()
        val first = training.observeLibrary().first().size
        training.ensureLibrarySeeded()
        assertEquals(first, training.observeLibrary().first().size)
        assertEquals(FileSeedSource.exercises().size, first)
    }

    @Test
    fun `PPL setup builds the rotation with walkthrough choices and increments`() = runBlocking {
        val walkthrough = training.walkthrough(SplitTemplate.PPL)
        assertEquals(19, walkthrough.size)
        val shoulder = walkthrough.first { it.slotKey == "dumbbell_shoulder_press" }
        assertEquals(listOf("Dumbbell shoulder press", "Machine shoulder press"), shoulder.options.map { it.name })

        val machinePress = exerciseId("Machine shoulder press")
        val bench = exerciseId("Bench press")
        setUpPpl(choices = mapOf("dumbbell_shoulder_press" to machinePress), increments = mapOf(bench to 1250))

        assertEquals(listOf("Push", "Pull", "Legs"), rotationNames())
        val push = training.observeRotation().first().first()
        val rows = training.observeRoutineExercises(push.id).first()
        assertEquals(6, rows.size)
        assertEquals("Machine shoulder press", rows[1].exercise.name)
        assertEquals(1250, rows[0].exercise.incrementG)
        // Beginner policy: compound 8-12, high-rep 12-20 (PRD §4.3).
        assertEquals(8 to 12, rows[0].entry.repMin to rows[0].entry.repMax)
        assertEquals(12 to 20, rows.last().entry.repMin to rows.last().entry.repMax)
        assertTrue(rows.all { it.entry.targetSets == 3 })
    }

    @Test
    fun `re-running setup on the same split swaps variants in place and keeps other edits`() = runBlocking {
        setUpPpl()
        val push = training.observeRotation().first().first()
        val rows = training.observeRoutineExercises(push.id).first().map { it.entry }
        training.saveRoutineExercises(push.id, rows.dropLast(1)) // you removed lateral raises
        setUpPpl(choices = mapOf("pec_deck" to exerciseId("Cable fly")))

        val after = training.observeRoutineExercises(push.id).first()
        assertEquals(5, after.size)
        assertEquals("Cable fly", after[2].exercise.name)
        assertEquals(listOf("Push", "Pull", "Legs"), rotationNames())
    }

    @Test
    fun `switching split archives the old rotation`() = runBlocking {
        setUpPpl()
        training.applySetup(TrainingSetup(SplitTemplate.UPPER_LOWER, Experience.INTERMEDIATE, 4, emptyList(), emptyMap(), emptyMap()))
        assertEquals(listOf("Upper", "Lower"), rotationNames())
        // Intermediate compound range.
        val upper = training.observeRotation().first().first()
        val bench = training.observeRoutineExercises(upper.id).first().first()
        assertEquals(6 to 10, bench.entry.repMin to bench.entry.repMax)
    }

    @Test
    fun `rotation follows completed workouts, not discarded ones`() = runBlocking {
        setUpPpl()
        assertEquals("Push", nextName())
        val rotation = training.observeRotation().first()

        val push = workouts.startRoutine(rotation[0].id, now = 1_000)
        assertEquals("Push", nextName()) // still active
        tickAll(push, 40_000, 10, now = 2_000)
        workouts.finish(push, endedAt = 3_000)
        assertEquals("Pull", nextName())

        val pull = workouts.startRoutine(rotation[1].id, now = 4_000)
        workouts.discard(pull, now = 5_000)
        assertEquals("Pull", nextName())

        // Doing Legs instead of Pull moves the rotation on from Legs.
        val legs = workouts.startRoutine(rotation[2].id, now = 6_000)
        tickAll(legs, 50_000, 10, now = 7_000)
        workouts.finish(legs, endedAt = 8_000)
        assertEquals("Push", nextName())
    }

    @Test
    fun `only one workout can be active`() = runBlocking {
        setUpPpl()
        val rotation = training.observeRotation().first()
        val id = workouts.startRoutine(rotation[0].id)
        try {
            workouts.startEmpty()
            fail("expected WorkoutAlreadyActiveException")
        } catch (e: WorkoutAlreadyActiveException) {
            assertEquals(id, e.sessionId)
        }
    }

    @Test
    fun `a ticked set survives process death and the workout resumes`() = runBlocking {
        setUpPpl()
        val push = training.observeRotation().first().first()
        val sessionId = workouts.startRoutine(push.id, now = 1_000)
        val bench = workouts.observeSessionExercises(sessionId).first().first()
        assertEquals(3, bench.sets.size)
        workouts.completeSet(bench.sets[0].id, loadG = 40_000, reps = 12, rir = 2, now = 2_000)
        workouts.setValues(bench.sets[1].id, loadG = 42_500, reps = null)

        app.restart()

        val active = app.workouts.active()
        assertNotNull(active)
        assertEquals(sessionId, active!!.id)
        val sets = app.workouts.observeSessionExercises(sessionId).first().first().sets.sortedBy { it.setIndex }
        assertEquals(2_000L, sets[0].completedAt)
        assertEquals(Triple(40_000, 12, 2), Triple(sets[0].loadG, sets[0].reps, sets[0].rir))
        assertEquals(42_500, sets[1].loadG)
        assertNull(sets[1].completedAt)
        assertEquals(2_000L, app.workouts.staleEndTime(active))
    }

    @Test
    fun `finishing drops unticked sets, keeps skipped exercises and feeds next time's prefill`() = runBlocking {
        setUpPpl()
        val push = training.observeRotation().first().first()
        val first = workouts.startRoutine(push.id, now = 1_000)
        val exercises = workouts.observeSessionExercises(first).first()
        val bench = exercises[0]
        workouts.completeSet(bench.sets[0].id, 40_000, 12, null, now = 2_000)
        workouts.completeSet(bench.sets[1].id, 40_000, 11, null, now = 3_000)
        workouts.finish(first, endedAt = 4_000)

        val finished = workouts.observeSessionExercises(first).first()
        assertEquals(exercises.size, finished.size)
        assertEquals(2, finished[0].sets.size)
        assertTrue(finished.drop(1).all { it.sets.isEmpty() })

        assertEquals(listOf(LoggedSet(SetType.WORKING, 40_000, 12), LoggedSet(SetType.WORKING, 40_000, 11)), workouts.lastExposure(bench.entry.exerciseId))
        // A skipped exercise has no exposure, so it can't wipe out older history.
        assertEquals(emptyList<LoggedSet>(), workouts.lastExposure(exercises[1].entry.exerciseId))
    }

    @Test
    fun `active and discarded sessions never count as last time`() = runBlocking {
        setUpPpl()
        val push = training.observeRotation().first().first()
        val discarded = workouts.startRoutine(push.id, now = 1_000)
        val benchId = workouts.observeSessionExercises(discarded).first()[0].entry.exerciseId
        tickAll(discarded, 99_000, 1, now = 2_000)
        workouts.discard(discarded)
        val active = workouts.startRoutine(push.id, now = 3_000)
        tickAll(active, 98_000, 1, now = 4_000)
        assertEquals(emptyList<LoggedSet>(), workouts.lastExposure(benchId))
    }

    @Test
    fun `warm-ups from last time are recreated before the working sets`() = runBlocking {
        setUpPpl()
        val push = training.observeRotation().first().first()
        val first = workouts.startRoutine(push.id, now = 1_000)
        val bench = workouts.observeSessionExercises(first).first()[0]
        workouts.setType(bench.sets[0].id, SetType.WARMUP)
        workouts.addSet(bench.entry.id)
        tickAll(first, 40_000, 10, now = 2_000)
        workouts.finish(first, endedAt = 3_000)

        val second = workouts.startRoutine(push.id, now = 4_000)
        val sets = workouts.observeSessionExercises(second).first()[0].sets.sortedBy { it.setIndex }
        assertEquals(listOf(SetType.WARMUP, SetType.WORKING, SetType.WORKING, SetType.WORKING), sets.map { it.setType })
    }

    @Test
    fun `mid-workout changes leave the template alone until saved back`() = runBlocking {
        setUpPpl()
        val push = training.observeRotation().first().first()
        val sessionId = workouts.startRoutine(push.id, now = 1_000)
        assertFalse(workouts.differsFromTemplate(sessionId))

        val exercises = workouts.observeSessionExercises(sessionId).first()
        workouts.removeExercise(exercises.last().entry.id) // drop lateral raises
        workouts.swapExercise(exercises[2].entry.id, exerciseId("Cable fly"))
        workouts.addExercise(sessionId, exerciseId("Face pull"))
        workouts.moveExercise(sessionId, exercises[3].entry.id, up = true)
        workouts.addSet(exercises[0].entry.id)

        val template = training.observeRoutineExercises(push.id).first().map { it.exercise.name }
        assertEquals("Pec deck", template[2])
        assertTrue(workouts.differsFromTemplate(sessionId))

        workouts.saveToTemplate(sessionId)
        val saved = training.observeRoutineExercises(push.id).first()
        assertEquals(
            listOf("Bench press", "Dumbbell shoulder press", "Triceps pushdown", "Cable fly", "Cable overhead triceps extension", "Face pull"),
            saved.map { it.exercise.name },
        )
        assertEquals(4, saved[0].entry.targetSets)
        assertFalse(workouts.differsFromTemplate(sessionId))
    }

    @Test
    fun `swap is refused once a set is ticked`() = runBlocking {
        setUpPpl()
        val push = training.observeRotation().first().first()
        val sessionId = workouts.startRoutine(push.id)
        val bench = workouts.observeSessionExercises(sessionId).first()[0]
        workouts.completeSet(bench.sets[0].id, 40_000, 10, null)
        workouts.swapExercise(bench.entry.id, exerciseId("Dumbbell bench press"))
        assertEquals("Bench press", workouts.observeSessionExercises(sessionId).first()[0].exercise.name)
    }

    @Test
    fun `an ad-hoc workout doesn't move the rotation`() = runBlocking {
        setUpPpl()
        val sessionId = workouts.startEmpty(now = 1_000)
        workouts.addExercise(sessionId, exerciseId("Hammer curl"))
        tickAll(sessionId, 10_000, 12, now = 2_000)
        workouts.finish(sessionId, endedAt = 3_000)
        assertEquals("Push", nextName())
        assertFalse(workouts.differsFromTemplate(sessionId))
    }

    @Test
    fun `extra sessions log in one go and prefill from the last one`() = runBlocking {
        setUpPpl(extras = listOf(ExtraDraft(null, "Yoga class", SessionKind.YOGA, setOf(6))))
        val yoga = training.observeExtras().first().single()
        assertEquals(SessionKind.YOGA, yoga.kind)
        assertEquals(yoga.id, training.settings.first().extras.single().routineId)
        assertNull(workouts.lastExtra(yoga.id))

        workouts.logExtra(yoga, WorkoutRepository.ExtraEntry("Hatha", 60, 3, "hips tight"), now = 10_000_000)
        val prefill = workouts.lastExtra(yoga.id)
        assertEquals(WorkoutRepository.ExtraEntry("Hatha", 60, 3, "hips tight"), prefill)
        val week = workouts.observeWeek(workouts.today(10_000_000)).first()
        assertEquals(SessionKind.YOGA, week.single().kind)
        assertEquals(SessionStatus.COMPLETED, app.db.workoutDao().observeLatestCompleted().first()!!.status)
        // Extra sessions are not strength days and don't touch the rotation.
        assertEquals("Push", nextName())
    }

    @Test
    fun `removing an extra session archives it`() = runBlocking {
        setUpPpl(extras = listOf(ExtraDraft(null, "Yoga class", SessionKind.YOGA, setOf(6))))
        setUpPpl(extras = emptyList())
        assertTrue(training.observeExtras().first().isEmpty())
        assertTrue(training.settings.first().extras.isEmpty())
    }

    @Test
    fun `changing experience re-applies untouched rep ranges`() = runBlocking {
        setUpPpl(Experience.BEGINNER)
        val bench = exerciseId("Bench press")
        setUpPpl(Experience.ADVANCED)
        val row = app.db.exerciseDao().get(bench)!!
        assertEquals(5 to 8, row.defaultRepMin to row.defaultRepMax)
    }
}
