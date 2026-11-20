package app.recomp.domain.food

/**
 * Amounts as people type them (AI-04, TRD §6.2's local parser): "2", "1.5", "1/2", "1 1/2", "½", "2-3", "half a",
 * "one and a half", "a couple of". Plain rules, no AI.
 */
object Quantities {
    /** A quantity read from the start of some tokens, and how many tokens it took. */
    data class Read(val value: Double, val used: Int)

    private val WORDS = mapOf(
        "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0, "five" to 5.0, "six" to 6.0,
        "seven" to 7.0, "eight" to 8.0, "nine" to 9.0, "ten" to 10.0, "eleven" to 11.0, "twelve" to 12.0,
    )
    private val ARTICLES = setOf("a", "an")
    private val PARTS = mapOf("half" to 0.5, "quarter" to 0.25)
    private val GLYPHS = mapOf('½' to "1/2", '¼' to "1/4", '¾' to "3/4", '⅓' to "1/3", '⅔' to "2/3")

    /** Above this an amount is a typo or a phone number, not food. */
    const val MAX = 10_000.0

    private val TOKEN = Regex("""\d+(?:\.\d+)?(?:/\d+)?(?:-\d+(?:\.\d+)?)?|[\p{L}]+""")

    /** Lower-cased, with fraction glyphs spelled out ("2½" → "2 1/2") and "&" read as "and". */
    fun normalise(text: String): String = buildString {
        text.lowercase().forEach { c ->
            when {
                c in GLYPHS -> append(' ').append(GLYPHS.getValue(c)).append(' ')
                c == '&' -> append(" and ")
                c == '×' -> append(" x ")
                else -> append(c)
            }
        }
    }

    /** Numbers and words; punctuation is dropped, so "150g" is "150", "g" and "low-fat" is "low", "fat". */
    fun tokens(chunk: String): List<String> = TOKEN.findAll(chunk).map { it.value }.toList()

    fun isNumber(token: String): Boolean = token.firstOrNull()?.isDigit() == true

    private fun isFraction(token: String): Boolean = isNumber(token) && '/' in token && '-' !in token

    /** "2", "1.5", "1/2", or a range's midpoint ("2-3" → 2.5). Null for anything that isn't a positive amount. */
    fun number(token: String): Double? {
        if (!isNumber(token)) return null
        val value = when {
            '-' in token -> {
                val (low, high) = token.split('-', limit = 2).map { number(it) ?: return null }
                if (high < low) return null
                (low + high) / 2
            }
            '/' in token -> {
                val (top, bottom) = token.split('/', limit = 2).map { it.toDoubleOrNull() ?: return null }
                if (bottom == 0.0) return null
                top / bottom
            }
            else -> token.toDoubleOrNull() ?: return null
        }
        return value.takeIf { it.isFinite() && it > 0 && it <= MAX }
    }

    /** "and a half", "and half", "and 1/2" after a whole amount: what it adds and how many tokens it took. */
    private fun andPart(tokens: List<String>, from: Int): Read? {
        if (tokens.getOrNull(from) != "and") return null
        var i = from + 1
        if (tokens.getOrNull(i) in ARTICLES) i++
        val next = tokens.getOrNull(i) ?: return null
        val part = PARTS[next] ?: next.takeIf(::isFraction)?.let(::number)?.takeIf { it < 1 } ?: return null
        return Read(part, i + 1 - from)
    }

    /** Whether the "and" at [index] joins an amount ("one and a half") rather than two foods. */
    fun joinsAmount(tokens: List<String>, index: Int): Boolean {
        val before = tokens.getOrNull(index - 1) ?: return false
        return (isNumber(before) || before in WORDS) && andPart(tokens, index) != null
    }

    /** The amount at the start of [tokens], or null when they don't start with one. */
    fun leading(tokens: List<String>): Read? {
        val first = tokens.firstOrNull() ?: return null
        val second = tokens.getOrNull(1)
        return when {
            isNumber(first) -> {
                val whole = number(first) ?: return null
                // "1 1/2": a whole number, then a fraction.
                val mixed = second?.takeIf { isFraction(it) && '/' !in first && '-' !in first && whole % 1.0 == 0.0 }?.let(::number)
                if (mixed != null && mixed < 1) Read(whole + mixed, 2) else withPart(whole, 1, tokens)
            }
            first in WORDS -> withPart(WORDS.getValue(first), 1, tokens)
            first in PARTS -> Read(PARTS.getValue(first), if (second in ARTICLES) 2 else 1)
            first in ARTICLES -> when {
                second == "couple" -> Read(2.0, 2)
                second in PARTS -> Read(PARTS.getValue(second!!), 2)
                else -> Read(1.0, 1)
            }
            first == "couple" -> Read(2.0, 1)
            else -> null
        }
    }

    private fun withPart(whole: Double, used: Int, tokens: List<String>): Read {
        val part = andPart(tokens, used) ?: return Read(whole, used)
        return Read(whole + part.value, used + part.used)
    }
}
