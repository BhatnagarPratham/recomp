package app.recomp.data.repo

import app.recomp.data.db.AppDatabase
import app.recomp.domain.food.FoodDiet
import app.recomp.domain.food.FoodSource

/**
 * Keeps every recipe's diet tag equal to its strictest ingredient's (TRD §6.4) when an ingredient's tag
 * changes: a custom food edited, an AI-learned food re-tagged, or a database food re-imported. The tag is a
 * filter, not a number: a recipe tagged Vegetarian that now holds egg would show to a lacto-vegetarian.
 * Values stay as they were saved (§10 M14's results), so only the tag follows. Callers run it inside their
 * own transaction.
 */
object RecipeTags {
    suspend fun refresh(db: AppDatabase) {
        val foodDao = db.foodDao()
        val recipes = foodDao.bySource(FoodSource.RECIPE.name).associateBy { it.id }
        if (recipes.isEmpty()) return
        val contents = db.recipeDao().links().groupBy({ it.recipeId }, { it.foodId })
        val tags = foodDao.get(contents.values.flatten().distinct()).associate { it.id to it.diet }.toMutableMap()
        recipes.values.forEach { tags[it.id] = it.diet }
        // A recipe's tag depends on recipes inside it, so repeat until nothing moves. Saves refuse cycles,
        // and the bound stops a damaged backup's loop from spinning.
        var passes = 0
        var moved = true
        while (moved && passes++ <= recipes.size) {
            moved = false
            recipes.keys.forEach { id ->
                val tag = contents[id].orEmpty().mapNotNull { tags[it] }.maxOrNull() ?: FoodDiet.VEGAN
                if (tags[id] != tag) {
                    tags[id] = tag
                    moved = true
                }
            }
        }
        recipes.values.filter { tags[it.id] != it.diet }.forEach { foodDao.update(it.copy(diet = tags.getValue(it.id))) }
    }
}
