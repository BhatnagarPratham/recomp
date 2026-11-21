package app.recomp.data.repo

import android.graphics.Bitmap
import app.recomp.data.db.AiDao
import app.recomp.data.db.FoodEntity
import app.recomp.data.food.LabelReader
import app.recomp.data.food.OffReply
import app.recomp.data.food.OpenFoodFactsClient
import app.recomp.data.food.PhotoStore
import app.recomp.domain.ai.AiImage
import app.recomp.domain.ai.AiState
import app.recomp.domain.food.Barcodes
import app.recomp.domain.food.FoodDiet
import app.recomp.domain.food.FoodSource
import app.recomp.domain.food.LabelBasis
import app.recomp.domain.food.LabelField
import app.recomp.domain.food.LabelParser
import app.recomp.domain.food.LabelValues
import app.recomp.domain.food.OffProduct
import app.recomp.domain.food.OpenFoodFacts
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * What New food opens with after a scan (NU-19, NU-21): values as read, the fields that were filled (highlighted
 * for you to check), and where it came from, which the saved food keeps.
 */
data class FoodPrefill(
    val name: String,
    val brand: String?,
    val values: LabelValues,
    val diet: FoodDiet?,
    val source: FoodSource,
    val barcode: String?,
    /** Said above the fields, e.g. that AI wasn't reachable so this is the phone's own reading. */
    val note: String? = null,
) {
    val filled: Set<LabelField> get() = values.filled
}

/** A scanned barcode's outcome (NU-21). */
sealed interface BarcodeLookup {
    /** You already have this food: its portion sheet opens. */
    data class Yours(val food: FoodEntity) : BarcodeLookup
    /** Open Food Facts has it: New food opens with its values for you to check and save. */
    data class Found(val prefill: FoodPrefill) : BarcodeLookup
    data object NotFound : BarcodeLookup
    data object Invalid : BarcodeLookup
    data class Busy(val waitMs: Long) : BarcodeLookup
    data object Unreachable : BarcodeLookup
}

/** Open Food Facts search results for the International region (NU-11). */
sealed interface OffSearch {
    data class Results(val products: List<OffProduct>) : OffSearch
    data class Busy(val waitMs: Long) : OffSearch
    data object Unreachable : OffSearch
}

/** How a label scan went (NU-19): what New food opens with, and whether AI checked the reading. */
data class LabelScan(val prefill: FoodPrefill, val aiUsed: Boolean)

/**
 * Camera food (NU-19, NU-20, NU-21; TRD §10 M18): barcodes against your foods then Open Food Facts, its search for
 * the International region, and label scans read on the phone and, with AI on, cleaned up by your provider.
 */
@Singleton
class CameraFoodRepository @Inject constructor(
    private val foods: FoodRepository,
    private val ai: AiRepository,
    private val off: OpenFoodFactsClient,
    private val reader: LabelReader,
    private val photos: PhotoStore,
    private val aiDao: AiDao,
) {
    /** Searches already asked this session: Open Food Facts asks apps not to repeat them. */
    private val searches = object : LinkedHashMap<String, List<OffProduct>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<OffProduct>>?) = size > CACHE_SIZE
    }

    /** Products already fetched this session, by barcode. */
    private val products = mutableMapOf<String, OffProduct>()

    suspend fun barcode(code: String, now: Long = System.currentTimeMillis()): BarcodeLookup {
        val c = code.trim()
        if (!Barcodes.valid(c)) return BarcodeLookup.Invalid
        foods.byBarcode(c)?.let { return BarcodeLookup.Yours(it) }
        products[c]?.let { return BarcodeLookup.Found(prefill(it)) }
        return when (val r = off.product(c, now)) {
            is OffReply.Ok -> {
                products[c] = r.value
                BarcodeLookup.Found(prefill(r.value))
            }
            OffReply.NotFound -> BarcodeLookup.NotFound
            is OffReply.Busy -> BarcodeLookup.Busy(r.waitMs)
            OffReply.Unreachable -> BarcodeLookup.Unreachable
        }
    }

    suspend fun search(words: String, now: Long = System.currentTimeMillis()): OffSearch {
        val key = words.trim().lowercase().replace(Regex("\\s+"), " ")
        synchronized(searches) { searches[key] }?.let { return OffSearch.Results(it) }
        return when (val r = off.search(key, now)) {
            is OffReply.Ok -> {
                synchronized(searches) { searches[key] = r.value }
                r.value.forEach { products[it.code] = it }
                OffSearch.Results(r.value)
            }
            OffReply.NotFound -> OffSearch.Results(emptyList())
            is OffReply.Busy -> OffSearch.Busy(r.waitMs)
            OffReply.Unreachable -> OffSearch.Unreachable
        }
    }

    /** A product as New food shows it: per 100 g, blanks where Open Food Facts has nothing. */
    fun prefill(p: OffProduct): FoodPrefill {
        val v = OpenFoodFacts.values(p)
        return FoodPrefill(
            name = OpenFoodFacts.displayName(p),
            brand = p.brand?.takeIf { p.name != null },
            values = LabelValues(LabelBasis.PER_100G, v.kcal, v.proteinG, v.carbsG, v.fatG, v.fiberG, p.servingG),
            diet = OpenFoodFacts.diet(p.tags),
            source = FoodSource.OFF,
            barcode = p.code,
            note = if (v.complete) null else "Open Food Facts doesn't have every value for this product: fill in the blanks from the pack.",
        )
    }

    /**
     * Reads a label: on the phone first ([ocr] is the photo at reading size), then, with AI ready, the photo
     * ([image], at AI size) and that text go to your provider, whose values replace the phone's where it found
     * them. With AI off, or when the request fails, the phone's reading stands, and the note says so.
     */
    suspend fun scanLabel(ocr: Bitmap, image: AiImage, name: String, useAi: Boolean = true): LabelScan {
        val text = reader.read(ocr)
        val local = LabelParser.parse(text)
        val base = FoodPrefill(name.trim(), null, local, null, FoodSource.LABEL_OCR, null)
        if (!useAi || ai.gate.first().state == AiState.OFF) return LabelScan(base.withNote(local), aiUsed = false)
        return when (val read = ai.readLabel(image, text)) {
            is LabelRead.Read -> {
                val e = read.estimate
                val merged = LabelValues(
                    basis = LabelBasis.PER_100G,
                    kcal = e.kcal ?: local.per100(LabelField.KCAL),
                    proteinG = e.proteinG ?: local.per100(LabelField.PROTEIN),
                    carbsG = e.carbsG ?: local.per100(LabelField.CARBS),
                    fatG = e.fatG ?: local.per100(LabelField.FAT),
                    fiberG = e.fiberG ?: local.per100(LabelField.FIBER),
                    servingG = e.servingG ?: local.servingG,
                )
                LabelScan(
                    base.copy(name = base.name.ifEmpty { e.name.orEmpty() }, brand = e.brand, values = merged, diet = e.diet),
                    aiUsed = true,
                )
            }
            is LabelRead.NotReady -> LabelScan(base.withNote(local), aiUsed = false)
            is LabelRead.Failed -> LabelScan(base.withNote(local, aiFailed = true), aiUsed = false)
        }
    }

    /** A label scan's result, waiting for New food to open with it (the screens hand over through here). */
    private var pending: FoodPrefill? = null

    fun hold(prefill: FoodPrefill) {
        pending = prefill
    }

    fun take(): FoodPrefill? = pending.also { pending = null }

    /** Deletes kept meal photos that no draft points at any more (NU-20). */
    suspend fun sweepPhotos() = photos.sweep(aiDao.draftPhotos().toSet())

    private fun FoodPrefill.withNote(local: LabelValues, aiFailed: Boolean = false): FoodPrefill = copy(
        note = when {
            local.empty -> "Couldn't read the values from this photo. Try again closer and straight on, or type them in."
            aiFailed -> "AI couldn't check this reading, so these are the phone's own. Check each marked value."
            else -> "Read on the phone. Check each marked value against the label."
        },
    )

    /** A value per 100 g from the phone's reading, only when it read the per-100 g column (AI's values are per 100 g). */
    private fun LabelValues.per100(field: LabelField): Double? {
        if (basis != LabelBasis.PER_100G) return null
        return when (field) {
            LabelField.KCAL -> kcal
            LabelField.PROTEIN -> proteinG
            LabelField.CARBS -> carbsG
            LabelField.FAT -> fatG
            LabelField.FIBER -> fiberG
            LabelField.SERVING -> servingG
        }
    }

    companion object {
        const val CACHE_SIZE = 50
    }
}
