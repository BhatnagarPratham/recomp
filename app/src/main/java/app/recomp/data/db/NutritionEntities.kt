package app.recomp.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import app.recomp.domain.nutrition.Goal
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/** Why a target version was written (TRD §4). MANUAL is any change you made in onboarding's review or Settings. */
enum class TargetReason { INITIAL, CHECKIN, MANUAL }

/**
 * A version of the daily targets (PS-02), in force from [effectiveFrom] until the next one. One per day:
 * a second change the same day replaces it, and earlier versions are never rewritten. [proteinG] is the
 * full target; the ramp (NU-03) is applied when it's read.
 */
@Entity(tableName = "nutrition_target", indices = [Index(value = ["effective_from"], unique = true)])
data class NutritionTargetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "effective_from") val effectiveFrom: LocalDate,
    val goal: Goal,
    val kcal: Int,
    @ColumnInfo(name = "protein_g") val proteinG: Int,
    @ColumnInfo(name = "fat_g") val fatG: Int,
    @ColumnInfo(name = "carbs_g") val carbsG: Int,
    @ColumnInfo(name = "tdee_estimate") val tdeeEstimate: Int,
    @ColumnInfo(name = "bmr_kcal") val bmrKcal: Int,
    val reason: TargetReason,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Dao
interface NutritionDao {
    /** The version in force on [date]: the latest one that started on or before it. */
    @Query("SELECT * FROM nutrition_target WHERE effective_from <= :date ORDER BY effective_from DESC LIMIT 1")
    suspend fun targetOn(date: LocalDate): NutritionTargetEntity?

    @Query("SELECT * FROM nutrition_target WHERE effective_from <= :date ORDER BY effective_from DESC LIMIT 1")
    fun observeTargetOn(date: LocalDate): Flow<NutritionTargetEntity?>

    /** Every version, oldest first, for the 7-day average's per-day targets. */
    @Query("SELECT * FROM nutrition_target ORDER BY effective_from")
    fun observeAll(): Flow<List<NutritionTargetEntity>>

    @Query("SELECT * FROM nutrition_target ORDER BY effective_from")
    suspend fun all(): List<NutritionTargetEntity>

    /** Replaces a version that starts on the same day (the unique index on effective_from). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(target: NutritionTargetEntity): Long
}
