package app.recomp.domain.food

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A product as Open Food Facts returns it (NU-21, NU-11; TRD §6.4), already decoded. Open Food Facts is filled in
 * by volunteers, so any field may be missing: values the form can't take are left blank for you to fill.
 */
data class OffProduct(
    val code: String,
    val name: String?,
    val brand: String?,
    val kcal100g: Double?,
    val kj100g: Double?,
    val protein100g: Double?,
    val carbs100g: Double?,
    val fat100g: Double?,
    val fiber100g: Double?,
    /** `ingredients_analysis_tags` and `allergens_tags`, e.g. "en:vegan", "en:non-vegetarian", "en:eggs". */
    val tags: Set<String> = emptySet(),
    val servingG: Double? = null,
)

/** A product's values as the New food form shows them: per 100 g, with any it lacks left null. */
data class OffValues(val kcal: Double?, val proteinG: Double?, val carbsG: Double?, val fatG: Double?, val fiberG: Double?) {
    /** Everything a food row needs is there, so it can be saved without typing. */
    val complete: Boolean get() = kcal != null && proteinG != null && carbsG != null && fatG != null
}

object OpenFoodFacts {
    /** Its terms: about 15 product reads and 10 searches a minute per user (TRD §10 M18). */
    const val PRODUCTS_PER_MINUTE = 15
    const val SEARCHES_PER_MINUTE = 10

    /** Search waits for this pause in typing, and this many characters, so it's never search-as-you-type. */
    const val SEARCH_PAUSE_MS = 600L
    const val SEARCH_MIN_CHARS = 3

    /** Fewer of your own results than this, in the International region, and search asks Open Food Facts too. */
    const val FEW_RESULTS = 5

    /** A food row's key for a product, so scanning it twice never makes two foods. */
    fun sourceKey(code: String) = "off:$code"

    /** kcal when given, else kJ ÷ 4.184; macros as given. Values no food can have are dropped. */
    fun values(p: OffProduct): OffValues {
        fun grams(x: Double?) = x?.takeIf { it in 0.0..100.0 }?.round1()
        val kcal = (p.kcal100g ?: p.kj100g?.div(LabelParser.KJ_PER_KCAL))?.takeIf { it in 0.0..950.0 }?.round1()
        return OffValues(kcal, grams(p.protein100g), grams(p.carbs100g), grams(p.fat100g), grams(p.fiber100g))
    }

    /** "Brand · name", or whichever there is, or the barcode. */
    fun displayName(p: OffProduct): String = p.name?.trim()?.takeIf { it.isNotEmpty() } ?: p.brand?.trim()?.takeIf { it.isNotEmpty() } ?: p.code

    /**
     * A diet tag from Open Food Facts' own analysis and allergens, or null when it doesn't say (the form then asks).
     * It can't tell dairy from egg in a "vegetarian" product, so egg in the allergens decides that; it's the
     * strictest reading, since a tag only hides a food from diets that don't allow it.
     */
    fun diet(tags: Set<String>): FoodDiet? {
        val eggs = "en:eggs" in tags
        val fish = tags.any { it in FISH_ALLERGENS }
        return when {
            "en:vegan" in tags -> FoodDiet.VEGAN
            "en:non-vegetarian" in tags -> if (fish && "en:maybe-vegetarian" !in tags && tags.none { it.contains("meat") }) FoodDiet.FISH else FoodDiet.MEAT
            "en:vegetarian" in tags -> if (eggs) FoodDiet.EGG else FoodDiet.VEGETARIAN
            else -> null
        }
    }

    private val FISH_ALLERGENS = setOf("en:fish", "en:crustaceans", "en:molluscs")

    private fun Double.round1(): Double = (this * 10).roundToInt() / 10.0
}

/**
 * Retail barcodes (NU-21): EAN-13, EAN-8, UPC-A (12 digits) and UPC-E, as ZXing reads them. Only codes whose
 * check digit is right are looked up, so a misread never costs one of Open Food Facts' requests.
 */
object Barcodes {
    fun valid(code: String): Boolean {
        if (!code.all { it.isDigit() }) return false
        return when (code.length) {
            8, 12, 13, 14 -> checkDigitOk(code)
            else -> false
        }
    }

    /** GS1's mod-10: from the right, excluding the check digit, weights 3, 1, 3… */
    private fun checkDigitOk(code: String): Boolean {
        val digits = code.map { it - '0' }
        val body = digits.dropLast(1).reversed()
        val sum = body.withIndex().sumOf { (i, d) -> if (i % 2 == 0) d * 3 else d }
        return (10 - sum % 10) % 10 == digits.last()
    }
}

/**
 * Keeps us inside Open Food Facts' limits (TRD §10 M18): a sliding minute per kind of request, and after a 429
 * no request of either kind until the wait it asked for (or a minute, doubling while it keeps refusing) is over.
 * Pure, with times passed in, so it's tested without a clock.
 */
class OffThrottle {
    enum class Kind(val perMinute: Int) { PRODUCT(OpenFoodFacts.PRODUCTS_PER_MINUTE), SEARCH(OpenFoodFacts.SEARCHES_PER_MINUTE) }

    private val sent = mutableMapOf<Kind, ArrayDeque<Long>>()
    private var blockedUntil = 0L
    private var backoffMs = BASE_BACKOFF_MS

    /** How long to wait before a request of [kind] may go, 0 when it may go now. */
    @Synchronized
    fun waitMs(kind: Kind, now: Long): Long {
        if (now < blockedUntil) return blockedUntil - now
        val times = sent.getOrPut(kind) { ArrayDeque() }
        while (times.isNotEmpty() && now - times.first() >= MINUTE_MS) times.removeFirst()
        return if (times.size < kind.perMinute) 0 else MINUTE_MS - (now - times.first())
    }

    /** Records a request that went out. */
    @Synchronized
    fun sentAt(kind: Kind, now: Long) {
        sent.getOrPut(kind) { ArrayDeque() }.addLast(now)
    }

    /** A 429: back off for what the server asked, else a minute that doubles up to [MAX_BACKOFF_MS]. */
    @Synchronized
    fun refused(now: Long, retryAfterMs: Long?) {
        val wait = retryAfterMs?.coerceIn(1_000, MAX_BACKOFF_MS) ?: backoffMs
        blockedUntil = max(blockedUntil, now + wait)
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
    }

    /** A request that got an answer ends any backoff's doubling. */
    @Synchronized
    fun answered() {
        backoffMs = BASE_BACKOFF_MS
    }

    companion object {
        const val MINUTE_MS = 60_000L
        const val BASE_BACKOFF_MS = 60_000L
        const val MAX_BACKOFF_MS = 15 * 60_000L
    }
}
