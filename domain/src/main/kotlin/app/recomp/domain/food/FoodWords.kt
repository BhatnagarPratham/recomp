package app.recomp.domain.food

/** The local parser's vocabulary (AI-04, TRD §6.2): units, synonyms and the words that carry no food. */
object FoodWords {
    /** Words that say nothing about which food it is. */
    val STOP = setOf("of", "a", "an", "the", "some", "my", "small", "medium", "large", "big", "fresh", "homemade", "x")

    /** Words that split one chunk into two foods, unless a food's own name carries them ("poha with curd"). */
    val SOFT = setOf("and", "with")

    /** Weights and volumes, in grams (1 ml counts as 1 g). */
    val WEIGHTS = mapOf(
        "g" to 1.0, "gm" to 1.0, "gms" to 1.0, "gram" to 1.0, "grams" to 1.0, "ml" to 1.0,
        "kg" to 1000.0, "kgs" to 1000.0, "kilo" to 1000.0, "kilos" to 1000.0,
        "l" to 1000.0, "litre" to 1000.0, "litres" to 1000.0, "liter" to 1000.0, "liters" to 1000.0,
    )

    /** Other spellings of a unit's label. */
    private val UNIT_SPELLINGS = mapOf(
        "tablespoon" to "tbsp", "tablespoons" to "tbsp", "tbs" to "tbsp", "tbsps" to "tbsp",
        "teaspoon" to "tsp", "teaspoons" to "tsp", "tsps" to "tsp",
        "pc" to "piece", "pcs" to "piece",
        "vati" to "katori", "wati" to "katori",
    )

    /** Units the parser knows before it has seen any food; a food's own unit labels are added to these. */
    val UNITS = setOf("katori", "glass", "tbsp", "tsp", "cup", "roti", "piece", "plate", "slice", "bowl", "scoop", "handful", "egg", "serving")

    /** Units that are foods too, so they only read as units after an amount. */
    val FOOD_UNITS = setOf("roti", "egg")

    /** A unit to use when the food has none of the typed label: a bowl is a katori. */
    private val UNIT_SYNONYMS = mapOf("bowl" to "katori")

    /** Weights for units that are neither the food's own nor one of the region's household measures. */
    val GENERIC_UNITS = mapOf("cup" to 240.0, "tsp" to 5.0)

    /**
     * Names for the same food. A typed word is also looked up as each of the others; what's shown is always the
     * matched food's own name.
     */
    private val NAME_GROUPS = listOf(
        listOf("roti", "chapati", "chapatti", "phulka", "fulka"),
        listOf("curd", "dahi", "yogurt", "yoghurt"),
        listOf("tea", "chai"),
        listOf("rice", "chawal"),
        listOf("potato", "aloo"),
        listOf("egg", "anda"),
        listOf("paratha", "parantha", "parotha"),
        listOf("chana", "channa", "chickpea"),
        listOf("moong", "mung"),
        listOf("khichdi", "khichri"),
        listOf("chaas", "chhaas", "buttermilk"),
    )
    private val GROUP_OF: Map<String, List<String>> = NAME_GROUPS.flatMap { group -> group.map { it to group } }.toMap()

    /** Words that mark the everyday version of a food: "rice" means boiled rice before lemon rice. */
    val PLAIN = setOf("plain", "boiled", "steamed", "cooked", "whole", "simple", "hot")

    /** "rotis" → "roti", "mangoes" → "mango", "berries" → "berry". A guess: callers try the typed word first. */
    fun singular(word: String): String = when {
        word.length > 4 && word.endsWith("ies") -> word.dropLast(3) + "y"
        word.length > 4 && word.endsWith("oes") -> word.dropLast(2)
        word.length > 4 && (word.endsWith("ches") || word.endsWith("shes") || word.endsWith("xes") || word.endsWith("sses")) -> word.dropLast(2)
        word.length > 3 && word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us") && !word.endsWith("aas") -> word.dropLast(1)
        else -> word
    }

    /** One spelling for every word of a synonym group, in the singular: how two names are compared. */
    fun canonical(word: String): String {
        val one = singular(word)
        return GROUP_OF[one]?.first() ?: GROUP_OF[word]?.first() ?: one
    }

    /** The other names for [word], singular first; empty when it has none. */
    fun synonyms(word: String): List<String> = (GROUP_OF[word] ?: GROUP_OF[singular(word)]).orEmpty().filter { it != word && it != singular(word) }

    /** The unit a word names ("bowls" → "bowl", "tablespoons" → "tbsp"), or null when it isn't one. [own] are foods' own unit labels. */
    fun unit(word: String, own: Set<String> = emptySet()): String? {
        val spelled = UNIT_SPELLINGS[word] ?: word
        val one = UNIT_SPELLINGS[singular(word)] ?: singular(spelled)
        return listOf(spelled, one).firstOrNull { it in UNITS || it in own }
    }

    fun unitSynonym(unit: String): String? = UNIT_SYNONYMS[unit]

    /** The words of a name, as the search index splits them. */
    fun words(text: String): List<String> = Quantities.tokens(text.lowercase()).filterNot(Quantities::isNumber)

    /**
     * The names a food goes by: its whole name, and each part between brackets and commas, so "Pea paneer curry
     * (Matar paneer)" is matar paneer. A slash is read two ways, as the database uses it:
     * - between single words ("Chapati/Roti") or after one word ("Okra/Lady's fingers fry"), each side is a name;
     * - after a longer name, a single word is another spelling of its last word: "Quinoa khichdi/khichri" is quinoa
     *   khichdi or quinoa khichri, never a bare khichri, and "Bhindi sabzi/sabji/subji" is never a bare subji.
     */
    fun aliases(name: String): List<List<String>> {
        val parts = name.lowercase().split(SEGMENT).flatMap(::slashNames).filter { it.isNotEmpty() }
        // The name without what's in brackets: "Milk, toned (3% fat)" is toned milk.
        val outside = words(name.replace(BRACKETED, " ").replace('/', ' ')).takeIf { '(' in name }
        return (listOf(words(name)) + listOfNotNull(outside) + parts).distinct()
    }

    private val SEGMENT = Regex("""[(),;]|\bor\b""")

    private val BRACKETED = Regex("""\([^)]*\)""")

    /** One bracket- or comma-separated part of a name, read for slashes (see [aliases]). */
    private fun slashNames(part: String): List<List<String>> {
        val sides = part.split('/').map(::words).filter { it.isNotEmpty() }
        if (sides.size < 2) return sides
        val first = sides.first()
        if (first.size == 1) return sides
        return listOf(first) + sides.drop(1).map { side -> if (side.size == 1) first.dropLast(1) + side else side }
    }

    /** Words after which a name lists what the food is without: "Mayonnaise without eggs" isn't eggs. */
    val NEGATORS = setOf("without", "no", "eggless")

    /** Whether every place [word] appears in [name] comes after "without" or "no", so the food doesn't have it. */
    fun onlyNegated(name: String, word: String): Boolean {
        val all = words(name).map(::canonical)
        val target = canonical(word)
        val at = all.indices.filter { all[it] == target }
        val negator = all.indexOfFirst { it in NEGATORS }
        return at.isNotEmpty() && negator >= 0 && at.all { it > negator }
    }

    /** Whether two names are the same food name, ignoring plurals, synonyms and word order. */
    fun sameName(a: List<String>, b: List<String>): Boolean = key(a) == key(b)

    fun key(words: List<String>): List<String> = words.filterNot { it in STOP }.map(::canonical).sorted()
}
