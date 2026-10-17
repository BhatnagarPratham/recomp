package app.recomp.data.repo

import androidx.room.withTransaction
import app.recomp.data.db.AppDatabase
import app.recomp.data.db.ExerciseDao
import app.recomp.data.db.ExerciseEntity
import app.recomp.data.db.RoutineDao
import app.recomp.data.db.RoutineEntity
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
        routineDao.exercises(routineId).forEachIndexed { position, row ->
            insertExercise(sessionId, row.exercise, position, row.entry.targetSets, row.entry.repMin, row.entry.repMax, row.entry.restSec)
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
        sessionId: Long, exercise: ExerciseEntity, position: Int, sets: Int, repMin: Int, repMax: Int, restSec: Int,
    ): Long {
        val id = workoutDao.insertSessionExercise(
            SessionExerciseEntity(
                sessionId = sessionId, exerciseId = exercise.id, position = position,
                targetSets = sets, repMin = repMin, repMax = repMax, restSec = restSec,
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

    /** Swapping is only offered before the first set is ticked, so logged sets never change exercise. */
    suspend fun swapExercise(sessionExerciseId: Long, newExerciseId: Long) = db.withTransaction {
        val row = workoutDao.sessionExercise(sessionExerciseId) ?: return@withTransaction
        if (workoutDao.completedSets(row.id).isNotEmpty()) return@withTransaction
        val exercise = exerciseDao.get(newExerciseId) ?: return@withTransaction
        workoutDao.deleteSessionExercise(row.id)
        insertExercise(row.sessionId, exercise, row.position, row.targetSets, exercise.defaultRepMin, exercise.defaultRepMax, exercise.defaultRestSec)
    }

    suspend fun removeExercise(sessionExerciseId: Long) = workoutDao.deleteSessionExercise(sessionExerciseId)

    suspend fun moveExercise(sessionId: Long, sessionExerciseId: Long, up: Boolean) = db.withTransaction {
        val rows = workoutDao.sessionExercises(sessionId).map { it.entry }.toMutableList()
        val from = rows.indexOfFirst { it.id == sessionExerciseId }
        val to = if (up) from - 1 else from + 1
        if (from < 0 || to !in rows.indices) return@withTransaction
        rows.add(to, rows.removeAt(from))
        workoutDao.updateSessionExercises(rows.mapIndexed { i, r -> r.copy(position = i) })
    }

    suspend fun setExerciseNotes(sessionExerciseId: Long, notes: String?) {
        workoutDao.sessionExercise(sessionExerciseId)?.let { workoutDao.updateSessionExercise(it.copy(notes = notes?.ifBlank { null })) }
    }

    suspend fun setSessionNotes(sessionId: Long, notes: String?) = workoutDao.setSessionNotes(sessionId, notes?.ifBlank { null })

    // Sets

    /**
     * Ticking a set (WL-02, WL-06), written before the UI moves on. A working set at a load other than
     * the target's records an override (PO-07).
     */
    suspend fun completeSet(setId: Long, loadG: Int?, reps: Int, rir: Int?, now: Long = System.currentTimeMillis()) = db.withTransaction {
        workoutDao.completeSet(setId, loadG, reps, rir, now)
        val set = workoutDao.set(setId) ?: return@withTransaction
        if (set.setType != SetType.WORKING) return@withTransaction
        val row = workoutDao.sessionExercise(set.sessionExerciseId) ?: return@withTransaction
        val target = ProgressionRepository.decode(row.recommendationJson)?.loadG ?: return@withTransaction
        if (loadG != target && !row.userOverrode) workoutDao.markOverrode(row.id)
    }

    suspend fun uncompleteSet(setId: Long) = workoutDao.uncompleteSet(setId)

    suspend fun setValues(setId: Long, loadG: Int?, reps: Int?) = workoutDao.setValues(setId, loadG, reps)

    suspend fun setRir(setId: Long, rir: Int?) = workoutDao.setRir(setId, rir)

    suspend fun setType(setId: Long, type: SetType) = db.withTransaction {
        val set = workoutDao.set(setId) ?: return@withTransaction
        workoutDao.setType(setId, type)
        adjustTargetSets(set.sessionExerciseId, before = set.setType, after = type)
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

    /** Whether the workout's plan (exercises, order, sets, reps, rest) differs from its routine (RT-03). */
    suspend fun differsFromTemplate(sessionId: Long): Boolean {
        val session = workoutDao.session(sessionId) ?: return false
        val routineId = session.routineId ?: return false
        val template = routineDao.exercises(routineId).map { PlanRow(it.entry.exerciseId, it.entry.targetSets, it.entry.repMin, it.entry.repMax, it.entry.restSec) }
        return template != planRows(sessionId)
    }

    private suspend fun planRows(sessionId: Long) = workoutDao.sessionExercises(sessionId).map {
        PlanRow(it.entry.exerciseId, it.entry.targetSets, it.entry.repMin, it.entry.repMax, it.entry.restSec)
    }

    /** "Save changes to the routine" (RT-03): the routine takes the workout's exercises, order and targets. */
    suspend fun saveToTemplate(sessionId: Long) = db.withTransaction {
        val session = workoutDao.session(sessionId) ?: return@withTransaction
        val routineId = session.routineId ?: return@withTransaction
        val schemes = routineDao.exercises(routineId).associate { it.entry.exerciseId to (it.entry.scheme to it.entry.supersetGroup) }
        val rows = workoutDao.sessionExercises(sessionId).map { row ->
            val base = training.planRowFor(row.exercise, row.entry.targetSets)
            val (scheme, superset) = schemes[row.entry.exerciseId] ?: (base.scheme to null)
            base.copy(repMin = row.entry.repMin, repMax = row.entry.repMax, restSec = row.entry.restSec, scheme = scheme, supersetGroup = superset)
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

fun SetEntryEntity.toLogged() = LoggedSet(setType, loadG, reps ?: 0)
