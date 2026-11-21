package app.recomp.data

import app.recomp.data.db.ExerciseEntity
import app.recomp.data.db.PersonalRecordEntity
import app.recomp.data.db.SessionExerciseWithSets
import app.recomp.domain.model.SessionStatus
import app.recomp.data.db.YogaDetailEntity
import app.recomp.data.db.WorkoutSessionEntity
import app.recomp.data.repo.ExtraDraft
import app.recomp.data.repo.ExtraEdit
import app.recomp.data.repo.ProgressionRepository
import app.recomp.data.repo.TrainingSetup
import app.recomp.data.repo.WorkoutRepository.ExtraEntry
import app.recomp.domain.model.SessionKind
import app.recomp.domain.model.SetType
import app.recomp.domain.persona.Experience
import app.recomp.domain.persona.SplitTemplate
import app.recomp.domain.persona.WeightSource
import app.recomp.domain.progression.Action
import app.recomp.domain.progression.ExerciseChart
import app.recomp.domain.progression.ExerciseChartData
import app.recomp.domain.progression.PrType
import app.recomp.domain.training.EditedExercise
import app.recomp.domain.training.EditedSession
import app.recomp.domain.training.EditedSet
import app.recomp.domain.training.LogDates
import app.recomp.domain.training.WeeklyPlan
import java.io.File
import java.time.ZoneId
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

/** M10 against a real SQLite file: editing and deleting past workouts, PR rebuilds, rotation, weekly sets. Data is invented. */
@RunWith(RobolectricTestRunner::class)
class HistoryFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: TestApp
    private val history get() = app.history
    private val workouts get() = app.workouts
    private val day = 86_400_000L
    private var clock = 1_000_000_000L
    private val later get() = clock + 30 * day

    @Before
    fun setUp() = runBlocking {
        app = TestApp(File(tmp.root, "app").apply { mkdirs() })
        app.training.applySetup(
            TrainingSetup(SplitTemplate.PPL, Experience.BEGINNER, 3, listOf(ExtraDraft(null, "Yoga class", SessionKind.YOGA, setOf(6))), emptyMap(), emptyMap()),
        )
    }

    @After
    fun tearDown() = app.close()

    private suspend fun rotation() = app.training.observeRotation().first()
    private suspend fun push() = rotation().first()
    private suspend fun exercise(name: String) = app.training.observeLibrary().first().first { it.name == name }
    private suspend fun rows(sessionId: Long): List<SessionExerciseWithSets> = workouts.observeSessionExercises(sessionId).first()
    private suspend fun session(id: Long) = app.db.workoutDao().session(id)!!

    private suspend fun routine(vararg exercises: ExerciseEntity): Long {
        val id = app.training.createRoutine("Test")
        app.training.saveRoutineExercises(id, exercises.map { app.training.planRowFor(it) })
        return id
    }

    /** A day later: a workout of [routineId] where exercise n logs [logs]`[n]` as (load, reps); the rest are skipped. */
    private suspend fun workout(routineId: Long, vararg logs: List<Pair<Int?, Int>>): Long {
        clock += day
        val id = workouts.startRoutine(routineId, now = clock)
        rows(id).zip(logs.toList()).forEach { (row, sets) ->
            repeat((sets.size - row.sets.size).coerceAtLeast(0)) { workouts.addSet(row.entry.id) }
            rows(id).first { it.entry.id == row.entry.id }.sets.sortedBy { it.setIndex }.zip(sets).forEachIndexed { i, (set, log) ->
                workouts.completeSet(set.id, log.first, log.second, null, now = clock + (i + 1) * 60_000)
            }
        }
        workouts.finish(id, endedAt = clock + 3_600_000)
        return id
    }

    private fun sets(load: Int?, vararg reps: Int) = reps.map { load to it }

    private suspend fun bench(load: Int, vararg reps: Int) = workout(push().id, sets(load, *reps))

    private suspend fun records(): Set<PersonalRecordEntity> = app.db.backupCheckDao().personalRecord().map { it.copy(id = 0) }.toSet()
    private suspend fun types(sessionId: Long) = app.db.backupCheckDao().personalRecord().filter { it.sessionId == sessionId }.map { it.type }.toSet()

    /** The workout as the edit screen would send it back unchanged. */
    private suspend fun draft(sessionId: Long): EditedSession {
        val e = history.editable(sessionId)!!
        return EditedSession(
            e.session.startedAt, e.session.endedAt!!, e.session.notes,
            e.exercises.map { row -> EditedExercise(row.entry.id, row.sets.map { EditedSet(it.id, it.setType, it.loadG, it.reps!!, it.rir) }) },
        )
    }

    private fun EditedSession.exercise(index: Int, change: (List<EditedSet>) -> List<EditedSet>) =
        copy(exercises = exercises.mapIndexed { i, ex -> if (i == index) ex.copy(sets = change(ex.sets)) else ex })

    /** Saves, then checks the incremental PR rebuild left exactly what a rebuild of everything gives. */
    private suspend fun saveAndCompare(sessionId: Long, edit: EditedSession) {
        assertNull(history.saveEdit(sessionId, edit, now = later))
        assertIncrementalMatchesScratch()
    }

    private suspend fun assertIncrementalMatchesScratch() {
        val incremental = records()
        history.rebuildAllPrs()
        assertEquals(records(), incremental)
    }

    /** Six workouts over two routines, with a bodyweight and an assisted lift among them. */
    private suspend fun seedHistory(): List<Long> {
        app.body.logWeighIn(80_000, WeightSource.HOME_SCALE, clock)
        val mixed = routine(exercise("Bench press"), exercise("Push-up"), exercise("Assisted pull-up"))
        return listOf(
            workout(mixed, sets(40_000, 10, 10, 9), sets(null, 12, 11, 10), sets(30_000, 8, 8, 7)),
            workout(push().id, sets(40_000, 11, 10, 10), sets(20_000, 10, 10, 10)),
            workout(mixed, sets(42_500, 8, 8, 8), sets(null, 13, 12, 10), sets(25_000, 8, 8, 8)),
            workout(push().id, sets(42_500, 9, 9, 8), sets(22_500, 8, 8, 8)),
            workout(mixed, sets(45_000, 8, 7, 6), sets(null, 14, 12, 11), sets(25_000, 9, 8, 8)),
            workout(push().id, sets(45_000, 8, 8, 7), sets(22_500, 9, 9, 8)),
        )
    }

    // PRs (WL-10, TRD §10 M10)

    @Test
    fun `a rebuild of untouched history writes what finishing each workout wrote`() = runBlocking {
        seedHistory()
        val atFinish = records()
        assertTrue(atFinish.size > 8)
        history.rebuildAllPrs()
        assertEquals(atFinish, records())
    }

    @Test
    fun `raising an old set takes a later PR away, and lowering it gives it back`() = runBlocking {
        val first = bench(40_000, 10, 10, 10)
        val second = bench(42_500, 8, 8, 8)
        val third = bench(45_000, 8, 8, 8)
        assertTrue(PrType.MAX_LOAD in types(third))

        saveAndCompare(second, draft(second).exercise(0) { s -> listOf(s[0].copy(loadG = 50_000)) + s.drop(1) })
        assertFalse(PrType.MAX_LOAD in types(third))
        assertTrue(PrType.MAX_LOAD in types(second))
        assertTrue(types(first).isEmpty())

        saveAndCompare(second, draft(second).exercise(0) { s -> listOf(s[0].copy(loadG = 42_500)) + s.drop(1) })
        assertTrue(PrType.MAX_LOAD in types(third))
    }

    @Test
    fun `adding and deleting sets in an older workout moves the volume PR`() = runBlocking {
        bench(40_000, 10, 10, 10)
        val second = bench(40_000, 11, 11, 11)
        val third = bench(40_000, 12, 12, 12)
        assertTrue(PrType.SESSION_VOLUME in types(third))

        // A fourth set makes the second workout the bigger one.
        saveAndCompare(second, draft(second).exercise(0) { it + EditedSet(null, SetType.WORKING, 40_000, 11) })
        assertFalse(PrType.SESSION_VOLUME in types(third))
        assertTrue(PrType.SESSION_VOLUME in types(second))
        val added = rows(second).first().sets.sortedBy { it.setIndex }
        assertEquals(listOf(0, 1, 2, 3), added.map { it.setIndex })
        assertEquals(added[2].completedAt, added[3].completedAt) // done after the set before it

        // Deleting two sets takes its volume PR away and gives the third's back.
        saveAndCompare(second, draft(second).exercise(0) { it.take(2) })
        assertFalse(PrType.SESSION_VOLUME in types(second))
        assertTrue(PrType.SESSION_VOLUME in types(third))
        assertEquals(listOf(0, 1), rows(second).first().sets.sortedBy { it.setIndex }.map { it.setIndex })
    }

    @Test
    fun `every kind of edit leaves the same PRs as a rebuild from scratch`() = runBlocking {
        val ids = seedHistory()
        // Retype a weight and reps in the oldest workout.
        saveAndCompare(ids[0], draft(ids[0]).exercise(0) { s -> s.map { it.copy(loadG = 47_500, reps = 6) } })
        // A working set becomes a warm-up, which no PR counts.
        saveAndCompare(ids[2], draft(ids[2]).exercise(0) { s -> listOf(s[0].copy(type = SetType.WARMUP)) + s.drop(1) })
        // The bodyweight and assisted lifts.
        saveAndCompare(ids[2], draft(ids[2]).exercise(1) { s -> s.map { it.copy(reps = 20) } }.exercise(2) { s -> s.map { it.copy(loadG = 10_000) } })
        // Add a set, delete a set.
        saveAndCompare(ids[1], draft(ids[1]).exercise(1) { it + EditedSet(null, SetType.WORKING, 30_000, 12) })
        saveAndCompare(ids[3], draft(ids[3]).exercise(0) { it.drop(1) })
        // Remove an exercise.
        saveAndCompare(ids[4], draft(ids[4]).let { d -> d.copy(exercises = d.exercises.drop(1)) })
        // Move the newest workout to before all the others, and the oldest to after them.
        val newest = draft(ids[5])
        saveAndCompare(ids[5], newest.copy(startedAt = 1_000_000_000L, endedAt = 1_003_600_000L))
        val oldest = draft(ids[0])
        saveAndCompare(ids[0], oldest.copy(startedAt = clock + 2 * day, endedAt = clock + 2 * day + 3_600_000))
        // Delete one from the middle, then the first.
        assertTrue(history.deleteSession(ids[2]))
        assertIncrementalMatchesScratch()
        assertTrue(history.deleteSession(ids[5]))
        assertIncrementalMatchesScratch()
        assertTrue(records().isNotEmpty())
    }

    @Test
    fun `deleting the first workout leaves the next one as the first, with no PRs`() = runBlocking {
        val first = bench(40_000, 10, 10, 10)
        val second = bench(42_500, 8, 8, 8)
        val third = bench(45_000, 8, 8, 8)
        assertTrue(types(second).isNotEmpty())
        assertTrue(history.deleteSession(first))
        assertTrue(types(second).isEmpty())
        assertTrue(PrType.MAX_LOAD in types(third))
        assertIncrementalMatchesScratch()
        // Everything the workout owned went with it.
        assertNull(app.db.workoutDao().session(first))
        assertTrue(app.db.backupCheckDao().personalRecord().none { it.sessionId == first })
        assertTrue(app.db.backupCheckDao().setEntry().none { set -> rows(first).any { it.entry.id == set.sessionExerciseId } })
    }

    @Test
    fun `a rebuilt PR points at its set and its workout's end`() = runBlocking {
        bench(40_000, 10, 10, 10)
        val second = bench(40_000, 10, 10, 10)
        assertTrue(types(second).isEmpty())
        val d = draft(second)
        saveAndCompare(second, d.exercise(0) { s -> listOf(s[0], s[1].copy(loadG = 45_000), s[2]) })
        val pr = app.db.backupCheckDao().personalRecord().single { it.sessionId == second && it.type == PrType.MAX_LOAD }
        assertEquals(d.exercises[0].sets[1].id, pr.setEntryId)
        assertEquals(45_000L, pr.value)
        assertEquals(session(second).endedAt, pr.achievedAt)
    }

    // Rotation (RT-02)

    @Test
    fun `deleting your last strength workout moves the rotation back`() = runBlocking {
        val (pushDay, pullDay, legsDay) = rotation()
        workout(pushDay.id, sets(40_000, 10, 10, 10))
        assertEquals(pullDay.id, app.training.observeNextRoutine().first()!!.id)
        val pull = workout(pullDay.id, sets(50_000, 10, 10, 10))
        assertEquals(legsDay.id, app.training.observeNextRoutine().first()!!.id)

        assertTrue(history.deleteSession(pull))
        assertEquals(pullDay.id, app.training.observeNextRoutine().first()!!.id)
    }

    @Test
    fun `moving a workout behind another moves the rotation with it, and extras never do`() = runBlocking {
        val (pushDay, pullDay, legsDay) = rotation()
        val push = workout(pushDay.id, sets(40_000, 10, 10, 10))
        val pull = workout(pullDay.id, sets(50_000, 10, 10, 10))
        // The pull day is redated to before the push day, so Push is now the latest workout.
        val start = session(push).startedAt - day
        assertNull(history.saveEdit(pull, draft(pull).copy(startedAt = start, endedAt = start + 3_600_000), now = later))
        assertEquals(pullDay.id, app.training.observeNextRoutine().first()!!.id)

        val yoga = app.training.observeExtras().first().single()
        val extra = workouts.logExtra(yoga, ExtraEntry("Vinyasa", 60, 3, null), now = clock + day)
        assertTrue(history.deleteSession(extra))
        assertEquals(pullDay.id, app.training.observeNextRoutine().first()!!.id)
        assertNotNull(legsDay)
    }

    // Frozen targets (TRD §5.1)

    @Test
    fun `an edit never rewrites a frozen target or the plan, in any workout`() = runBlocking {
        val ids = seedHistory()
        suspend fun frozen() = ids.flatMap { id -> rows(id).map { Triple(it.entry.id, it.entry.recommendationJson, listOf(it.entry.targetSets, it.entry.repMin, it.entry.repMax, it.entry.restSec)) } }
        val before = frozen()
        assertTrue(before.all { it.second != null })

        saveAndCompare(ids[1], draft(ids[1]).exercise(0) { s -> s.map { it.copy(loadG = 60_000, reps = 12) } + EditedSet(null, SetType.WORKING, 60_000, 12) })
        saveAndCompare(ids[3], draft(ids[3]).exercise(0) { it.take(1) }.copy(startedAt = 1_000_000_000L, endedAt = 1_000_600_000L, notes = "Moved"))
        assertEquals(before, frozen())

        // Neither edit touched the last two bench workouts (45 × 8/7/6, then 45 × 8/8/7), so today's call stands.
        val benchPress = exercise("Bench press")
        assertEquals(Action.DELOAD, app.progression.recommend(benchPress, 3, 8, 12).action)
        // Editing the last workout itself does change the next target.
        saveAndCompare(ids[5], draft(ids[5]).exercise(0) { s -> s.map { it.copy(reps = 12) } })
        assertEquals(Action.INCREASE, app.progression.recommend(benchPress, 3, 8, 12).action)
        assertEquals(before, frozen())
    }

    @Test
    fun `fixing a typo'd weight clears a false override`() = runBlocking {
        bench(40_000, 12, 12, 12) // next target: 42.5 × 8
        val second = bench(4_250, 8, 8, 8) // typed 4.25 for 42.5
        assertTrue(rows(second).first().entry.userOverrode)
        saveAndCompare(second, draft(second).exercise(0) { s -> s.map { it.copy(loadG = 42_500) } })
        assertFalse(rows(second).first().entry.userOverrode)
        saveAndCompare(second, draft(second).exercise(0) { s -> s.map { it.copy(loadG = 40_000) } })
        assertTrue(rows(second).first().entry.userOverrode)
    }

    // Stall resets (PO-05)

    @Test
    fun `a stall choice follows its workout when the time moves, and goes when the workout does`() = runBlocking {
        repeat(4) { bench(50_000, 10, 10, 9) }
        clock += day
        val held = workouts.startRoutine(push().id, now = clock)
        val row = rows(held).first()
        assertTrue(ProgressionRepository.decode(row.entry.recommendationJson)!!.stalled)
        app.progression.holdStall(row.entry.id)
        row.sets.sortedBy { it.setIndex }.forEach { workouts.completeSet(it.id, 50_000, 10, null, now = clock + 60_000) }
        workouts.finish(held, endedAt = clock + 3_600_000)
        suspend fun benchPress() = app.db.exerciseDao().get(row.exercise.id)!!
        assertEquals(clock - 1, benchPress().stallResetAt)

        // A notes-only edit leaves it alone.
        assertNull(history.saveEdit(held, draft(held).copy(notes = "Felt heavy"), now = later))
        assertEquals(clock - 1, benchPress().stallResetAt)

        // Two hours earlier: the reset is still just before this workout.
        val start = clock - 7_200_000
        assertNull(history.saveEdit(held, draft(held).copy(startedAt = start, endedAt = start + 3_600_000), now = later))
        assertEquals(start - 1, benchPress().stallResetAt)
        assertTrue(benchPress().stallResetKeepsBaseline)

        // Another workout's edit doesn't touch it.
        val other = bench(50_000, 10, 10, 9)
        assertNull(history.saveEdit(other, draft(other).copy(notes = "x"), now = later))
        assertTrue(history.deleteSession(other))
        assertEquals(start - 1, benchPress().stallResetAt)

        // Deleting the workout the Hold was chosen in clears it, so the stall card can come back.
        assertTrue(history.deleteSession(held))
        assertNull(benchPress().stallResetAt)
        assertFalse(benchPress().stallResetKeepsBaseline)
        assertTrue(app.progression.recommend(benchPress(), 3, 8, 12).stalled)
    }

    @Test
    fun `removing the lift from the workout its deload was chosen in clears the reset`() = runBlocking {
        repeat(4) { bench(50_000, 10, 10, 9) }
        clock += day
        val id = workouts.startRoutine(push().id, now = clock)
        val all = rows(id)
        app.progression.deloadToday(all[0].entry.id)
        all.take(2).forEach { row -> row.sets.forEach { workouts.completeSet(it.id, 45_000, 8, null, now = clock + 60_000) } }
        workouts.finish(id, endedAt = clock + 3_600_000)
        assertEquals(clock - 1, app.db.exerciseDao().get(all[0].exercise.id)!!.stallResetAt)

        saveAndCompare(id, draft(id).let { d -> d.copy(exercises = d.exercises.drop(1)) })
        assertNull(app.db.exerciseDao().get(all[0].exercise.id)!!.stallResetAt)
        assertEquals(all.size - 1, rows(id).size)
    }

    // Supersets (RT-05)

    @Test
    fun `removing one of a superset of two leaves the other on its own`() = runBlocking {
        clock += day
        val id = workouts.startRoutine(push().id, now = clock)
        val all = rows(id)
        workouts.supersetWithNext(id, all[0].entry.id)
        workouts.supersetWithNext(id, all[2].entry.id)
        workouts.supersetWithNext(id, all[3].entry.id)
        all.forEach { row -> row.sets.forEach { workouts.completeSet(it.id, 20_000, 10, null, now = clock + 60_000) } }
        workouts.finish(id, endedAt = clock + 3_600_000)
        assertEquals(listOf(1, 1, 2, 2, 2, null), rows(id).map { it.entry.supersetGroup })

        // Drop the first exercise, and the middle one of the three.
        saveAndCompare(id, draft(id).let { d -> d.copy(exercises = d.exercises.filterIndexed { i, _ -> i != 0 && i != 3 }) })
        assertEquals(listOf(null, 1, 1, null), rows(id).map { it.entry.supersetGroup })
        assertEquals(listOf(all[1], all[2], all[4], all[5]).map { it.entry.id }, rows(id).map { it.entry.id })
    }

    // Times and notes

    @Test
    fun `new times move the log date and every set's tick, and notes are trimmed`() = runBlocking {
        val id = bench(40_000, 10, 10, 10)
        val before = session(id)
        val ticks = rows(id).first().sets.sortedBy { it.setIndex }.map { it.completedAt!! }
        val start = before.startedAt - 3 * day + 1_800_000
        assertNull(history.saveEdit(id, draft(id).copy(startedAt = start, endedAt = start + 2_700_000, notes = "  Short on time  "), now = later))
        val after = session(id)
        assertEquals(start, after.startedAt)
        assertEquals(start + 2_700_000, after.endedAt)
        assertEquals(LogDates.of(start, ZoneId.systemDefault(), app.prefs.currentDayStart()), after.logDate)
        assertEquals(before.logDate.minusDays(3), after.logDate)
        assertEquals("Short on time", after.notes)
        assertEquals(ticks.map { it + (start - before.startedAt) }, rows(id).first().sets.sortedBy { it.setIndex }.map { it.completedAt })
        assertNull(history.saveEdit(id, draft(id).copy(notes = "   "), now = later))
        assertNull(session(id).notes)
    }

    @Test
    fun `an edit that can't be saved writes nothing`() = runBlocking {
        val id = bench(40_000, 10, 10, 10)
        val before = Triple(session(id), rows(id), records())
        val d = draft(id)
        assertNotNull(history.saveEdit(id, d.copy(startedAt = later + 1, endedAt = later + 2), now = later))
        assertNotNull(history.saveEdit(id, d.copy(endedAt = d.startedAt - 1), now = later))
        assertNotNull(history.saveEdit(id, d.exercise(0) { s -> s.map { it.copy(reps = 0) } }, now = later))
        assertNotNull(history.saveEdit(id, d.exercise(0) { s -> s.map { it.copy(loadG = null) } }, now = later))
        assertNotNull(history.saveEdit(id, d.copy(exercises = d.exercises + EditedExercise(9_999, emptyList())), now = later))
        assertEquals(before, Triple(session(id), rows(id), records()))

        // With a workout in progress, a past one can't be moved to start at or after it.
        val activeStart = clock + day
        val inProgress = workouts.startRoutine(push().id, now = activeStart)
        assertNotNull(history.saveEdit(id, d.copy(startedAt = activeStart + 60_000, endedAt = activeStart + 120_000), now = later))
        assertNotNull(history.saveEdit(id, d.copy(startedAt = activeStart, endedAt = activeStart + 60_000), now = later))
        assertEquals(before, Triple(session(id), rows(id), records()))
        assertNull(history.saveEdit(id, d.copy(startedAt = activeStart - 3_600_000, endedAt = activeStart - 60_000), now = later))
        workouts.discard(inProgress)

        // An active workout is edited in the workout screen, a discarded one nowhere.
        val active = workouts.startRoutine(push().id, now = clock + 2 * day)
        assertNull(history.editable(active))
        assertNotNull(history.saveEdit(active, d, now = later))
        assertFalse(history.deleteSession(active))
        workouts.discard(active)
        assertNull(history.editable(active))
        assertFalse(history.deleteSession(active))
        assertNotNull(session(active))
    }

    @Test
    fun `an exercise can lose every set and stays as skipped`() = runBlocking {
        bench(40_000, 10, 10, 10)
        val id = bench(42_500, 10, 10, 10)
        saveAndCompare(id, draft(id).exercise(0) { emptyList() })
        assertTrue(rows(id).first().sets.isEmpty())
        assertTrue(types(id).isEmpty())
        assertEquals(1, app.progression.history(rows(id).first().exercise.id).size)
    }

    @Test
    fun `a bodyweight set never stores a load`() = runBlocking {
        val id = workout(routine(exercise("Push-up")), sets(null, 10, 10, 10))
        saveAndCompare(id, draft(id).exercise(0) { s -> s.map { it.copy(loadG = 5_000, reps = 15) } })
        assertTrue(rows(id).single().sets.all { it.loadG == null && it.reps == 15 })
    }

    // The History list and extras (PRD §12.6)

    @Test
    fun `history lists workouts and extra sessions, newest first, and extras can be edited and deleted`() = runBlocking {
        val first = bench(40_000, 10, 10, 10)
        val yoga = app.training.observeExtras().first().single()
        clock += day
        val extra = workouts.logExtra(yoga, ExtraEntry("Vinyasa", 60, 3, "Hips"), now = clock)
        val last = workout(push().id, sets(42_500, 8, 8, 8), sets(20_000, 10, 10))
        val discarded = workouts.startRoutine(push().id, now = clock + day).also { workouts.discard(it) }

        val list = history.observeHistory(50).first()
        assertEquals(listOf(last, extra, first), list.map { it.session.id })
        assertTrue(list.none { it.session.id == discarded })
        assertEquals(3, history.observeCount().first())
        assertEquals("Push", list[0].routineName)
        assertEquals(2 to 5, list[0].exercises to list[0].sets)
        assertEquals("Vinyasa" to 60, list[1].style to list[1].durationMin)
        assertEquals(listOf(last), history.observeHistory(1).first().map { it.session.id })

        val before = session(extra)
        assertNull(history.editable(extra)) // not a strength workout
        assertNull(history.saveExtra(extra, ExtraEdit(before.logDate.minusDays(2), 45, 4, " Shoulders "), now = later))
        val after = session(extra)
        assertEquals(before.logDate.minusDays(2), after.logDate)
        assertEquals(before.startedAt - 2 * day, after.startedAt)
        assertEquals(after.startedAt + 45 * 60_000, after.endedAt)
        assertEquals("Shoulders", after.notes)
        assertEquals(45 to 4, history.extra(extra)!!.second.let { it.durationMin to it.intensity })
        assertEquals("Vinyasa", history.extra(extra)!!.second.style)
        assertEquals(listOf(last, first, extra), history.observeHistory(50).first().map { it.session.id })

        assertNotNull(history.saveExtra(extra, ExtraEdit(after.logDate, 0, null, null), now = later))
        assertNotNull(history.saveExtra(extra, ExtraEdit(after.logDate.plusDays(400), 45, null, null), now = later))
        assertNotNull(history.saveExtra(first, ExtraEdit(after.logDate, 45, null, null), now = later))
        assertEquals(after, session(extra))

        assertTrue(history.deleteSession(extra))
        assertNull(history.extra(extra))
        assertTrue(app.db.backupCheckDao().yogaDetail().isEmpty())
    }

    // Weekly sets per muscle (PO-10)

    @Test
    fun `this week's sets count working sets of completed workouts only`() = runBlocking {
        // The test clock's "now" decides the week, so the workouts are logged around the real today.
        val today = app.days.today()
        clock = System.currentTimeMillis() - day - 3_600_000
        val benchPress = exercise("Bench press")
        val id = workout(routine(benchPress), sets(40_000, 10, 10, 10, 10))
        // One of the four becomes a warm-up and one a drop set.
        assertNull(history.saveEdit(id, draft(id).exercise(0) { s -> listOf(s[0].copy(type = SetType.WARMUP), s[1], s[2], s[3].copy(type = SetType.DROP)) }))
        val logDate = session(id).logDate
        val week = history.observeWeekVolume().first()
        val expected = if (WeeklyPlan.weekStart(logDate) == WeeklyPlan.weekStart(today)) 2.0 else 0.0
        assertEquals(expected, week.muscles.first { it.muscle == benchPress.primaryMuscle }.sets, 0.0)
        benchPress.secondaryMuscles.forEach { m -> assertEquals(expected / 2, week.muscles.first { it.muscle == m }.sets, 0.0) }
        // Muscles the rotation trains show even at zero; the band is the default.
        assertTrue(week.muscles.any { it.halfSets == 0 })
        assertEquals(10 to 20, week.band.min to week.band.max)

        // An active workout's ticked sets don't count until it's finished.
        val active = workouts.startRoutine(routine(benchPress), now = System.currentTimeMillis() - 60_000)
        rows(active).single().sets.forEach { workouts.completeSet(it.id, 40_000, 10, null) }
        assertEquals(expected, history.observeWeekVolume().first().muscles.first { it.muscle == benchPress.primaryMuscle }.sets, 0.0)
        // Last week's workout is out of this week.
        assertNull(history.saveEdit(id, draft(id).let { d -> d.copy(startedAt = d.startedAt - 8 * day, endedAt = d.endedAt - 8 * day) }))
        assertEquals(0.0, history.observeWeekVolume().first().muscles.first { it.muscle == benchPress.primaryMuscle }.sets, 0.0)

        history.setVolumeBand(8, 16)
        assertEquals(8 to 16, history.observeWeekVolume().first().band.let { it.min to it.max })
    }

    // Charts (PO-09)

    @Test
    fun `an exercise's chart follows an edit and plots assisted lifts by their assistance`() = runBlocking {
        val first = bench(40_000, 12, 12, 12)
        val second = bench(45_000, 8, 8, 8)
        val benchPress = rows(second).first().exercise
        suspend fun chart() = ExerciseChart.of(app.progression.chartExposures(benchPress.id), benchPress.loadType)
        assertEquals(listOf(56.0, 57.0), chart().line.map { it.value })
        assertEquals(listOf(40.0, 45.0), chart().dots.map { it.value })
        assertEquals(listOf(session(first).logDate, session(second).logDate), chart().line.map { it.date })

        saveAndCompare(second, draft(second).exercise(0) { s -> s.map { it.copy(loadG = 50_000) } })
        assertEquals(listOf(40.0, 50.0), chart().dots.map { it.value })
        assertTrue(history.deleteSession(first))
        assertEquals(1, chart().dots.size)

        app.body.logWeighIn(80_000, WeightSource.HOME_SCALE, clock)
        val pullUp = exercise("Assisted pull-up")
        val r = routine(pullUp)
        workout(r, sets(30_000, 8, 8, 8))
        workout(r, sets(25_000, 8, 8, 8))
        val assisted = ExerciseChart.of(app.progression.chartExposures(pullUp.id), pullUp.loadType)
        assertEquals(ExerciseChartData.Kind.ASSISTANCE, assisted.kind)
        assertEquals(listOf(30.0, 25.0), assisted.line.map { it.value })
    }

    @Test
    fun `an edit never rewrites assisted PRs as the trend weight moves`() = runBlocking {
        val pullUp = exercise("Assisted pull-up")
        val r = routine(pullUp)
        // Before any weigh-in: least assistance is the record, in assistance grams.
        val noWeight1 = workout(r, sets(40_000, 8, 8, 8))
        val noWeight2 = workout(r, sets(35_000, 8, 8, 8))
        app.body.logWeighIn(80_000, WeightSource.HOME_SCALE, clock + 2 * 3_600_000)
        val first = workout(r, sets(30_000, 12, 12, 12))
        val second = workout(r, sets(20_000, 6, 6, 6))
        val atFinish = records()
        assertEquals(35_000L, app.db.backupCheckDao().personalRecord().single { it.sessionId == noWeight2 && it.type == PrType.MAX_LOAD }.value)
        assertTrue(PrType.E1RM in types(second))

        // The trend moves a long way afterwards.
        repeat(10) { i -> app.body.logWeighIn(120_000, WeightSource.HOME_SCALE, clock + (i + 1) * day) }
        clock += 11 * day

        // Editing the oldest workout's notes rebuilds every later one, and changes none of their records.
        saveAndCompare(noWeight1, draft(noWeight1).copy(notes = "Grip slipped"))
        assertEquals(atFinish, records())
        history.rebuildAllPrs()
        assertEquals(atFinish, records())
        assertTrue(PrType.E1RM in types(second))
        assertNotNull(first)
    }

    // Focus areas (YG-03)

    @Test
    fun `focus areas are stored in one order and prefill the next log`() = runBlocking {
        val yoga = app.training.observeExtras().first().single()
        workouts.logExtra(yoga, ExtraEntry("Hatha", 45, null, null, listOf("core", "hips", "core")), now = clock)
        assertEquals(listOf("hips", "core"), workouts.lastExtra(yoga.id)!!.focusAreas)
        assertEquals(listOf("hips", "core"), app.db.backupCheckDao().yogaDetail().single().focusAreas)
    }

    @Test
    fun `editing an extra session in History keeps or changes its focus areas`() = runBlocking {
        val yoga = app.training.observeExtras().first().single()
        val id = workouts.logExtra(yoga, ExtraEntry("Hatha", 45, null, null, listOf("hips")), now = clock)
        val date = session(id).logDate
        fun focus() = runBlocking { app.db.backupCheckDao().yogaDetail().single().focusAreas }

        assertNull(history.saveExtra(id, ExtraEdit(date, 50, null, null), now = clock + 3_600_000))
        assertEquals(listOf("hips"), focus())
        assertNull(history.saveExtra(id, ExtraEdit(date, 50, null, null, listOf("core", "back", "back")), now = clock + 3_600_000))
        assertEquals(listOf("back", "core"), focus())
        assertNull(history.saveExtra(id, ExtraEdit(date, 50, null, null, emptyList()), now = clock + 3_600_000))
        assertEquals(emptyList<String>(), focus())
    }

    // Other apps' sessions (AR-04, PRD §12.9)

    @Test
    fun `another app's session shows in History but can't be edited or deleted here`() = runBlocking {
        val walk = app.db.workoutDao().insertExtraSession(
            WorkoutSessionEntity(
                routineId = null, kind = SessionKind.CARDIO, status = SessionStatus.COMPLETED, startedAt = clock, endedAt = clock + 35 * 60_000,
                logDate = session(bench(40_000, 10, 10, 10)).logDate, notes = "from Google Fit", hcRecordId = "hc-walk-1",
            ),
            YogaDetailEntity(sessionId = 0, style = "Walk", durationMin = 35),
        )
        val before = session(walk)
        assertTrue(history.observeHistory(50).first().any { it.session.id == walk })
        assertNull(history.extra(walk))
        assertNull(history.editable(walk))
        assertNotNull(history.saveExtra(walk, ExtraEdit(before.logDate.minusDays(1), 20, 2, "mine"), now = later))
        assertFalse(history.deleteSession(walk))
        assertEquals(before, session(walk))
        assertEquals(35, app.db.workoutDao().yogaDetail(walk)!!.durationMin)
    }
}

