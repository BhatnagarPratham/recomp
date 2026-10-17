package app.recomp.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

enum class Equipment { BARBELL, DUMBBELL, MACHINE, PLATE_LOADED, CABLE, BODYWEIGHT, KETTLEBELL, BAND }
enum class Mechanic { COMPOUND, ISOLATION }
enum class LoadType { WEIGHTED, BODYWEIGHT, BODYWEIGHT_PLUS, ASSISTED }

/** `exercise` table (TRD §4). Loads are integer grams so progression comparisons are exact. */
@Entity(tableName = "exercise")
data class ExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "primary_muscle") val primaryMuscle: String,
    @ColumnInfo(name = "secondary_muscles") val secondaryMuscles: List<String> = emptyList(),
    val equipment: Equipment,
    val mechanic: Mechanic,
    @ColumnInfo(name = "movement_pattern") val movementPattern: String,
    @ColumnInfo(name = "load_type") val loadType: LoadType = LoadType.WEIGHTED,
    @ColumnInfo(name = "increment_g") val incrementG: Int,
    @ColumnInfo(name = "default_rep_min") val defaultRepMin: Int,
    @ColumnInfo(name = "default_rep_max") val defaultRepMax: Int,
    @ColumnInfo(name = "default_rest_sec") val defaultRestSec: Int,
    @ColumnInfo(name = "is_unilateral") val isUnilateral: Boolean = false,
    @ColumnInfo(name = "setup_notes") val setupNotes: String? = null,
    @ColumnInfo(name = "is_custom") val isCustom: Boolean = false,
    val archived: Boolean = false,
)
