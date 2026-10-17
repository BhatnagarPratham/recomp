package app.recomp.data.repo

import app.recomp.data.ai.AiKeyStore
import app.recomp.data.ai.Connectivity
import app.recomp.data.db.AiDao
import app.recomp.data.db.AiRequestLogEntity
import app.recomp.data.db.FoodDao
import app.recomp.data.db.MealDraftEntity
import app.recomp.data.prefs.AiSettings
import app.recomp.data.prefs.DayBoundary
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.domain.ai.AiGate
import app.recomp.domain.ai.AiKind
import app.recomp.domain.ai.AiModel
import app.recomp.domain.ai.AiPricing
import app.recomp.domain.ai.AiState
import app.recomp.domain.ai.AiStatus
import app.recomp.domain.ai.CheckedMeal
import app.recomp.domain.ai.Clarification
import app.recomp.domain.ai.MealCheck
import app.recomp.domain.ai.MealEstimator
import app.recomp.domain.ai.MealPrompt
import app.recomp.domain.ai.MealRequest
import app.recomp.domain.ai.OwnFood
import app.recomp.domain.food.Origin
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** The gate as screens show it: the state, plus the cap and the day a reached cap lifts (PRD §7.7 *Not ready*). */
data class AiGateView(val state: AiState, val capUsd: Double?, val resumesOn: LocalDate)

/** This month's AI spend for Settings (AI-03). */
data class AiSpend(val month: LocalDate, val costUsd: Double, val requests: Int)

/** How an estimate ended. Only [Estimated] carries items; a [Failed] request was still logged and costed. */
sealed interface EstimateResult {
    data class Estimated(val meal: CheckedMeal, val requestId: Long) : EstimateResult
    data class NotReady(val state: AiState) : EstimateResult
    data class Failed(val status: AiStatus, val requestId: Long) : EstimateResult
}

/**
 * AI-01 to AI-03 and NU-17 (TRD §6.2): the gate, Describe it's requests and their log, drafts, and the AI settings.
 * Nothing leaves the phone unless the gate is READY at the moment of sending.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AiRepository @Inject constructor(
    private val aiDao: AiDao,
    private val foodDao: FoodDao,
    private val prefs: SettingsPrefs,
    private val keys: AiKeyStore,
    private val connectivity: Connectivity,
    private val estimator: MealEstimator,
    private val foods: FoodRepository,
    /** Spend counts toward this clock's local calendar month. */
    private val clock: Clock,
) {
    val settings: Flow<AiSettings> = prefs.ai

    val hasKey: Flow<Boolean> = keys.hasKey

    /** The 1st of the current month, again when the month turns while the app is open. */
    private val month: Flow<LocalDate> = DayBoundary.ticks(LocalTime.MIDNIGHT, clock.zone) { clock.millis() }
        .map(AiGate::monthStart)
        .distinctUntilChanged()

    private fun startMs(month: LocalDate): Long = month.atStartOfDay(clock.zone).toInstant().toEpochMilli()

    val spend: Flow<AiSpend> = month.flatMapLatest { m -> aiDao.observeSpend(startMs(m)).map { AiSpend(m, it.costUsd, it.requests) } }

    val gate: Flow<AiGateView> = combine(prefs.ai, keys.hasKey, spend, connectivity.online) { s, key, spend, online ->
        AiGateView(AiGate.state(s.enabled, key, spend.costUsd, s.monthlyCapUsd, online), s.monthlyCapUsd, spend.month.plusMonths(1))
    }.distinctUntilChanged()

    /** The gate right now, read fresh, for the check just before sending. */
    suspend fun state(): AiState {
        val s = prefs.ai.first()
        val month = AiGate.monthStart(LocalDate.now(clock))
        return AiGate.state(s.enabled, keys.hasKey.first(), aiDao.spendSince(startMs(month)), s.monthlyCapUsd, connectivity.online.first())
    }

    /**
     * Estimates one meal (NU-17). The request always runs to completion, even if the caller stops waiting
     * (*Cancel*), so its spend is logged; the caller then just ignores the result.
     */
    suspend fun estimate(text: String, origin: Origin, clarification: Clarification? = null): EstimateResult = withContext(NonCancellable) {
        val state = state()
        if (state != AiState.READY) return@withContext EstimateResult.NotReady(state)
        val key = keys.key() ?: return@withContext EstimateResult.NotReady(AiState.NO_KEY)
        val model = prefs.ai.first().model
        val reply = estimator.estimate(MealRequest(key, model, systemPrompt(), MealPrompt.user(text, origin, clarification)))
        val checked = reply.estimate?.takeIf { reply.status == AiStatus.OK }?.let(MealCheck::check)
        val status = if (reply.status == AiStatus.OK && checked?.items.isNullOrEmpty()) AiStatus.INVALID else reply.status
        val id = aiDao.insertRequest(
            AiRequestLogEntity(
                createdAt = clock.millis(),
                kind = AiKind.MEAL_TEXT,
                model = reply.servedBy,
                inputTokens = reply.attempts.sumOf { it.inputTokens },
                outputTokens = reply.attempts.sumOf { it.outputTokens },
                cacheReadTokens = reply.attempts.sumOf { it.cacheReadTokens },
                cacheWriteTokens = reply.attempts.sumOf { it.cacheWriteTokens },
                costUsd = AiPricing.cost(reply.attempts),
                status = status,
                latencyMs = reply.latencyMs,
                responseJson = reply.responseText,
            ),
        )
        if (status == AiStatus.OK && checked != null) EstimateResult.Estimated(checked, id) else EstimateResult.Failed(status, id)
    }

    /** The persona-built system prompt (TRD §6.2): diet and region rules, your household measures and your top own foods. */
    suspend fun systemPrompt(): String {
        val filter = foods.filter.first()
        val own = foodDao.ownFoods(filter.diets, filter.region.name, MealPrompt.TOP_FOODS)
        val units = foodDao.units(own.map { it.id }).groupBy { it.foodId }
        return MealPrompt.system(
            diet = filter.diet,
            region = filter.region,
            household = foods.household.first(),
            foods = own.map { f -> OwnFood(f.name, f.per100g, units[f.id].orEmpty().map { it.label to it.grams }) },
        )
    }

    suspend fun request(id: Long): AiRequestLogEntity? = aiDao.request(id)

    // Drafts (AI-02)

    fun observeDrafts(date: LocalDate): Flow<List<MealDraftEntity>> = aiDao.observeDrafts(date)

    suspend fun draft(id: Long): MealDraftEntity? = aiDao.draft(id)

    /** Keeps a description to estimate later. [reason] is the failed request's status, or null when you saved it yourself. */
    suspend fun saveDraft(date: LocalDate, slot: String, text: String, origin: Origin, reason: AiStatus?): Long {
        require(text.isNotBlank()) { "Nothing to keep" }
        return aiDao.insertDraft(MealDraftEntity(createdAt = clock.millis(), logDate = date, mealSlot = slot, text = text.trim(), origin = origin, reason = reason))
    }

    /** Deletes a draft and returns it, so *Undo* can put it back with [restoreDraft]. */
    suspend fun deleteDraft(id: Long): MealDraftEntity? = aiDao.draft(id)?.also { aiDao.deleteDraft(id) }

    suspend fun restoreDraft(draft: MealDraftEntity) {
        aiDao.insertDraft(draft)
    }

    // Settings → AI

    suspend fun setEnabled(on: Boolean) = prefs.setAiEnabled(on)

    suspend fun setModel(model: AiModel) = prefs.setAiModel(model)

    suspend fun setMonthlyCap(usd: Double?) {
        require(usd == null || (usd >= 0 && usd.isFinite())) { "A cap can't be negative" }
        prefs.setAiMonthlyCap(usd)
    }

    suspend fun saveKey(apiKey: String) = keys.save(apiKey)

    suspend fun removeKey() = keys.clear()

    suspend fun markNoticeSeen() = prefs.setAiNoticeSeen()
}
