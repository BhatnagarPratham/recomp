package app.recomp.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/**
 * Foods and their units (TRD §6.4). Search filters take diet tags and the region as enum names. Hidden
 * foods are archived rows; they never show in search.
 */
@Dao
interface FoodDao {
    /**
     * NU-11. [match] is an FTS4 expression (`FoodQuery.match`) and [prefix] the query with LIKE wildcards
     * escaped. Foods you've logged come first, then custom, AI-learned, the basics and the databases,
     * caveat foods last among those, then names that start with the query, then shorter names.
     */
    @Query(
        """
        SELECT food.* FROM food JOIN food_fts ON food.id = food_fts.rowid
        WHERE food_fts MATCH :match AND food.archived = 0 AND food.diet IN (:diets)
          AND (food.region IS NULL OR food.region = :region)
        ORDER BY food.use_count > 0 DESC, food.use_count DESC, food.last_used_at DESC,
          CASE food.source WHEN 'CUSTOM' THEN 0 WHEN 'AI' THEN 1 WHEN 'SEED' THEN 2 ELSE 3 END,
          food.caveat IS NOT NULL,
          CASE WHEN food.name LIKE :prefix || '%' ESCAPE '\' THEN 0 ELSE 1 END,
          length(food.name), food.name
        LIMIT :limit
        """,
    )
    suspend fun search(match: String, prefix: String, diets: List<String>, region: String, limit: Int): List<FoodEntity>

    /** Foods you've logged, most recent first: the empty search. */
    @Query(
        """
        SELECT * FROM food WHERE archived = 0 AND use_count > 0 AND diet IN (:diets) AND (region IS NULL OR region = :region)
        ORDER BY last_used_at DESC LIMIT :limit
        """,
    )
    suspend fun recent(diets: List<String>, region: String, limit: Int): List<FoodEntity>

    /** The protein-rich basics for a diet, by protein per 100 kcal: the empty search on a fresh install. */
    @Query(
        """
        SELECT * FROM food WHERE archived = 0 AND source = 'SEED' AND diet IN (:diets) AND (region IS NULL OR region = :region)
        ORDER BY protein_100g * 100.0 / max(kcal_100g, 1) DESC LIMIT :limit
        """,
    )
    suspend fun basics(diets: List<String>, region: String, limit: Int): List<FoodEntity>

    @Query("SELECT * FROM food WHERE id = :id")
    suspend fun get(id: Long): FoodEntity?

    @Query("SELECT * FROM food WHERE id IN (:ids)")
    suspend fun get(ids: List<Long>): List<FoodEntity>

    @Query("SELECT * FROM food WHERE source = :source")
    suspend fun bySource(source: String): List<FoodEntity>

    @Insert
    suspend fun insert(food: FoodEntity): Long

    @Insert
    suspend fun insertAll(foods: List<FoodEntity>): List<Long>

    @Update
    suspend fun update(food: FoodEntity)

    @Update
    suspend fun updateAll(foods: List<FoodEntity>)

    /** [times] counts a food logged more than once in one go (a saved meal or a copied day). */
    @Query("UPDATE food SET use_count = use_count + :times, last_used_at = :at WHERE id = :id")
    suspend fun markUsed(id: Long, times: Int, at: Long)

    /** Takes back an undone log: the count goes down, and "last used" falls back to the food's latest remaining entry. */
    @Query(
        """
        UPDATE food SET use_count = max(0, use_count - :times),
          last_used_at = (SELECT max(logged_at) FROM food_log WHERE food_id = :id)
        WHERE id = :id
        """,
    )
    suspend fun unmarkUsed(id: Long, times: Int)

    @Query("UPDATE food SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)

    @Query("SELECT * FROM food WHERE archived = 1 ORDER BY name")
    fun observeHidden(): Flow<List<FoodEntity>>

    @Query("SELECT COUNT(*) FROM food WHERE source = :source")
    fun observeCount(source: String): Flow<Int>

    // Units

    @Query("SELECT * FROM food_unit WHERE food_id = :foodId ORDER BY id")
    suspend fun units(foodId: Long): List<FoodUnitEntity>

    @Query("SELECT * FROM food_unit WHERE food_id IN (:foodIds) ORDER BY id")
    suspend fun units(foodIds: List<Long>): List<FoodUnitEntity>

    @Query("SELECT food_unit.* FROM food_unit JOIN food ON food.id = food_unit.food_id WHERE food.source = :source")
    suspend fun unitsOfSource(source: String): List<FoodUnitEntity>

    /** Replaces the unit with the same food and label (the unique index). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUnits(units: List<FoodUnitEntity>)

    @Query("DELETE FROM food_unit WHERE food_id = :foodId")
    suspend fun deleteUnits(foodId: Long)

    @Query("DELETE FROM food_unit WHERE food_id = :foodId AND calibrated = 0")
    suspend fun deleteUncalibratedUnits(foodId: Long)
}

@Dao
interface FoodLogDao {
    @Query("SELECT * FROM food_log WHERE log_date = :date ORDER BY logged_at, id")
    fun observeDay(date: LocalDate): Flow<List<FoodLogEntity>>

    @Query("SELECT * FROM food_log WHERE log_date = :date ORDER BY logged_at, id")
    suspend fun day(date: LocalDate): List<FoodLogEntity>

    /** kcal and protein per day with food logged, for Today, the 7-day average and the ramp (TRD §5.9). */
    @Query("SELECT log_date, SUM(kcal) AS kcal, SUM(protein_g) AS protein_g FROM food_log WHERE log_date BETWEEN :from AND :to GROUP BY log_date")
    fun observeIntake(from: LocalDate, to: LocalDate): Flow<List<DayIntakeRow>>

    /** The days before [before] with food logged, newest first (NU-13's copy). */
    @Query(
        """
        SELECT log_date, COUNT(*) AS entries, SUM(kcal) AS kcal, SUM(protein_g) AS protein_g FROM food_log
        WHERE log_date < :before GROUP BY log_date ORDER BY log_date DESC LIMIT :limit
        """,
    )
    suspend fun daysBefore(before: LocalDate, limit: Int): List<LoggedDayRow>

    /** The last time a food was logged, for the portion sheet's prefill. */
    @Query("SELECT * FROM food_log WHERE food_id = :foodId ORDER BY logged_at DESC, id DESC LIMIT 1")
    suspend fun lastOf(foodId: Long): FoodLogEntity?

    @Query("SELECT * FROM food_log WHERE id = :id")
    suspend fun get(id: Long): FoodLogEntity?

    @Query("SELECT * FROM food_log WHERE id IN (:ids) ORDER BY logged_at, id")
    suspend fun get(ids: List<Long>): List<FoodLogEntity>

    @Insert
    suspend fun insert(row: FoodLogEntity): Long

    @Insert
    suspend fun insertAll(rows: List<FoodLogEntity>): List<Long>

    @Update
    suspend fun update(row: FoodLogEntity)

    @Query("DELETE FROM food_log WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)
}

@Dao
interface SavedMealDao {
    @Transaction
    @Query("SELECT * FROM saved_meal ORDER BY use_count DESC, last_used_at DESC, name")
    fun observeAll(): Flow<List<SavedMealWithItems>>

    @Transaction
    @Query("SELECT * FROM saved_meal WHERE id = :id")
    suspend fun get(id: Long): SavedMealWithItems?

    @Query("SELECT * FROM saved_meal WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun byName(name: String): SavedMealEntity?

    @Insert
    suspend fun insert(meal: SavedMealEntity): Long

    @Insert
    suspend fun insertItems(items: List<SavedMealItemEntity>)

    @Query("DELETE FROM saved_meal_item WHERE saved_meal_id = :mealId")
    suspend fun deleteItems(mealId: Long)

    @Query("DELETE FROM saved_meal WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE saved_meal SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE saved_meal SET default_slot = :slot WHERE id = :id")
    suspend fun setDefaultSlot(id: Long, slot: String)

    @Query("UPDATE saved_meal SET use_count = use_count + 1, last_used_at = :at WHERE id = :id")
    suspend fun markUsed(id: Long, at: Long)

    @Query("UPDATE saved_meal SET use_count = max(0, use_count - 1) WHERE id = :id")
    suspend fun unmarkUsed(id: Long)
}
