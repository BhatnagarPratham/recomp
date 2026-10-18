package app.recomp.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import app.recomp.domain.checkin.CheckinFlag
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/** What became of a week's check-in (PRD §12.1). Anything but PENDING is frozen. */
enum class CheckinDecision {
    PENDING,
    ACCEPTED,
    ADJUSTED,
    KEPT,

    /** Not answered before the next week's check-in was ready. Its estimate still counts. */
    LAPSED,
}

/**
 * One week's check-in (NU-05, GL-08, TRD §10 M5), keyed by the week's Monday. [tdeeNew] is the running
 * maintenance estimate the next week starts from ([tdeePrev] is the previous row's, or the first target's
 * TDEE). [proposedKcal] is set only when the goal's flag fired and the change is 50 kcal or more.
 * [targetId] is the version that *Accept* or *Adjust* wrote; a later change the same day replaces that version.
 */
@Entity(tableName = "weekly_checkin")
data class WeeklyCheckinEntity(
    @PrimaryKey @ColumnInfo(name = "week_start") val weekStart: LocalDate,
    @ColumnInfo(name = "computed_at") val computedAt: Long,
    @ColumnInfo(name = "window_days") val windowDays: Int,
    @ColumnInfo(name = "logged_days") val loggedDays: Int,
    @ColumnInfo(name = "weigh_ins") val weighIns: Int,
    @ColumnInfo(name = "kcal_mean") val kcalMean: Double? = null,
    @ColumnInfo(name = "trend_start_g") val trendStartG: Int? = null,
    @ColumnInfo(name = "trend_end_g") val trendEndG: Int? = null,
    @ColumnInfo(name = "tdee_obs") val tdeeObs: Int? = null,
    @ColumnInfo(name = "tdee_prev") val tdeePrev: Int,
    @ColumnInfo(name = "tdee_new") val tdeeNew: Int,
    val flag: CheckinFlag? = null,
    @ColumnInfo(name = "proposed_kcal") val proposedKcal: Int? = null,
    val decision: CheckinDecision = CheckinDecision.PENDING,
    @ColumnInfo(name = "decided_at") val decidedAt: Long? = null,
    @ColumnInfo(name = "target_id") val targetId: Long? = null,
) {
    val weekEnd: LocalDate get() = weekStart.plusDays(6)
    val pending: Boolean get() = decision == CheckinDecision.PENDING
}

/**
 * Per-day facts that aren't food or weigh-ins (TRD §4): whether you marked the day's food incomplete (PRD §12.1),
 * and M13's steps and sleep, so that milestone needs no migration.
 */
@Entity(tableName = "daily_activity")
data class DailyActivityEntity(
    @PrimaryKey @ColumnInfo(name = "log_date") val logDate: LocalDate,
    @ColumnInfo(name = "food_incomplete") val foodIncomplete: Boolean = false,
    val steps: Int? = null,
    @ColumnInfo(name = "steps_synced_at") val stepsSyncedAt: Long? = null,
    @ColumnInfo(name = "sleep_hours") val sleepHours: Double? = null,
)

@Dao
interface CheckinDao {
    @Query("SELECT * FROM weekly_checkin ORDER BY week_start DESC LIMIT 1")
    fun observeLatest(): Flow<WeeklyCheckinEntity?>

    @Query("SELECT * FROM weekly_checkin ORDER BY week_start DESC LIMIT 1")
    suspend fun latest(): WeeklyCheckinEntity?

    @Query("SELECT * FROM weekly_checkin WHERE week_start = :weekStart")
    suspend fun week(weekStart: LocalDate): WeeklyCheckinEntity?

    @Query("SELECT * FROM weekly_checkin WHERE week_start = :weekStart")
    fun observeWeek(weekStart: LocalDate): Flow<WeeklyCheckinEntity?>

    /** The week before [weekStart] that has a row: the estimate this one starts from. */
    @Query("SELECT * FROM weekly_checkin WHERE week_start < :weekStart ORDER BY week_start DESC LIMIT 1")
    suspend fun before(weekStart: LocalDate): WeeklyCheckinEntity?

    @Query("SELECT * FROM weekly_checkin ORDER BY week_start")
    suspend fun all(): List<WeeklyCheckinEntity>

    /** The latest week with a maintenance estimate of its own, whatever its decision: what Settings' targets use. */
    @Query("SELECT * FROM weekly_checkin WHERE tdee_obs IS NOT NULL ORDER BY week_start DESC LIMIT 1")
    fun observeLatestEstimate(): Flow<WeeklyCheckinEntity?>

    @Query("SELECT * FROM weekly_checkin WHERE tdee_obs IS NOT NULL ORDER BY week_start DESC LIMIT 1")
    suspend fun latestEstimate(): WeeklyCheckinEntity?

    @Upsert
    suspend fun upsert(row: WeeklyCheckinEntity)

    /**
     * Weeks from [monday] on haven't ended, so a row for one means the clock ran ahead. The unanswered ones go (pending,
     * or lapsed by the app); an answer you gave stays.
     */
    @Query("DELETE FROM weekly_checkin WHERE week_start >= :monday AND decision IN ('PENDING', 'LAPSED')")
    suspend fun dropUnansweredFrom(monday: LocalDate)

    /** Days in [from]..[to] marked incomplete. */
    @Query("SELECT log_date FROM daily_activity WHERE food_incomplete = 1 AND log_date BETWEEN :from AND :to")
    suspend fun incompleteDays(from: LocalDate, to: LocalDate): List<LocalDate>

    @Query("SELECT log_date FROM daily_activity WHERE food_incomplete = 1 AND log_date BETWEEN :from AND :to")
    fun observeIncompleteDays(from: LocalDate, to: LocalDate): Flow<List<LocalDate>>

    @Query("SELECT * FROM daily_activity WHERE log_date = :date")
    suspend fun activity(date: LocalDate): DailyActivityEntity?

    @Upsert
    suspend fun upsert(activity: DailyActivityEntity)

    /** With [setFoodIncomplete], in one transaction: the flag changes without rewriting the day's other columns. */
    @Query("INSERT OR IGNORE INTO daily_activity (log_date, food_incomplete) VALUES (:date, 0)")
    suspend fun ensureActivity(date: LocalDate)

    @Query("UPDATE daily_activity SET food_incomplete = :incomplete WHERE log_date = :date")
    suspend fun setFoodIncomplete(date: LocalDate, incomplete: Boolean)
}
