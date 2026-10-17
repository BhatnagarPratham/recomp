package app.recomp.ui.food

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.recomp.data.db.FoodEntity
import app.recomp.data.db.SavedMealWithItems
import app.recomp.data.prefs.DayBoundary
import app.recomp.data.repo.AiGateView
import app.recomp.data.repo.AiRepository
import app.recomp.data.repo.CustomFood
import app.recomp.data.repo.FoodFilter
import app.recomp.data.repo.FoodHit
import app.recomp.data.repo.FoodLogRepository
import app.recomp.data.repo.FoodRepository
import app.recomp.data.repo.MealGroup
import app.recomp.domain.food.FoodDiet
import app.recomp.domain.food.FoodSource
import app.recomp.domain.food.Macros
import app.recomp.domain.food.MealSlot
import app.recomp.domain.food.MealSlots
import app.recomp.domain.food.Origin
import app.recomp.domain.food.PortionUnit
import app.recomp.domain.food.Portions
import app.recomp.domain.persona.Diet
import app.recomp.domain.persona.FoodRegion
import app.recomp.ui.components.ConfirmDialog
import app.recomp.ui.components.MenuItem
import app.recomp.ui.components.OverflowMenu
import app.recomp.ui.components.TextEntryDialog
import app.recomp.domain.ai.AiState
import app.recomp.ui.navigation.AddFoodRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The list under the search box: your recent foods, the basics, or search results. */
data class FoodResults(val query: String, val heading: String, val hits: List<FoodHit>)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class AddFoodViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val foods: FoodRepository,
    private val log: FoodLogRepository,
    ai: AiRepository,
    days: DayBoundary,
) : ViewModel() {
    private val route = savedState.toRoute<AddFoodRoute>()
    val date: LocalDate = LocalDate.parse(route.date)
    val slot: String = route.slot

    val query = MutableStateFlow("")

    /** Bumped after a change the results depend on (a log, a hide, a new food). */
    private val version = MutableStateFlow(0)

    val today: StateFlow<LocalDate> = days.observeToday().stateIn(viewModelScope, SharingStarted.Eagerly, LocalDate.now())

    val results: StateFlow<FoodResults?> = combine(query.debounce(120), version, foods.filter) { q, _, _ -> q }
        .mapLatest { q ->
            if (q.isBlank()) {
                val s = foods.suggestions()
                FoodResults(q, if (s.recent) "Recent" else "Protein-rich basics", s.hits)
            } else {
                FoodResults(q, "Results", foods.search(q))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val savedMeals: StateFlow<List<SavedMealWithItems>> = log.observeSavedMeals().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The meal you're adding to, so the screen shows what's in it so far. */
    val meal: StateFlow<MealGroup?> = log.observeDay(date).map { day -> day.meals.firstOrNull { it.slot.key == slot } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val slots: StateFlow<List<MealSlot>> = log.mealSlots.stateIn(viewModelScope, SharingStarted.Eagerly, MealSlots.DEFAULT)

    val filter: StateFlow<FoodFilter> = foods.filter.stateIn(viewModelScope, SharingStarted.Eagerly, FoodFilter(Diet.NON_VEGETARIAN, FoodRegion.INDIAN))

    val household: StateFlow<Map<String, Double>> = foods.household.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Describe it shows unless AI is off (AI-01); when AI isn't ready its screen keeps a draft instead (AI-02). */
    val gate: StateFlow<AiGateView?> = ai.gate.stateIn(viewModelScope, SharingStarted.Eagerly, null)


    fun refresh() {
        version.value++
    }

    suspend fun portion(foodId: Long) = foods.portion(foodId)

    suspend fun units(foodId: Long) = foods.units(foodId)

    suspend fun calibrate(foodId: Long, label: String, grams: Double) = foods.calibrate(foodId, label, grams)

    suspend fun log(food: FoodEntity, quantity: Double, unit: PortionUnit, origin: Origin): Long =
        log.log(date, slot, food, quantity, unit, origin).also { refresh() }

    suspend fun logMeal(mealId: Long): List<Long> = log.logSavedMeal(mealId, date, slot).also { refresh() }

    suspend fun quickAdd(v: QuickAddValues): Long = log.quickAdd(date, slot, v.name, v.kcal, v.proteinG, v.carbsG, v.fatG, v.origin)

    suspend fun undo(ids: List<Long>) = log.undoInsert(ids)

    suspend fun undoMeal(mealId: Long, ids: List<Long>) = log.undoSavedMeal(mealId, ids)

    suspend fun hide(id: Long) = foods.hide(id).also { refresh() }

    suspend fun restore(id: Long) = foods.restore(id).also { refresh() }

    suspend fun defaultDiet(): FoodDiet = foods.defaultDiet()

    suspend fun create(food: CustomFood): Long = foods.createCustom(food).also { refresh() }

    suspend fun update(id: Long, food: CustomFood) = foods.updateCustom(id, food).also { refresh() }

    /** A custom food as the form edits it: per 100 g, with its serving unit. */
    suspend fun custom(id: Long): CustomFood? {
        val f = foods.food(id)?.takeIf { it.source == FoodSource.CUSTOM } ?: return null
        return CustomFood(f.name, f.brand, f.per100g, f.diet, foods.serving(id)?.let { it.label to it.grams })
    }

    fun renameMeal(id: Long, name: String) = viewModelScope.launch { log.renameMeal(id, name) }

    fun deleteMeal(id: Long) = viewModelScope.launch { log.deleteMeal(id) }
}

/** What the portion sheet is logging. */
private data class Logging(val food: FoodEntity, val state: PortionSheetState)

/** A custom food being created ([id] null) or edited. */
private data class FoodForm(val id: Long?, val initial: CustomFood?, val name: String, val diet: FoodDiet)

/**
 * Adding food to a meal (J3): search, My meals in one tap, Quick add and New food. It stays open after
 * each log, with Undo, so a meal of several foods is several quick logs; Done goes back to the day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFoodScreen(
    onDone: () -> Unit,
    onDescribe: (LocalDate, String) -> Unit,
    describeOutcome: DescribeOutcome?,
    onDescribeOutcomeShown: () -> Unit,
    viewModel: AddFoodViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val savedMeals by viewModel.savedMeals.collectAsStateWithLifecycle()
    val meal by viewModel.meal.collectAsStateWithLifecycle()
    val slots by viewModel.slots.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val household by viewModel.household.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    val gate by viewModel.gate.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var logging by remember { mutableStateOf<Logging?>(null) }
    var quickAdding by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf<FoodForm?>(null) }
    var renaming by remember { mutableStateOf<SavedMealWithItems?>(null) }
    var deleting by remember { mutableStateOf<SavedMealWithItems?>(null) }
    val slotName = slots.firstOrNull { it.key == viewModel.slot }?.name ?: viewModel.slot

    fun undoable(message: String, undo: suspend () -> Unit) = scope.launch {
        snackbar.currentSnackbarData?.dismiss()
        if (snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) undo()
    }

    // What Describe it said when it came back: the saved estimate (with Undo), or why it became a draft.
    LaunchedEffect(describeOutcome) {
        val outcome = describeOutcome ?: return@LaunchedEffect
        onDescribeOutcomeShown()
        viewModel.refresh()
        // In the screen's scope: clearing the outcome restarts this effect, which mustn't cancel the snackbar.
        if (outcome.undoIds.isEmpty()) scope.launch { snackbar.showSnackbar(outcome.message, duration = SnackbarDuration.Long) }
        else undoable(outcome.message) { viewModel.undo(outcome.undoIds) }
    }

    fun open(foodId: Long) = scope.launch {
        val choice = viewModel.portion(foodId) ?: return@launch
        logging = Logging(choice.food, PortionSheetState(choice.food.name, choice.food.caveat, choice.units, choice.quantity, choice.unit, choice.origin))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add to $slotName" + if (viewModel.date != today) " · ${FoodText.day(viewModel.date, today)}" else "") },
                navigationIcon = { TextButton(onClick = onDone) { Text("Back") } },
                actions = { TextButton(onClick = onDone) { Text("Done") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            meal?.takeIf { it.entries.isNotEmpty() }?.let { m ->
                item {
                    Text(
                        "$slotName so far: ${m.entries.size} ${if (m.entries.size == 1) "item" else "items"} · ${FoodText.kcalProtein(Macros(m.totals.kcal, m.totals.proteinG))}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { viewModel.query.value = it },
                    label = { Text("Search foods") },
                    singleLine = true,
                    trailingIcon = if (query.isNotEmpty()) {
                        { TextButton(onClick = { viewModel.query.value = "" }, modifier = Modifier.semantics { contentDescription = "Clear search" }) { Text("✕") } }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                val state = gate?.state
                if (state != null && state != AiState.OFF) {
                    FilledTonalButton(
                        onClick = { onDescribe(viewModel.date, viewModel.slot) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 4.dp),
                    ) { Text(if (state == AiState.READY) "Describe it" else "Describe it · keep a draft") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    OutlinedButton(onClick = { quickAdding = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Quick add") }
                    OutlinedButton(
                        onClick = { scope.launch { form = FoodForm(null, null, query.trim(), viewModel.defaultDiet()) } },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text("New food") }
                }
            }

            val words = query.trim().lowercase()
            val meals = savedMeals.filter { words.isEmpty() || it.meal.name.lowercase().contains(words) }
            if (meals.isNotEmpty()) {
                item { Heading("My meals") }
                items(meals, key = { "meal-${it.meal.id}" }) { m ->
                    SavedMealRow(
                        m,
                        onLog = {
                            scope.launch {
                                val ids = viewModel.logMeal(m.meal.id)
                                undoable("Added ${m.meal.name} · ${ids.size} ${if (ids.size == 1) "item" else "items"}") { viewModel.undoMeal(m.meal.id, ids) }
                            }
                        },
                        onRename = { renaming = m },
                        onDelete = { deleting = m },
                    )
                }
            }

            val r = results
            if (r != null) {
                if (r.hits.isNotEmpty()) item { Heading(r.heading) }
                items(r.hits, key = { "food-${it.food.id}" }) { hit ->
                    FoodRow(
                        hit,
                        onOpen = { open(hit.food.id) },
                        onEdit = if (hit.food.source == FoodSource.CUSTOM) {
                            { scope.launch { form = FoodForm(hit.food.id, viewModel.custom(hit.food.id), hit.food.name, hit.food.diet) } }
                        } else {
                            null
                        },
                        onHide = {
                            scope.launch {
                                viewModel.hide(hit.food.id)
                                undoable("Hid ${hit.food.name}. Settings → Food restores it.") { viewModel.restore(hit.food.id) }
                            }
                        },
                    )
                }
                if (r.query.isNotBlank() && r.hits.isEmpty()) {
                    item {
                        Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("No food matches “${r.query.trim()}”.", style = MaterialTheme.typography.bodyLarge)
                            Text("Quick add it, or save it as a new food.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    logging?.let { l ->
        PortionSheet(
            state = l.state,
            slots = slots,
            macrosFor = { q, unit -> l.food.per100g.forGrams(q * unit.grams) },
            onCalibrate = { label, grams ->
                scope.launch {
                    viewModel.calibrate(l.food.id, label, grams)
                    logging = l.copy(state = l.state.copy(units = viewModel.units(l.food.id)))
                }
            },
            onDismiss = { logging = null },
        ) { q, unit, origin, _ ->
            logging = null
            scope.launch {
                val id = viewModel.log(l.food, q, unit, origin)
                viewModel.query.value = ""
                val amount = if (unit.label == Portions.GRAMS) "${q.toInt()} g" else "${Portions.format(q)} ${unit.label}"
                undoable("Added $amount ${l.food.name}") { viewModel.undo(listOf(id)) }
            }
        }
    }

    if (quickAdding) {
        QuickAddSheet(initial = null, slots = slots, onDismiss = { quickAdding = false }) { values ->
            quickAdding = false
            scope.launch {
                val id = viewModel.quickAdd(values)
                undoable("Added ${values.name?.trim()?.ifEmpty { null } ?: "a quick add"}") { viewModel.undo(listOf(id)) }
            }
        }
    }

    form?.let { f ->
        CustomFoodSheet(
            initial = f.initial,
            initialName = f.name,
            servingUnits = Portions.servingUnits(filter.region),
            household = household,
            defaultDiet = f.diet,
            onDismiss = { form = null },
        ) { food ->
            form = null
            scope.launch {
                if (f.id == null) {
                    // A new food is usually about to be logged: open its portion sheet.
                    open(viewModel.create(food))
                } else {
                    viewModel.update(f.id, food)
                    snackbar.showSnackbar("Saved ${food.name}. Entries already logged keep their numbers.")
                }
            }
        }
    }

    renaming?.let { m ->
        TextEntryDialog("Rename meal", m.meal.name, onDismiss = { renaming = null }) { name ->
            renaming = null
            if (name.isNotBlank()) viewModel.renameMeal(m.meal.id, name)
        }
    }
    deleting?.let { m ->
        ConfirmDialog("Delete “${m.meal.name}”?", "It's removed from My meals. Food you've already logged stays.", confirm = "Delete", onDismiss = { deleting = null }) {
            deleting = null
            viewModel.deleteMeal(m.meal.id)
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun SavedMealRow(m: SavedMealWithItems, onLog: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val kcal = m.items.sumOf { it.kcal }
    val protein = m.items.sumOf { it.proteinG }
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(
            onClick = onLog,
            modifier = Modifier.weight(1f).heightIn(min = 56.dp).semantics { contentDescription = "Log ${m.meal.name}" },
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(m.meal.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${m.items.size} ${if (m.items.size == 1) "item" else "items"} · ${FoodText.kcalProtein(Macros(kcal, protein))}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        OverflowMenu("${m.meal.name} options") { close ->
            MenuItem("Rename") { close(); onRename() }
            MenuItem("Delete") { close(); onDelete() }
        }
    }
}

@Composable
private fun FoodRow(hit: FoodHit, onOpen: () -> Unit, onEdit: (() -> Unit)?, onHide: () -> Unit) {
    val food = hit.food
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).heightIn(min = 56.dp).clickable(onClickLabel = "Log ${food.name}", onClick = onOpen).padding(vertical = 8.dp)) {
            Text(food.name + if (food.source == FoodSource.CUSTOM) " · yours" else "", style = MaterialTheme.typography.bodyLarge)
            Text(FoodText.serving(food, hit.serving), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (food.caveat != null) Text("⚠ Frying oil counted in full; likely reads high", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
        OverflowMenu("${food.name} options") { close ->
            if (onEdit != null) MenuItem("Edit") { close(); onEdit() }
            MenuItem("Hide from search") { close(); onHide() }
        }
    }
    HorizontalDivider()
}
