package app.recomp.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import app.recomp.domain.food.Origin
import app.recomp.domain.model.LoadType
import app.recomp.domain.progression.PrType
import java.time.LocalDate

/** A PR set in the week, with its exercise (HM-03). */
data class WeekRecordRow(
    val exercise: String,
    @ColumnInfo(name = "load_type") val loadType: LoadType,
    val type: PrType,
    val value: Long,
    @ColumnInfo(name = "load_g") val loadG: Int?,
)

/** An exercise's frozen recommendation in one of the week's workouts: its stall card showed if it says stalled (PO-05). */
data class WeekRecommendationRow(val exercise: String, val recommendation: String)

data class OriginKcalRow(val origin: Origin, val kcal: Double)

/** Steps and sleep over the week (M13 fills `daily_activity`); the counts say how many days had each. */
data class WeekActivityRow(
    @ColumnInfo(name = "steps_avg") val stepsAvg: Double?,
    @ColumnInfo(name = "steps_days") val stepsDays: Int,
    @ColumnInfo(name = "sleep_avg") val sleepAvg: Double?,
    @ColumnInfo(name = "sleep_days") val sleepDays: Int,
)

/**
 * The weekly review's read-only aggregates (HM-03, HM-04, TRD §10 M8). Each covers one week or month of log dates:
 * `food_log(log_date)` and `daily_activity`'s key are indexed, and the workout tables are small.
 */
@Dao
interface ReviewDao {
    /** Completed sessions in [from]..[to], for the weekly plan and the calendar. */
    @Query("SELECT log_date, kind, routine_id FROM workout_session WHERE status = 'COMPLETED' AND log_date BETWEEN :from AND :to")
    suspend fun sessions(from: LocalDate, to: LocalDate): List<PlanSessionRow>

    @Query(
        """
        SELECT e.name AS exercise, e.load_type AS load_type, pr.type AS type, pr.value AS value, pr.load_g AS load_g
        FROM personal_record pr
        JOIN workout_session s ON s.id = pr.session_id
        JOIN exercise e ON e.id = pr.exercise_id
        WHERE s.status = 'COMPLETED' AND s.log_date BETWEEN :from AND :to
        ORDER BY pr.achieved_at, pr.id
        """,
    )
    suspend fun records(from: LocalDate, to: LocalDate): List<WeekRecordRow>

    @Query(
        """
        SELECT e.name AS exercise, x.recommendation_json AS recommendation
        FROM session_exercise x
        JOIN workout_session s ON s.id = x.session_id
        JOIN exercise e ON e.id = x.exercise_id
        WHERE s.status = 'COMPLETED' AND s.log_date BETWEEN :from AND :to AND x.recommendation_json IS NOT NULL
        ORDER BY s.started_at, x.position
        """,
    )
    suspend fun recommendations(from: LocalDate, to: LocalDate): List<WeekRecommendationRow>

    /** Calories by origin, leaving out days marked incomplete, as the week's averages do (PRD §12.1). */
    @Query(
        """
        SELECT origin, SUM(kcal) AS kcal FROM food_log
        WHERE log_date BETWEEN :from AND :to
          AND log_date NOT IN (SELECT log_date FROM daily_activity WHERE food_incomplete = 1)
        GROUP BY origin
        """,
    )
    suspend fun kcalByOrigin(from: LocalDate, to: LocalDate): List<OriginKcalRow>

    /** Days with any food logged, for the calendar's tint. */
    @Query("SELECT DISTINCT log_date FROM food_log WHERE log_date BETWEEN :from AND :to")
    suspend fun foodDays(from: LocalDate, to: LocalDate): List<LocalDate>

    @Query(
        """
        SELECT AVG(steps) AS steps_avg, COUNT(steps) AS steps_days, AVG(sleep_hours) AS sleep_avg, COUNT(sleep_hours) AS sleep_days
        FROM daily_activity WHERE log_date BETWEEN :from AND :to
        """,
    )
    suspend fun activity(from: LocalDate, to: LocalDate): WeekActivityRow
}
