@file:JvmName("IndbConvert")

package app.recomp.domain.food.indb

import app.recomp.domain.food.FoodCaveat
import app.recomp.domain.food.FoodDiet
import app.recomp.domain.food.Per100g
import app.recomp.domain.food.Portions
import app.recomp.domain.persona.FoodRegion
import java.io.File
import java.util.Locale

/**
 * A bundled food, as written to `food/indb.json` and `seed/foods.json` (TRD §6.4). [units] are the
 * food's own units with their weights in grams.
 */
data class FoodRecord(
    val key: String,
    val name: String,
    val diet: FoodDiet,
    val region: FoodRegion?,
    val per100g: Per100g,
    val units: List<Pair<String, Double>> = emptyList(),
    val caveat: FoodCaveat? = null,
)

data class Conversion(val foods: List<FoodRecord>, val skipped: List<String>) {
    fun summary(): String {
        val byDiet = FoodDiet.entries.joinToString(", ") { d -> "${foods.count { it.diet == d }} ${d.name.lowercase()}" }
        return "${foods.size} recipes ($byDiet); ${foods.count { it.caveat == FoodCaveat.FRYING_OIL }} with the frying-oil caveat; " +
            "${skipped.size} skipped"
    }
}

/**
 * Turns INDB's `INDB.xlsx` (nutrients per 100 g and per serving) and `recipes.xlsx` (ingredients) into
 * food records (TRD §6.4). Runs at build time only, from `:app:fetchIndb`; the data is never committed.
 */
object IndbConverter {
    /**
     * Bumped when the output changes for the same input, so phones re-import. 2: tags read the recipe's own
     * wording. 3: implausible and gram-like units are dropped.
     */
    const val FORMAT = 3

    /** Fried recipes above this read far too high, because INDB counts all the frying oil as eaten. */
    const val FRYING_CAVEAT_KCAL = 250.0

    private val FRYING = Regex("\\bfr(y|ying|yng)\\b", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("\\s+")

    fun convert(nutrients: Table, recipes: Table): Conversion {
        nutrients.require("food_code", "food_name", "energy_kcal", "protein_g", "carb_g", "fat_g", "fibre_g", "servings_unit", "unit_serving_energy_kcal")
        recipes.require("recipe_code", "food_code", "food_name", "ingredient_name_org", "amount_org", "unit_org")

        val ingredients = recipes.rows.groupBy({ recipes[it, "recipe_code"].orEmpty() }) {
            Ingredient(recipes[it, "food_code"], recipes[it, "food_name"], recipes[it, "ingredient_name_org"])
        }
        val fried = recipes.rows.filter { row ->
            recipes[row, "food_code"].orEmpty().startsWith("T") &&
                FRYING.containsMatchIn(listOf("ingredient_name_org", "amount_org", "unit_org").joinToString(" ") { recipes[row, it].orEmpty() })
        }.mapNotNull { recipes[it, "recipe_code"] }.toSet()

        val skipped = mutableListOf<String>()
        val foods = nutrients.rows.mapNotNull { row ->
            val code = nutrients[row, "food_code"]
            val name = nutrients[row, "food_name"]?.replace(SPACES, " ")
            fun num(column: String) = nutrients[row, column]?.toDoubleOrNull()?.takeIf { it.isFinite() }
            val kcal = num("energy_kcal")
            val protein = num("protein_g")
            val carbs = num("carb_g")
            val fat = num("fat_g")
            if (code == null || name == null || kcal == null || protein == null || carbs == null || fat == null) {
                skipped += code ?: "row ${nutrients.rows.indexOf(row) + 2}"
                return@mapNotNull null
            }
            val per100 = Per100g(kcal.round(2), protein.round(2), carbs.round(2), fat.round(2), (num("fibre_g") ?: 0.0).round(2))
            FoodRecord(
                key = "indb:$code",
                name = name,
                diet = DietTags.ofRecipe(name, ingredients[code].orEmpty()),
                region = FoodRegion.INDIAN,
                per100g = per100,
                units = listOfNotNull(servingUnit(nutrients, row, per100)),
                caveat = FoodCaveat.FRYING_OIL.takeIf { code in fried && kcal > FRYING_CAVEAT_KCAL },
            )
        }
        return Conversion(foods, skipped)
    }

    /**
     * The recipe's serving unit, weighing per-serving energy ÷ per-100 g energy × 100 (or another macro
     * when energy is zero). Null when it can't be weighed sensibly, when one unit is too big to be a serving
     * (`Portions.MAX_UNIT_GRAMS`), or when the label just means grams.
     */
    private fun servingUnit(t: Table, row: List<String?>, per100: Per100g): Pair<String, Double>? {
        val pairs = listOf(
            "unit_serving_energy_kcal" to per100.kcal,
            "unit_serving_protein_g" to per100.proteinG,
            "unit_serving_carb_g" to per100.carbsG,
            "unit_serving_fat_g" to per100.fatG,
        )
        val grams = pairs.firstNotNullOfOrNull { (column, per100g) ->
            val serving = t[row, column]?.toDoubleOrNull()?.takeIf { it.isFinite() }
            if (serving != null && serving > 0 && per100g > 0) serving / per100g * 100 else null
        } ?: return null
        val label = unitLabel(t[row, "servings_unit"])
        if (!Portions.plausible(grams) || label in Portions.GRAM_LABELS) return null
        return label to grams.round(1)
    }

    /** "Tablespoon" → "tbsp" so it doesn't sit beside the household tbsp; blank → "serving". */
    fun unitLabel(raw: String?): String {
        val label = raw?.trim()?.lowercase(Locale.ROOT)?.replace(SPACES, " ").orEmpty()
        return when (label) {
            "" -> "serving"
            "tablespoon", "tablespoons", "tbsp." -> "tbsp"
            "teaspoon", "teaspoons", "tsp." -> "tsp"
            else -> label
        }
    }

    private fun Double.round(places: Int): Double {
        val f = Math.pow(10.0, places.toDouble())
        return Math.round(this * f) / f
    }
}

/**
 * The bundled food file format (TRD §6.4), written by hand because `:domain` has no JSON library. The
 * app reads it with kotlinx.serialization (`FoodFile` in `:app`).
 */
object FoodFileWriter {
    fun write(version: String, foods: List<FoodRecord>): String = buildString {
        append("{\"version\":").append(quote(version)).append(",\"foods\":[\n")
        foods.forEachIndexed { i, f ->
            if (i > 0) append(",\n")
            append("{\"key\":").append(quote(f.key))
            append(",\"name\":").append(quote(f.name))
            append(",\"diet\":").append(quote(f.diet.name))
            f.region?.let { append(",\"region\":").append(quote(it.name)) }
            append(",\"kcal\":").append(num(f.per100g.kcal))
            append(",\"protein\":").append(num(f.per100g.proteinG))
            append(",\"carbs\":").append(num(f.per100g.carbsG))
            append(",\"fat\":").append(num(f.per100g.fatG))
            append(",\"fiber\":").append(num(f.per100g.fiberG))
            if (f.units.isNotEmpty()) {
                append(",\"units\":[")
                append(f.units.joinToString(",") { (label, grams) -> "{\"label\":${quote(label)},\"grams\":${num(grams)}}" })
                append("]")
            }
            f.caveat?.let { append(",\"caveat\":").append(quote(it.name)) }
            append("}")
        }
        append("\n]}\n")
    }

    private fun num(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

    internal fun quote(s: String): String = buildString {
        append('"')
        s.forEach { ch ->
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch < ' ' -> append("\\u%04x".format(ch.code))
                else -> append(ch)
            }
        }
        append('"')
    }
}

/**
 * `:app:fetchIndb`'s converter: `IndbConvert <INDB.xlsx> <recipes.xlsx> <version> <out.json>`.
 * Fails the build when a column is missing or nothing converts.
 */
fun main(args: Array<String>) {
    require(args.size == 4) { "Usage: IndbConvert <INDB.xlsx> <recipes.xlsx> <version> <out.json>" }
    val (nutrientsPath, recipesPath, version, outPath) = args
    val nutrients = File(nutrientsPath).inputStream().use { Xlsx.table(it) }
    val recipes = File(recipesPath).inputStream().use { Xlsx.table(it) }
    val result = IndbConverter.convert(nutrients, recipes)
    check(result.foods.isNotEmpty()) { "INDB converted to nothing" }
    File(outPath).apply { parentFile.mkdirs() }.writeText(FoodFileWriter.write("$version-f${IndbConverter.FORMAT}", result.foods))
    println("INDB: ${result.summary()}")
}
