package app.recomp.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import app.recomp.domain.food.FoodCaveat
import app.recomp.domain.food.FoodDiet
import app.recomp.domain.food.FoodSource
import app.recomp.domain.food.LogSource
import app.recomp.domain.food.Macros
import app.recomp.domain.food.Origin
import app.recomp.domain.food.Per100g
import app.recomp.domain.persona.FoodRegion
import java.time.LocalDate

/**
 * A food (TRD §4, §6.4). [sourceKey] ties a bundled row to its file ("seed:paneer", "indb:ASC001") so
 * re-imports update it in place. A null [region] shows for every region. Referenced foods are archived
 * (hidden), never deleted.
 */
@Entity(tableName = "food", indices = [Index(value = ["source_key"], unique = true)])
data class FoodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val brand: String? = null,
    val source: FoodSource,
    val barcode: String? = null,
    @ColumnInfo(name = "source_key") val sourceKey: String? = null,
    val diet: FoodDiet,
    val region: FoodRegion? = null,
    @ColumnInfo(name = "kcal_100g") val kcal100g: Double,
    @ColumnInfo(name = "protein_100g") val protein100g: Double,
    @ColumnInfo(name = "carbs_100g") val carbs100g: Double,
    @ColumnInfo(name = "fat_100g") val fat100g: Double,
    @ColumnInfo(name = "fiber_100g") val fiber100g: Double,
    val caveat: FoodCaveat? = null,
    @ColumnInfo(name = "use_count") val useCount: Int = 0,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long? = null,
    val archived: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    val per100g: Per100g get() = Per100g(kcal100g, protein100g, carbs100g, fat100g, fiber100g)
}

/** Full-text index over food names (TRD §4). Room's triggers keep it in sync with [FoodEntity]. */
@Fts4(contentEntity = FoodEntity::class)
@Entity(tableName = "food_fts")
data class FoodFtsEntity(val name: String)

/**
 * One of a food's own units (NU-12). [calibrated] marks a weight you set for your utensils, which
 * re-imports leave alone and which beats the household measure of the same name.
 */
@Entity(
    tableName = "food_unit",
    foreignKeys = [ForeignKey(entity = FoodEntity::class, parentColumns = ["id"], childColumns = ["food_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["food_id", "label"], unique = true)],
)
data class FoodUnitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "food_id") val foodId: Long,
    val label: String,
    val grams: Double,
    val calibrated: Boolean = false,
)

/**
 * A logged entry (NU-10 to NU-16). Its macros are a snapshot, so editing a food never rewrites history.
 * Null carbs, fat or fibre are unknown (a quick add). [mealSlot] is a slot key (`meal_slots`).
 */
@Entity(
    tableName = "food_log",
    foreignKeys = [ForeignKey(entity = FoodEntity::class, parentColumns = ["id"], childColumns = ["food_id"])],
    indices = [Index("log_date"), Index("food_id")],
)
data class FoodLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "log_date") val logDate: LocalDate,
    @ColumnInfo(name = "meal_slot") val mealSlot: String,
    @ColumnInfo(name = "food_id") val foodId: Long?,
    val name: String,
    val quantity: Double,
    @ColumnInfo(name = "unit_label") val unitLabel: String,
    val grams: Double?,
    val kcal: Double,
    @ColumnInfo(name = "protein_g") val proteinG: Double,
    @ColumnInfo(name = "carbs_g") val carbsG: Double?,
    @ColumnInfo(name = "fat_g") val fatG: Double?,
    @ColumnInfo(name = "fiber_g") val fiberG: Double?,
    val origin: Origin,
    val source: LogSource,
    @ColumnInfo(name = "ai_request_id") val aiRequestId: Long? = null,
    @ColumnInfo(name = "logged_at") val loggedAt: Long,
) {
    val macros: Macros get() = Macros(kcal, proteinG, carbsG, fatG, fiberG)
}

/** A saved meal (NU-13), logged in one tap. */
@Entity(tableName = "saved_meal")
data class SavedMealEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "default_slot") val defaultSlot: String,
    @ColumnInfo(name = "use_count") val useCount: Int = 0,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * An item of a saved meal. With a [foodId], logging recomputes from the food's current values (so a
 * corrected food flows through); without one (a quick add), from this snapshot.
 */
@Entity(
    tableName = "saved_meal_item",
    foreignKeys = [
        ForeignKey(entity = SavedMealEntity::class, parentColumns = ["id"], childColumns = ["saved_meal_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FoodEntity::class, parentColumns = ["id"], childColumns = ["food_id"]),
    ],
    indices = [Index("saved_meal_id"), Index("food_id")],
)
data class SavedMealItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "saved_meal_id") val savedMealId: Long,
    val position: Int,
    @ColumnInfo(name = "food_id") val foodId: Long?,
    val name: String,
    val quantity: Double,
    @ColumnInfo(name = "unit_label") val unitLabel: String,
    val grams: Double?,
    val kcal: Double,
    @ColumnInfo(name = "protein_g") val proteinG: Double,
    @ColumnInfo(name = "carbs_g") val carbsG: Double?,
    @ColumnInfo(name = "fat_g") val fatG: Double?,
    @ColumnInfo(name = "fiber_g") val fiberG: Double?,
    val origin: Origin,
)

data class SavedMealWithItems(
    @Embedded val meal: SavedMealEntity,
    @Relation(parentColumn = "id", entityColumn = "saved_meal_id") val items: List<SavedMealItemEntity>,
)

/** A day's intake for Today and the 7-day average (TRD §5.9). */
data class DayIntakeRow(
    @ColumnInfo(name = "log_date") val logDate: LocalDate,
    val kcal: Double,
    @ColumnInfo(name = "protein_g") val proteinG: Double,
)

/** A day with food logged, for *Copy from another day* (NU-13). */
data class LoggedDayRow(
    @ColumnInfo(name = "log_date") val logDate: LocalDate,
    val entries: Int,
    val kcal: Double,
    @ColumnInfo(name = "protein_g") val proteinG: Double,
)
