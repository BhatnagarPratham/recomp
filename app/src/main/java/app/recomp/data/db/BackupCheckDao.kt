package app.recomp.data.db

import androidx.room.Dao
import androidx.room.Query

/**
 * Reads every row of every table through Room's own converters (TRD §6.5), so an import finds a value this
 * version can't read (an unknown enum name, a bad date) in the scratch database, before anything on the phone
 * is replaced. Each table needs a line here; BackupTest fails when one is missing.
 */
@Dao
interface BackupCheckDao {
    @Query("SELECT * FROM exercise")
    suspend fun exercise(): List<ExerciseEntity>

    @Query("SELECT * FROM routine")
    suspend fun routine(): List<RoutineEntity>

    @Query("SELECT * FROM routine_exercise")
    suspend fun routineExercise(): List<RoutineExerciseEntity>

    @Query("SELECT * FROM workout_session")
    suspend fun workoutSession(): List<WorkoutSessionEntity>

    @Query("SELECT * FROM session_exercise")
    suspend fun sessionExercise(): List<SessionExerciseEntity>

    @Query("SELECT * FROM set_entry")
    suspend fun setEntry(): List<SetEntryEntity>

    @Query("SELECT * FROM yoga_detail")
    suspend fun yogaDetail(): List<YogaDetailEntity>

    @Query("SELECT * FROM personal_record")
    suspend fun personalRecord(): List<PersonalRecordEntity>

    @Query("SELECT * FROM nutrition_target")
    suspend fun nutritionTarget(): List<NutritionTargetEntity>

    @Query("SELECT * FROM food")
    suspend fun food(): List<FoodEntity>

    @Query("SELECT * FROM food_unit")
    suspend fun foodUnit(): List<FoodUnitEntity>

    @Query("SELECT * FROM food_log")
    suspend fun foodLog(): List<FoodLogEntity>

    @Query("SELECT * FROM saved_meal")
    suspend fun savedMeal(): List<SavedMealEntity>

    @Query("SELECT * FROM saved_meal_item")
    suspend fun savedMealItem(): List<SavedMealItemEntity>

    @Query("SELECT * FROM meal_draft")
    suspend fun mealDraft(): List<MealDraftEntity>

    @Query("SELECT * FROM ai_request_log")
    suspend fun aiRequestLog(): List<AiRequestLogEntity>

    @Query("SELECT * FROM body_composition")
    suspend fun bodyComposition(): List<BodyCompositionEntity>

    @Query("SELECT * FROM body_weight")
    suspend fun bodyWeight(): List<BodyWeightEntity>

    @Query("SELECT * FROM measurement")
    suspend fun measurement(): List<MeasurementEntity>
}

/** Every table's reader, by table name. */
fun BackupCheckDao.readers(): Map<String, suspend () -> List<Any>> = mapOf(
    "exercise" to { exercise() },
    "routine" to { routine() },
    "routine_exercise" to { routineExercise() },
    "workout_session" to { workoutSession() },
    "session_exercise" to { sessionExercise() },
    "set_entry" to { setEntry() },
    "yoga_detail" to { yogaDetail() },
    "personal_record" to { personalRecord() },
    "nutrition_target" to { nutritionTarget() },
    "food" to { food() },
    "food_unit" to { foodUnit() },
    "food_log" to { foodLog() },
    "saved_meal" to { savedMeal() },
    "saved_meal_item" to { savedMealItem() },
    "meal_draft" to { mealDraft() },
    "ai_request_log" to { aiRequestLog() },
    "body_composition" to { bodyComposition() },
    "body_weight" to { bodyWeight() },
    "measurement" to { measurement() },
)
