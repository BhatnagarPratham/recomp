package app.recomp.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.time.LocalDate
import kotlinx.serialization.json.Json

/**
 * Every schema change ships a tested Migration; destructive fallback is never enabled (TRD §4).
 * Schemas are exported to app/schemas and checked into git.
 */
@Database(
    entities = [
        ExerciseEntity::class,
        RoutineEntity::class,
        RoutineExerciseEntity::class,
        WorkoutSessionEntity::class,
        SessionExerciseEntity::class,
        SetEntryEntity::class,
        YogaDetailEntity::class,
        PersonalRecordEntity::class,
        NutritionTargetEntity::class,
        FoodEntity::class,
        FoodFtsEntity::class,
        FoodUnitEntity::class,
        FoodLogEntity::class,
        SavedMealEntity::class,
        SavedMealItemEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun exerciseDao(): ExerciseDao
    abstract fun routineDao(): RoutineDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun nutritionDao(): NutritionDao
    abstract fun foodDao(): FoodDao
    abstract fun foodLogDao(): FoodLogDao
    abstract fun savedMealDao(): SavedMealDao

    companion object {
        const val NAME = "recomp.db"
        val MIGRATIONS = arrayOf(Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3, Migrations.MIGRATION_3_4, Migrations.MIGRATION_4_5)
    }
}

class Converters {
    @TypeConverter
    fun stringListToJson(value: List<String>): String = Json.encodeToString(value)

    @TypeConverter
    fun jsonToStringList(value: String): List<String> = Json.decodeFromString(value)

    /** `log_date` is an ISO date string (TRD §4), so it sorts and groups as text. */
    @TypeConverter
    fun localDateToString(value: LocalDate): String = value.toString()

    @TypeConverter
    fun stringToLocalDate(value: String): LocalDate = LocalDate.parse(value)
}
