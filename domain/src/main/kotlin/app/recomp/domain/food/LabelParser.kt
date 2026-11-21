package app.recomp.domain.food

import kotlin.math.roundToInt

/** A value the label scan can fill in New food (NU-19), so the form can highlight what it filled. */
enum class LabelField { KCAL, PROTEIN, CARBS, FAT, FIBER, SERVING }

/** Which column the label's numbers came from. */
enum class LabelBasis { PER_100G, PER_SERVING }

/**
 * What a nutrition label says, as read (NU-19, TRD §6.4). Values are for [basis]: per 100 g, or per serving of
 * [servingG] grams (null when the label didn't say). Anything not found is null, and the form leaves it blank.
 */
data class LabelValues(
    val basis: LabelBasis,
    val kcal: Double? = null,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    val fiberG: Double? = null,
    val servingG: Double? = null,
) {
    val filled: Set<LabelField>
        get() = buildSet {
            if (kcal != null) add(LabelField.KCAL)
            if (proteinG != null) add(LabelField.PROTEIN)
            if (carbsG != null) add(LabelField.CARBS)
            if (fatG != null) add(LabelField.FAT)
            if (fiberG != null) add(LabelField.FIBER)
            if (servingG != null) add(LabelField.SERVING)
        }

    /** Nothing useful was read: the form says so rather than opening empty-handed. */
    val empty: Boolean get() = kcal == null && proteinG == null && carbsG == null && fatG == null
}

/**
 * Reads the text an on-device recogniser got from a nutrition label (NU-19, TRD §6.4). Tolerant by design: OCR
 * slips letters into numbers, labels put the columns in either order, and energy comes in kcal, kJ or both.
 *
 * - **FSSAI and EU tables:** a header names the columns ("Per 100 g", "Per Serve (30 g)"), and each nutrient's line
 *   has one number per column. The per-100 g column is used when there is one.
 * - **US "Nutrition Facts":** values are per serving, with "Serving size … (55g)" giving its weight.
 * - **Energy:** kcal when the label gives it, else kJ ÷ 4.184.
 *
 * Lines about sugars, saturated or trans fat and the like are never taken for carbohydrate or fat. Values a label
 * can't have (more than 100 g of a nutrient per 100 g, or over 950 kcal) are dropped, so a misread digit leaves
 * a blank to fill rather than a wrong number.
 */
object LabelParser {
    const val KJ_PER_KCAL = 4.184
    private const val MAX_KCAL_100G = 950.0
    private const val MAX_GRAMS_100G = 100.0

    fun parse(text: String): LabelValues {
        val lines = text.lines().map(::clean).filter { it.isNotBlank() }
        val layout = layout(lines)
        val serving = servingGrams(lines)
        // No per-100 g column, but a serving size: the numbers are per serving (US labels, some packs).
        val basis = if (layout.per100Index == null && (serving != null || layout.servingOnly)) LabelBasis.PER_SERVING else LabelBasis.PER_100G
        val column = when (basis) {
            LabelBasis.PER_100G -> layout.per100Index ?: 0
            LabelBasis.PER_SERVING -> layout.servingIndex ?: 0
        }

        fun pick(field: LabelField): Double? {
            val line = lines.firstOrNull { matches(field, it) } ?: return null
            val numbers = if (field == LabelField.KCAL) energy(line, lines) else numbers(afterLabel(line))
            return numbers.getOrNull(column) ?: numbers.takeIf { it.size == 1 }?.first()
        }

        val values = LabelValues(
            basis = basis,
            kcal = pick(LabelField.KCAL),
            proteinG = pick(LabelField.PROTEIN),
            carbsG = pick(LabelField.CARBS),
            fatG = pick(LabelField.FAT),
            fiberG = pick(LabelField.FIBER),
            servingG = serving,
        )
        return if (basis == LabelBasis.PER_100G) plausible(values) else plausibleServing(values)
    }

    // Layout

    /** Which number on a nutrient line is per 100 g and which per serving, from the header line. */
    private data class Layout(val per100Index: Int?, val servingIndex: Int?, val servingOnly: Boolean)

    private val per100 = Regex("""(?i)100\s*(g|gm|grams?|ml)\b""")
    private val perServing = Regex("""(?i)\b(serv(e|ing)s?|portion|per\s+pack|per\s+piece|per\s+\d+(\.\d+)?\s*(g|ml)\b)""")

    private fun layout(lines: List<String>): Layout {
        for (line in lines) {
            val hundred = per100.find(line)
            // "per 100 g" also looks like "per <n> g": a serving match that overlaps the 100 g one is the same column.
            val serve = perServing.findAll(line).firstOrNull { m -> hundred == null || m.range.last < hundred.range.first || m.range.first > hundred.range.last }
            // A "Serving size" line is a fact about the pack, not a column header.
            if (servingSize.containsMatchIn(line) && hundred == null) continue
            when {
                hundred != null && serve != null -> return if (hundred.range.first < serve.range.first) Layout(0, 1, false) else Layout(1, 0, false)
                hundred != null -> return Layout(0, null, false)
            }
        }
        val anyServeHeader = lines.any { perServing.containsMatchIn(it) && !servingSize.containsMatchIn(it) && Regex("""(?i)\bper\b""").containsMatchIn(it) }
        return Layout(null, if (anyServeHeader) 0 else null, anyServeHeader)
    }

    private val servingSize = Regex("""(?i)serv(ing|e)\s*size""")
    private val gramsInLine = Regex("""(?i)(\d+(?:\.\d+)?)\s*(g|gm|ml)\b""")

    /** "Serving size 2/3 cup (55g)", "Per Serve (30 g)", "per 30 g portion": the serving's weight in grams. */
    private fun servingGrams(lines: List<String>): Double? {
        for (line in lines) {
            val serve = servingSize.containsMatchIn(line) || perServing.containsMatchIn(line)
            if (!serve) continue
            val grams = gramsInLine.findAll(line).map { it.groupValues[1].toDouble() }.filter { it != 100.0 && it > 0 && it <= 2_000 }.toList()
            grams.lastOrNull()?.let { return it }
        }
        return null
    }

    // Nutrient lines

    private val labels: Map<LabelField, Regex> = mapOf(
        LabelField.KCAL to Regex("""(?i)\b(energy|calories|calorie|kcal)\b"""),
        LabelField.PROTEIN to Regex("""(?i)\bprotein"""),
        LabelField.CARBS to Regex("""(?i)\b(carbohydrates?|total\s+carb\w*|carbs)\b"""),
        LabelField.FAT to Regex("""(?i)\b(total\s+fat|fat)\b"""),
        LabelField.FIBER to Regex("""(?i)\b(dietary\s+)?fib(re|er)\b"""),
    )

    /** Sub-lines that would otherwise be read as their parent nutrient. */
    private val subLine = Regex("""(?i)(of\s+which|sugar|saturate|trans|mono|poly|added|from\s+fat|cholesterol|%\s*dv)""")

    private fun matches(field: LabelField, line: String): Boolean {
        if (!labels.getValue(field).containsMatchIn(line)) return false
        if (field == LabelField.CARBS || field == LabelField.FAT) {
            if (subLine.containsMatchIn(line)) return false
        }
        if (field == LabelField.KCAL && servingSize.containsMatchIn(line)) return false
        // A header ("Nutrient Per 100 g") isn't a value line.
        return numbers(afterLabel(line)).isNotEmpty() || field == LabelField.KCAL
    }

    /** The text after the nutrient's name and its "(g)", so "Protein (g)" and "Per 100 g" don't add numbers. */
    private fun afterLabel(line: String): String {
        val unitParen = Regex("""\((k?cal|kj|g|mg|mcg|µg)\)""", RegexOption.IGNORE_CASE)
        val firstDigit = line.indexOfFirst { it.isDigit() }
        val stripped = unitParen.replace(line, " ")
        return if (firstDigit < 0) "" else stripped.substring(stripped.indexOfFirst { it.isDigit() }.coerceAtLeast(0))
    }

    private val number = Regex("""(\d+(?:\.\d+)?)\s*(%)?""")

    /** The numbers on a line, leaving out percentages (% daily value). */
    private fun numbers(text: String): List<Double> =
        number.findAll(text).filter { it.groupValues[2].isEmpty() }.map { it.groupValues[1].toDouble() }.toList()

    private val kcalValue = Regex("""(?i)(\d+(?:\.\d+)?)\s*k?cal\b""")
    private val kjValue = Regex("""(?i)(\d+(?:\.\d+)?)\s*kj\b""")

    /**
     * Energy per column. "1680 kJ / 402 kcal 504 kJ / 121 kcal" gives the kcal numbers. A line in kJ only
     * ("Energy (kJ) 1680 504") is converted, unless a kcal line follows it, which is used instead.
     */
    private fun energy(line: String, all: List<String>): List<Double> {
        val kcal = kcalValue.findAll(line).map { it.groupValues[1].toDouble() }.toList()
        if (kcal.isNotEmpty()) return kcal
        val kj = kjValue.findAll(line).map { it.groupValues[1].toDouble() }.toList()
        val headerKj = Regex("""(?i)\(\s*kj\s*\)""").containsMatchIn(line)
        val headerKcal = Regex("""(?i)\(\s*k?cal\s*\)""").containsMatchIn(line)
        if (kj.isEmpty() && headerKj) {
            // "Energy (kJ)" here; a kcal line of its own wins.
            all.firstOrNull { it != line && Regex("""(?i)(energy|calories)""").containsMatchIn(it) && Regex("""(?i)k?cal""").containsMatchIn(it) && !Regex("""(?i)kj""").containsMatchIn(it) }
                ?.let { other -> numbers(afterLabel(other)).takeIf { it.isNotEmpty() }?.let { return it } }
            return numbers(afterLabel(line)).map { it / KJ_PER_KCAL }
        }
        if (kj.isNotEmpty()) return kj.map { it / KJ_PER_KCAL }
        return numbers(afterLabel(line)).takeIf { headerKcal || it.isNotEmpty() }.orEmpty()
    }

    // Cleaning and checks

    /**
     * OCR's usual slips inside numbers: O for 0, l or I for 1, a comma for the decimal point, and a space after
     * the point ("12. 5"). Words are left alone.
     */
    fun clean(line: String): String {
        var s = line.replace(' ', ' ').replace('|', ' ')
        s = Regex("""(?<=\d)[Oo](?=[\d\s.,gk]|$)|(?<=[\s(])[Oo](?=[.,]\d)""").replace(s, "0")
        s = Regex("""(?<=\d)[lI](?=[\d.,\s]|$)|(?<=\s)[lI](?=\d)""").replace(s, "1")
        s = Regex("""(?<=\d),(?=\d)""").replace(s, ".")
        s = Regex("""(?<=\d)\.\s(?=\d)""").replace(s, ".")
        return s.trim()
    }

    private fun plausible(v: LabelValues): LabelValues = v.copy(
        kcal = v.kcal?.takeIf { it in 0.0..MAX_KCAL_100G }?.round1(),
        proteinG = v.proteinG?.takeIf { it in 0.0..MAX_GRAMS_100G }?.round1(),
        carbsG = v.carbsG?.takeIf { it in 0.0..MAX_GRAMS_100G }?.round1(),
        fatG = v.fatG?.takeIf { it in 0.0..MAX_GRAMS_100G }?.round1(),
        fiberG = v.fiberG?.takeIf { it in 0.0..MAX_GRAMS_100G }?.round1(),
    )

    /** Per serving, a nutrient can't outweigh the serving. */
    private fun plausibleServing(v: LabelValues): LabelValues {
        val cap = v.servingG ?: 2_000.0
        fun ok(x: Double?) = x?.takeIf { it in 0.0..cap }?.round1()
        return v.copy(
            kcal = v.kcal?.takeIf { it in 0.0..(cap * 9.5) }?.round1(),
            proteinG = ok(v.proteinG), carbsG = ok(v.carbsG), fatG = ok(v.fatG), fiberG = ok(v.fiberG),
        )
    }

    private fun Double.round1(): Double = (this * 10).roundToInt() / 10.0
}
