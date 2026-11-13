package app.recomp.data.repo

import app.recomp.data.ai.AiKeyStore
import app.recomp.data.ai.Connectivity
import app.recomp.data.ai.HostResolver
import app.recomp.data.ai.LocalNetwork
import app.recomp.data.ai.StoredKeys
import app.recomp.data.db.AiDao
import androidx.room.withTransaction
import app.recomp.data.db.AiRequestLogEntity
import app.recomp.data.db.AppDatabase
import app.recomp.data.db.FoodDao
import app.recomp.data.db.MealDraftEntity
import app.recomp.data.prefs.AiSettings
import app.recomp.data.prefs.DayBoundary
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.domain.ai.AiGate
import app.recomp.domain.ai.AiKind
import app.recomp.domain.ai.AiModel
import app.recomp.domain.ai.AiNotice
import app.recomp.domain.ai.AiPricing
import app.recomp.domain.ai.AiProviderId
import app.recomp.domain.ai.AiState
import app.recomp.domain.ai.AiStatus
import app.recomp.domain.ai.AiTarget
import app.recomp.domain.ai.AiUrl
import app.recomp.domain.ai.CheckedMeal
import app.recomp.domain.ai.Clarification
import app.recomp.domain.ai.EstimatorReply
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The gate as screens show it: the state, plus the cap and the day a reached cap lifts (PRD §7.7 *Not ready*),
 * and the provider in use, which the messages name (AI-05).
 */
data class AiGateView(val state: AiState, val capUsd: Double?, val resumesOn: LocalDate, val provider: AiProviderId = AiProviderId.DEFAULT)

/** This month's AI spend for Settings (AI-03). */
data class AiSpend(val month: LocalDate, val costUsd: Double, val requests: Int)

/** How an estimate ended. Only [Estimated] carries items; a [Failed] request was still logged and costed. */
sealed interface EstimateResult {
    data class Estimated(val meal: CheckedMeal, val requestId: Long) : EstimateResult
    data class NotReady(val state: AiState) : EstimateResult
    /** [draftId] is the draft the description was kept as, when the caller asked for one (AI-02). */
    data class Failed(val status: AiStatus, val requestId: Long?, val draftId: Long? = null) : EstimateResult
}

/**
 * Where a failed estimate's description is kept (AI-02): a draft for [slot] on [date], updating [replacing] when it
 * still exists. [keep] is asked once the reply is in; it says false when the caller stopped waiting (*Cancel*),
 * which keeps nothing. The draft is written in the same non-cancellable step as the request's log row, so a screen
 * that goes away mid-request never loses it.
 */
class DraftPlan(val date: LocalDate, val slot: String, val replacing: Long?, val keep: () -> Boolean = { true })

/**
 * AI-01 to AI-03 and NU-17 (TRD §6.2): the gate, Describe it's requests and their log, drafts, and the AI settings.
 * Nothing leaves the phone unless the gate is READY at the moment of sending.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AiRepository @Inject constructor(
    private val db: AppDatabase,
    private val aiDao: AiDao,
    private val foodDao: FoodDao,
    private val prefs: SettingsPrefs,
    private val keys: AiKeyStore,
    private val connectivity: Connectivity,
    private val localNetwork: LocalNetwork,
    private val resolver: HostResolver,
    private val estimator: MealEstimator,
    private val foods: FoodRepository,
    /** Spend counts toward this clock's local calendar month. */
    private val clock: Clock,
) {
    val settings: Flow<AiSettings> = prefs.ai

    /**
     * The providers whose key can be used now (AI-05): switching provider keeps the others' keys. The
     * OpenAI-compatible key counts only for the server it was entered for.
     */
    val storedKeys: Flow<Set<AiProviderId>> = combine(prefs.ai, keys.stored) { s, stored ->
        AiProviderId.entries.filter { stored.usable(it, s.compatibleUrl) }.toSet()
    }.distinctUntilChanged()

    /** Whether the provider in use has its key. */
    val hasKey: Flow<Boolean> = combine(prefs.ai, storedKeys) { s, stored -> s.provider in stored }.distinctUntilChanged()

    /**
     * Servers reached by a name whose text doesn't place it, looked up: host to whether it's on the local network.
     * Only filled in while the local-network permission is missing, which is the only time it matters.
     */
    private val lookedUp = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** Whether [url] needs the local-network permission: by its text, or by where its name was found to lead. */
    private fun onLocalNetwork(url: AiUrl?, lookedUp: Map<String, Boolean>): Boolean = url != null && (url.localNetwork || lookedUp[url.host] == true)

    /**
     * Looks up where [url]'s name leads when the text doesn't say and the permission is missing. A Tailscale name
     * or a home reverse proxy over https resolves to a local address, and without the permission Android 17 only
     * lets the connection time out; this lets the gate say so instead.
     */
    private suspend fun place(url: AiUrl?) {
        if (url == null || url.local || localNetwork.allowed.value) return
        val local = resolver.addresses(url.host).any(AiUrl::onLocalNetwork)
        lookedUp.update { it + (url.host to local) }
    }

    /** The 1st of the current month, again when the month turns while the app is open. */
    private val month: Flow<LocalDate> = DayBoundary.ticks(LocalTime.MIDNIGHT, clock.zone) { clock.millis() }
        .map(AiGate::monthStart)
        .distinctUntilChanged()

    private fun startMs(month: LocalDate): Long = month.atStartOfDay(clock.zone).toInstant().toEpochMilli()

    val spend: Flow<AiSpend> = month.flatMapLatest { m -> aiDao.observeSpend(startMs(m)).map { AiSpend(m, it.costUsd, it.requests) } }

    /**
     * AI-05's part of the gate, the same for the live flow and the check before sending. A server reached over
     * plain http needs no key, a local one no internet, and one on the local network Android's permission.
     */
    private fun state(s: AiSettings, stored: StoredKeys, spendUsd: Double, online: Boolean, localAllowed: Boolean, lookedUp: Map<String, Boolean>): AiState = AiGate.state(
        enabled = s.enabled,
        hasKey = !s.needsKey || stored.usable(s.provider, s.url),
        monthSpendUsd = spendUsd,
        monthlyCapUsd = s.monthlyCapUsd,
        online = online || s.url?.local == true,
        configured = s.configured,
        localNetworkAllowed = localAllowed || !onLocalNetwork(s.url, lookedUp),
    )

    val gate: Flow<AiGateView> = combine(prefs.ai, keys.stored, spend, combine(connectivity.online, lookedUp, ::Pair), localNetwork.allowed) { s, stored, spend, net, local ->
        AiGateView(state(s, stored, spend.costUsd, net.first, local, net.second), s.monthlyCapUsd, spend.month.plusMonths(1), s.provider)
    }.distinctUntilChanged()

    /** The gate right now, read fresh, for the check just before sending. */
    suspend fun state(): AiState {
        val s = prefs.ai.first()
        val month = AiGate.monthStart(LocalDate.now(clock))
        localNetwork.refresh()
        place(s.url)
        return state(s, keys.stored.first(), aiDao.spendSince(startMs(month)), connectivity.online.first(), localNetwork.allowed.value, lookedUp.value)
    }

    /**
     * Estimates one meal (NU-17). The request always runs to completion, even if the caller stops waiting
     * (*Cancel*), so its spend is logged; the caller then just ignores the result.
     */
    suspend fun estimate(text: String, origin: Origin, clarification: Clarification? = null, draft: DraftPlan? = null): EstimateResult = withContext(NonCancellable) {
        // Check the gate and reserve the request's cost in one step, so requests made at the same time each see the
        // others' reservations and the cap holds (AI-03).
        val (request, pending) = sending.withLock {
            val state = state()
            if (state != AiState.READY) return@withContext EstimateResult.NotReady(state)
            val s = prefs.ai.first()
            // The key of the provider in use, and only over https: a server reached over plain http is never sent one.
            // A compatible server gets its key only if it's the server the key was entered for.
            val key = if (s.needsKey) keys.key(s.provider, s.url) ?: return@withContext EstimateResult.NotReady(AiState.NO_KEY) else null
            val target = AiTarget(s.provider, key, s.modelId, serverFallback = s.model?.serverFallback == true, baseUrl = s.url, rates = s.typedRates)
            val request = MealRequest(target, systemPrompt(), MealPrompt.user(text, origin, clarification))
            request to reserve(request)
        }
        val reply = try {
            estimator.estimate(request)
        } catch (e: Exception) {
            // The estimator returns its failures; anything thrown is a bug, which still mustn't lose the description.
            EstimatorReply(AiStatus.UNEXPECTED, null, request.model, emptyList(), latencyMs = 0, responseText = null, detail = e.javaClass.simpleName)
        }
        val checked = reply.estimate?.takeIf { reply.status == AiStatus.OK }?.let(MealCheck::check)
        val status = if (reply.status == AiStatus.OK && checked?.items.isNullOrEmpty()) AiStatus.INVALID else reply.status
        aiDao.updateRequest(
            pending.copy(
                model = reply.servedBy,
                inputTokens = reply.attempts.sumOf { it.inputTokens },
                outputTokens = reply.attempts.sumOf { it.outputTokens },
                cacheReadTokens = reply.attempts.sumOf { it.cacheReadTokens },
                cacheWriteTokens = reply.attempts.sumOf { it.cacheWriteTokens },
                costUsd = AiPricing.cost(reply.attempts, LocalDate.now(clock)),
                status = status,
                latencyMs = reply.latencyMs,
                responseJson = reply.responseText,
            ),
        )
        if (status == AiStatus.OK && checked != null) {
            EstimateResult.Estimated(checked, pending.id)
        } else {
            val draftId = draft?.takeIf { it.keep() }?.let { keepDraft(it.date, it.slot, it.replacing, text, origin, status) }
            EstimateResult.Failed(status, pending.id, draftId)
        }
    }

    /** Serializes the gate check with the reservation it leads to. */
    private val sending = Mutex()

    /**
     * Logs the request as PENDING at its upper-bound cost before it's sent. Monthly spend counts it at once, and
     * if the app dies mid-request the row stays, so a request the server may have billed is never missing.
     */
    private suspend fun reserve(request: MealRequest): AiRequestLogEntity {
        val bound = request.upperBound
        val row = AiRequestLogEntity(
            createdAt = clock.millis(),
            kind = AiKind.MEAL_TEXT,
            provider = request.target.provider,
            model = request.model,
            inputTokens = bound.inputTokens,
            outputTokens = bound.outputTokens,
            cacheReadTokens = 0,
            cacheWriteTokens = 0,
            costUsd = AiPricing.cost(bound, LocalDate.now(clock)),
            status = AiStatus.PENDING,
            latencyMs = 0,
            responseJson = null,
        )
        return row.copy(id = aiDao.insertRequest(row))
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
    suspend fun saveDraft(date: LocalDate, slot: String, text: String, origin: Origin, reason: AiStatus?): Long =
        keepDraft(date, slot, replacing = null, text, origin, reason)

    /**
     * One draft per description: [replacing], when it still exists, is updated in place, in one transaction, rather
     * than deleted and added again. Returns the draft's id.
     */
    suspend fun keepDraft(date: LocalDate, slot: String, replacing: Long?, text: String, origin: Origin, reason: AiStatus?): Long = db.withTransaction {
        require(text.isNotBlank()) { "Nothing to keep" }
        val row = MealDraftEntity(createdAt = clock.millis(), logDate = date, mealSlot = slot, text = text.trim(), origin = origin, reason = reason)
        val existing = replacing?.let { aiDao.draft(it) }
        if (existing != null) {
            aiDao.updateDraft(row.copy(id = existing.id))
            existing.id
        } else {
            aiDao.insertDraft(row)
        }
    }

    /** Deletes a draft and returns it, so *Undo* can put it back with [restoreDraft]. */
    suspend fun deleteDraft(id: Long): MealDraftEntity? = aiDao.draft(id)?.also { aiDao.deleteDraft(id) }

    suspend fun restoreDraft(draft: MealDraftEntity) {
        aiDao.insertDraft(draft)
    }

    // Settings → AI

    suspend fun setEnabled(on: Boolean) = prefs.setAiEnabled(on)

    suspend fun setProvider(provider: AiProviderId) = prefs.setAiProvider(provider)

    suspend fun setModel(model: AiModel) = prefs.setAiModel(model)

    /**
     * The OpenAI-compatible provider's server, model and prices per million tokens (AI-05). The URL must pass
     * [AiUrl.check]: https anywhere, plain http only to this phone or the local network. Returns whether the server
     * is on the local network, by its address or by where its name leads, so the caller can ask for the permission.
     * A key saved for another server stays stored but isn't used for this one.
     */
    suspend fun setCompatible(baseUrl: String, model: String, inputUsd: Double, outputUsd: Double): Boolean {
        val url = requireNotNull(AiUrl.ok(baseUrl)) { "Not a usable URL" }
        require(model.isNotBlank()) { "No model" }
        require(inputUsd >= 0 && inputUsd.isFinite() && outputUsd >= 0 && outputUsd.isFinite()) { "A price can't be negative" }
        prefs.setAiCompatible(url.base, model, inputUsd, outputUsd)
        localNetwork.refresh()
        place(url)
        return onLocalNetwork(url, lookedUp.value)
    }

    /** Reads the local-network permission again, after its prompt is answered. */
    fun localNetworkAnswered() = localNetwork.refresh()

    suspend fun setMonthlyCap(usd: Double?) {
        require(usd == null || (usd >= 0 && usd.isFinite())) { "A cap can't be negative" }
        prefs.setAiMonthlyCap(usd)
    }

    /** Saves the key of the provider in use, or of [provider]. The other providers' keys stay. */
    suspend fun saveKey(apiKey: String, provider: AiProviderId? = null) {
        val s = prefs.ai.first()
        keys.save(provider ?: s.provider, apiKey, s.compatibleUrl)
    }

    suspend fun removeKey(provider: AiProviderId? = null) = keys.clear(provider ?: prefs.ai.first().provider)

    /** The notice for [kind] was shown for the provider in use (and, for a compatible server, its host). */
    suspend fun markNoticeSeen(kind: AiKind = AiKind.MEAL_TEXT) = prefs.setAiNoticeSeen(prefs.ai.first().notice(kind))
}
