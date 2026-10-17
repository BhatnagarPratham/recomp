package app.recomp.data.repo

import androidx.room.withTransaction
import app.recomp.data.db.AppDatabase
import app.recomp.data.db.FoodDao
import app.recomp.data.db.FoodEntity
import app.recomp.data.db.FoodLogDao
import app.recomp.data.db.FoodLogEntity
import app.recomp.data.db.FoodUnitEntity
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.domain.food.FoodDiet
import app.recomp.domain.food.FoodQuery
import app.recomp.domain.food.FoodSource
import app.recomp.domain.food.Origin
import app.recomp.domain.food.Per100g
import app.recomp.domain.food.PortionUnit
import app.recomp.domain.food.Portions
import app.recomp.domain.food.allowedFoods
import app.recomp.domain.food.ownFoodTag
import app.recomp.domain.persona.Diet
import app.recomp.domain.persona.FoodRegion
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** A search row: the food and its first own unit, for "1 bowl · 158 kcal". */
data class FoodHit(val food: FoodEntity, val serving: FoodUnitEntity?)

/** The empty search: your recent foods, or the basics for your diet before you've logged anything. */
data class Suggestions(val recent: Boolean, val hits: List<FoodHit>)

/** What the portion sheet needs: the units it offers and the prefill (PRD §7.7 Portion). */
data class PortionChoice(
    val food: FoodEntity,
    val units: List<PortionUnit>,
    val quantity: Double,
    val unit: PortionUnit,
    val origin: Origin,
)

/** A custom food from the form (NU-12), already converted to per 100 g. [serving] becomes its own unit. */
data class CustomFood(
    val name: String,
    val brand: String?,
    val per100g: Per100g,
    val diet: FoodDiet,
    val serving: Pair<String, Double>?,
)

/** The persona's food filter (PS-03): which diet tags and which region search shows. */
data class FoodFilter(val diet: Diet, val region: FoodRegion) {
    val diets: List<String> get() = diet.allowedFoods.map { it.name }
}

/** Foods: search, units and calibration, custom foods and hiding (NU-11, NU-12, TRD §6.4). */
@Singleton
class FoodRepository @Inject constructor(
    private val db: AppDatabase,
    private val foodDao: FoodDao,
    private val foodLogDao: FoodLogDao,
    private val prefs: SettingsPrefs,
) {
    /** Onboarding always sets diet and region; before it has, nothing is filtered out. */
    val filter: Flow<FoodFilter> = prefs.persona.map { FoodFilter(it.diet ?: Diet.NON_VEGETARIAN, it.region ?: FoodRegion.INDIAN) }

    /** The region's household measures at your calibrated weights. */
    val household: Flow<Map<String, Double>> = combine(filter, prefs.portionSizes) { f, sizes -> Portions.household(f.region, sizes) }

    suspend fun search(text: String, limit: Int = 50): List<FoodHit> {
        val match = FoodQuery.match(text) ?: return emptyList()
        val f = filter.first()
        val prefix = FoodQuery.words(text).joinToString(" ").replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return hits(foodDao.search(match, prefix, f.diets, f.region.name, limit))
    }

    suspend fun suggestions(limit: Int = 30): Suggestions {
        val f = filter.first()
        val recent = foodDao.recent(f.diets, f.region.name, limit)
        return if (recent.isNotEmpty()) Suggestions(true, hits(recent)) else Suggestions(false, hits(foodDao.basics(f.diets, f.region.name, limit)))
    }

    private suspend fun hits(foods: List<FoodEntity>): List<FoodHit> {
        val first = foodDao.units(foods.map { it.id }).groupBy { it.foodId }.mapValues { it.value.first() }
        return foods.map { FoodHit(it, first[it.id]) }
    }

    suspend fun food(id: Long): FoodEntity? = foodDao.get(id)

    suspend fun units(foodId: Long): List<PortionUnit> =
        Portions.unitsFor(foodDao.units(foodId).map { it.label to it.grams }, household.first())

    /**
     * The units an entry's edit sheet offers: its own unit at the weight it was logged with first, then the
     * food's current units. A unit recalibrated since (or re-imported) never rewrites the entry (TRD §4).
     */
    suspend fun unitsForEntry(entry: FoodLogEntity): List<PortionUnit> {
        val foodId = entry.foodId ?: return emptyList()
        val grams = entry.grams ?: return emptyList()
        val logged = PortionUnit(entry.unitLabel, if (entry.unitLabel == Portions.GRAMS) 1.0 else grams / entry.quantity, PortionUnit.Kind.OWN)
        val current = units(foodId).filter { it.label != entry.unitLabel }
        return if (entry.unitLabel == Portions.GRAMS) current + logged else listOf(logged) + current
    }

    /**
     * The portion sheet's prefill: what you logged for this food last time (amount, unit, origin) when
     * that unit still exists, else one of its own servings, else 100 g. Origin defaults to Home, or
     * Packaged for a food with a brand (NU-15).
     */
    suspend fun portion(foodId: Long): PortionChoice? {
        val food = foodDao.get(foodId) ?: return null
        val units = units(foodId)
        val last: FoodLogEntity? = foodLogDao.lastOf(foodId)
        val lastUnit = last?.let { l -> units.firstOrNull { it.label == l.unitLabel } }
        val origin = last?.origin ?: if (food.brand != null) Origin.PACKAGED else Origin.HOME
        return when {
            last != null && lastUnit != null -> PortionChoice(food, units, last.quantity, lastUnit, origin)
            units.first().kind == PortionUnit.Kind.OWN -> PortionChoice(food, units, 1.0, units.first(), origin)
            else -> PortionChoice(food, units, 100.0, units.last(), origin)
        }
    }

    /** Sets what one [label] weighs for this food (NU-12). Re-imports leave it alone. */
    suspend fun calibrate(foodId: Long, label: String, grams: Double) {
        require(grams > 0 && label != Portions.GRAMS)
        foodDao.upsertUnits(listOf(FoodUnitEntity(foodId = foodId, label = label, grams = grams, calibrated = true)))
    }

    /** Calibrates a household measure for every food (Settings → Food → Portion sizes); null resets it. */
    suspend fun setHouseholdMeasure(label: String, grams: Double?) = prefs.edit {
        portionSizes = if (grams == null) portionSizes - label else portionSizes + (label to grams)
    }

    suspend fun defaultDiet(): FoodDiet = filter.first().diet.ownFoodTag

    suspend fun createCustom(food: CustomFood, now: Long = System.currentTimeMillis()): Long = db.withTransaction {
        val id = foodDao.insert(
            FoodEntity(
                name = food.name.trim(),
                brand = food.brand?.trim()?.ifEmpty { null },
                source = FoodSource.CUSTOM,
                diet = food.diet,
                kcal100g = food.per100g.kcal,
                protein100g = food.per100g.proteinG,
                carbs100g = food.per100g.carbsG,
                fat100g = food.per100g.fatG,
                fiber100g = food.per100g.fiberG,
                createdAt = now,
            ),
        )
        setServing(id, food.serving)
        id
    }

    /** Edits a custom food. Logged entries keep their snapshot, so only future logs change. */
    suspend fun updateCustom(id: Long, food: CustomFood) = db.withTransaction {
        val old = foodDao.get(id) ?: return@withTransaction
        require(old.source == FoodSource.CUSTOM) { "Only custom foods can be edited" }
        foodDao.update(
            old.copy(
                name = food.name.trim(),
                brand = food.brand?.trim()?.ifEmpty { null },
                diet = food.diet,
                kcal100g = food.per100g.kcal,
                protein100g = food.per100g.proteinG,
                carbs100g = food.per100g.carbsG,
                fat100g = food.per100g.fatG,
                fiber100g = food.per100g.fiberG,
            ),
        )
        setServing(id, food.serving)
    }

    /** The first own unit of a custom food, which the form edits as its serving. */
    suspend fun serving(foodId: Long): FoodUnitEntity? = foodDao.units(foodId).firstOrNull()

    /**
     * Makes [serving] a custom food's first unit, replacing the previous serving (null clears it). Units
     * you calibrated for the food stay, after it.
     */
    private suspend fun setServing(foodId: Long, serving: Pair<String, Double>?) {
        val others = foodDao.units(foodId).drop(1).filter { it.label != serving?.first }
        foodDao.deleteUnits(foodId)
        val first = serving?.let { (label, grams) ->
            require(grams > 0 && label != Portions.GRAMS)
            FoodUnitEntity(foodId = foodId, label = label.trim(), grams = grams, calibrated = true)
        }
        foodDao.upsertUnits(listOfNotNull(first) + others.map { it.copy(id = 0) })
    }

    /** Hides a food from search, e.g. one tagged wrongly (PRD §7.7). Settings → Food → Hidden foods restores it. */
    suspend fun hide(id: Long) = foodDao.setArchived(id, true)

    suspend fun restore(id: Long) = foodDao.setArchived(id, false)

    fun observeHidden(): Flow<List<FoodEntity>> = foodDao.observeHidden()

    /** How many INDB foods this phone has (Settings → Food → Food data). */
    fun observeIndbCount(): Flow<Int> = foodDao.observeCount(FoodSource.INDB.name)
}
