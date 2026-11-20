package app.recomp.domain.food

import app.recomp.domain.ai.Confidence
import app.recomp.domain.ai.EstimatedItem

/** A food the parser can match: what search knows about it, with its own units and the unit it was last logged in. */
data class IndexedFood(
    val id: Long,
    val name: String,
    val source: FoodSource,
    val per100g: Per100g,
    val units: List<Pair<String, Double>> = emptyList(),
    val useCount: Int = 0,
    val caveat: FoodCaveat? = null,
    val lastUnit: String? = null,
) {
    /** Yours: a custom food, one learned from an AI estimate you edited, or a label you scanned. */
    val own: Boolean get() = source == FoodSource.CUSTOM || source == FoodSource.AI || source == FoodSource.LABEL_OCR
}

/**
 * The foods the local parser matches against (AI-04). The repository supplies it over the food search index,
 * already limited to unarchived foods that the persona's diet and region allow.
 */
interface FoodIndex {
    /** Foods whose names contain every one of [words] as a whole word, best first as search orders them. */
    suspend fun lookup(words: List<String>): List<IndexedFood>

    /** Every food's own unit labels ("parantha", "scoop"), so they read as units. */
    val unitLabels: Set<String>
}

/** One matched food with its amount. [typed] is what you wrote for it; [note] says why the grams are what they are. */
data class ParsedItem(
    val food: IndexedFood,
    val typed: String,
    val quantity: Double,
    val unit: String,
    val grams: Double,
    val confidence: Confidence,
    val note: String,
) {
    val macros: Macros get() = food.per100g.forGrams(grams)

    /** The item in the review's shape, with the food's own numbers for its weight. */
    fun estimated(): EstimatedItem {
        val m = macros
        return EstimatedItem(food.name, quantity, unit, grams, m.kcal, m.proteinG, m.carbsG ?: 0.0, m.fatG ?: 0.0, m.fiberG ?: 0.0, confidence, note)
    }

    /** "2 roti = Chapati/Roti, 80 g": how the AI is told this part is already counted. */
    val counted: String get() = "$typed = ${food.name}, ${Portions.format(grams)} g"
}

/** A parsed description: what matched, and the chunks that didn't, as typed. */
data class ParsedMeal(val items: List<ParsedItem>, val unmatched: List<String>)

/**
 * The local describe parser (AI-04, TRD §6.2): "2 roti, 1 katori dal makhani and salad" → matched foods with
 * amounts, and "Couldn't find: salad". Plain rules over the food index, no AI; the same parser answers
 * *Will it fit?* offline.
 */
object DescribeParser {
    const val MAX_CHARS = 500
    const val MAX_CHUNKS = 20

    private val HARD = Regex("""[,;\n+]""")

    /** One chunk read into its parts. [weight] is a typed weight in grams; [unit] the word typed as a unit. */
    internal data class Chunk(val quantity: Double, val weight: Double?, val unit: String?, val unitWord: String?, val name: List<String>, val typed: String)

    suspend fun parse(text: String, index: FoodIndex, household: Map<String, Double>): ParsedMeal {
        val items = mutableListOf<ParsedItem>()
        val unmatched = mutableListOf<String>()
        val chunks = Quantities.normalise(text.take(MAX_CHARS)).split(HARD).map(Quantities::tokens).filter { it.isNotEmpty() }.take(MAX_CHUNKS)
        for (tokens in chunks) {
            // "poha with curd" is one dish when a food carries all of it; otherwise "and" and "with" split it.
            val whole = if (tokens.any { it in FoodWords.SOFT }) chunk(tokens, index.unitLabels, keepSoft = true)?.let { match(it, index, household) } else null
            if (whole != null) {
                items += whole
                continue
            }
            for (part in softSplit(tokens)) {
                val chunk = chunk(part, index.unitLabels) ?: continue
                val item = match(chunk, index, household)
                if (item != null) items += item else unmatched += chunk.typed
            }
        }
        return ParsedMeal(items, unmatched)
    }

    /** A typed chunk without its amount and unit ("1 katori cucumber salad" → "cucumber salad"), to search for or name a quick add. */
    fun nameOf(typed: String): String =
        chunk(Quantities.tokens(Quantities.normalise(typed)), emptySet(), keepSoft = true)?.name?.joinToString(" ") ?: typed.trim()

    /** Splits at "and" and "with", except an "and" inside an amount ("one and a half"). */
    internal fun softSplit(tokens: List<String>): List<List<String>> {
        val parts = mutableListOf(mutableListOf<String>())
        tokens.forEachIndexed { i, token ->
            if (token in FoodWords.SOFT && !(token == "and" && Quantities.joinsAmount(tokens, i))) parts += mutableListOf<String>() else parts.last() += token
        }
        return parts.filter { it.isNotEmpty() }
    }

    /**
     * `[amount] [unit] name`, or the amount after the name ("roti x2", "rajma 1 katori", "paneer 150 g").
     * No amount means one. Null when there's no name left.
     */
    internal fun chunk(tokens: List<String>, ownUnits: Set<String>, keepSoft: Boolean = false): Chunk? {
        var quantity: Double? = null
        var weight: Double? = null
        var unitWord: String? = null
        var rest = tokens
        val lead = Quantities.leading(tokens)
        if (lead != null && lead.used < tokens.size) {
            rest = tokens.drop(lead.used)
            val factor = FoodWords.WEIGHTS[rest.first()]
            if (factor != null && rest.size > 1) {
                weight = lead.value * factor
                rest = rest.drop(1)
            } else {
                quantity = lead.value
            }
        } else {
            // The amount after the name: the last number, with at most a unit after it.
            val at = tokens.indexOfLast(Quantities::isNumber)
            val tail = if (at > 0) Quantities.leading(tokens.drop(at)) else null
            val after = if (tail != null) tokens.drop(at + tail.used) else emptyList()
            if (tail != null && after.size <= 1) {
                val word = after.firstOrNull()
                val factor = word?.let(FoodWords.WEIGHTS::get)
                when {
                    word == null -> quantity = tail.value
                    factor != null -> weight = tail.value * factor
                    FoodWords.unit(word, ownUnits) != null -> {
                        quantity = tail.value
                        unitWord = word
                    }
                }
                if (quantity != null || weight != null) rest = tokens.take(at)
            }
        }
        var name = rest.filterNot { it in FoodWords.STOP || Quantities.isNumber(it) || (!keepSoft && it in FoodWords.SOFT) }
        // A unit needs a food after it: "2 roti" is two of the food called roti. Without an amount, a unit that is
        // also a food ("roti sabzi", "egg curry") is read as the food.
        val first = name.firstOrNull()?.let { FoodWords.unit(it, ownUnits) }
        if (weight == null && unitWord == null && name.size > 1 && first != null && (quantity != null || first !in FoodWords.FOOD_UNITS)) {
            unitWord = name.first()
            name = name.drop(1)
        }
        if (name.isEmpty()) return null
        return Chunk(quantity ?: 1.0, weight, unitWord?.let { FoodWords.unit(it, ownUnits) }, unitWord, name, tokens.joinToString(" "))
    }

    private suspend fun match(chunk: Chunk, index: FoodIndex, household: Map<String, Double>): ParsedItem? {
        val asUnit = pick(chunk.name, index)?.let { item(chunk, it.first, it.second, chunk.name, chunk.unit, household) }
        if (chunk.unitWord == null || asUnit?.second == true) return asUnit?.first
        // The "unit" may be part of the name ("egg curry", "glass noodles"): tried when the food has no such unit.
        val named = listOf(chunk.unitWord) + chunk.name
        val asName = pick(named, index)?.let { item(chunk, it.first, it.second, named, null, household) }
        return asName?.first ?: asUnit?.first
    }

    /**
     * The food for some name words, and whether its name is exactly those words. Every typed word has to be in
     * the food's name: "dal makhani" never falls back to plain dal. Exact names among your own foods come first,
     * then exact names, then search's order.
     */
    private suspend fun pick(name: List<String>, index: FoodIndex): Pair<IndexedFood, Boolean>? {
        val found = LinkedHashMap<Long, Pair<IndexedFood, Int>>()
        variants(name).forEachIndexed { v, words -> index.lookup(words).forEachIndexed { i, food -> found.getOrPut(food.id) { food to v * 1000 + i } } }
        if (found.isEmpty()) return null
        fun exact(food: IndexedFood) = FoodWords.aliases(food.name).any { FoodWords.sameName(it, name) }
        val wanted = FoodWords.key(name).toSet()
        // How far the nearest of the food's names is from what was typed; everyday words ("boiled") count for less.
        fun extra(food: IndexedFood): Int = FoodWords.aliases(food.name).filter { alias -> FoodWords.key(alias).containsAll(wanted) }
            .minOfOrNull { alias -> (FoodWords.key(alias) - wanted).sumOf { w -> if (w in FoodWords.PLAIN) 1 else 2 } } ?: 99
        val best = found.values.sortedWith(
            compareBy<Pair<IndexedFood, Int>>(
                { !(exact(it.first) && it.first.own) },
                { !exact(it.first) },
                { it.first.useCount == 0 },
                { -it.first.useCount },
                { !it.first.own },
                { it.first.caveat != null },
                { extra(it.first) },
                { it.second },
                { it.first.name.length },
            ),
        ).first().first
        return best to exact(best)
    }

    /** The words as typed, in the singular, and with each synonym in turn. */
    private fun variants(name: List<String>): List<List<String>> {
        val single = name.map(FoodWords::singular)
        val swapped = single.indices.flatMap { i -> FoodWords.synonyms(name[i]).map { s -> single.toMutableList().also { it[i] = s } } }
        return (listOf(name, single) + swapped).distinct()
    }

    /** The item for a picked food, and whether the typed unit (if any) was one the food or the household has. */
    private fun item(chunk: Chunk, food: IndexedFood, exact: Boolean, name: List<String>, unit: String?, household: Map<String, Double>): Pair<ParsedItem, Boolean> {
        val own = food.units.filter { (label, grams) -> label != Portions.GRAMS && grams > 0 }
        fun ownUnit(label: String) = own.firstOrNull { it.first.lowercase() == label } ?: own.firstOrNull { FoodWords.words(it.first).lastOrNull() == label }
        var resolved = true
        var sure = true
        val why: String
        val amount: Pair<String, Double>
        when {
            chunk.weight != null -> {
                amount = Portions.GRAMS to 1.0
                why = ""
            }
            unit != null -> {
                val typed = ownUnit(unit) ?: FoodWords.unitSynonym(unit)?.let(::ownUnit) ?: household[unit]?.let { unit to it }
                    ?: FoodWords.unitSynonym(unit)?.let { s -> household[s]?.let { s to it } }
                    ?: FoodWords.GENERIC_UNITS[unit]?.let { unit to it }
                if (typed != null) {
                    amount = typed
                    why = "1 ${typed.first} = ${Portions.format(typed.second)} g"
                } else {
                    resolved = false
                    sure = false
                    amount = usual(food, own, name, household).first
                    why = "no “$unit” for this food, so its usual serving was used"
                }
            }
            else -> {
                val (serving, certain) = usual(food, own, name, household)
                amount = serving
                sure = certain
                why = if (serving.first == Portions.GRAMS) "no serving size known, so counted as 100 g each" else "1 ${serving.first} = ${Portions.format(serving.second)} g"
            }
        }
        val grams = chunk.weight ?: (chunk.quantity * amount.second * if (amount.first == Portions.GRAMS) 100.0 else 1.0)
        val quantity = if (amount.first == Portions.GRAMS) grams else chunk.quantity
        val confidence = when {
            !resolved -> Confidence.LOW
            exact && sure -> Confidence.HIGH
            exact || (sure && food.useCount > 0) -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        val note = listOf("Matched “${chunk.typed}”", why).filter { it.isNotEmpty() }.joinToString("; ")
        return ParsedItem(food, chunk.typed, quantity, amount.first, grams, confidence, note) to resolved
    }

    /**
     * The serving when no unit was typed, and whether it's a sure one: the food's unit named in the text
     * ("2 roti"), else the unit you logged it in last, else its first own serving, else 100 g.
     */
    private fun usual(food: IndexedFood, own: List<Pair<String, Double>>, name: List<String>, household: Map<String, Double>): Pair<Pair<String, Double>, Boolean> {
        val words = name.map(FoodWords::canonical).toSet()
        own.firstOrNull { FoodWords.canonical(it.first.lowercase()) in words }?.let { return it to true }
        food.lastUnit?.let { last -> (own.firstOrNull { it.first == last } ?: household[last]?.let { last to it })?.let { return it to true } }
        own.firstOrNull()?.takeIf { food.own || Portions.plausible(it.second) }?.let { return it to (own.size == 1) }
        return (Portions.GRAMS to 1.0) to false
    }
}
