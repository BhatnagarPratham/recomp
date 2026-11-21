package app.recomp.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * A recipe (NU-22, TRD §10 M14): the part of a `food` with `source = RECIPE` that says how much it makes.
 * The food row holds its name, diet tag and values per 100 g, recomputed from the ingredients on each save,
 * so search, logging and saved meals treat it like any other food. A null [cookedWeightG] means the dish
 * is taken to weigh what its ingredients do; a null [servings] means it has no serving unit.
 */
@Entity(
    tableName = "recipe",
    foreignKeys = [ForeignKey(entity = FoodEntity::class, parentColumns = ["id"], childColumns = ["food_id"], onDelete = ForeignKey.CASCADE)],
)
data class RecipeEntity(
    @PrimaryKey @ColumnInfo(name = "food_id") val foodId: Long,
    val servings: Double? = null,
    @ColumnInfo(name = "cooked_weight_g") val cookedWeightG: Double? = null,
)

/** One ingredient: [grams] of a food, as weighed before cooking. The food may itself be a recipe, but never this one. */
@Entity(
    tableName = "recipe_ingredient",
    foreignKeys = [
        ForeignKey(entity = RecipeEntity::class, parentColumns = ["food_id"], childColumns = ["recipe_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FoodEntity::class, parentColumns = ["id"], childColumns = ["food_id"]),
    ],
    indices = [Index("recipe_id"), Index("food_id")],
)
data class RecipeIngredientEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "recipe_id") val recipeId: Long,
    val position: Int,
    @ColumnInfo(name = "food_id") val foodId: Long,
    val grams: Double,
)

/** Which recipe holds which food: every ingredient link, for the no-recipe-inside-itself check. */
data class RecipeLink(
    @ColumnInfo(name = "recipe_id") val recipeId: Long,
    @ColumnInfo(name = "food_id") val foodId: Long,
)

@Dao
interface RecipeDao {
    @Query("SELECT * FROM recipe WHERE food_id = :foodId")
    suspend fun get(foodId: Long): RecipeEntity?

    @Query("SELECT * FROM recipe_ingredient WHERE recipe_id = :recipeId ORDER BY position, id")
    suspend fun ingredients(recipeId: Long): List<RecipeIngredientEntity>

    @Query("SELECT recipe_id, food_id FROM recipe_ingredient")
    suspend fun links(): List<RecipeLink>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(recipe: RecipeEntity)

    @Query("UPDATE recipe SET servings = :servings, cooked_weight_g = :cookedWeightG WHERE food_id = :foodId")
    suspend fun update(foodId: Long, servings: Double?, cookedWeightG: Double?)

    @Insert
    suspend fun insertIngredients(rows: List<RecipeIngredientEntity>)

    @Query("DELETE FROM recipe_ingredient WHERE recipe_id = :recipeId")
    suspend fun deleteIngredients(recipeId: Long)
}
