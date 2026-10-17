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
import app.recomp.data.repo.AiItemToLog
import app.recomp.data.repo.AiRepository
import app.recomp.data.repo.DraftPlan
import app.recomp.data.repo.EstimateResult
import app.recomp.data.repo.FoodLogRepository
import app.recomp.data.repo.FoodRepository
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

/** A reviewed item: what Claude estimated and what you've made of it. Changing anything makes it [edited]. */
data class ReviewItem(val original: EstimatedItem, val current: EstimatedItem) {
    val edited: Boolean get() = current != original
}

/** Describe it's three steps (PRD §7.7): typing, waiting for Claude, and reviewing the estimate. */
sealed interface DescribePhase {
    data object Input : DescribePhase
    data object Estimating : DescribePhase
    /** [notice] says why a re-estimate didn't replace this one. */
    data class Review(
        val requestId: Long,
        val items: List<ReviewItem>,
        val rejected: List<String>,
        val question: String?,
        val notice: String? = null,
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
) : ViewModel() {
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

    fun noticeSeen() = viewModelScope.launch { ai.markNoticeSeen() }

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
                ai.estimate(description, origin.value, clarification, plan)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EstimateResult.Failed(AiStatus.UNEXPECTED, requestId = null)
            }
            if (mine != attempt) return@launch
            val earlier = beforeReestimate
            beforeReestimate = null
            when (result) {
                is EstimateResult.Estimated -> _phase.value = DescribePhase.Review(
                    requestId = result.requestId,
                    items = result.meal.items.map { ReviewItem(it, it) },
                    rejected = result.meal.rejected,
                    question = result.meal.clarifyingQuestion,
                )
                // The gate changed since the button showed: back to the text (or the earlier estimate), which says why.
                is EstimateResult.NotReady -> _phase.value = earlier?.copy(notice = "AI isn't ready right now, so this is the earlier estimate.")
                    ?: DescribePhase.Input
                is EstimateResult.Failed -> if (earlier != null) {
                    _phase.value = earlier.copy(notice = "${AiText.reason(result.status)}, so this is the earlier estimate.")
                } else {
                    // Whatever went wrong, the description is kept: by the repository, or here if it never got that far.
                    draftId = result.draftId ?: ai.keepDraft(date, slot, draftId, description, origin.value, result.status)
                    _outcome.value = DescribeOutcome("${AiText.reason(result.status)}. Kept as a draft in ${slotName()}; log it now with search or quick add.")
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

    fun update(index: Int, item: EstimatedItem) = editReview { items -> items.mapIndexed { i, r -> if (i == index) r.copy(current = item) else r } }

    fun remove(index: Int) = editReview { items -> items.filterIndexed { i, _ -> i != index } }

    private fun editReview(change: (List<ReviewItem>) -> List<ReviewItem>) {
        val review = _phase.value as? DescribePhase.Review ?: return
        _phase.value = review.copy(items = change(review.items))
    }

    suspend fun mealNamed(name: String) = log.savedMealNamed(name)

    /** Logs the reviewed items (and, with [mealName], saves them to My meals); a draft it came from is done. */
    fun save(mealName: String?) {
        val review = _phase.value as? DescribePhase.Review ?: return
        if (review.items.isEmpty() || !claim()) return
        viewModelScope.launch {
            finishing {
                val ids = log.saveAiMeal(
                    date, slot, review.items.map { AiItemToLog(it.current, it.edited) }, origin.value, review.requestId, foods.defaultDiet(),
                    mealName = mealName, draftId = draftId,
                )
                val totals = MealCheck.totals(review.items.map { it.current })
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
    var editing by remember { mutableStateOf<Int?>(null) }
    var naming by remember { mutableStateOf<String?>(null) }
    var replacing by remember { mutableStateOf<String?>(null) }
    val slotName = slots.firstOrNull { it.key == viewModel.slot }?.name ?: viewModel.slot
    val ready = gate?.state == AiState.READY

    LaunchedEffect(outcome) { outcome?.let(onDone) }

    /** The first estimate shows the privacy notice once (TRD §6.2). */
    fun requestEstimate() {
        if (settings?.noticeSeen == true) viewModel.estimate() else notice = true
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
                modelLabel = settings?.model?.label,
                onText = viewModel::setText,
                onOrigin = viewModel::setOrigin,
                onEstimate = ::requestEstimate,
                onSaveDraft = viewModel::saveDraft,
                onAddKey = { addingKey = true },
                onCancel = viewModel::cancel,
                busy = busy,
            )
            is DescribePhase.Review -> ReviewStep(
                padding = padding,
                review = p,
                origin = origin,
                onEdit = { editing = it },
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
            title = { Text("Before your first estimate") },
            text = { Text(AiText.PRIVACY) },
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
        ApiKeyDialog(hasKey = hasKey, onDismiss = { addingKey = false }, onSave = { key -> viewModel.saveKey(key); addingKey = false }, onRemove = null)
    }
    val review = phase as? DescribePhase.Review
    editing?.let { index ->
        val item = review?.items?.getOrNull(index)
        if (item == null) {
            editing = null
        } else {
            AiItemSheet(
                item = item.current,
                onDismiss = { editing = null },
                onRemove = { editing = null; viewModel.remove(index) },
            ) { edited ->
                editing = null
                viewModel.update(index, edited)
            }
        }
    }
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
                    Text(reason, style = MaterialTheme.typography.bodyMedium)
                    if (gate?.state == AiState.NO_KEY) OutlinedButton(onClick = onAddKey) { Text("Add key") }
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
            Text("Claude assumes restaurant levels of oil, ghee and cream.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (estimating) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.padding(4.dp))
                Text("Estimating with ${modelLabel ?: "Claude"}… usually a few seconds.", Modifier.weight(1f))
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel") }
        } else if (ready) {
            Button(onClick = onEstimate, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Estimate") }
        } else {
            Button(onClick = onSaveDraft, enabled = text.isNotBlank() && gate != null && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Save draft") }
        }
    }
}

@Composable
private fun ReviewStep(
    padding: PaddingValues,
    review: DescribePhase.Review,
    origin: Origin,
    onEdit: (Int) -> Unit,
    onReestimate: (String) -> Unit,
    onSave: () -> Unit,
    onSaveAsMeal: () -> Unit,
    busy: Boolean,
) {
    var answer by remember(review.requestId) { mutableStateOf("") }
    val totals = MealCheck.totals(review.items.map { it.current })
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
                            Text("Claude asks: $q", style = MaterialTheme.typography.bodyLarge)
                            OutlinedTextField(value = answer, onValueChange = { answer = it }, label = { Text("Your answer") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedButton(onClick = { onReestimate(answer) }, enabled = answer.isNotBlank()) { Text("Re-estimate") }
                        }
                    }
                }
            }
            if (review.rejected.isNotEmpty()) {
                item {
                    Text(
                        "Left out, because Claude gave no weight: ${review.rejected.joinToString()}. Add them with search or quick add.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            itemsIndexed(review.items) { index, r -> ReviewRow(r, onClick = { onEdit(index) }) }
            if (review.items.isEmpty()) item { Text("Every item was removed. Go back to describe the meal again.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Column(Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, enabled = review.items.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Save") }
            OutlinedButton(onClick = onSaveAsMeal, enabled = review.items.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Save + My meals") }
        }
    }
}

@Composable
private fun ReviewRow(r: ReviewItem, onClick: () -> Unit) {
    val item = r.current
    Column(Modifier.fillMaxWidth().clickable(onClickLabel = "Change ${item.name}", onClick = onClick).padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name + if (r.edited) " · edited" else "", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${amount(item)} · ${AiText.confidence(item.confidence)}",
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
        if (MealCheck.mismatched(item)) {
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
private fun amount(item: EstimatedItem): String {
    val unit = item.unit.trim().lowercase()
    return if (unit in Portions.GRAM_LABELS && unit != "ml") "${item.grams.roundToInt()} g"
    else "${Portions.format(item.quantity)} ${item.unit.trim()} · ${item.grams.roundToInt()} g"
}

/**
 * Changes one estimated item: name, weight (which scales its macros) and numbers, or removes it. Any change
 * makes it an AI-learned food when saved (TRD §6.2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiItemSheet(item: EstimatedItem, onDismiss: () -> Unit, onRemove: () -> Unit, onSave: (EstimatedItem) -> Unit) {
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
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
                MacroField("Calories", kcal, "kcal", Modifier.weight(1f)) { v -> typed { kcal = v } }
                MacroField("Protein", protein, "g", Modifier.weight(1f)) { v -> typed { protein = v } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MacroField("Carbs", carbs, "g", Modifier.weight(1f)) { v -> typed { carbs = v } }
                MacroField("Fat", fat, "g", Modifier.weight(1f)) { v -> typed { fat = v } }
            }
            MacroField("Fibre", fiber, "g", Modifier.fillMaxWidth()) { v -> typed { fiber = v } }
            Text(
                "Saving a changed item also keeps it as one of your foods (AI-learned), so it shows in search and later estimates.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    val v = values.map { it!! }
                    val changed = item.copy(name = name.trim(), grams = g!!, kcal = v[0], proteinG = v[1], carbsG = v[2], fatG = v[3], fiberG = v[4])
                    // Only a real change counts: retyping the same numbers leaves the item as Claude's.
                    onSave(if (sameNumbers(changed, item)) item else changed)
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
private fun MacroField(label: String, value: String, suffix: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = parseNumber(value)?.let { it < 0 } != false,
        modifier = modifier,
    )
}
