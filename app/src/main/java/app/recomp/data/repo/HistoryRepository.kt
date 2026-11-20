package app.recomp.data.repo

import androidx.room.withTransaction
import app.recomp.data.db.AppDatabase
import app.recomp.data.db.ExerciseDao
import app.recomp.data.db.HistoryDao
import app.recomp.data.db.HistoryRow
import app.recomp.data.db.PersonalRecordEntity
import app.recomp.data.db.SessionExerciseWithSets
import app.recomp.data.db.SetEntryEntity
import app.recomp.data.db.WorkoutDao
import app.recomp.data.db.WorkoutSessionEntity
import app.recomp.data.db.YogaDetailEntity
import app.recomp.data.prefs.DayBoundary
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.data.prefs.VolumeBand
import app.recomp.domain.model.LoadType
import app.recomp.domain.model.SessionKind
import app.recomp.domain.model.SessionStatus
import app.recomp.domain.progression.PrRebuilder
import app.recomp.domain.progression.RebuildExposure
import app.recomp.domain.progression.SessionSet
import app.recomp.domain.progression.Targets
import app.recomp.domain.training.EditedSession
import app.recomp.domain.training.MuscleSets
import app.recomp.domain.training.MuscleVolume
import app.recomp.domain.training.SessionEdit
import app.recomp.domain.training.Supersets
import app.recomp.domain.training.VolumeRow
import app.recomp.domain.training.WeeklyPlan
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest

/** A completed workout as the edit screen loads it: only what was logged. */
data class EditableSession(val session: WorkoutSessionEntity, val routineName: String?, val exercises: List<SessionExerciseWithSets>)

/** This week's working sets per muscle against the reference band (PO-10). */
data class WeekVolume(val weekStart: LocalDate, val muscles: List<MuscleSets>, val band: VolumeBand)

/** An extra session's editable details (PRD §12.6). */
data class ExtraEdit(val logDate: LocalDate, val durationMin: Int, val intensity: Int?, val notes: String?)

/**
 * Training history (M10, PRD §12.6): the list of past sessions, editing and deleting them (WL-10), and
 * the weekly sets per muscle (PO-10).
 *
 * PRs are the only derived data that's stored, so they're the only thing an edit rebuilds
 * ([PrRebuilder]). The rotation, next time's targets, stall cards and the weekly plan are computed from
 * the sessions on every read, so they follow an edit on their own. Each save is one transaction.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class HistoryRepository @Inject constructor(
    private val db: AppDatabase,
    private val historyDao: HistoryDao,
    private val workoutDao: WorkoutDao,
    private val exerciseDao: ExerciseDao,
    private val progression: ProgressionRepository,
    private val prefs: SettingsPrefs,
    private val days: DayBoundary,
) {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun observeHistory(limit: Int): Flow<List<HistoryRow>> = historyDao.observeHistory(limit)

    fun observeCount(): Flow<Int> = historyDao.observeHistoryCount()

    suspend fun dayStart() = prefs.currentDayStart()

    /** A completed strength workout with its logged sets, or null if it can't be edited here. */
    suspend fun editable(sessionId: Long): EditableSession? {
        val session = workoutDao.session(sessionId)?.takeIf { it.status == SessionStatus.COMPLETED && it.kind == SessionKind.STRENGTH } ?: return null
        val exercises = workoutDao.sessionExercises(sessionId).map { row ->
            row.copy(sets = row.sets.filter { it.completedAt != null }.sortedWith(compareBy({ it.setIndex }, { it.id })))
        }
        return EditableSession(session, session.routineId?.let { db.routineDao().get(it)?.name }, exercises)
    }

    /**
     * Saves an edited past workout (WL-10) in one transaction. Returns why it can't be saved, or null.
     *
     * - An exercise missing from [edit] is removed, and its superset closes up as in a live workout.
     * - Sets are updated, deleted or added, and renumbered. Their tick times move with the start time.
     * - The target frozen on each exercise (`recommendation_json`) and its plan (sets, rep range, rest)
     *   are never touched: they're what you were shown that day. Only `user_overrode` is recomputed, so
     *   a corrected typo stops counting as an override.
     * - A stall Hold or Deload chosen in this workout follows its new start time, and is cleared when
     *   the exercise is removed from it.
     * - PRs are rebuilt for every exercise the workout had, from the earlier of its old and new start.
     */
    suspend fun saveEdit(sessionId: Long, edit: EditedSession, now: Long = System.currentTimeMillis()): String? = db.withTransaction {
        val old = workoutDao.session(sessionId)?.takeIf { it.status == SessionStatus.COMPLETED && it.kind == SessionKind.STRENGTH }
            ?: return@withTransaction "This workout can't be edited."
        SessionEdit.timeProblem(edit.startedAt, edit.endedAt, now)?.let { return@withTransaction it }
        val rows = workoutDao.sessionExercises(sessionId)
        val kept = edit.exercises.associateBy { it.sessionExerciseId }
        if (kept.keys.any { id -> rows.none { it.entry.id == id } }) return@withTransaction "This workout changed while you were editing it."
        rows.forEach { row ->
            kept[row.entry.id]?.sets?.forEach { set ->
                SessionEdit.setProblem(set, row.exercise.loadType)?.let { return@withTransaction "${row.exercise.name}: $it" }
            }
        }

        val shift = edit.startedAt - old.startedAt
        workoutDao.updateSession(
            old.copy(startedAt = edit.startedAt, endedAt = edit.endedAt, logDate = days.of(edit.startedAt), notes = edit.notes?.trim()?.ifBlank { null }),
        )
        rows.forEach { row ->
            val edited = kept[row.entry.id]
            if (edited == null) {
                workoutDao.deleteSessionExercise(row.entry.id)
                return@forEach
            }
            val existing = historyDao.sets(row.entry.id).associateBy { it.id }
            val keptIds = edited.sets.mapNotNull { it.id }.toSet()
            existing.values.filter { it.id !in keptIds }.forEach { workoutDao.deleteSet(it.id) }
            var lastTick: Long? = null
            val updates = mutableListOf<SetEntryEntity>()
            edited.sets.forEachIndexed { index, raw ->
                val set = SessionEdit.normalized(raw, row.exercise.loadType)
                val before = set.id?.let(existing::get)
                // An added set was done between its neighbours; with none before it, at the end.
                val tick = before?.completedAt?.plus(shift) ?: lastTick ?: edit.endedAt
                lastTick = tick
                val entity = SetEntryEntity(
                    id = before?.id ?: 0, sessionExerciseId = row.entry.id, setIndex = index,
                    setType = set.type, loadG = set.loadG, reps = set.reps, rir = set.rir, completedAt = tick,
                )
                if (before == null) workoutDao.insertSet(entity) else if (entity != before) updates += entity
            }
            if (updates.isNotEmpty()) historyDao.updateSets(updates)
            val done = workoutDao.completedSets(row.entry.id).map { it.toLogged() }
            val overrode = Targets.overrode(ProgressionRepository.decode(row.entry.recommendationJson), done)
            if (overrode != row.entry.userOverrode) workoutDao.setOverrode(row.entry.id, overrode)
        }
        if (rows.any { it.entry.id !in kept }) regroup(sessionId)

        val stillHere = rows.filter { it.entry.id in kept }.map { it.exercise.id }.toSet()
        val affected = rows.map { it.exercise.id }.distinct()
        moveStallResets(affected, old.startedAt) { exerciseId -> if (exerciseId in stillHere) edit.startedAt else null }
        rebuildPrs(affected, from = minOf(old.startedAt, edit.startedAt))
        null
    }

    /**
     * Deletes a completed session of any kind (WL-10); its exercises, sets and PRs go with it. Later
     * PRs are rebuilt, and a stall choice made in it is cleared. Deleting your last strength workout
     * moves the rotation back, because the rotation follows the newest workout that's left.
     */
    suspend fun deleteSession(sessionId: Long): Boolean = db.withTransaction {
        val session = workoutDao.session(sessionId)?.takeIf { it.status == SessionStatus.COMPLETED } ?: return@withTransaction false
        val exercises = workoutDao.sessionExercises(sessionId).map { it.exercise.id }.distinct()
        historyDao.deleteSession(sessionId)
        moveStallResets(exercises, session.startedAt) { null }
        rebuildPrs(exercises, from = session.startedAt)
        true
    }

    /** Superset groups after an exercise was removed, as [WorkoutRepository.removeExercise] leaves them. */
    private suspend fun regroup(sessionId: Long) {
        val rows = workoutDao.sessionExercises(sessionId).map { it.entry }
        val groups = Supersets.normalize(rows.map { it.supersetGroup })
        rows.zip(groups).forEach { (row, group) -> if (row.supersetGroup != group) workoutDao.setSupersetGroup(row.id, group) }
    }

    /**
     * A Hold or Deload is stored as "just before the start of the workout it was chosen in" (TRD §5.1).
     * For each of [exerciseIds] whose reset points at the workout that started at [oldStartedAt],
     * [newStart] gives that workout's new start, or null when the exercise is no longer in it.
     */
    private suspend fun moveStallResets(exerciseIds: List<Long>, oldStartedAt: Long, newStart: (Long) -> Long?) {
        exerciseIds.forEach { id ->
            val exercise = exerciseDao.get(id)?.takeIf { it.stallResetAt == oldStartedAt - 1 } ?: return@forEach
            val start = newStart(id)
            when {
                start == null -> exerciseDao.update(exercise.copy(stallResetAt = null, stallResetKeepsBaseline = false))
                start != oldStartedAt -> exerciseDao.update(exercise.copy(stallResetAt = start - 1))
            }
        }
    }

    /**
     * Replaces the PRs of [exerciseIds] in every session that starts at or after [from] with what the
     * history now implies. Earlier sessions' PRs can't have changed, so they're left as they are.
     * Assisted lifts are judged at today's trend weight, as everywhere else (WL-09).
     */
    suspend fun rebuildPrs(exerciseIds: Collection<Long>, from: Long) = db.withTransaction {
        exerciseIds.distinct().forEach { exerciseId ->
            historyDao.deleteRecordsFrom(exerciseId, from)
            val exercise = exerciseDao.get(exerciseId) ?: return@forEach
            val exposures = historyDao.allCompletedSets(exerciseId).groupBy { it.set.sessionExerciseId }.values.map { rows ->
                val first = rows.first()
                RebuildExposure(first.sessionId, first.startedAt, first.endedAt ?: first.startedAt, rows.map { SessionSet(it.set.id, it.set.toLogged()) })
            }
            val bodyWeight = if (exercise.loadType == LoadType.ASSISTED) progression.trendWeightG() else null
            val records = PrRebuilder.rebuild(exposures, from, exercise.loadType, bodyWeight).map {
                PersonalRecordEntity(
                    exerciseId = exerciseId, sessionId = it.sessionId, type = it.record.type, value = it.record.value,
                    loadG = it.record.loadG, setEntryId = it.record.setId, achievedAt = it.achievedAt,
                )
            }
            if (records.isNotEmpty()) workoutDao.insertRecords(records)
        }
    }

    /** Every exercise's PRs, recomputed from the whole history: what the incremental rebuild is tested against. */
    suspend fun rebuildAllPrs() = rebuildPrs(historyDao.exercisesWithHistory(), PrRebuilder.FROM_SCRATCH)

    // Extra sessions

    suspend fun extra(sessionId: Long): Pair<WorkoutSessionEntity, YogaDetailEntity>? {
        val session = workoutDao.session(sessionId)?.takeIf { it.status == SessionStatus.COMPLETED && it.kind.isExtra } ?: return null
        return workoutDao.yogaDetail(sessionId)?.let { session to it }
    }

    /** Edits an extra session. It keeps its time of day; a new date moves it by whole days. */
    suspend fun saveExtra(sessionId: Long, edit: ExtraEdit, now: Long = System.currentTimeMillis()): String? = db.withTransaction {
        val (session, detail) = extra(sessionId) ?: return@withTransaction "This session can't be edited."
        if (edit.durationMin !in 1..24 * 60) return@withTransaction "Enter how long it took, in minutes."
        if (edit.intensity != null && edit.intensity !in 1..5) return@withTransaction "Intensity is 1 to 5."
        val moved = ChronoUnit.DAYS.between(session.logDate, edit.logDate)
        val startedAt = Instant.ofEpochMilli(session.startedAt).atZone(zone).plusDays(moved).toInstant().toEpochMilli()
        val endedAt = startedAt + edit.durationMin * 60_000L
        SessionEdit.timeProblem(startedAt, endedAt, now)?.let { return@withTransaction "That date is in the future." }
        workoutDao.updateSession(session.copy(startedAt = startedAt, endedAt = endedAt, logDate = days.of(startedAt), notes = edit.notes?.trim()?.ifBlank { null }))
        historyDao.updateYogaDetail(detail.copy(durationMin = edit.durationMin, intensity = edit.intensity))
        null
    }

    // Weekly sets per muscle (PO-10)

    fun observeWeekVolume(): Flow<WeekVolume> = days.observeToday().flatMapLatest { today ->
        val start = WeeklyPlan.weekStart(today)
        combine(historyDao.observeWorkingSets(start, start.plusDays(7)), historyDao.observeRotationMuscles(), prefs.volumeBand) { rows, rotation, band ->
            val sets = rows.map { VolumeRow(it.logDate, it.primaryMuscle, it.secondaryMuscles, it.workingSets) }
            WeekVolume(start, MuscleVolume.week(sets, today, alsoShow = rotation.sorted()), band)
        }
    }

    suspend fun setVolumeBand(min: Int, max: Int) = prefs.setVolumeBand(min, max)
}
