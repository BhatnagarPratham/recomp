package app.recomp.data.repo

import androidx.room.withTransaction
import app.recomp.data.db.AppDatabase
import app.recomp.data.db.ExerciseDao
import app.recomp.data.db.ExerciseEntity
import app.recomp.data.db.RoutineDao
import app.recomp.data.db.RoutineEntity
import app.recomp.data.db.RoutineExerciseEntity
import app.recomp.data.db.SessionExerciseEntity
import app.recomp.data.db.SessionExerciseWithSets
import app.recomp.data.db.SetEntryEntity
import app.recomp.data.db.WorkoutDao
import app.recomp.data.db.WorkoutSessionEntity
import app.recomp.data.db.YogaDetailEntity
import app.recomp.data.prefs.DayBoundary
import app.recomp.domain.model.SessionKind
import app.recomp.domain.model.SessionStatus
import app.recomp.domain.model.SetType
import app.recomp.domain.training.LoggedSet
import app.recomp.domain.training.PlanRow
import app.recomp.domain.training.PlanSession
import app.recomp.domain.training.Prefill
import app.recomp.domain.training.SetValues
import app.recomp.domain.training.Supersets
import app.recomp.domain.training.WeeklyPlan
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Thrown when a workout is started while another is still active (TRD §6.1: one ACTIVE session). */
class WorkoutAlreadyActiveException(val sessionId: Long) : IllegalStateException("Workout $sessionId is still active")

/** The active-workout lifecycle (TRD §6.1) and extra-session logging (YG-01). */
@Singleton
class WorkoutRepository @Inject constructor(
    private val db: AppDatabase,
    private val workoutDao: WorkoutDao,
    private val routineDao: RoutineDao,
    private val exerciseDao: ExerciseDao,
    private val training: TrainingRepository,
    private val progression: ProgressionRepository,
    private val days: DayBoundary,
) {

    fun observeActive(): Flow<WorkoutSessionEntity?> = workoutDao.observeActive()
    suspend fun active(): WorkoutSessionEntity? = workoutDao.active()
    fun observeSession(id: Long): Flow<WorkoutSessionEntity?> = workoutDao.observeSession(id)
    fun observeSessionExercises(id: Long): Flow<List<SessionExerciseWithSets>> = workoutDao.observeSessionExercises(id)
    fun observeLatestStrength(): Flow<WorkoutSessionEntity?> = workoutDao.observeLatestCompletedStrength()
    suspend fun routine(id: Long): RoutineEntity? = routineDao.get(id)

    fun observeWeek(today: LocalDate): Flow<List<PlanSession>> =
        workoutDao.observeCompletedSince(WeeklyPlan.weekStart(today)).map { rows ->
            rows.map { PlanSession(it.logDate, it.kind, it.routineId) }
        }

    /** Today's log date under the "day starts at" setting (PR-03). */
    suspend fun today(now: Long = System.currentTimeMillis()): LocalDate = days.of(now)

    fun observeToday(): Flow<LocalDate> = days.observeToday()

    /** The latest completed exposure of an exercise (TRD §4 hot query), for ghost values and "last time". */
    suspend fun lastExposure(exerciseId: Long): List<LoggedSet> {
        val last = workoutDao.lastCompletedExposures(exerciseId, limit = 1).firstOrNull() ?: return emptyList()
        return workoutDao.completedSets(last.id).map { it.toLogged() }
    }

    // Starting

    suspend fun startRoutine(routineId: Long, now: Long = System.currentTimeMillis()): Long = db.withTransaction {
        workoutDao.active()?.let { throw WorkoutAlreadyActiveException(it.id) }
        val sessionId = workoutDao.insertSession(newSession(routineId, now, days.of(now)))
        val rows = routineDao.exercises(routineId)
        val groups = Supersets.normalize(rows.map { it.entry.supersetGroup })
        rows.forEachIndexed { position, row ->
            insertExercise(sessionId, row.exercise, position, row.entry.targetSets, row.entry.repMin, row.entry.repMax, row.entry.restSec, groups[position])
        }
        sessionId
    }

    /** An ad-hoc workout (RT-03): no routine, exercises added as you go. It doesn't move the rotation. */
    suspend fun startEmpty(now: Long = System.currentTimeMillis()): Long = db.withTransaction {
        workoutDao.active()?.let { throw WorkoutAlreadyActiveException(it.id) }
        workoutDao.insertSession(newSession(routineId = null, now, days.of(now)))
    }

    private fun newSession(routineId: Long?, now: Long, logDate: LocalDate) = WorkoutSessionEntity(
        routineId = routineId,
        kind = SessionKind.STRENGTH,
        status = SessionStatus.ACTIVE,
        startedAt = now,
        logDate = logDate,
    )

    private suspend fun insertExercise(
        sessionId: Long, exercise: ExerciseEntity, position: Int, sets: Int, repMin: Int, repMax: Int, restSec: Int, supersetGroup: Int? = null,
    ): Long {
        val id = workoutDao.insertSessionExercise(
            SessionExerciseEntity(
                sessionId = sessionId, exerciseId = exercise.id, position = position,
                targetSets = sets, repMin = repMin, repMax = repMax, restSec = restSec, supersetGroup = supersetGroup,
            ),
        )
        insertInitialSets(id, exercise.id, sets)
        // Frozen now, so the summary grades against what you were shown (TRD §5.1).
        workoutDao.setRecommendation(id, ProgressionRepository.encode(progression.recommend(exercise, sets, repMin, repMax)))
        return id
    }

    private suspend fun insertInitialSets(sessionExerciseId: Long, exerciseId: Long, targetSets: Int) {
        val types = Prefill.initialTypes(lastExposure(exerciseId), targetSets)
        workoutDao.insertSets(types.mapIndexed { i, type -> SetEntryEntity(sessionExerciseId = sessionExerciseId, setIndex = i, setType = type) })
    }

    // Changing the workout without touching the template (RT-03)

    suspend fun addExercise(sessionId: Long, exerciseId: Long) = db.withTransaction {
        val exercise = exerciseDao.get(exerciseId) ?: return@withTransaction
        val plan = training.planRowFor(exercise)
        insertExercise(sessionId, exercise, workoutDao.maxPosition(sessionId) + 1, plan.targetSets, plan.repMin, plan.repMax, plan.restSec)
    }

    /**
     * Swapping is only offered before the first set is ticked, so logged sets never change exercise.
     * The replacement takes the same place, superset included.
     */
    suspend fun swapExercise(sessionExerciseId: Long, newExerciseId: Long) = db.withTransaction {
        val row = workoutDao.sessionExercise(sessionExerciseId) ?: return@withTransaction
        if (workoutDao.completedSets(row.id).isNotEmpty()) return@withTransaction
        val exercise = exerciseDao.get(newExerciseId) ?: return@withTransaction
        workoutDao.deleteSessionExercise(row.id)
        insertExercise(row.sessionId, exercise, row.position, row.targetSets, exercise.defaultRepMin, exercise.defaultRepMax, exercise.defaultRestSec, row.supersetGroup)
    }

    /** Removing an exercise from a superset of two leaves the other on its own. */
    suspend fun removeExercise(sessionExerciseId: Long) = db.withTransaction {
        val row = workoutDao.sessionExercise(sessionExerciseId) ?: return@withTransaction
        workoutDao.deleteSessionExercise(row.id)
        regroup(row.sessionId) { rows -> rows.map { it.supersetGroup } }
    }

    /** Block-aware, so a move never breaks up a superset ([Supersets.move]). */
    suspend fun moveExercise(sessionId: Long, sessionExerciseId: Long, up: Boolean) = db.withTransaction {
        val rows = workoutDao.sessionExercises(sessionId).map { it.entry }
        val from = rows.indexOfFirst { it.id == sessionExerciseId }
        val order = Supersets.move(rows.map { it.supersetGroup }, from, up) ?: return@withTransaction
        workoutDao.updateSessionExercises(order.mapIndexed { i, old -> rows[old].copy(position = i) })
    }

    // Supersets (RT-05), in today's workout only

    /** *Superset with next* from a card's menu. */
    suspend fun supersetWithNext(sessionId: Long, sessionExerciseId: Long) = db.withTransaction {
        regroup(sessionId) { rows -> Supersets.joinNext(rows.map { it.supersetGroup }, rows.indexOfFirst { it.id == sessionExerciseId }) }
    }

    /** *Split superset*: every exercise in it stands alone again. */
    suspend fun splitSuperset(sessionId: Long, sessionExerciseId: Long) = db.withTransaction {
        regroup(sessionId) { rows -> Supersets.split(rows.map { it.supersetGroup }, rows.indexOfFirst { it.id == sessionExerciseId }) }
    }

    /** Writes the session's groups from [change], normalized, touching only rows that change. */
    private suspend fun regroup(sessionId: Long, change: (List<SessionExerciseEntity>) -> List<Int?>) {
        val rows = workoutDao.sessionExercises(sessionId).map { it.entry }
        val groups = Supersets.normalize(change(rows))
        rows.zip(groups).forEach { (row, group) -> if (row.supersetGroup != group) workoutDao.setSupersetGroup(row.id, group) }
    }

    suspend fun setExerciseNotes(sessionExerciseId: Long, notes: String?) {
        workoutDao.sessionExercise(sessionExerciseId)?.let { workoutDao.updateSessionExercise(it.copy(notes = notes?.ifBlank { null })) }
    }

    suspend fun setSessionNotes(sessionId: Long, notes: String?) = workoutDao.setSessionNotes(sessionId, notes?.ifBlank { null })

    // Sets

    /** Ticking a set (WL-02, WL-06), written before the UI moves on. */
    suspend fun completeSet(setId: Long, loadG: Int?, reps: Int, rir: Int?, now: Long = System.currentTimeMillis()) = db.withTransaction {
        workoutDao.completeSet(setId, loadG, reps, rir, now)
        workoutDao.set(setId)?.let { recomputeOverride(it.sessionExerciseId) }
    }

    suspend fun uncompleteSet(setId: Long) = db.withTransaction {
        workoutDao.uncompleteSet(setId)
        workoutDao.set(setId)?.let { recomputeOverride(it.sessionExerciseId) }
    }

    /**
     * PO-07: an override is a top completed working load other than the target's (for an assisted lift,
     * the least assistance). Back-off sets and unticked typos don't count, so it's recomputed on every change.
     */
    private suspend fun recomputeOverride(sessionExerciseId: Long) {
        val row = workoutDao.sessionExercise(sessionExerciseId) ?: return
        val rec = ProgressionRepository.decode(row.recommendationJson)
        val target = rec?.loadG
        val loads = workoutDao.completedSets(sessionExerciseId).filter { it.setType == SetType.WORKING }.map { it.loadG ?: 0 }
        val top = if (rec?.assisted == true) loads.minOrNull() else loads.maxOrNull()
        val overrode = target != null && top != null && top != target
        if (overrode != row.userOverrode) workoutDao.setOverrode(row.id, overrode)
    }

    /** The routine's own plan per exercise, which the next workout will start from. */
    suspend fun routinePlan(routineId: Long): Map<Long, RoutineExerciseEntity> =
        routineDao.exercises(routineId).associate { it.entry.exerciseId to it.entry }

    suspend fun setValues(setId: Long, loadG: Int?, reps: Int?) = workoutDao.setValues(setId, loadG, reps)

    suspend fun setRir(setId: Long, rir: Int?) = workoutDao.setRir(setId, rir)

    suspend fun setType(setId: Long, type: SetType) = db.withTransaction {
        val set = workoutDao.set(setId) ?: return@withTransaction
        workoutDao.setType(setId, type)
        adjustTargetSets(set.sessionExerciseId, before = set.setType, after = type)
        recomputeOverride(set.sessionExerciseId)
    }

    /** Adds a working set. The exercise's target grows with it, so saving back to the template keeps it. */
    suspend fun addSet(sessionExerciseId: Long) = db.withTransaction {
        workoutDao.insertSet(SetEntryEntity(sessionExerciseId = sessionExerciseId, setIndex = workoutDao.maxSetIndex(sessionExerciseId) + 1))
        adjustTargetSets(sessionExerciseId, before = null, after = SetType.WORKING)
    }

    suspend fun deleteSet(setId: Long) = db.withTransaction {
        val set = workoutDao.set(setId) ?: return@withTransaction
        workoutDao.deleteSet(setId)
        adjustTargetSets(set.sessionExerciseId, before = set.setType, after = null)
        recomputeOverride(set.sessionExerciseId)
    }

    /**
     * WL-11's *Add*: the suggested warm-ups go in before the exercise's other sets, with their loads and
     * reps entered, for you to tick. Returns the first one's id, to select it.
     */
    suspend fun addWarmups(sessionExerciseId: Long, plan: List<SetValues>): Long? = db.withTransaction {
        if (plan.isEmpty() || workoutDao.sessionExercise(sessionExerciseId) == null) return@withTransaction null
        workoutDao.shiftSetIndexes(sessionExerciseId, plan.size)
        plan.mapIndexed { i, warmup ->
            workoutDao.insertSet(
                SetEntryEntity(sessionExerciseId = sessionExerciseId, setIndex = i, setType = SetType.WARMUP, loadG = warmup.loadG, reps = warmup.reps),
            )
        }.first()
    }

    private suspend fun adjustTargetSets(sessionExerciseId: Long, before: SetType?, after: SetType?) {
        val delta = (if (after == SetType.WORKING) 1 else 0) - (if (before == SetType.WORKING) 1 else 0)
        if (delta == 0) return
        val row = workoutDao.sessionExercise(sessionExerciseId) ?: return
        workoutDao.updateSessionExercise(row.copy(targetSets = (row.targetSets + delta).coerceAtLeast(0)))
    }

    // Finishing

    /** End time for a stale session (TRD §6.1, > 4 h old): the last ticked set, else the start. */
    suspend fun staleEndTime(session: WorkoutSessionEntity): Long = workoutDao.lastSetCompletedAt(session.id) ?: session.startedAt

    suspend fun hasCompletedSets(sessionId: Long): Boolean = workoutDao.lastSetCompletedAt(sessionId) != null

    /** Finishing (TRD §6.1): PRs are found while the session is still active, then it's completed. */
    suspend fun finish(sessionId: Long, endedAt: Long = System.currentTimeMillis()) = db.withTransaction {
        workoutDao.deleteIncompleteSets(sessionId)
        progression.recordPrs(sessionId, endedAt)
        workoutDao.finish(sessionId, endedAt)
    }

    suspend fun discard(sessionId: Long, now: Long = System.currentTimeMillis()) =
        workoutDao.setStatus(sessionId, SessionStatus.DISCARDED, now)

    /** Whether the workout's plan (exercises, order, sets, reps, rest, supersets) differs from its routine (RT-03, RT-05). */
    suspend fun differsFromTemplate(sessionId: Long): Boolean {
        val session = workoutDao.session(sessionId) ?: return false
        val routineId = session.routineId ?: return false
        val template = routineDao.exercises(routineId).map { it.entry }
        val templateGroups = Supersets.normalize(template.map { it.supersetGroup })
        val plan = template.mapIndexed { i, it -> PlanRow(it.exerciseId, it.targetSets, it.repMin, it.repMax, it.restSec, templateGroups[i]) }
        return plan != planRows(sessionId)
    }

    private suspend fun planRows(sessionId: Long): List<PlanRow> {
        val rows = workoutDao.sessionExercises(sessionId).map { it.entry }
        val groups = Supersets.normalize(rows.map { it.supersetGroup })
        return rows.mapIndexed { i, it -> PlanRow(it.exerciseId, it.targetSets, it.repMin, it.repMax, it.restSec, groups[i]) }
    }

    /** "Save changes to the routine" (RT-03): the routine takes the workout's exercises, order, targets and supersets. */
    suspend fun saveToTemplate(sessionId: Long) = db.withTransaction {
        val session = workoutDao.session(sessionId) ?: return@withTransaction
        val routineId = session.routineId ?: return@withTransaction
        val schemes = routineDao.exercises(routineId).associate { it.entry.exerciseId to it.entry.scheme }
        val sessionRows = workoutDao.sessionExercises(sessionId)
        val groups = Supersets.normalize(sessionRows.map { it.entry.supersetGroup })
        val rows = sessionRows.mapIndexed { i, row ->
            val base = training.planRowFor(row.exercise, row.entry.targetSets)
            base.copy(
                repMin = row.entry.repMin, repMax = row.entry.repMax, restSec = row.entry.restSec,
                scheme = schemes[row.entry.exerciseId] ?: base.scheme, supersetGroup = groups[i],
            )
        }
        routineDao.replaceExercises(routineId, rows)
    }

    // Extra sessions (YG-01)

    data class ExtraEntry(val style: String, val durationMin: Int, val intensity: Int?, val notes: String?)

    /** Prefill for "Log class": the last session of this kind. */
    suspend fun lastExtra(routineId: Long): ExtraEntry? {
        val session = workoutDao.lastCompletedOfRoutine(routineId) ?: return null
        val detail = workoutDao.yogaDetail(session.id) ?: return null
        return ExtraEntry(detail.style, detail.durationMin, detail.intensity, session.notes)
    }

    suspend fun logExtra(routine: RoutineEntity, entry: ExtraEntry, now: Long = System.currentTimeMillis()): Long =
        workoutDao.insertExtraSession(
            WorkoutSessionEntity(
                routineId = routine.id,
                kind = routine.kind,
                status = SessionStatus.COMPLETED,
                startedAt = now - entry.durationMin * 60_000L,
                endedAt = now,
                logDate = days.of(now),
                notes = entry.notes?.ifBlank { null },
            ),
            YogaDetailEntity(sessionId = 0, style = entry.style, durationMin = entry.durationMin, intensity = entry.intensity),
        )
}

fun SetEntryEntity.toLogged() = LoggedSet(setType, loadG, reps ?: 0, rir)
