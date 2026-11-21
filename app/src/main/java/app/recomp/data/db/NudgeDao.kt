package app.recomp.data.db

import androidx.room.Dao
import androidx.room.Query
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/** What reminders and the evening nudge ask of a day (PRD §12.8). Queries only: M12 adds no table. */
@Dao
interface NudgeDao {
    /** "You've weighed in today": any source counts. */
    @Query("SELECT EXISTS(SELECT 1 FROM body_weight WHERE log_date = :date)")
    suspend fun weighedIn(date: LocalDate): Boolean

    @Query("SELECT COUNT(*) FROM body_weight WHERE log_date = :date")
    fun observeWeighIns(date: LocalDate): Flow<Int>

    /** The meal slots with an entry on [date]. */
    @Query("SELECT DISTINCT meal_slot FROM food_log WHERE log_date = :date")
    suspend fun mealsLogged(date: LocalDate): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM body_composition WHERE log_date = :date)")
    suspend fun scanned(date: LocalDate): Boolean

    @Query("SELECT COUNT(*) FROM body_composition WHERE log_date = :date")
    fun observeScans(date: LocalDate): Flow<Int>

    /** M13 fills this; null until then, which hides the steps part of the nudge. */
    @Query("SELECT steps FROM daily_activity WHERE log_date = :date")
    suspend fun steps(date: LocalDate): Int?

    @Query("SELECT steps FROM daily_activity WHERE log_date = :date")
    fun observeSteps(date: LocalDate): Flow<Int?>

    /** NU-23: your most-logged foods that the diet and region allow. */
    @Query(
        """
        SELECT * FROM food WHERE archived = 0 AND use_count > 0 AND diet IN (:diets) AND (region IS NULL OR region = :region)
        ORDER BY use_count DESC, last_used_at DESC, id LIMIT :limit
        """,
    )
    suspend fun mostLogged(diets: List<String>, region: String, limit: Int): List<FoodEntity>
}
