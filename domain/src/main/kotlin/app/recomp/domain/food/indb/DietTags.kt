package app.recomp.domain.food.indb

import app.recomp.domain.food.FoodDiet

/** One ingredient row of an INDB recipe: its IFCT-style food code (e.g. "L003") and name. */
data class Ingredient(val code: String?, val name: String?)

/**
 * Diet tags from ingredients (TRD §6.4), because INDB has no vegetarian flag. INDB codes each
 * ingredient with an IFCT food group: L milk products, M egg, N poultry, O meat, P/Q/R/S fish and
 * shellfish. A handful of codes in other groups are animal products and are listed by hand. Uncoded
 * ingredients (essences, colours) are judged by name.
 */
object DietTags {
    private val CODE = Regex("^[A-Z]\\d{3}$")

    /** Dairy (or honey) outside group L: butter, ghee, ice cream, honey, chocolate, milk-based sauces, biscuits. */
    private val DAIRY_CODES = setOf("T500", "T501", "T506", "I501", "I507", "I509", "V504", "X500", "X506", "X508", "U500", "U509")

    /** Mayonnaise sits in group L but is made with egg; thousand island dressing is mayonnaise-based. */
    private val EGG_CODES = setOf("L507", "X509")

    /** Gelatine, chicken stock cubes and (gelatine) jelly. */
    private val MEAT_CODES = setOf("K504", "K506", "W501")

    /** Worcestershire sauce has anchovies. */
    private val FISH_CODES = setOf("X507")

    private val ic = setOf(RegexOption.IGNORE_CASE)
    private const val MEATS = "chicken|mutton|lamb|goat|beef|pork|ham|bacon|duck|turkey|liver|meat|keema|kheema|qeema|sausages?|salami"
    private val MEAT_WORDS = Regex("\\b($MEATS)\\b", ic)

    /** An uncoded ingredient named jelly or gelatine is gelatine-set; a recipe named "Guava jelly" is a fruit preserve. */
    private val GELATINE_WORDS = Regex("\\b(gelatine?|jelly)\\b", ic)
    private val FISH_WORDS = Regex("\\b(fish|prawns?|shrimps?|crabs?|lobsters?|squid|tuna|salmon|sardines?|anchov(y|ies)|mackerel|pomfret|rohu|machli|machhli|jhinga)\\b", ic)
    private val EGG_WORDS = Regex("\\beggs?\\b(?!\\s*plant)", ic)
    private val DAIRY_WORDS = Regex("\\b(milk|curd|yogh?urt|dahi|paneer|cheese|butter|ghee|cream|khoa|khoya|malai|whey|lassi|honey)\\b", ic)
    private val NOT_DAIRY = Regex("\\b(coconut|soya?|almond|oat|rice|cashew|peanut|cocoa)\\s+(milk|butter|cream)\\b|\\bcream of tartar\\b|\\bbutter\\s*beans?\\b", ic)

    /** A recipe name that says it's the vegetarian version ("Soya keema", "Vegetarian egg kofta"). */
    private val VEG_VERSION = Regex("\\b(vegetarian|veg|vegan|mock|soya|soy|eggless)\\b", ic)

    /** An ingredient's tag: by its food code when it has one, by name otherwise. */
    fun ofIngredient(ingredient: Ingredient): FoodDiet {
        val code = ingredient.code?.trim()?.uppercase().orEmpty()
        return if (CODE.matches(code)) ofCode(code) else ofName(ingredient.name.orEmpty())
    }

    fun ofCode(code: String): FoodDiet = when {
        code in EGG_CODES -> FoodDiet.EGG
        code in MEAT_CODES -> FoodDiet.MEAT
        code in FISH_CODES -> FoodDiet.FISH
        code in DAIRY_CODES -> FoodDiet.VEGETARIAN
        else -> when (code.first()) {
            'L' -> FoodDiet.VEGETARIAN
            'M' -> FoodDiet.EGG
            'N', 'O' -> FoodDiet.MEAT
            'P', 'Q', 'R', 'S' -> FoodDiet.FISH
            else -> FoodDiet.VEGAN
        }
    }

    /** For uncoded ingredients: the strictest thing the name mentions. */
    fun ofName(name: String): FoodDiet = when {
        MEAT_WORDS.containsMatchIn(name) || GELATINE_WORDS.containsMatchIn(name) -> FoodDiet.MEAT
        FISH_WORDS.containsMatchIn(name) -> FoodDiet.FISH
        EGG_WORDS.containsMatchIn(name) -> FoodDiet.EGG
        DAIRY_WORDS.containsMatchIn(NOT_DAIRY.replace(name, "")) -> FoodDiet.VEGETARIAN
        else -> FoodDiet.VEGAN
    }

    /**
     * A recipe's tag: its strictest ingredient. As a safety net, a name that says meat or fish lifts it,
     * unless the name says it's the vegetarian version. A recipe with no ingredients can't be judged,
     * so it's tagged meat and shows only for non-vegetarians.
     */
    fun ofRecipe(name: String, ingredients: List<Ingredient>): FoodDiet {
        if (ingredients.isEmpty()) return FoodDiet.MEAT
        val fromIngredients = ingredients.maxOf(::ofIngredient)
        val fromName = when {
            VEG_VERSION.containsMatchIn(name) -> FoodDiet.VEGAN
            MEAT_WORDS.containsMatchIn(name) -> FoodDiet.MEAT
            FISH_WORDS.containsMatchIn(name) -> FoodDiet.FISH
            else -> FoodDiet.VEGAN
        }
        return maxOf(fromIngredients, fromName)
    }
}
