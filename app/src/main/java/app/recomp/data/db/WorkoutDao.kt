package app.recomp.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.recomp.domain.model.SessionStatus
import app.recomp.domain.model.SetType
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutDao {
    // Sessions

    @Query("SELECT * FROM workout_session WHERE status = 'ACTIVE' ORDER BY started_at DESC LIMIT 1")
    fun observeActive(): Flow<WorkoutSessionEntity?>

    @Query("SELECT * FROM workout_session WHERE status = 'ACTIVE' ORDER BY started_at DESC LIMIT 1")
    suspend fun active(): WorkoutSessionEntity?

    @Query("SELECT * FROM workout_session WHERE id = :id")
    fun observeSession(id: Long): Flow<WorkoutSessionEntity?>

    @Query("SELECT * FROM workout_session WHERE id = :id")
    suspend fun session(id: Long): WorkoutSessionEntity?

    @Insert
    suspend fun insertSession(session: WorkoutSessionEntity): Long

    @Update
    suspend fun updateSession(session: WorkoutSessionEntity)

    @Query("UPDATE workout_session SET notes = :notes WHERE id = :id")
    suspend fun setSessionNotes(id: Long, notes: String?)

    /** Routine ids of completed strength sessions, newest first, for the rotation (RT-02). */
    @Query("SELECT routine_id FROM workout_session WHERE status = 'COMPLETED' AND kind = 'STRENGTH' ORDER BY started_at DESC LIMIT 30")
    fun observeRecentStrengthRoutineIds(): Flow<List<Long?>>

    @Query("SELECT log_date, kind, routine_id FROM workout_session WHERE status = 'COMPLETED' AND log_date >= :from")
    fun observeCompletedSince(from: LocalDate): Flow<List<PlanSessionRow>>

    @Query("SELECT * FROM workout_session WHERE status = 'COMPLETED' AND kind = 'STRENGTH' ORDER BY started_at DESC LIMIT 1")
    fun observeLatestCompletedStrength(): Flow<WorkoutSessionEntity?>

    @Query("SELECT * FROM workout_session WHERE routine_id = :routineId AND status = 'COMPLETED' ORDER BY started_at DESC LIMIT 1")
    suspend fun lastCompletedOfRoutine(routineId: Long): WorkoutSessionEntity?

    /** Whether any session on [date] had finished by [at], for a scan's "trained before" (TRD §5.6). */
    @Query("SELECT EXISTS(SELECT 1 FROM workout_session WHERE status = 'COMPLETED' AND log_date = :date AND ended_at IS NOT NULL AND ended_at <= :at)")
    suspend fun completedBefore(date: LocalDate, at: Long): Boolean

    // Exercises in a session

    @Transaction
    @Query("SELECT * FROM session_exercise WHERE session_id = :sessionId ORDER BY position, id")
    fun observeSessionExercises(sessionId: Long): Flow<List<SessionExerciseWithSets>>

    @Transaction
    @Query("SELECT * FROM session_exercise WHERE session_id = :sessionId ORDER BY position, id")
    suspend fun sessionExercises(sessionId: Long): List<SessionExerciseWithSets>

    @Query("SELECT * FROM session_exercise WHERE id = :id")
    suspend fun sessionExercise(id: Long): SessionExerciseEntity?

    @Insert
    suspend fun insertSessionExercise(row: SessionExerciseEntity): Long

    @Update
    suspend fun updateSessionExercise(row: SessionExerciseEntity)

    @Update
    suspend fun updateSessionExercises(rows: List<SessionExerciseEntity>)

    @Query("DELETE FROM session_exercise WHERE id = :id")
    suspend fun deleteSessionExercise(id: Long)

    @Query("SELECT COALESCE(MAX(position), -1) FROM session_exercise WHERE session_id = :sessionId")
    suspend fun maxPosition(sessionId: Long): Int

    /**
     * The hot query (TRD §4): the latest completed exposures of an exercise, newest first. Sessions that
     * are still active or were discarded never count, and neither does a skipped exercise with no sets.
     */
    @Query(
        """
        SELECT se.* FROM session_exercise se JOIN workout_session ws ON ws.id = se.session_id
        WHERE se.exercise_id = :exerciseId AND ws.status = 'COMPLETED'
          AND EXISTS (SELECT 1 FROM set_entry s WHERE s.session_exercise_id = se.id AND s.completed_at IS NOT NULL)
        ORDER BY ws.started_at DESC, se.position LIMIT :limit
        """,
    )
    suspend fun lastCompletedExposures(exerciseId: Long, limit: Int): List<SessionExerciseEntity>

    /**
     * The completed sets of an exercise's latest [limit] exposures that started at or before
     * [maxStartedAt], newest exposure first, in one query: the progression engine's history.
     */
    @Query(
        """
        SELECT s.*, ws.id AS x_session_id, ws.started_at AS x_started_at, ws.log_date AS x_log_date, r.name AS x_routine_name
        FROM set_entry s
        JOIN session_exercise se ON se.id = s.session_exercise_id
        JOIN workout_session ws ON ws.id = se.session_id
        LEFT JOIN routine r ON r.id = ws.routine_id
        WHERE s.completed_at IS NOT NULL AND se.id IN (
            SELECT se2.id FROM session_exercise se2 JOIN workout_session ws2 ON ws2.id = se2.session_id
            WHERE se2.exercise_id = :exerciseId AND ws2.status = 'COMPLETED' AND ws2.started_at <= :maxStartedAt
              AND EXISTS (SELECT 1 FROM set_entry s2 WHERE s2.session_exercise_id = se2.id AND s2.completed_at IS NOT NULL)
            ORDER BY ws2.started_at DESC, se2.position LIMIT :limit
        )
        ORDER BY ws.started_at DESC, se.position, s.set_index
        """,
    )
    suspend fun exposureSets(exerciseId: Long, limit: Int, maxStartedAt: Long = Long.MAX_VALUE): List<ExposureSetRow>

    @Query("UPDATE session_exercise SET recommendation_json = :json WHERE id = :id")
    suspend fun setRecommendation(id: Long, json: String?)

    @Query("UPDATE session_exercise SET user_overrode = :overrode WHERE id = :id")
    suspend fun setOverrode(id: Long, overrode: Boolean)

    // Personal records (PO-04)

    @Insert
    suspend fun insertRecords(records: List<PersonalRecordEntity>)

    @Query("SELECT * FROM personal_record WHERE session_id = :sessionId ORDER BY id")
    fun observeSessionRecords(sessionId: Long): Flow<List<PersonalRecordEntity>>

    @Query("SELECT * FROM personal_record WHERE exercise_id = :exerciseId")
    suspend fun exerciseRecords(exerciseId: Long): List<PersonalRecordEntity>

    // Sets

    @Query("SELECT * FROM set_entry WHERE session_exercise_id = :sessionExerciseId AND completed_at IS NOT NULL ORDER BY set_index")
    suspend fun completedSets(sessionExerciseId: Long): List<SetEntryEntity>

    @Query("SELECT * FROM set_entry WHERE id = :id")
    suspend fun set(id: Long): SetEntryEntity?

    @Insert
    suspend fun insertSets(sets: List<SetEntryEntity>)

    @Insert
    suspend fun insertSet(set: SetEntryEntity): Long

    @Query("SELECT COALESCE(MAX(set_index), -1) FROM set_entry WHERE session_exercise_id = :sessionExerciseId")
    suspend fun maxSetIndex(sessionExerciseId: Long): Int

    /** Ticking a set (WL-02, WL-06) is a single UPDATE, so it's atomic and lands before the UI moves on. */
    @Query("UPDATE set_entry SET load_g = :loadG, reps = :reps, rir = :rir, completed_at = :completedAt WHERE id = :id")
    suspend fun completeSet(id: Long, loadG: Int?, reps: Int, rir: Int?, completedAt: Long)

    @Query("UPDATE set_entry SET completed_at = NULL WHERE id = :id")
    suspend fun uncompleteSet(id: Long)

    @Query("UPDATE set_entry SET load_g = :loadG, reps = :reps WHERE id = :id")
    suspend fun setValues(id: Long, loadG: Int?, reps: Int?)

    @Query("UPDATE set_entry SET rir = :rir WHERE id = :id")
    suspend fun setRir(id: Long, rir: Int?)

    @Query("UPDATE set_entry SET set_type = :type WHERE id = :id")
    suspend fun setType(id: Long, type: SetType)

    @Query("DELETE FROM set_entry WHERE id = :id")
    suspend fun deleteSet(id: Long)

    @Query("SELECT MAX(completed_at) FROM set_entry se JOIN session_exercise x ON x.id = se.session_exercise_id WHERE x.session_id = :sessionId")
    suspend fun lastSetCompletedAt(sessionId: Long): Long?

    @Query("DELETE FROM set_entry WHERE completed_at IS NULL AND session_exercise_id IN (SELECT id FROM session_exercise WHERE session_id = :sessionId)")
    suspend fun deleteIncompleteSets(sessionId: Long)

    @Query("UPDATE workout_session SET status = :status, ended_at = :endedAt WHERE id = :id")
    suspend fun setStatus(id: Long, status: SessionStatus, endedAt: Long)

    /**
     * Finishing (TRD §6.1): unticked sets are dropped, so history only holds what you did. An exercise
     * with no sets stays as a record that it was skipped; exposures without sets never count.
     */
    @Transaction
    suspend fun finish(sessionId: Long, endedAt: Long) {
        deleteIncompleteSets(sessionId)
        setStatus(sessionId, SessionStatus.COMPLETED, endedAt)
    }

    // Extra sessions

    @Insert
    suspend fun insertYogaDetail(detail: YogaDetailEntity)

    @Query("SELECT * FROM yoga_detail WHERE session_id = :sessionId")
    suspend fun yogaDetail(sessionId: Long): YogaDetailEntity?

    @Transaction
    suspend fun insertExtraSession(session: WorkoutSessionEntity, detail: YogaDetailEntity): Long {
        val id = insertSession(session)
        insertYogaDetail(detail.copy(sessionId = id))
        return id
    }
}
