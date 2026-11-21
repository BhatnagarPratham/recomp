package app.recomp.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/**
 * One weekly programme review (HM-06, TRD §10 M11): what was sent, what came back and what you applied. [sections]
 * is the checklist as ticked ("goal,food,training"), [sentText] the full text exactly as previewed, and
 * [responseJson] the reply, or null when the request failed ([aiRequestId] has its status and cost). [appliedJson]
 * lists the suggestions you applied, by their index in the reply.
 */
@Entity(tableName = "programme_review", indices = [Index("week_start")])
data class ProgrammeReviewEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "week_start") val weekStart: LocalDate,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val sections: String,
    @ColumnInfo(name = "sent_text") val sentText: String,
    @ColumnInfo(name = "ai_request_id") val aiRequestId: Long?,
    @ColumnInfo(name = "response_json") val responseJson: String? = null,
    @ColumnInfo(name = "applied_json", defaultValue = "'[]'") val appliedJson: String = "[]",
)

@Dao
interface ProgrammeReviewDao {
    @Insert
    suspend fun insert(row: ProgrammeReviewEntity): Long

    @Update
    suspend fun update(row: ProgrammeReviewEntity)

    @Query("SELECT * FROM programme_review WHERE id = :id")
    suspend fun get(id: Long): ProgrammeReviewEntity?

    @Query("SELECT * FROM programme_review WHERE id = :id")
    fun observe(id: Long): Flow<ProgrammeReviewEntity?>

    /** Every review, newest first, for the history. */
    @Query("SELECT * FROM programme_review ORDER BY created_at DESC, id DESC")
    fun observeAll(): Flow<List<ProgrammeReviewEntity>>

    /** Exercises a deload is waiting on (M11): the review's or the scheduled deload week's. */
    @Query("UPDATE exercise SET deload_pending = 1 WHERE id IN (:ids)")
    suspend fun markDeload(ids: List<Long>)

    @Query("UPDATE exercise SET deload_pending = 0 WHERE id IN (:ids)")
    suspend fun clearDeload(ids: List<Long>)

    @Query("SELECT id FROM exercise WHERE deload_pending = 1")
    suspend fun pendingDeloads(): List<Long>

    /** Completed working sets per exercise in [from]..[to], with its muscles, for sets per muscle (TRD §5.5). */
    @Query(
        """
        SELECT e.primary_muscle AS primary_muscle, e.secondary_muscles AS secondary_muscles, COUNT(*) AS sets
        FROM set_entry s
        JOIN session_exercise se ON se.id = s.session_exercise_id
        JOIN workout_session ws ON ws.id = se.session_id
        JOIN exercise e ON e.id = se.exercise_id
        WHERE ws.status = 'COMPLETED' AND ws.log_date BETWEEN :from AND :to AND s.completed_at IS NOT NULL AND s.set_type = 'WORKING'
        GROUP BY e.id
        """,
    )
    suspend fun workingSets(from: LocalDate, to: LocalDate): List<MuscleSetsRow>

    /** The day of the first completed strength workout: where the scheduled deload counts from. */
    @Query("SELECT MIN(log_date) FROM workout_session WHERE status = 'COMPLETED' AND kind = 'STRENGTH'")
    suspend fun firstStrengthDay(): LocalDate?

    /** Every exercise in the active rotation's routines: a deload week marks them all. */
    @Query(
        """
        SELECT DISTINCT re.exercise_id FROM routine_exercise re JOIN routine r ON r.id = re.routine_id
        WHERE r.archived = 0 AND r.kind = 'STRENGTH'
        """,
    )
    suspend fun rotationExercises(): List<Long>
}

data class MuscleSetsRow(
    @ColumnInfo(name = "primary_muscle") val primaryMuscle: String,
    @ColumnInfo(name = "secondary_muscles") val secondaryMuscles: List<String>,
    val sets: Int,
)
