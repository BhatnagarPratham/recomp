package app.recomp.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Update
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/** One line of the History list (PRD §12.6): a completed workout or extra session. */
data class HistoryRow(
    @Embedded val session: WorkoutSessionEntity,
    @ColumnInfo(name = "x_routine_name") val routineName: String?,
    /** Exercises with at least one logged set, and logged sets that aren't warm-ups. */
    @ColumnInfo(name = "x_exercises") val exercises: Int,
    @ColumnInfo(name = "x_sets") val sets: Int,
    @ColumnInfo(name = "x_style") val style: String?,
    @ColumnInfo(name = "x_duration_min") val durationMin: Int?,
)

/** A completed set with its session, for rebuilding an exercise's PRs (WL-10). */
data class RebuildSetRow(
    @Embedded val set: SetEntryEntity,
    @ColumnInfo(name = "x_session_id") val sessionId: Long,
    @ColumnInfo(name = "x_started_at") val startedAt: Long,
    @ColumnInfo(name = "x_ended_at") val endedAt: Long?,
)

/** An exercise's working sets in one completed session, with its muscles (PO-10). */
data class VolumeSetsRow(
    @ColumnInfo(name = "log_date") val logDate: LocalDate,
    @ColumnInfo(name = "primary_muscle") val primaryMuscle: String,
    @ColumnInfo(name = "secondary_muscles") val secondaryMuscles: List<String>,
    @ColumnInfo(name = "x_sets") val workingSets: Int,
)

/** Reads and writes behind training history (M10): the list, editing and deleting, PR rebuilds, weekly volume. */
@Dao
interface HistoryDao {
    @Query(
        """
        SELECT ws.*, r.name AS x_routine_name, y.style AS x_style, y.duration_min AS x_duration_min,
          (SELECT COUNT(*) FROM session_exercise se WHERE se.session_id = ws.id
             AND EXISTS (SELECT 1 FROM set_entry s WHERE s.session_exercise_id = se.id AND s.completed_at IS NOT NULL)) AS x_exercises,
          (SELECT COUNT(*) FROM set_entry s JOIN session_exercise se ON se.id = s.session_exercise_id
             WHERE se.session_id = ws.id AND s.completed_at IS NOT NULL AND s.set_type != 'WARMUP') AS x_sets
        FROM workout_session ws
        LEFT JOIN routine r ON r.id = ws.routine_id
        LEFT JOIN yoga_detail y ON y.session_id = ws.id
        WHERE ws.status = 'COMPLETED'
        ORDER BY ws.started_at DESC, ws.id DESC LIMIT :limit
        """,
    )
    fun observeHistory(limit: Int): Flow<List<HistoryRow>>

    @Query("SELECT COUNT(*) FROM workout_session WHERE status = 'COMPLETED'")
    fun observeHistoryCount(): Flow<Int>

    /** Every completed set of an exercise, oldest session first: the whole history a PR rebuild reads. */
    @Query(
        """
        SELECT s.*, ws.id AS x_session_id, ws.started_at AS x_started_at, ws.ended_at AS x_ended_at
        FROM set_entry s
        JOIN session_exercise se ON se.id = s.session_exercise_id
        JOIN workout_session ws ON ws.id = se.session_id
        WHERE se.exercise_id = :exerciseId AND ws.status = 'COMPLETED' AND s.completed_at IS NOT NULL
        ORDER BY ws.started_at, ws.id, se.position, se.id, s.set_index
        """,
    )
    suspend fun allCompletedSets(exerciseId: Long): List<RebuildSetRow>

    /** Drops an exercise's PRs in sessions that start at or after [from], before they're rebuilt. */
    @Query("DELETE FROM personal_record WHERE exercise_id = :exerciseId AND session_id IN (SELECT id FROM workout_session WHERE started_at >= :from)")
    suspend fun deleteRecordsFrom(exerciseId: Long, from: Long)

    @Query("SELECT DISTINCT exercise_id FROM session_exercise UNION SELECT DISTINCT exercise_id FROM personal_record")
    suspend fun exercisesWithHistory(): List<Long>

    /** Cascades to the session's exercises, sets, PRs and extra-session details. */
    @Query("DELETE FROM workout_session WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Update
    suspend fun updateSets(sets: List<SetEntryEntity>)

    @Query("SELECT * FROM set_entry WHERE session_exercise_id = :sessionExerciseId ORDER BY set_index, id")
    suspend fun sets(sessionExerciseId: Long): List<SetEntryEntity>

    @Update
    suspend fun updateYogaDetail(detail: YogaDetailEntity)

    /** PO-10: working sets per exercise per completed session, for log dates in [from, to). */
    @Query(
        """
        SELECT ws.log_date AS log_date, e.primary_muscle AS primary_muscle, e.secondary_muscles AS secondary_muscles, COUNT(s.id) AS x_sets
        FROM set_entry s
        JOIN session_exercise se ON se.id = s.session_exercise_id
        JOIN workout_session ws ON ws.id = se.session_id
        JOIN exercise e ON e.id = se.exercise_id
        WHERE ws.status = 'COMPLETED' AND s.completed_at IS NOT NULL AND s.set_type = 'WORKING'
          AND ws.log_date >= :from AND ws.log_date < :to
        GROUP BY se.id
        """,
    )
    fun observeWorkingSets(from: LocalDate, to: LocalDate): Flow<List<VolumeSetsRow>>

    /** The primary muscles your rotation trains, so the weekly card can show one at zero. */
    @Query(
        """
        SELECT DISTINCT e.primary_muscle FROM routine_exercise re
        JOIN routine r ON r.id = re.routine_id JOIN exercise e ON e.id = re.exercise_id
        WHERE r.archived = 0 AND r.kind = 'STRENGTH'
        """,
    )
    fun observeRotationMuscles(): Flow<List<String>>
}
