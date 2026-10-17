package app.recomp.data.repo

import androidx.room.withTransaction
import app.recomp.data.db.AiDao
import app.recomp.data.db.AppDatabase
import app.recomp.data.db.FoodDao
import app.recomp.data.db.FoodEntity
import app.recomp.data.db.FoodLogDao
import app.recomp.data.db.FoodLogEntity
import app.recomp.data.db.FoodUnitEntity
import app.recomp.data.db.LoggedDayRow
import app.recomp.data.db.MealDraftEntity
import app.recomp.data.db.SavedMealDao
import app.recomp.data.db.SavedMealEntity
import app.recomp.data.db.SavedMealItemEntity
import app.recomp.data.db.SavedMealWithItems
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.domain.ai.EstimatedItem
import app.recomp.domain.food.DayTotals
import app.recomp.domain.food.FoodDiet
import app.recomp.domain.food.FoodSource
import app.recomp.domain.food.LogSource
import app.recomp.domain.food.Macros
import app.recomp.domain.food.MealSlot
import app.recomp.domain.food.MealSlots
import app.recomp.domain.food.Origin
import app.recomp.domain.food.PortionUnit
import app.recomp.domain.food.Portions
import app.recomp.domain.food.Remaining
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** A meal on the day view, with its totals (NU-16) and any descriptions waiting to be estimated (AI-02). */
data class MealGroup(
    val slot: MealSlot,
    val entries: List<FoodLogEntity>,
    val totals: DayTotals,
    val drafts: List<MealDraftEntity> = emptyList(),
)

/** A reviewed Describe it item to log (NU-17). [edited] says you changed it, which makes it an AI-learned food. */
data class AiItemToLog(val item: EstimatedItem, val edited: Boolean)

/** The day view (NU-16). [targets] is null before onboarding has written any. */
data class DayLog(
    val date: LocalDate,
    val meals: List<MealGroup>,
    val totals: DayTotals,
    val targets: DayTargets?,
) {
    val remaining: Remaining? get() = targets?.let { Remaining.of(totals, it.target.kcal, it.protein.todayG) }
}

/** Logging (NU-10, NU-13 to NU-16): entries, quick add, saved meals and copy. Writes return new ids for Undo. */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FoodLogRepository @Inject constructor(
    private val db: AppDatabase,
    private val foodDao: FoodDao,
    private val foodLogDao: FoodLogDao,
    private val savedMealDao: SavedMealDao,
    private val aiDao: AiDao,
    private val prefs: SettingsPrefs,
    private val nutrition: NutritionRepository,
) {
    val mealSlots: Flow<List<MealSlot>> = prefs.mealSlots

    fun observeDay(date: LocalDate): Flow<DayLog> =
        combine(foodLogDao.observeDay(date), prefs.mealSlots, nutrition.observeTargets(date), aiDao.observeDrafts(date)) { entries, slots, targets, drafts ->
            val bySlot = entries.groupBy { it.mealSlot }
            val draftsBySlot = drafts.groupBy { it.mealSlot }
            val meals = MealSlots.forDay(slots, bySlot.keys + draftsBySlot.keys).map { slot ->
                val rows = bySlot[slot.key].orEmpty()
                MealGroup(slot, rows, DayTotals.of(rows.map { it.macros }), draftsBySlot[slot.key].orEmpty())
            }
            DayLog(date, meals, DayTotals.of(entries.map { it.macros }), targets)
        }

    /** Logs [quantity] × [unit] of a food, snapshotting its macros (TRD §4). */
    suspend fun log(
        date: LocalDate,
        slot: String,
        food: FoodEntity,
        quantity: Double,
        unit: PortionUnit,
        origin: Origin,
        now: Long = System.currentTimeMillis(),
    ): Long = db.withTransaction {
        require(quantity > 0) { "Nothing to log" }
        val grams = quantity * unit.grams
        val id = foodLogDao.insert(entry(date, slot, food, quantity, unit.label, grams, origin, LogSource.MANUAL, now))
        markUsed(listOf(food.id), now)
        id
    }

    /** NU-14: calories and protein, with carbs and fat only if you know them. */
    suspend fun quickAdd(
        date: LocalDate,
        slot: String,
        name: String?,
        kcal: Double,
        proteinG: Double,
        carbsG: Double?,
        fatG: Double?,
        origin: Origin,
        now: Long = System.currentTimeMillis(),
    ): Long {
        require(kcal >= 0 && proteinG >= 0) { "Negative macros" }
        return foodLogDao.insert(
            FoodLogEntity(
                logDate = date, mealSlot = slot, foodId = null, name = name?.trim()?.ifEmpty { null } ?: QUICK_ADD_NAME,
                quantity = 1.0, unitLabel = QUICK_ADD_UNIT, grams = null, kcal = kcal, proteinG = proteinG,
                carbsG = carbsG, fatG = fatG, fiberG = null, origin = origin, source = LogSource.QUICK_ADD, loggedAt = now,
            ),
        )
    }

    suspend fun entry(id: Long): FoodLogEntity? = foodLogDao.get(id)

    /**
     * Changes an entry's amount, unit, origin or meal. Its macros scale from its own snapshot, so a food
     * edited since never rewrites this entry.
     */
    suspend fun update(id: Long, quantity: Double, unit: PortionUnit?, origin: Origin, slot: String) {
        val old = foodLogDao.get(id) ?: return
        require(quantity > 0) { "Nothing to log" }
        val grams = unit?.let { quantity * it.grams }
        val ratio = when {
            grams != null && old.grams != null && old.grams > 0 -> grams / old.grams
            else -> quantity / old.quantity
        }
        foodLogDao.update(
            old.copy(
                quantity = quantity,
                unitLabel = unit?.label ?: old.unitLabel,
                grams = grams ?: old.grams?.times(ratio),
                kcal = old.kcal * ratio,
                proteinG = old.proteinG * ratio,
                carbsG = old.carbsG?.times(ratio),
                fatG = old.fatG?.times(ratio),
                fiberG = old.fiberG?.times(ratio),
                origin = origin,
                mealSlot = slot,
            ),
        )
    }

    /** Edits a quick add's own numbers (NU-14). */
    suspend fun updateQuickAdd(id: Long, name: String?, kcal: Double, proteinG: Double, carbsG: Double?, fatG: Double?, origin: Origin, slot: String) {
        val old = foodLogDao.get(id) ?: return
        require(kcal >= 0 && proteinG >= 0) { "Negative macros" }
        foodLogDao.update(
            old.copy(
                name = name?.trim()?.ifEmpty { null } ?: QUICK_ADD_NAME, kcal = kcal, proteinG = proteinG, carbsG = carbsG, fatG = fatG,
                origin = origin, mealSlot = slot,
            ),
        )
    }

    /** Deletes entries and returns them, so *Undo* can put them back with [restore]. */
    suspend fun delete(ids: List<Long>): List<FoodLogEntity> = db.withTransaction {
        val rows = foodLogDao.get(ids)
        foodLogDao.delete(ids)
        rows
    }

    suspend fun restore(rows: List<FoodLogEntity>) {
        foodLogDao.insertAll(rows)
    }

    /**
     * *Undo* after a log, a saved meal or a copy: the rows go, and so does the usage they added, so an undone
     * mis-tap never lifts a food in Recent or search.
     */
    suspend fun undoInsert(ids: List<Long>) = db.withTransaction {
        val rows = foodLogDao.get(ids)
        foodLogDao.delete(ids)
        rows.mapNotNull { it.foodId }.groupingBy { it }.eachCount().forEach { (foodId, times) -> foodDao.unmarkUsed(foodId, times) }
    }

    /** *Undo* after logging a saved meal, which also takes back the meal's own use. */
    suspend fun undoSavedMeal(mealId: Long, ids: List<Long>) = db.withTransaction {
        undoInsert(ids)
        savedMealDao.unmarkUsed(mealId)
    }

    /**
     * Logs a reviewed Describe it meal (NU-17, TRD §6.2): one row per item, `source = AI_TEXT`, tied to its request.
     * An item you edited becomes an AI-learned food first (the learning loop), so the row links to it and the
     * dish shows in search and later prompts at your numbers. Unedited items keep their snapshot only.
     */
    suspend fun logAiMeal(
        date: LocalDate,
        slot: String,
        items: List<AiItemToLog>,
        origin: Origin,
        requestId: Long?,
        dietTag: FoodDiet,
        now: Long = System.currentTimeMillis(),
    ): List<Long> = db.withTransaction {
        require(items.isNotEmpty()) { "Nothing to log" }
        val rows = items.mapIndexed { i, (item, edited) ->
            require(item.grams > 0) { "An item without a weight" }
            val gramsUnit = item.unit.trim().lowercase() in Portions.GRAM_LABELS && item.unit.trim().lowercase() != "ml"
            FoodLogEntity(
                logDate = date, mealSlot = slot, foodId = if (edited) learn(item, dietTag, now) else null, name = item.name.trim(),
                quantity = if (gramsUnit) item.grams else item.quantity, unitLabel = if (gramsUnit) Portions.GRAMS else item.unit.trim(),
                grams = item.grams, kcal = item.kcal, proteinG = item.proteinG, carbsG = item.carbsG, fatG = item.fatG, fiberG = item.fiberG,
                origin = origin, source = LogSource.AI_TEXT, aiRequestId = requestId, loggedAt = now + i,
            )
        }
        val ids = foodLogDao.insertAll(rows)
        markUsed(rows.mapNotNull { it.foodId }, now)
        ids
    }

    /**
     * The learning loop (TRD §6.2): your edited item's values per 100 g, with its unit at the item's weight. One of
     * your own foods of the same name is updated rather than duplicated: an AI-learned one takes the item's diet tag
     * too, a custom one keeps the tag you gave it. Bundled foods are never changed, so an edited dish named like one
     * becomes your AI-learned version beside it. The tag is what the item contains (the model's per-item diet, by
     * its strictest ingredient), or [fallbackTag], your own-food tag, when the model gave none.
     */
    private suspend fun learn(item: EstimatedItem, fallbackTag: FoodDiet, now: Long): Long {
        val per = item.per100g
        val name = item.name.trim()
        val existing = foodDao.ownFoodNamed(name)
        val id = if (existing != null) {
            foodDao.update(
                existing.copy(
                    name = if (existing.source == FoodSource.AI) name else existing.name,
                    diet = if (existing.source == FoodSource.AI) item.diet ?: existing.diet else existing.diet,
                    kcal100g = per.kcal, protein100g = per.proteinG, carbs100g = per.carbsG, fat100g = per.fatG, fiber100g = per.fiberG,
                ),
            )
            existing.id
        } else {
            foodDao.insert(
                FoodEntity(
                    name = name, source = FoodSource.AI, diet = item.diet ?: fallbackTag, kcal100g = per.kcal, protein100g = per.proteinG,
                    carbs100g = per.carbsG, fat100g = per.fatG, fiber100g = per.fiberG, createdAt = now,
                ),
            )
        }
        val label = item.unit.trim().lowercase()
        if (label.isNotEmpty() && label !in Portions.GRAM_LABELS && item.quantity > 0) {
            foodDao.upsertUnits(listOf(FoodUnitEntity(foodId = id, label = label, grams = item.grams / item.quantity, calibrated = true)))
        }
        return id
    }

    /** Counts each food once per entry, so a meal with two portions of it counts twice. */
    private suspend fun markUsed(foodIds: List<Long>, now: Long) =
        foodIds.groupingBy { it }.eachCount().forEach { (foodId, times) -> foodDao.markUsed(foodId, times, now) }

    // Saved meals (NU-13)

    fun observeSavedMeals(): Flow<List<SavedMealWithItems>> = savedMealDao.observeAll()

    suspend fun savedMealNamed(name: String): SavedMealEntity? = savedMealDao.byName(name.trim())

    /** Saves a day's meal under [name]. A meal of the same name is saved over: its items are replaced. */
    suspend fun saveMeal(name: String, date: LocalDate, slot: String, now: Long = System.currentTimeMillis()): Long = db.withTransaction {
        saveRows(name, foodLogDao.day(date).filter { it.mealSlot == slot }, slot, now)
    }

    /** Saves just these entries as a meal, e.g. a Describe it estimate (*Save + My meals*, NU-17). */
    suspend fun saveMealOf(name: String, entryIds: List<Long>, slot: String, now: Long = System.currentTimeMillis()): Long = db.withTransaction {
        saveRows(name, foodLogDao.get(entryIds), slot, now)
    }

    private suspend fun saveRows(name: String, rows: List<FoodLogEntity>, slot: String, now: Long): Long {
        require(rows.isNotEmpty()) { "That meal is empty" }
        val existing = savedMealDao.byName(name.trim())
        val mealId = if (existing != null) {
            savedMealDao.deleteItems(existing.id)
            savedMealDao.setDefaultSlot(existing.id, slot)
            existing.id
        } else {
            savedMealDao.insert(SavedMealEntity(name = name.trim(), defaultSlot = slot, createdAt = now))
        }
        savedMealDao.insertItems(
            rows.mapIndexed { i, r ->
                SavedMealItemEntity(
                    savedMealId = mealId, position = i, foodId = r.foodId, name = r.name, quantity = r.quantity, unitLabel = r.unitLabel,
                    grams = r.grams, kcal = r.kcal, proteinG = r.proteinG, carbsG = r.carbsG, fatG = r.fatG, fiberG = r.fiberG, origin = r.origin,
                )
            },
        )
        return mealId
    }

    /**
     * One tap (NU-13): logs every item into [slot]. An item with a food recomputes from the food's current
     * values, so a corrected custom food flows through; one without (a quick add) uses its snapshot.
     */
    suspend fun logSavedMeal(mealId: Long, date: LocalDate, slot: String, now: Long = System.currentTimeMillis()): List<Long> = db.withTransaction {
        val meal = savedMealDao.get(mealId) ?: return@withTransaction emptyList()
        val foods = foodDao.get(meal.items.mapNotNull { it.foodId }).associateBy { it.id }
        val rows = meal.items.sortedBy { it.position }.mapIndexed { i, item ->
            val food = item.foodId?.let(foods::get)
            if (food != null && item.grams != null) {
                entry(date, slot, food, item.quantity, item.unitLabel, item.grams, item.origin, LogSource.SAVED_MEAL, now + i)
            } else {
                FoodLogEntity(
                    logDate = date, mealSlot = slot, foodId = item.foodId, name = item.name, quantity = item.quantity, unitLabel = item.unitLabel,
                    grams = item.grams, kcal = item.kcal, proteinG = item.proteinG, carbsG = item.carbsG, fatG = item.fatG, fiberG = item.fiberG,
                    origin = item.origin, source = LogSource.SAVED_MEAL, loggedAt = now + i,
                )
            }
        }
        val ids = foodLogDao.insertAll(rows)
        markUsed(rows.mapNotNull { it.foodId }.filter { it in foods }, now)
        savedMealDao.markUsed(mealId, now)
        ids
    }

    suspend fun renameMeal(id: Long, name: String) = savedMealDao.rename(id, name.trim())

    suspend fun deleteMeal(id: Long) = savedMealDao.delete(id)

    // Copy (NU-13)

    /** The days before [date] with food logged, newest first, for *Copy from another day*. */
    suspend fun daysBefore(date: LocalDate, limit: Int = COPY_DAYS): List<LoggedDayRow> = foodLogDao.daysBefore(date, limit)

    /** The meals logged on a day, for choosing what to copy. */
    suspend fun day(date: LocalDate): List<FoodLogEntity> = foodLogDao.day(date)

    /**
     * Copies a whole day ([fromSlot] null) or one meal into [to]. Rows keep their macros, origin and
     * source; a meal lands in [toSlot] (or its own slot), a day in each row's own slot. Copies are added
     * beside what's there, never replacing it.
     */
    suspend fun copy(from: LocalDate, fromSlot: String?, to: LocalDate, toSlot: String?, now: Long = System.currentTimeMillis()): List<Long> =
        db.withTransaction {
            val rows = foodLogDao.day(from).filter { fromSlot == null || it.mealSlot == fromSlot }
            val ids = foodLogDao.insertAll(
                rows.mapIndexed { i, r -> r.copy(id = 0, logDate = to, mealSlot = if (fromSlot != null) toSlot ?: r.mealSlot else r.mealSlot, loggedAt = now + i) },
            )
            markUsed(rows.mapNotNull { it.foodId }, now)
            ids
        }

    private fun entry(
        date: LocalDate,
        slot: String,
        food: FoodEntity,
        quantity: Double,
        unitLabel: String,
        grams: Double,
        origin: Origin,
        source: LogSource,
        now: Long,
    ): FoodLogEntity {
        val m: Macros = food.per100g.forGrams(grams)
        return FoodLogEntity(
            logDate = date, mealSlot = slot, foodId = food.id, name = food.name, quantity = quantity, unitLabel = unitLabel, grams = grams,
            kcal = m.kcal, proteinG = m.proteinG, carbsG = m.carbsG, fatG = m.fatG, fiberG = m.fiberG,
            origin = origin, source = source, loggedAt = now,
        )
    }

    companion object {
        const val QUICK_ADD_NAME = "Quick add"
        const val QUICK_ADD_UNIT = "serving"
        const val COPY_DAYS = 14
    }
}
