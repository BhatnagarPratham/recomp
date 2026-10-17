package app.recomp.domain.food

/** Food search text (NU-11, TRD §6.4). */
object FoodQuery {
    private val WORD = Regex("[\\p{L}\\p{N}]+")
    private const val MAX_WORDS = 8

    /** The words of a query, lower-cased, ignoring punctuation and FTS syntax. */
    fun words(text: String): List<String> = WORD.findAll(text.lowercase()).map { it.value }.take(MAX_WORDS).toList()

    /**
     * An FTS4 MATCH expression that finds every word as a prefix ("pan* tik*"), or null when the text
     * has no words, in which case search lists recent foods instead.
     */
    fun match(text: String): String? = words(text).takeIf { it.isNotEmpty() }?.joinToString(" ") { "$it*" }
}
