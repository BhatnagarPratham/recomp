package app.recomp.domain.food

/** One ingredient of a recipe: a food's values and diet tag, and how much of it goes in, as you weighed it. */
data class RecipeIngredient(val per100g: Per100g, val diet: FoodDiet, val grams: Double)

/**
 * What a recipe works out to (NU-22, TRD §10 M14).
 *
 * [total] is everything in the pot. [weightG] is what the pot weighs: the cooked weight when you gave one,
 * else the ingredients' own weights added up ([cooked] false), which ignores water lost or absorbed in
 * cooking. [per100g] is the total spread over that weight, and [servingG] is one serving's share of it.
 */
data class RecipeValues(
    val total: Macros,
    val rawWeightG: Double,
    val weightG: Double,
    val cooked: Boolean,
    val per100g: Per100g,
    val servingG: Double?,
    val diet: FoodDiet,
) {
    val perServing: Macros? get() = servingG?.let(per100g::forGrams)
}

/** Why a recipe can't be saved as it stands. */
enum class RecipeProblem { NO_NAME, NO_INGREDIENTS, MISSING_INGREDIENT, BAD_AMOUNT, BAD_COOKED_WEIGHT, BAD_SERVINGS, NO_YIELD, CONTAINS_ITSELF }

object Recipes {
    /** The recipe's own unit: cooked weight ÷ servings. */
    const val SERVING = "serving"

    /** A pot heavier than this, or an ingredient, is a typing slip. */
    const val MAX_GRAMS = 50_000.0

    const val MAX_SERVINGS = 200.0

    /** The strictest ingredient's tag (TRD §6.4): one egg makes the dish an egg dish. Vegan when there are none. */
    fun diet(ingredients: List<RecipeIngredient>): FoodDiet = ingredients.maxOfOrNull { it.diet } ?: FoodDiet.VEGAN

    /** What's wrong with the amounts, if anything. The pot's weight or the servings must be given (PRD §12.10). */
    fun problem(ingredients: List<RecipeIngredient>, cookedWeightG: Double?, servings: Double?): RecipeProblem? = when {
        ingredients.isEmpty() -> RecipeProblem.NO_INGREDIENTS
        ingredients.any { !(it.grams > 0 && it.grams <= MAX_GRAMS) } -> RecipeProblem.BAD_AMOUNT
        cookedWeightG != null && !(cookedWeightG > 0 && cookedWeightG <= MAX_GRAMS) -> RecipeProblem.BAD_COOKED_WEIGHT
        servings != null && !(servings > 0 && servings <= MAX_SERVINGS) -> RecipeProblem.BAD_SERVINGS
        cookedWeightG == null && servings == null -> RecipeProblem.NO_YIELD
        else -> null
    }

    /**
     * The recipe's values, or null while [problem] finds something wrong. With no cooked weight, 100 g of
     * the dish is taken to be 100 g of its ingredients as weighed. With no servings, it has no serving unit
     * and is logged by weight.
     */
    fun values(ingredients: List<RecipeIngredient>, cookedWeightG: Double?, servings: Double?): RecipeValues? {
        if (problem(ingredients, cookedWeightG, servings) != null) return null
        val parts = ingredients.map { it.per100g.forGrams(it.grams) }
        val total = Macros(
            kcal = parts.sumOf { it.kcal },
            proteinG = parts.sumOf { it.proteinG },
            carbsG = parts.sumOf { it.carbsG ?: 0.0 },
            fatG = parts.sumOf { it.fatG ?: 0.0 },
            fiberG = parts.sumOf { it.fiberG ?: 0.0 },
        )
        val raw = ingredients.sumOf { it.grams }
        val weight = cookedWeightG ?: raw
        return RecipeValues(
            total = total,
            rawWeightG = raw,
            weightG = weight,
            cooked = cookedWeightG != null,
            per100g = Per100g.fromServing(weight, total),
            servingG = servings?.let { weight / it },
            diet = diet(ingredients),
        )
    }

    /**
     * Whether a recipe would end up inside itself: one of [ingredientFoodIds] is the recipe, or is a recipe
     * that contains it at any depth. [ingredientsOf] gives a food's own ingredients (none for a plain food).
     * A new recipe ([recipeFoodId] null) can't be inside anything yet.
     */
    fun containsItself(recipeFoodId: Long?, ingredientFoodIds: Collection<Long>, ingredientsOf: (Long) -> Collection<Long>): Boolean {
        if (recipeFoodId == null) return false
        val seen = mutableSetOf<Long>()
        val queue = ArrayDeque(ingredientFoodIds)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (id == recipeFoodId) return true
            if (seen.add(id)) queue.addAll(ingredientsOf(id))
        }
        return false
    }

    /**
     * Every recipe that holds [foodId] at any depth, so the editor never offers one of them as an ingredient
     * of [foodId]. [containers] gives the recipes a food is directly in.
     */
    fun holders(foodId: Long, containers: (Long) -> Collection<Long>): Set<Long> {
        val found = mutableSetOf<Long>()
        val queue = ArrayDeque(containers(foodId))
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (found.add(id)) queue.addAll(containers(id))
        }
        return found
    }
}
