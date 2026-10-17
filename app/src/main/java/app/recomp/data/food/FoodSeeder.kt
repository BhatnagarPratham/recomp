package app.recomp.data.food

import android.content.Context
import androidx.room.withTransaction
import app.recomp.data.db.AppDatabase
import app.recomp.data.db.FoodDao
import app.recomp.data.db.FoodEntity
import app.recomp.data.db.FoodUnitEntity
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.domain.food.FoodCaveat
import app.recomp.domain.food.FoodDiet
import app.recomp.domain.food.FoodSource
import app.recomp.domain.persona.FoodRegion
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A bundled food file (TRD §6.4): `seed/foods.json` (committed) or `food/indb.json` (generated at build time). */
@Serializable
data class FoodFile(val version: String, val foods: List<FoodFileEntry>) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val VERSION = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")

        fun parse(text: String): FoodFile = json.decodeFromString(text)

        /** The file's version without parsing it all: launches only need this when nothing changed. */
        fun version(text: String): String = VERSION.find(text.take(512))?.groupValues?.get(1) ?: parse(text).version
    }
}

@Serializable
data class FoodFileEntry(
    val key: String,
    val name: String,
    val diet: FoodDiet,
    val region: FoodRegion? = null,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val fiber: Double = 0.0,
    val units: List<FoodFileUnit> = emptyList(),
    val caveat: FoodCaveat? = null,
)

@Serializable
data class FoodFileUnit(val label: String, val grams: Double)

/** Where the bundled food files come from: assets on the phone, strings in tests. */
interface FoodFiles {
    fun seeds(): String

    /** Null when the build has no INDB (`-Precomp.indb=false`). */
    fun indb(): String?
}

@Singleton
class AssetFoodFiles @Inject constructor(@ApplicationContext private val context: Context) : FoodFiles {
    override fun seeds(): String = read("seed/foods.json")

    override fun indb(): String? = if (context.assets.list("food")?.contains("indb.json") == true) read("food/indb.json") else null

    private fun read(path: String) = context.assets.open(path).bufferedReader().use { it.readText() }
}

/**
 * Imports the protein-rich basics and, when the build has it, INDB (TRD §6.4). It only runs when a file's
 * version differs from the one last imported. Rows are matched by `source_key`: updates keep use
 * counts, hidden foods and calibrated units, and a food that disappears from a file is archived, never
 * deleted, because log rows may point at it.
 */
@Singleton
class FoodSeeder @Inject constructor(
    private val db: AppDatabase,
    private val foodDao: FoodDao,
    private val files: FoodFiles,
    private val prefs: SettingsPrefs,
) {
    private val lock = Mutex()

    suspend fun ensureSeeded(now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        lock.withLock { seed(now) }
    }

    private suspend fun seed(now: Long) {
        val seedsText = files.seeds()
        val indbText = files.indb()
        val version = listOfNotNull(FoodFile.version(seedsText), indbText?.let(FoodFile::version)).joinToString("+")
        if (prefs.foodDataVersion() == version) return
        val seeds = FoodFile.parse(seedsText)
        val indb = indbText?.let(FoodFile::parse)
        db.withTransaction {
            import(FoodSource.SEED, seeds, now)
            // Without INDB in this build, INDB rows already on the phone stay as they are.
            if (indb != null) import(FoodSource.INDB, indb, now)
        }
        prefs.setFoodDataVersion(version)
    }

    private suspend fun import(source: FoodSource, file: FoodFile, now: Long) {
        val existing = foodDao.bySource(source.name).associateBy { it.sourceKey }
        val calibrated = foodDao.unitsOfSource(source.name).filter { it.calibrated }.groupBy({ it.foodId }) { it.label }
        val entries = file.foods.distinctBy { it.key }

        val updates = entries.mapNotNull { e -> existing[e.key]?.let { old -> e.applyTo(old) } }
        foodDao.updateAll(updates)
        val fresh = entries.filter { it.key !in existing }
        val freshIds = foodDao.insertAll(fresh.map { it.applyTo(FoodEntity(name = it.name, source = source, diet = it.diet, kcal100g = 0.0, protein100g = 0.0, carbs100g = 0.0, fat100g = 0.0, fiber100g = 0.0, createdAt = now)) })

        val idByKey = existing.mapValues { it.value.id } + fresh.map { it.key }.zip(freshIds)
        val units = entries.flatMap { e ->
            val foodId = idByKey.getValue(e.key)
            val keep = calibrated[foodId].orEmpty()
            foodDao.deleteUncalibratedUnits(foodId)
            e.units.filter { it.label !in keep && it.grams > 0 }.distinctBy { it.label }.map { FoodUnitEntity(foodId = foodId, label = it.label, grams = it.grams) }
        }
        foodDao.upsertUnits(units)

        val keys = entries.map { it.key }.toSet()
        val gone = existing.values.filter { it.sourceKey !in keys && !it.archived }
        foodDao.updateAll(gone.map { it.copy(archived = true) })
    }

    private fun FoodFileEntry.applyTo(row: FoodEntity) = row.copy(
        name = name,
        sourceKey = key,
        diet = diet,
        region = region,
        kcal100g = kcal,
        protein100g = protein,
        carbs100g = carbs,
        fat100g = fat,
        fiber100g = fiber,
        caveat = caveat,
    )
}
