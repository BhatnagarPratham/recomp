package app.recomp.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import app.recomp.domain.model.SessionKind
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/** Where a night's `sleep_hours` came from (AR-02). A typed value wins: a sync never writes over it. */
enum class SleepSource { TYPED, HEALTH_CONNECT }

/**
 * An InBody measurement read from Health Connect (IB-07), waiting for the numbers it has no type for: skeletal
 * muscle and visceral fat. Deleted once it's saved as a scan or dismissed. [hcIds] are the records it came from.
 */
@Entity(tableName = "scan_draft")
data class ScanDraftEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "measured_at") val measuredAt: Long,
    @ColumnInfo(name = "weight_g") val weightG: Int,
    @ColumnInfo(name = "body_fat_pct") val bodyFatPct: Double? = null,
    @ColumnInfo(name = "bmr_kcal") val bmrKcal: Int? = null,
    @ColumnInfo(name = "hc_ids") val hcIds: List<String> = emptyList(),
)

/** A session another app wrote to Health Connect (AR-04), as Today and History list it. */
data class ImportedSessionRow(
    val id: Long,
    @ColumnInfo(name = "log_date") val logDate: LocalDate,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    val style: String,
    @ColumnInfo(name = "duration_min") val durationMin: Int,
    val notes: String?,
)

data class SessionTimes(@ColumnInfo(name = "started_at") val startedAt: Long, @ColumnInfo(name = "ended_at") val endedAt: Long?)

/** What Health Connect fills (TRD §10 M13): steps and sleep in `daily_activity`, scan drafts, and other apps' sessions. */
@Dao
interface HealthDao {
    // Steps and sleep

    @Query("SELECT * FROM daily_activity WHERE log_date BETWEEN :from AND :to ORDER BY log_date")
    fun observeActivity(from: LocalDate, to: LocalDate): Flow<List<DailyActivityEntity>>

    @Query("SELECT * FROM daily_activity WHERE log_date BETWEEN :from AND :to ORDER BY log_date")
    suspend fun activity(from: LocalDate, to: LocalDate): List<DailyActivityEntity>

    @Query("SELECT * FROM daily_activity WHERE log_date = :date")
    fun observeDay(date: LocalDate): Flow<DailyActivityEntity?>

    @Query("SELECT * FROM daily_activity WHERE log_date = :date")
    suspend fun day(date: LocalDate): DailyActivityEntity?

    /** Each writer below changes only its own columns, so the day's other facts stay as they are. */
    @Query("INSERT OR IGNORE INTO daily_activity (log_date, food_incomplete) VALUES (:date, 0)")
    suspend fun ensureDay(date: LocalDate)

    @Query("UPDATE daily_activity SET steps = :steps, steps_synced_at = :syncedAt WHERE log_date = :date")
    suspend fun setSteps(date: LocalDate, steps: Int?, syncedAt: Long)

    @Query("UPDATE daily_activity SET sleep_hours = :hours, sleep_source = 'TYPED' WHERE log_date = :date")
    suspend fun setTypedSleep(date: LocalDate, hours: Double)

    @Query("UPDATE daily_activity SET sleep_hours = NULL, sleep_source = NULL WHERE log_date = :date")
    suspend fun clearSleep(date: LocalDate)

    /** A synced night never replaces one you typed. Returns how many rows it changed. */
    @Query("UPDATE daily_activity SET sleep_hours = :hours, sleep_source = 'HEALTH_CONNECT' WHERE log_date = :date AND (sleep_source IS NULL OR sleep_source != 'TYPED')")
    suspend fun setSyncedSleep(date: LocalDate, hours: Double): Int

    /** The tracker's session is gone, so its night is too. A typed night stays. */
    @Query("UPDATE daily_activity SET sleep_hours = NULL, sleep_source = NULL WHERE log_date = :date AND sleep_source = 'HEALTH_CONNECT'")
    suspend fun clearSyncedSleep(date: LocalDate)

    // InBody drafts (IB-07)

    @Query("SELECT * FROM scan_draft ORDER BY measured_at DESC")
    fun observeDrafts(): Flow<List<ScanDraftEntity>>

    @Query("SELECT * FROM scan_draft ORDER BY measured_at")
    suspend fun drafts(): List<ScanDraftEntity>

    @Query("SELECT * FROM scan_draft WHERE id = :id")
    suspend fun draft(id: Long): ScanDraftEntity?

    @Insert
    suspend fun insert(draft: ScanDraftEntity): Long

    @Update
    suspend fun update(draft: ScanDraftEntity)

    @Query("DELETE FROM scan_draft WHERE id = :id")
    suspend fun deleteDraft(id: Long)

    @Query("SELECT measured_at FROM body_composition")
    suspend fun scanTimes(): List<Long>

    // Other apps' sessions (AR-04): rows with hc_record_id set

    @Query("SELECT * FROM workout_session WHERE hc_record_id = :recordId LIMIT 1")
    suspend fun imported(recordId: String): WorkoutSessionEntity?

    /** The same session under another record id, as after Health Connect's own restore on a new phone. */
    @Query("SELECT * FROM workout_session WHERE hc_record_id IS NOT NULL AND started_at = :startedAt AND ended_at = :endedAt LIMIT 1")
    suspend fun importedAt(startedAt: Long, endedAt: Long): WorkoutSessionEntity?

    @Query("SELECT * FROM workout_session WHERE hc_record_id IS NOT NULL AND started_at >= :since")
    suspend fun importedSince(since: Long): List<WorkoutSessionEntity>

    /** `yoga_detail` goes with it (ON DELETE CASCADE). */
    @Query("DELETE FROM workout_session WHERE hc_record_id = :recordId")
    suspend fun deleteImported(recordId: String)

    @Query("DELETE FROM workout_session WHERE id = :id AND hc_record_id IS NOT NULL")
    suspend fun deleteImportedRow(id: Long)

    @Query("UPDATE yoga_detail SET style = :style, duration_min = :durationMin WHERE session_id = :sessionId")
    suspend fun setDetail(sessionId: Long, style: String, durationMin: Int)

    /**
     * Strength workouts you logged in Recomp that overlap [start]..[end]; one still in progress runs until [now]. Only
     * strength: its times are when you trained. An extra's are made up from *Save* minus its length (YG-01), so
     * extras are matched by day and kind instead ([ownExtraOn]).
     */
    @Query(
        "SELECT started_at, ended_at FROM workout_session WHERE hc_record_id IS NULL AND kind = 'STRENGTH' AND status != 'DISCARDED' " +
            "AND started_at < :end AND COALESCE(ended_at, :now) > :start",
    )
    suspend fun ownStrengthOverlapping(start: Long, end: Long, now: Long): List<SessionTimes>

    /** Whether you logged an extra session of [kind] yourself on [date]: an import of that kind then counts toward nothing. */
    @Query("SELECT EXISTS(SELECT 1 FROM workout_session WHERE hc_record_id IS NULL AND status = 'COMPLETED' AND log_date = :date AND kind = :kind)")
    suspend fun ownExtraOn(date: LocalDate, kind: SessionKind): Boolean

    /** Other imports of [kind] overlapping [start]..[end], [exceptId] aside: possibly another app's copy of the same session. */
    @Query(
        "SELECT * FROM workout_session WHERE hc_record_id IS NOT NULL AND kind = :kind AND id != :exceptId " +
            "AND started_at < :end AND COALESCE(ended_at, started_at) > :start",
    )
    suspend fun importedOverlapping(kind: SessionKind, start: Long, end: Long, exceptId: Long): List<WorkoutSessionEntity>

    @Query("UPDATE workout_session SET routine_id = :routineId WHERE id = :id")
    suspend fun setRoutine(id: Long, routineId: Long?)

    @Query(
        "SELECT ws.id, ws.log_date, ws.started_at, d.style, d.duration_min, ws.notes FROM workout_session ws " +
            "JOIN yoga_detail d ON d.session_id = ws.id WHERE ws.hc_record_id IS NOT NULL AND ws.status = 'COMPLETED' AND ws.log_date = :date " +
            "ORDER BY ws.started_at",
    )
    fun observeImportedOn(date: LocalDate): Flow<List<ImportedSessionRow>>
}
