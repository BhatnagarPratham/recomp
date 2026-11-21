package app.recomp.ui.food

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.recomp.data.prefs.AiSettings
import app.recomp.data.prefs.DayBoundary
import app.recomp.data.repo.AiGateView
import app.recomp.data.repo.AiRepository
import app.recomp.data.repo.DescribeResult
import app.recomp.data.repo.FoodHit
import app.recomp.data.repo.FoodLogRepository
import app.recomp.data.repo.FoodRepository
import app.recomp.data.repo.MealDescriber
import app.recomp.data.repo.NutritionRepository
import app.recomp.data.repo.PortionChoice
import app.recomp.data.repo.UnmatchedPart
import app.recomp.data.repo.TodayNutrition
import app.recomp.domain.ai.AiKind
import app.recomp.domain.ai.AiProviderId
import app.recomp.domain.ai.AiState
import app.recomp.domain.ai.AiStatus
import app.recomp.domain.food.MealSlot
import app.recomp.domain.food.MealSlots
import app.recomp.domain.food.MealTimes
import app.recomp.domain.food.Origin
import app.recomp.domain.nutrition.FitCheck
import app.recomp.domain.nutrition.FitResult
import app.recomp.ui.components.ChoiceChips
import app.recomp.ui.navigation.FitRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalTime
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** *Will it fit?*'s steps: describing the dish, waiting, and its effect on today. */
sealed interface FitPhase {
    data object Input : FitPhase
    data object Checking : FitPhase

    /** [requestId] is the AI request, or null when the local parser answered alone. */
    data class Result(
        val requestId: Long?,
        val items: List<ReviewItem>,
        val unmatched: List<UnmatchedPart>,
        val rejected: List<String>,
        val notice: String?,
        val alreadyCounted: List<ReviewItem> = emptyList(),
    ) : FitPhase
}

/**
 * *Will it fit?* (NU-18, PRD §12.5): describe a dish and see today's numbers before and after, without logging
 * it. *Log it* adds it to the meal for the time of day. It never keeps a draft: there's nothing to log yet.
 */
@HiltViewModel
class FitViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val describer: MealDescriber,
    private val ai: AiRepository,
    private val log: FoodLogRepository,
    private val foods: FoodRepository,
    nutrition: NutritionRepository,
    private val days: DayBoundary,
    private val clock: Clock,
) : ViewModel(), ReviewActions {
    private val route = savedState.toRoute<FitRoute>()

    val text: StateFlow<String> = savedState.getStateFlow(TEXT, "")

    /** A dish you're thinking of ordering: *Ordered* unless you say otherwise. */
    val origin: StateFlow<Origin> = savedState.getStateFlow(ORIGIN, Origin.ORDERED)

    val gate: StateFlow<AiGateView?> = ai.gate.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val settings: StateFlow<AiSettings?> = ai.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val slots: StateFlow<List<MealSlot>> = log.mealSlots.stateIn(viewModelScope, SharingStarted.Eagerly, MealSlots.DEFAULT)

    /** Today's targets and what's eaten so far; null before any target exists. */
    val today: StateFlow<TodayNutrition?> = nutrition.observeToday().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _phase = MutableStateFlow<FitPhase>(FitPhase.Input)
    val phase: StateFlow<FitPhase> = _phase

    /** The dish's effect on today, from the items as you've left them. Null without targets or a result. */
    val fit: StateFlow<FitResult?> = combine(today, phase) { t, p ->
        val items = (p as? FitPhase.Result)?.items?.takeIf { it.isNotEmpty() } ?: return@combine null
        if (t == null) null else FitCheck.of(t.target.goal, t.target.kcal, t.protein, t.eaten, items.totals())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The meal *Log it* adds to: the one you came from, else the meal for the time of day; yours to change. */
    private val pickedSlot = MutableStateFlow(route.slot.ifEmpty { null })
    val slot: StateFlow<String?> = combine(pickedSlot, slots) { picked, all ->
        picked?.takeIf { key -> all.any { it.key == key && !it.removed } } ?: MealTimes.slotAt(all, LocalTime.now(clock))?.key
    }.stateIn(viewModelScope, SharingStarted.Eagerly, route.slot.ifEmpty { null })

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _outcome = MutableStateFlow<DescribeOutcome?>(null)
    val outcome: StateFlow<DescribeOutcome?> = _outcome

    /** Bumped by *Cancel*, so a reply to an abandoned check is ignored (it's still logged and costed). */
    private var attempt = 0

    fun setText(value: String) {
        savedState[TEXT] = value
    }

    fun setOrigin(value: Origin) {
        savedState[ORIGIN] = value
    }

    fun setSlot(key: String) {
        pickedSlot.value = key
    }

    fun noticeSeen() = viewModelScope.launch { ai.markNoticeSeen(AiKind.FIT_CHECK) }

    private fun provider() = settings.value?.provider ?: AiProviderId.DEFAULT

    fun check() {
        val description = text.value.trim()
        if (description.isEmpty() || _phase.value == FitPhase.Checking || _busy.value) return
        val mine = ++attempt
        _phase.value = FitPhase.Checking
        viewModelScope.launch {
            val result = try {
                describer.describe(description, origin.value, AiKind.FIT_CHECK, parserOnFailure = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DescribeResult.Failed(AiStatus.UNEXPECTED, requestId = null)
            }
            if (mine != attempt) return@launch
            _phase.value = when (result) {
                is DescribeResult.Described -> FitPhase.Result(
                    requestId = result.meal.requestId.takeIf { result.meal.aiFailure == null },
                    items = result.meal.reviewItems(),
                    unmatched = result.meal.unmatched,
                    rejected = result.meal.rejected,
                    notice = DescribeText.notice(result.meal, provider()),
                    alreadyCounted = result.meal.countedItems(),
                )
                // Only when describing itself broke: nothing was found, and the reason is all there is to show.
                is DescribeResult.Failed -> FitPhase.Result(null, emptyList(), listOf(UnmatchedPart(description)), emptyList(), AiText.reason(result.status, provider()) + ".")
            }
        }
    }

    /** *Cancel* only stops waiting; the request still finishes and is counted in the spend. */
    fun cancel() {
        attempt++
        _phase.value = FitPhase.Input
    }

    fun backToText() {
        _phase.value = FitPhase.Input
    }

    override fun update(index: Int, item: ReviewItem) = edit { it.copy(items = ReviewEdits.update(it.items, index, item)) }

    override fun remove(index: Int) = edit { it.copy(items = ReviewEdits.remove(it.items, index)) }

    override fun resolve(chunk: String, item: ReviewItem) = edit { it.copy(items = it.items + item, unmatched = ReviewEdits.without(it.unmatched, chunk)) }

    override fun skip(chunk: String) = edit { it.copy(unmatched = ReviewEdits.without(it.unmatched, chunk)) }

    override fun addAnyway(index: Int) = edit { r -> ReviewEdits.addAnyway(r.items, r.alreadyCounted, index).let { (items, counted) -> r.copy(items = items, alreadyCounted = counted) } }

    override suspend fun searchFoods(query: String): List<FoodHit> = foods.search(query)

    override suspend fun portion(foodId: Long): PortionChoice? = foods.portion(foodId)

    private fun edit(change: (FitPhase.Result) -> FitPhase.Result) {
        val result = _phase.value as? FitPhase.Result ?: return
        _phase.value = change(result)
    }

    /** *Log it*: the dish goes into today's chosen meal, with its request id when the AI estimated it. */
    fun logIt() {
        val result = _phase.value as? FitPhase.Result ?: return
        val into = slot.value ?: return
        if (result.items.isEmpty() || !_busy.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                val ids = log.saveDescribedMeal(
                    today.value?.today ?: days.today(), into, result.items.map { it.toLog() }, origin.value, result.requestId, foods.defaultDiet(),
                    mealName = null, draftId = null,
                )
                val name = log.mealSlots.first().firstOrNull { it.key == into }?.name ?: into
                _outcome.value = DescribeOutcome("Logged to $name · ${FoodText.kcalProtein(result.items.totals())}", ids)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing logged: the result stays, with the reason.
                (_phase.value as? FitPhase.Result)?.let { _phase.value = it.copy(notice = DescribeText.saveFailed(e)) }
            } finally {
                if (_outcome.value == null) _busy.value = false
            }
        }
    }

    private companion object {
        const val TEXT = "text"
        const val ORIGIN = "origin"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FitScreen(onBack: () -> Unit, onDone: (DescribeOutcome) -> Unit, viewModel: FitViewModel = hiltViewModel()) {
    val text by viewModel.text.collectAsStateWithLifecycle()
    val origin by viewModel.origin.collectAsStateWithLifecycle()
    val gate by viewModel.gate.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val fit by viewModel.fit.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    val slots by viewModel.slots.collectAsStateWithLifecycle()
    val slot by viewModel.slot.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()
    var notice by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<ReviewTarget?>(null) }
    val ready = gate?.state == AiState.READY

    LaunchedEffect(outcome) { outcome?.let(onDone) }

    /** A fit check is its own kind of request, so its first send to a provider shows what it sends (TRD §6.2). */
    fun requestCheck() {
        if (!ready || settings?.noticeSeen(AiKind.FIT_CHECK) == true) viewModel.check() else notice = true
    }

    BackHandler(enabled = phase !is FitPhase.Input) {
        if (phase is FitPhase.Checking) viewModel.cancel() else viewModel.backToText()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Will it fit?") },
                navigationIcon = {
                    TextButton(onClick = {
                        when (phase) {
                            FitPhase.Input -> onBack()
                            FitPhase.Checking -> viewModel.cancel()
                            is FitPhase.Result -> viewModel.backToText()
                        }
                    }) { Text("Back") }
                },
            )
        },
    ) { padding ->
        when (val p = phase) {
            FitPhase.Input, FitPhase.Checking -> Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp).imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val checking = p == FitPhase.Checking
                Text("Describe a dish before you order or cook it, and see what it does to today. Nothing is logged unless you say so.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                AiText.notReady(gate)?.let { reason ->
                    Text("${AiText.firstSentence(reason)} ${DescribeText.PARSER_MEANWHILE}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = viewModel::setText,
                    label = { Text("What are you thinking of?") },
                    placeholder = { Text("1 plate paneer butter masala, 2 butter naan") },
                    minLines = 2,
                    enabled = !checking,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Origin", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(Origin.entries, origin, FoodText::origin, { if (!checking) viewModel.setOrigin(it) })
                if (gate?.state == AiState.OFF) {
                    Text(DescribeText.PARSER_ONLY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (checking) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.padding(4.dp))
                        Text(if (ready) "Estimating… usually a few seconds." else "Matching your foods…", Modifier.weight(1f))
                    }
                    OutlinedButton(onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel") }
                } else {
                    Button(onClick = ::requestCheck, enabled = text.isNotBlank() && gate != null, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Check") }
                }
            }
            is FitPhase.Result -> Column(Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                val f = fit
                                when {
                                    p.items.isEmpty() -> Text("Nothing to check yet.", style = MaterialTheme.typography.titleMedium)
                                    f != null -> {
                                        val (main, other) = FitText.lines(f)
                                        Text(main, style = MaterialTheme.typography.titleLarge)
                                        Text(other, style = MaterialTheme.typography.bodyLarge)
                                    }
                                    today == null -> Text("Set your targets to see what's left of today.", style = MaterialTheme.typography.bodyLarge)
                                }
                                if (p.items.isNotEmpty()) {
                                    Text(
                                        "This dish: ${FoodText.kcalProtein(p.items.totals())} · ${FoodText.origin(origin)} · not logged",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    p.notice?.let { n -> item { Text(n, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary) } }
                    if (p.rejected.isNotEmpty()) {
                        item {
                            Text(
                                "Left out, because the estimate gave no usable weight or amount: ${p.rejected.joinToString()}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                    itemsIndexed(p.items) { index, r -> ReviewRow(r, onClick = { target = ReviewTarget.Edit(index) }) }
                    itemsIndexed(p.unmatched) { _, part ->
                        UnmatchedRow(
                            part,
                            onUse = { part.guess?.let { viewModel.resolve(part.typed, it.reviewItem()) } },
                            onSearch = { target = ReviewTarget.Search(part.typed) },
                            onQuickAdd = { target = ReviewTarget.Quick(part.typed) },
                            onSkip = { viewModel.skip(part.typed) },
                        )
                    }
                    itemsIndexed(p.alreadyCounted) { index, r -> CountedRow(r, onAdd = { viewModel.addAnyway(index) }) }
                }
                Column(Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Log it to", style = MaterialTheme.typography.labelLarge)
                    ChoiceChips(slots.filter { !it.removed }, slots.firstOrNull { it.key == slot }, MealSlot::name, { viewModel.setSlot(it.key) })
                    Button(
                        onClick = viewModel::logIt,
                        enabled = p.items.isNotEmpty() && slot != null && !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) { Text("Log it") }
                }
            }
        }
    }

    if (notice) {
        AlertDialog(
            onDismissRequest = { notice = false },
            title = { Text("Before your first check") },
            text = { Text(settings?.let(AiText::fitPrivacy).orEmpty()) },
            confirmButton = {
                TextButton(onClick = {
                    notice = false
                    viewModel.noticeSeen()
                    viewModel.check()
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { notice = false }) { Text("Cancel") } },
        )
    }
    ReviewSheets(target, (phase as? FitPhase.Result)?.items.orEmpty(), slots, origin, viewModel) { target = null }
}
