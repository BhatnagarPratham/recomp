package app.recomp.ui.food

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.recomp.data.prefs.AiSettings
import app.recomp.data.repo.AiGateView
import app.recomp.data.repo.DescribeResult
import app.recomp.data.repo.FoodHit
import app.recomp.data.repo.ItemFrom
import app.recomp.data.repo.MealDescriber
import app.recomp.data.repo.PortionChoice
import app.recomp.data.repo.AiRepository
import app.recomp.data.repo.DraftPlan
import app.recomp.data.repo.FoodLogRepository
import app.recomp.data.repo.FoodRepository
import app.recomp.domain.ai.AiKind
import app.recomp.ui.settings.rememberLocalNetworkRequest
import app.recomp.domain.ai.AiProviderId
import app.recomp.domain.ai.AiState
import app.recomp.domain.ai.AiStatus
import app.recomp.domain.ai.Clarification
import app.recomp.domain.ai.EstimatedItem
import app.recomp.domain.ai.MealCheck
import app.recomp.domain.food.MealSlot
import app.recomp.domain.food.MealSlots
import app.recomp.domain.food.Origin
import app.recomp.domain.food.Portions
import app.recomp.domain.persona.FoodRegion
import app.recomp.ui.components.ChoiceChips
import app.recomp.ui.components.ConfirmDialog
import app.recomp.ui.components.TextEntryDialog
import app.recomp.ui.navigation.DescribeRoute
import app.recomp.ui.onboarding.GoalText
import app.recomp.ui.settings.ApiKeyDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Describe it's three steps (PRD §7.7): typing, waiting for the estimate, and reviewing it. */
sealed interface DescribePhase {
    data object Input : DescribePhase
    data object Estimating : DescribePhase

    /**
     * [requestId] is the AI request, or null when the local parser answered alone (AI-04). [unmatched] are the
     * chunks nothing found, as typed. [notice] says why a re-estimate didn't replace this one, or why the AI's
     * part is missing.
     */
    data class Review(
        val requestId: Long?,
        val items: List<ReviewItem>,
        val rejected: List<String>,
        val question: String?,
        val notice: String? = null,
        val unmatched: List<String> = emptyList(),
    ) : DescribePhase
}

/** How Describe it ended, for the screen it returns to: a message, and the new entries' ids for *Undo*. */
data class DescribeOutcome(val message: String, val undoIds: List<Long> = emptyList())

@HiltViewModel
class DescribeViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val ai: AiRepository,
    private val log: FoodLogRepository,
    private val foods: FoodRepository,
    private val describer: MealDescriber,
) : ViewModel(), ReviewActions {
    private val route = savedState.toRoute<DescribeRoute>()
    val date: LocalDate = LocalDate.parse(route.date)
    val slot: String = route.slot
    private var draftId: Long? = route.draftId.takeIf { it > 0 }

    /** Read before [text] exists: `getStateFlow` stores its initial value, so afterwards the key is always there. */
    private val restored = savedState.contains(TEXT)

    /** The description and origin survive process death; an estimate in flight doesn't need to. */
    val text: StateFlow<String> = savedState.getStateFlow(TEXT, "")
    val origin: StateFlow<Origin> = savedState.getStateFlow(ORIGIN, Origin.HOME)

    val gate: StateFlow<AiGateView?> = ai.gate.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val settings: StateFlow<AiSettings?> = ai.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val hasKey: StateFlow<Boolean> = ai.hasKey.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val slots: StateFlow<List<MealSlot>> = log.mealSlots.stateIn(viewModelScope, SharingStarted.Eagerly, MealSlots.DEFAULT)
    val region: StateFlow<FoodRegion> = foods.filter.map { it.region }.stateIn(viewModelScope, SharingStarted.Eagerly, FoodRegion.INDIAN)

    private val _phase = MutableStateFlow<DescribePhase>(DescribePhase.Input)
    val phase: StateFlow<DescribePhase> = _phase

    /**
     * Set before the first suspend, so a second tap on Save or Save draft is ignored instead of logging the meal
     * (or the draft) twice. It stays set once the screen is done; a failure releases it.
     */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _outcome = MutableStateFlow<DescribeOutcome?>(null)
    val outcome: StateFlow<DescribeOutcome?> = _outcome

    /** Set once a draft's text is loaded: *Estimate now* starts the estimate when the screen opens. */
    private val _autoStart = MutableStateFlow(false)
    val autoStart: StateFlow<Boolean> = _autoStart

    /** Bumped by *Cancel*, so a reply to an abandoned request is ignored (it's still logged and costed). */
    private var attempt = 0

    /** The estimate a *Re-estimate* started from: already paid for, so a failed or cancelled re-estimate returns to it. */
    private var beforeReestimate: DescribePhase.Review? = null

    init {
        val id = draftId
        if (id != null && !restored) {
            viewModelScope.launch {
                val draft = ai.draft(id)
                if (draft == null) {
                    draftId = null
                } else {
                    savedState[TEXT] = draft.text
                    savedState[ORIGIN] = draft.origin
                    _autoStart.value = true
                }
            }
        }
    }

    fun setText(value: String) {
        savedState[TEXT] = value
    }

    fun setOrigin(value: Origin) {
        savedState[ORIGIN] = value
    }

    fun autoStarted() {
        _autoStart.value = false
    }

    fun noticeSeen() = viewModelScope.launch { ai.markNoticeSeen(AiKind.MEAL_TEXT) }

    /** The local-network permission's prompt was answered: the gate reads it again. */
    fun localNetworkAnswered() = ai.localNetworkAnswered()

    /** The provider in use, which every message names (AI-05). */
    private fun provider() = settings.value?.provider ?: AiProviderId.DEFAULT

    suspend fun saveKey(key: String) = ai.saveKey(key)

    fun estimate(clarification: Clarification? = null) {
        val description = text.value.trim()
        // A second tap while one runs would send (and pay for) the same meal twice.
        if (description.isEmpty() || _phase.value == DescribePhase.Estimating || _busy.value) return
        val mine = ++attempt
        beforeReestimate = if (clarification != null) _phase.value as? DescribePhase.Review else null
        _phase.value = DescribePhase.Estimating
        // A first estimate keeps its description as a draft if it fails, unless you cancelled; a re-estimate has the
        // earlier estimate to go back to instead.
        val plan = if (clarification == null) DraftPlan(date, slot, draftId, keep = { mine == attempt }) else null
        viewModelScope.launch {
            val result = try {
                describer.describe(description, origin.value, AiKind.MEAL_TEXT, clarification, plan)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DescribeResult.Failed(AiStatus.UNEXPECTED, requestId = null)
            }
            if (mine != attempt) return@launch
            val earlier = beforeReestimate
            beforeReestimate = null
            when (result) {
                is DescribeResult.Described -> {
                    val meal = result.meal
                    _phase.value = when {
                        // A re-estimate that didn't reach the AI keeps the estimate it started from, which was paid for.
                        earlier != null && meal.aiFailure != null -> earlier.copy(notice = "${AiText.reason(meal.aiFailure, provider())}, so this is the earlier estimate.")
                        earlier != null && meal.requestId == null -> earlier.copy(notice = "AI isn't ready right now, so this is the earlier estimate.")
                        meal.items.isEmpty() && meal.unmatched.isEmpty() -> DescribePhase.Input
                        else -> DescribePhase.Review(
                            requestId = meal.requestId.takeIf { meal.aiFailure == null },
                            items = meal.reviewItems(),
                            rejected = meal.rejected,
                            question = meal.question,
                            notice = DescribeText.notice(meal, provider()),
                            unmatched = meal.unmatched,
                        )
                    }
                }
                is DescribeResult.Failed -> if (earlier != null) {
                    _phase.value = earlier.copy(notice = "${AiText.reason(result.status, provider())}, so this is the earlier estimate.")
                } else {
                    // Whatever went wrong, the description is kept: by the repository, or here if it never got that far.
                    draftId = result.draftId ?: ai.keepDraft(date, slot, draftId, description, origin.value, result.status)
                    _outcome.value = DescribeOutcome("${AiText.reason(result.status, provider())}. Kept as a draft in ${slotName()}; log it now with search or quick add.")
                }
            }
        }
    }

    /** *Cancel* only stops waiting: back to the text (or the estimate a re-estimate started from), and nothing is saved. */
    fun cancel() {
        attempt++
        _phase.value = beforeReestimate ?: DescribePhase.Input
        beforeReestimate = null
    }

    /** Back from the review returns to the text, where you can estimate again or keep a draft. */
    fun backToText() {
        _phase.value = DescribePhase.Input
    }

    /** *Save draft* (AI not ready). */
    fun saveDraft() {
        val description = text.value.trim()
        if (description.isEmpty() || !claim()) return
        viewModelScope.launch {
            finishing {
                draftId = ai.keepDraft(date, slot, draftId, description, origin.value, reason = null)
                _outcome.value = DescribeOutcome("Kept as a draft in ${slotName()}. Estimate it once AI is ready.")
            }
        }
    }

    private fun claim(): Boolean = _busy.compareAndSet(expect = false, update = true)

    private suspend fun finishing(block: suspend () -> Unit) {
        try {
            block()
        } finally {
            if (_outcome.value == null) _busy.value = false
        }
    }

    override fun update(index: Int, item: ReviewItem) = editReview { it.copy(items = ReviewEdits.update(it.items, index, item)) }

    override fun remove(index: Int) = editReview { it.copy(items = ReviewEdits.remove(it.items, index)) }

    override fun resolve(chunk: String, item: ReviewItem) = editReview { it.copy(items = it.items + item, unmatched = ReviewEdits.without(it.unmatched, chunk)) }

    override fun skip(chunk: String) = editReview { it.copy(unmatched = ReviewEdits.without(it.unmatched, chunk)) }

    override suspend fun searchFoods(query: String): List<FoodHit> = foods.search(query)

    override suspend fun portion(foodId: Long): PortionChoice? = foods.portion(foodId)

    private fun editReview(change: (DescribePhase.Review) -> DescribePhase.Review) {
        val review = _phase.value as? DescribePhase.Review ?: return
        _phase.value = change(review)
    }

    suspend fun mealNamed(name: String) = log.savedMealNamed(name)

    /** Logs the reviewed items (and, with [mealName], saves them to My meals); a draft it came from is done. */
    fun save(mealName: String?) {
        val review = _phase.value as? DescribePhase.Review ?: return
        if (review.items.isEmpty() || !claim()) return
        viewModelScope.launch {
            finishing {
                val ids = log.saveDescribedMeal(
                    date, slot, review.items.map { it.toLog() }, origin.value, review.requestId, foods.defaultDiet(),
                    mealName = mealName, draftId = draftId,
                )
                val totals = review.items.totals()
                val n = review.items.size
                _outcome.value = DescribeOutcome(
                    "Added $n ${if (n == 1) "item" else "items"} · ${FoodText.kcalProtein(totals)}" + if (mealName != null) " · saved to My meals" else "",
                    ids,
                )
            }
        }
    }

    private suspend fun slotName(): String = log.mealSlots.first().firstOrNull { it.key == slot }?.name ?: slot

    private companion object {
        const val TEXT = "text"
        const val ORIGIN = "origin"
    }
}

/**
 * Describe it (NU-17): type a meal, get Claude's itemised estimate, review and save. With AI on but not ready it
 * keeps the description as a draft instead (AI-02); search, My meals and Quick add are a Back away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DescribeScreen(onBack: () -> Unit, onDone: (DescribeOutcome) -> Unit, viewModel: DescribeViewModel = hiltViewModel()) {
    val text by viewModel.text.collectAsStateWithLifecycle()
    val origin by viewModel.origin.collectAsStateWithLifecycle()
    val gate by viewModel.gate.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val hasKey by viewModel.hasKey.collectAsStateWithLifecycle()
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()
    val autoStart by viewModel.autoStart.collectAsStateWithLifecycle()
    val slots by viewModel.slots.collectAsStateWithLifecycle()
    val region by viewModel.region.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf(false) }
    var addingKey by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<ReviewTarget?>(null) }
    var naming by remember { mutableStateOf<String?>(null) }
    var replacing by remember { mutableStateOf<String?>(null) }
    val slotName = slots.firstOrNull { it.key == viewModel.slot }?.name ?: viewModel.slot
    val ready = gate?.state == AiState.READY

    // Android's local-network permission, for a server on your own network (AI-05). The gate reads the answer.
    val askLocalNetwork = rememberLocalNetworkRequest(viewModel::localNetworkAnswered)

    LaunchedEffect(outcome) { outcome?.let(onDone) }

    /**
     * The first estimate that may reach the provider shows the privacy notice once (TRD §6.2). With AI off or not
     * ready nothing leaves the phone: the local parser answers (AI-04).
     */
    fun requestEstimate() {
        if (!ready || settings?.noticeSeen(AiKind.MEAL_TEXT) == true) viewModel.estimate() else notice = true
    }

    // Estimate now on a draft: start once the gate is known, and only if it's READY then. A gate that becomes
    // ready later never starts it on its own: drafts are only ever sent when you tap.
    LaunchedEffect(autoStart, gate?.state, settings) {
        if (autoStart && settings != null && gate != null) {
            viewModel.autoStarted()
            if (ready) requestEstimate()
        }
    }

    BackHandler(enabled = phase !is DescribePhase.Input) {
        if (phase is DescribePhase.Estimating) viewModel.cancel() else viewModel.backToText()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (phase is DescribePhase.Review) "Review $slotName" else "Describe $slotName") },
                navigationIcon = {
                    TextButton(onClick = {
                        when (phase) {
                            DescribePhase.Input -> onBack()
                            DescribePhase.Estimating -> viewModel.cancel()
                            is DescribePhase.Review -> viewModel.backToText()
                        }
                    }) { Text("Back") }
                },
            )
        },
    ) { padding ->
        when (val p = phase) {
            DescribePhase.Input, DescribePhase.Estimating -> InputStep(
                padding = padding,
                text = text,
                origin = origin,
                region = region,
                gate = gate,
                estimating = p == DescribePhase.Estimating,
                modelLabel = settings?.let { it.model?.label ?: it.compatibleModel.ifBlank { null } },
                onText = viewModel::setText,
                onOrigin = viewModel::setOrigin,
                onEstimate = ::requestEstimate,
                onSaveDraft = viewModel::saveDraft,
                onAddKey = { addingKey = true },
                onAllowLocalNetwork = askLocalNetwork,
                onCancel = viewModel::cancel,
                busy = busy,
            )
            is DescribePhase.Review -> ReviewStep(
                padding = padding,
                review = p,
                origin = origin,
                onEdit = { target = ReviewTarget.Edit(it) },
                onSearch = { target = ReviewTarget.Search(it) },
                onQuickAdd = { target = ReviewTarget.Quick(it) },
                onSkip = viewModel::skip,
                onReestimate = { answer -> p.question?.let { viewModel.estimate(Clarification(it, answer)) } },
                onSave = { viewModel.save(null) },
                onSaveAsMeal = { naming = text.trim().replace(Regex("\\s+"), " ").take(40).trimEnd(',', ' ') },
                busy = busy,
            )
        }
    }

    if (notice) {
        AlertDialog(
            onDismissRequest = { notice = false },
            title = { Text("Before your first estimate with ${settings?.provider?.label ?: "AI"}") },
            text = { Text(settings?.let(AiText::privacy).orEmpty()) },
            confirmButton = {
                TextButton(onClick = {
                    notice = false
                    viewModel.noticeSeen()
                    viewModel.estimate()
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { notice = false }) { Text("Cancel") } },
        )
    }
    if (addingKey) {
        ApiKeyDialog(provider = settings?.provider ?: AiProviderId.DEFAULT, hasKey = hasKey, onDismiss = { addingKey = false }, onSave = { key -> viewModel.saveKey(key); addingKey = false }, onRemove = null)
    }
    ReviewSheets(target, (phase as? DescribePhase.Review)?.items.orEmpty(), slots, origin, viewModel) { target = null }
    naming?.let { initial ->
        TextEntryDialog("Save to My meals", initial, onDismiss = { naming = null }) { name ->
            naming = null
            if (name.isBlank()) return@TextEntryDialog
            scope.launch { if (viewModel.mealNamed(name) != null) replacing = name else viewModel.save(name.trim()) }
        }
    }
    replacing?.let { name ->
        ConfirmDialog(
            "Replace “${name.trim()}”?",
            "My meals already has a meal with this name. Its items will be replaced by this estimate.",
            confirm = "Replace",
            onDismiss = { replacing = null },
        ) {
            replacing = null
            viewModel.save(name.trim())
        }
    }
}

@Composable
private fun InputStep(
    padding: PaddingValues,
    text: String,
    origin: Origin,
    region: FoodRegion,
    gate: AiGateView?,
    estimating: Boolean,
    modelLabel: String?,
    onText: (String) -> Unit,
    onOrigin: (Origin) -> Unit,
    onEstimate: () -> Unit,
    onSaveDraft: () -> Unit,
    onAddKey: () -> Unit,
    onAllowLocalNetwork: () -> Unit,
    onCancel: () -> Unit,
    busy: Boolean,
) {
    val ready = gate?.state == AiState.READY
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AiText.notReady(gate)?.let { reason ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$reason ${DescribeText.PARSER_MEANWHILE}", style = MaterialTheme.typography.bodyMedium)
                    if (gate?.state == AiState.NO_KEY) OutlinedButton(onClick = onAddKey) { Text("Add key") }
                    if (gate?.state == AiState.NO_LOCAL_NETWORK) OutlinedButton(onClick = onAllowLocalNetwork) { Text("Allow") }
                }
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = onText,
            label = { Text("What did you eat?") },
            placeholder = {
                Text(
                    when (region) {
                        FoodRegion.INDIAN -> "2 phulka, 1 katori rajma, cucumber salad, 1 glass chaas"
                        FoodRegion.INTERNATIONAL -> "2 slices of toast with butter, a fried egg, a cup of coffee with milk"
                    },
                )
            },
            minLines = 3,
            enabled = !estimating,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Origin", style = MaterialTheme.typography.labelLarge)
        ChoiceChips(Origin.entries, origin, FoodText::origin, { if (!estimating) onOrigin(it) })
        if (origin == Origin.ORDERED) {
            Text(
                if (ready) "The estimate assumes restaurant levels of oil, ghee and cream." else FoodText.ORDERED_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (gate?.state == AiState.OFF) {
            Text(DescribeText.PARSER_ONLY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (estimating) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.padding(4.dp))
                Text(if (ready) "Estimating with ${modelLabel ?: "AI"}… usually a few seconds." else "Matching your foods…", Modifier.weight(1f))
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel") }
        } else {
            Button(onClick = onEstimate, enabled = text.isNotBlank() && gate != null && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Estimate") }
            // AI on but not ready (AI-02): the parser answers now, and the description can still wait for the AI.
            if (!ready && gate != null && gate.state != AiState.OFF) {
                OutlinedButton(onClick = onSaveDraft, enabled = text.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Save draft") }
            }
        }
    }
}

@Composable
private fun ReviewStep(
    padding: PaddingValues,
    review: DescribePhase.Review,
    origin: Origin,
    onEdit: (Int) -> Unit,
    onSearch: (String) -> Unit,
    onQuickAdd: (String) -> Unit,
    onSkip: (String) -> Unit,
    onReestimate: (String) -> Unit,
    onSave: () -> Unit,
    onSaveAsMeal: () -> Unit,
    busy: Boolean,
) {
    var answer by remember(review.requestId) { mutableStateOf("") }
    val totals = review.items.totals()
    Column(Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(FoodText.kcalProtein(totals), style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${FoodText.g(totals.carbsG ?: 0.0)} carbs · ${FoodText.g(totals.fatG ?: 0.0)} fat · ${FoodText.g(totals.fiberG ?: 0.0)} fibre",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("${review.items.size} ${if (review.items.size == 1) "item" else "items"} · ${FoodText.origin(origin)} · tap one to change it", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            review.notice?.let { notice ->
                item { Text(notice, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary) }
            }
            review.question?.let { q ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Question: $q", style = MaterialTheme.typography.bodyLarge)
                            OutlinedTextField(value = answer, onValueChange = { answer = it }, label = { Text("Your answer") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedButton(onClick = { onReestimate(answer) }, enabled = answer.isNotBlank()) { Text("Re-estimate") }
                        }
                    }
                }
            }
            if (review.rejected.isNotEmpty()) {
                item {
                    Text(
                        "Left out, because the estimate gave no usable weight or amount: ${review.rejected.joinToString()}. Add them with search or quick add.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            itemsIndexed(review.items) { index, r -> ReviewRow(r, onClick = { onEdit(index) }) }
            itemsIndexed(review.unmatched) { _, chunk -> UnmatchedRow(chunk, onSearch = { onSearch(chunk) }, onQuickAdd = { onQuickAdd(chunk) }, onSkip = { onSkip(chunk) }) }
            if (review.items.isEmpty()) {
                item {
                    Text(
                        if (review.unmatched.isEmpty()) "Every item was removed. Go back to describe the meal again." else "Nothing matched yet. Search or quick add each part, or go back and reword it.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, enabled = review.items.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Save") }
            OutlinedButton(onClick = onSaveAsMeal, enabled = review.items.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Save + My meals") }
        }
    }
}

@Composable
internal fun ReviewRow(r: ReviewItem, onClick: () -> Unit) {
    val item = r.current
    Column(Modifier.fillMaxWidth().clickable(onClickLabel = "Change ${item.name}", onClick = onClick).padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name + if (r.edited) " · edited" else "", style = MaterialTheme.typography.bodyLarge)
                Text(
                    // Where the numbers came from (PRD §12.5), and how sure the estimate or the match is.
                    listOfNotNull(amount(item), FoodText.from(r.from), AiText.confidence(item.confidence).takeIf { r.quick == null && !r.picked }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(GoalText.kcal(item.kcal), style = MaterialTheme.typography.bodyMedium)
                Text("${FoodText.g(item.proteinG)} protein", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (item.assumptions.isNotBlank()) Text(item.assumptions, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (r.from == ItemFrom.AI && MealCheck.mismatched(item)) {
            Text(
                "⚠ Its calories don't match its macros (they add up to ${GoalText.kcal(item.macroKcal)}). Check the numbers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
    HorizontalDivider()
}

/** "2 piece · 70 g", or "150 g" when the unit is grams. */
private fun amount(item: EstimatedItem): String? {
    val unit = item.unit.trim().lowercase()
    return when {
        // A quick add has calories and protein, no weight.
        item.grams <= 0 -> null
        unit in Portions.GRAM_LABELS && unit != "ml" -> "${item.grams.roundToInt()} g"
        else -> "${Portions.format(item.quantity)} ${item.unit.trim()} · ${item.grams.roundToInt()} g"
    }
}

/**
 * Changes one estimated item: name, weight (which scales its macros) and numbers, or removes it. Any change
 * makes it an AI-learned food when saved (TRD §6.2). With [amountOnly] it's a food the parser matched or you
 * picked: only its weight changes, because its numbers are the food's own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiItemSheet(item: EstimatedItem, amountOnly: Boolean = false, onDismiss: () -> Unit, onRemove: () -> Unit, onSave: (EstimatedItem) -> Unit) {
    fun num(d: Double) = if (d % 1.0 == 0.0) "${d.toLong()}" else "%.1f".format(d)
    var name by remember { mutableStateOf(item.name) }
    var grams by remember { mutableStateOf(num(item.grams)) }
    var kcal by remember { mutableStateOf(num(item.kcal)) }
    var protein by remember { mutableStateOf(num(item.proteinG)) }
    var carbs by remember { mutableStateOf(num(item.carbsG)) }
    var fat by remember { mutableStateOf(num(item.fatG)) }
    var fiber by remember { mutableStateOf(num(item.fiberG)) }
    /**
     * What a new weight scales from: the numbers last typed (or Claude's) and the weight they were for. Scaling
     * from this base, not from the previous keystroke's rounded values, keeps typing "150" from drifting.
     */
    var base by remember { mutableStateOf(item.grams to listOf(item.kcal, item.proteinG, item.carbsG, item.fatG, item.fiberG)) }

    val g = parseNumber(grams)?.takeIf { it > 0 && it <= 5_000 }
    val values = listOf(kcal, protein, carbs, fat, fiber).map { parseNumber(it)?.takeIf { v -> v >= 0 } }
    val valid = name.isNotBlank() && g != null && values.all { it != null }

    fun rescale(newGrams: Double) {
        val (baseGrams, numbers) = base
        val scaled = numbers.map { num(it * newGrams / baseGrams) }
        kcal = scaled[0]; protein = scaled[1]; carbs = scaled[2]; fat = scaled[3]; fiber = scaled[4]
    }

    /** A typed number becomes the new base, at the weight shown now. */
    fun typed(update: () -> Unit) {
        update()
        val numbers = listOf(kcal, protein, carbs, fat, fiber).map { parseNumber(it) }
        if (g != null && numbers.all { it != null }) base = g to numbers.map { it!! }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Change item", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, enabled = !amountOnly, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = grams,
                onValueChange = { t ->
                    grams = t
                    parseNumber(t)?.takeIf { it > 0 && it <= 5_000 }?.let(::rescale)
                },
                label = { Text("Weight (${Portions.format(item.quantity)} ${item.unit.trim()})") },
                suffix = { Text("g") },
                supportingText = { Text("Changing the weight scales the numbers below.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = g == null,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MacroField("Calories", kcal, "kcal", Modifier.weight(1f), !amountOnly) { v -> typed { kcal = v } }
                MacroField("Protein", protein, "g", Modifier.weight(1f), !amountOnly) { v -> typed { protein = v } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MacroField("Carbs", carbs, "g", Modifier.weight(1f), !amountOnly) { v -> typed { carbs = v } }
                MacroField("Fat", fat, "g", Modifier.weight(1f), !amountOnly) { v -> typed { fat = v } }
            }
            MacroField("Fibre", fiber, "g", Modifier.fillMaxWidth(), !amountOnly) { v -> typed { fiber = v } }
            Text(
                if (amountOnly) "These are the food's own numbers for this weight. To change them, edit the food from search."
                else "Saving a changed item also keeps it as one of your foods (AI-learned), so it shows in search and later estimates.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    val v = values.map { it!! }
                    val changed = item.copy(name = name.trim(), grams = g!!, kcal = v[0], proteinG = v[1], carbsG = v[2], fatG = v[3], fiberG = v[4])
                    // Only a real change counts: retyping the same numbers leaves the item as it was estimated. A matched
                    // food keeps its exact per-gram numbers, scaled to the new weight.
                    onSave(if (sameNumbers(changed, item)) item else if (amountOnly) item.scaledTo(g) else changed)
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text("Done") }
            OutlinedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Remove item") }
        }
    }
}

/** Equal to the 0.1 the fields show, so opening and closing the sheet never marks an item as edited. */
private fun sameNumbers(a: EstimatedItem, b: EstimatedItem): Boolean {
    fun close(x: Double, y: Double) = kotlin.math.abs(x - y) < 0.05 + 1e-9
    return a.name == b.name && close(a.grams, b.grams) && close(a.kcal, b.kcal) && close(a.proteinG, b.proteinG) &&
        close(a.carbsG, b.carbsG) && close(a.fatG, b.fatG) && close(a.fiberG, b.fiberG)
}

@Composable
private fun MacroField(label: String, value: String, suffix: String, modifier: Modifier = Modifier, enabled: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = parseNumber(value)?.let { it < 0 } != false,
        modifier = modifier,
    )
}
