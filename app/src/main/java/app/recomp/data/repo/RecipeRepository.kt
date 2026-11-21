package app.recomp.data.repo

import androidx.room.withTransaction
import app.recomp.data.db.AppDatabase
import app.recomp.domain.food.FoodCaveat
import app.recomp.data.db.FoodEntity
import app.recomp.data.db.FoodUnitEntity
import app.recomp.data.db.RecipeEntity
import app.recomp.data.db.RecipeIngredientEntity
import app.recomp.domain.food.FoodSource
import app.recomp.domain.food.RecipeIngredient
import app.recomp.domain.food.RecipeProblem
import app.recomp.domain.food.Recipes
import javax.inject.Inject
import javax.inject.Singleton

/** An ingredient as the editor holds it: a food and how much of it, in grams as weighed. */
data class RecipeLine(val food: FoodEntity, val grams: Double) {
    val ingredient: RecipeIngredient get() = RecipeIngredient(food.per100g, food.diet, grams)
}

/** A recipe from the editor (NU-22). [ingredients] are food ids with their grams, in order. */
data class RecipeDraft(
    val name: String,
    val ingredients: List<Pair<Long, Double>>,
    val cookedWeightG: Double?,
    val servings: Double?,
)

/** A saved recipe, for editing: its ingredients carry each food's current values. */
data class RecipeDetail(val food: FoodEntity, val cookedWeightG: Double?, val servings: Double?, val lines: List<RecipeLine>)

sealed interface RecipeSaved {
    /** [foodId] is the recipe's food, ready to log. */
    data class Ok(val foodId: Long) : RecipeSaved

    data class Refused(val problem: RecipeProblem) : RecipeSaved
}

/**
 * Recipes (NU-22, TRD §10 M14). A recipe is a `food` row with `source = RECIPE` plus its ingredient list.
 * Saving recomputes the food's values per 100 g and diet tag from the ingredients as they are then, and sets
 * its *serving* unit. Log rows snapshot their macros, so a save changes future logs only. A later change to
 * an ingredient (or to a recipe inside this one) reaches it the next time it's saved.
 */
@Singleton
class RecipeRepository @Inject constructor(private val db: AppDatabase) {
    private val foodDao get() = db.foodDao()
    private val recipeDao get() = db.recipeDao()

    suspend fun recipe(foodId: Long): RecipeDetail? = db.withTransaction {
        val food = foodDao.get(foodId)?.takeIf { it.source == FoodSource.RECIPE } ?: return@withTransaction null
        val recipe = recipeDao.get(foodId) ?: return@withTransaction null
        val rows = recipeDao.ingredients(foodId)
        val foods = foodDao.get(rows.map { it.foodId }.distinct()).associateBy { it.id }
        RecipeDetail(food, recipe.cookedWeightG, recipe.servings, rows.mapNotNull { row -> foods[row.foodId]?.let { RecipeLine(it, row.grams) } })
    }

    /**
     * The foods that can't go into recipe [foodId]: itself, and every recipe that holds it at any depth.
     * The editor leaves them out of its ingredient search. Empty for a new recipe.
     */
    suspend fun blockedIngredients(foodId: Long?): Set<Long> {
        if (foodId == null) return emptySet()
        val holders = recipeDao.links().groupBy({ it.foodId }, { it.recipeId })
        return Recipes.holders(foodId) { holders[it].orEmpty() } + foodId
    }

    /**
     * Creates a recipe ([foodId] null) or saves an edited one. Refused, with nothing written, when the amounts
     * can't be right, an ingredient is gone, or the recipe would contain itself.
     */
    suspend fun save(foodId: Long?, draft: RecipeDraft, now: Long = System.currentTimeMillis()): RecipeSaved = db.withTransaction {
        val name = draft.name.trim()
        if (name.isEmpty()) return@withTransaction RecipeSaved.Refused(RecipeProblem.NO_NAME)
        val old = foodId?.let { id -> foodDao.get(id) }
        require(foodId == null || old?.source == FoodSource.RECIPE) { "Only a recipe can be saved as one" }

        val foods = foodDao.get(draft.ingredients.map { it.first }.distinct()).associateBy { it.id }
        // Foods are archived, never deleted, so this only guards against a damaged backup.
        if (draft.ingredients.any { it.first !in foods }) return@withTransaction RecipeSaved.Refused(RecipeProblem.MISSING_INGREDIENT)
        val lines = draft.ingredients.map { (id, grams) -> RecipeLine(foods.getValue(id), grams) }
        Recipes.problem(lines.map { it.ingredient }, draft.cookedWeightG, draft.servings)?.let { return@withTransaction RecipeSaved.Refused(it) }

        val contents = recipeDao.links().groupBy({ it.recipeId }, { it.foodId })
        if (Recipes.containsItself(foodId, foods.keys) { contents[it].orEmpty() }) return@withTransaction RecipeSaved.Refused(RecipeProblem.CONTAINS_ITSELF)

        val values = Recipes.values(lines.map { it.ingredient }, draft.cookedWeightG, draft.servings)!!
        val per = values.per100g
        // A dish with a deep-fried INDB ingredient reads high for the same reason it does (TRD §6.4).
        val caveat = lines.firstNotNullOfOrNull { it.food.caveat }
        val id = if (old == null) {
            foodDao.insert(
                FoodEntity(
                    name = name, source = FoodSource.RECIPE, diet = values.diet, caveat = caveat,
                    kcal100g = per.kcal, protein100g = per.proteinG, carbs100g = per.carbsG, fat100g = per.fatG, fiber100g = per.fiberG,
                    createdAt = now,
                ),
            ).also { recipeDao.upsert(RecipeEntity(it, draft.servings, draft.cookedWeightG)) }
        } else {
            foodDao.update(
                old.copy(
                    name = name, diet = values.diet, caveat = caveat,
                    kcal100g = per.kcal, protein100g = per.proteinG, carbs100g = per.carbsG, fat100g = per.fatG, fiber100g = per.fiberG,
                ),
            )
            // An update, not a replace: replacing the row would cascade its ingredients away mid-save.
            recipeDao.update(old.id, draft.servings, draft.cookedWeightG)
            old.id
        }
        recipeDao.deleteIngredients(id)
        recipeDao.insertIngredients(lines.mapIndexed { i, line -> RecipeIngredientEntity(recipeId = id, position = i, foodId = line.food.id, grams = line.grams) })
        setServing(id, values.servingG)
        // Recipes that hold this one take its new tag too.
        RecipeTags.refresh(db)
        RecipeSaved.Ok(id)
    }

    /** The recipe's *serving* becomes its first unit, at the new weight; units you calibrated for it stay, after it. */
    private suspend fun setServing(foodId: Long, grams: Double?) {
        val others = foodDao.units(foodId).filter { it.label != Recipes.SERVING }
        foodDao.deleteUnits(foodId)
        val serving = grams?.let { FoodUnitEntity(foodId = foodId, label = Recipes.SERVING, grams = it, calibrated = true) }
        foodDao.upsertUnits(listOfNotNull(serving) + others.map { it.copy(id = 0) })
    }
}
