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
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.recomp.data.db.FoodEntity
import app.recomp.data.db.FoodLogEntity
import app.recomp.data.db.LoggedDayRow
import app.recomp.data.prefs.DayBoundary
import app.recomp.data.repo.DayLog
import app.recomp.data.repo.FoodLogRepository
import app.recomp.data.repo.FoodRepository
import app.recomp.data.repo.MealGroup
import app.recomp.data.repo.NutritionRepository
import app.recomp.domain.food.Macros
import app.recomp.domain.food.MealSlot
import app.recomp.domain.food.MealSlots
import app.recomp.domain.food.Origin
import app.recomp.domain.food.PortionUnit
import app.recomp.domain.nutrition.ProteinGoal
import app.recomp.ui.components.ConfirmDialog
import app.recomp.ui.components.MenuItem
import app.recomp.ui.components.OverflowMenu
import app.recomp.ui.components.TextEntryDialog
import app.recomp.ui.onboarding.GoalText
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FoodViewModel @Inject constructor(
    private val log: FoodLogRepository,
    private val foods: FoodRepository,
    private val nutrition: NutritionRepository,
    days: DayBoundary,
) : ViewModel() {
    /** Today's log date (PR-03), rolling over while the screen is open. */
    val today: StateFlow<LocalDate> = days.observeToday().stateIn(viewModelScope, SharingStarted.Eagerly, LocalDate.now())

    /** A past day you moved to with ‹; null follows today. */
    private val picked = MutableStateFlow<LocalDate?>(null)

    val date: StateFlow<LocalDate> = combine(today, picked) { t, p -> if (p != null && p.isBefore(t)) p else t }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LocalDate.now())

    val day: StateFlow<DayLog?> = date.flatMapLatest { log.observeDay(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val slots: StateFlow<List<MealSlot>> = log.mealSlots.stateIn(viewModelScope, SharingStarted.Eagerly, MealSlots.DEFAULT)

    init {
        // Logged food may complete the protein ramp's baseline week (TRD §5.3).
        viewModelScope.launch { nutrition.lockRampBaseline() }
    }

    fun previous() {
        picked.value = date.value.minusDays(1)
    }

    /** Never past today: no logging ahead. */
    fun next() {
        val n = date.value.plusDays(1)
        picked.value = n.takeIf { it.isBefore(today.value) }
    }

    fun goTo(d: LocalDate) {
        picked.value = d.takeIf { it.isBefore(today.value) }
    }

    suspend fun food(id: Long): FoodEntity? = foods.food(id)

    suspend fun units(foodId: Long): List<PortionUnit> = foods.units(foodId)

    suspend fun unitsForEntry(entry: FoodLogEntity): List<PortionUnit> = foods.unitsForEntry(entry)

    suspend fun calibrate(foodId: Long, label: String, grams: Double) = foods.calibrate(foodId, label, grams)

    fun update(id: Long, quantity: Double, unit: PortionUnit, origin: Origin, slot: String) = viewModelScope.launch { log.update(id, quantity, unit, origin, slot) }

    fun updateQuickAdd(id: Long, v: QuickAddValues) =
        viewModelScope.launch { log.updateQuickAdd(id, v.name, v.kcal, v.proteinG, v.carbsG, v.fatG, v.origin, v.slot ?: return@launch) }

    suspend fun delete(id: Long): List<FoodLogEntity> = log.delete(listOf(id))

    suspend fun restore(rows: List<FoodLogEntity>) = log.restore(rows)

    suspend fun mealNamed(name: String) = log.savedMealNamed(name)

    suspend fun saveMeal(name: String, slot: String) = log.saveMeal(name, date.value, slot)

    suspend fun daysBefore(): List<LoggedDayRow> = log.daysBefore(date.value)

    suspend fun entriesOn(d: LocalDate): List<FoodLogEntity> = log.day(d)

    suspend fun copy(from: LocalDate, fromSlot: String?, toSlot: String?): List<Long> = log.copy(from, fromSlot, date.value, toSlot)

    suspend fun undoInsert(ids: List<Long>) = log.undoInsert(ids)
}

/** What *Copy from another day* copies into: one meal ([slot]) or the whole day. */
private data class CopyTarget(val slot: MealSlot?)

/** An entry being edited, with what its sheet needs. */
private sealed interface Editing {
    data class Portion(val entry: FoodLogEntity, val state: PortionSheetState, val foodId: Long) : Editing
    data class Quick(val entry: FoodLogEntity) : Editing
}

/** The Food tab: the daily view (NU-16), with each meal's + (J3), editing, saved meals and copy (NU-13). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodScreen(onAdd: (LocalDate, String) -> Unit, viewModel: FoodViewModel = hiltViewModel()) {
    val day by viewModel.day.collectAsStateWithLifecycle()
    val date by viewModel.date.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    val slots by viewModel.slots.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Editing?>(null) }
    var copying by remember { mutableStateOf<CopyTarget?>(null) }
    var naming by remember { mutableStateOf<MealSlot?>(null) }
    var replacing by remember { mutableStateOf<Pair<String, MealSlot>?>(null) }

    fun undoable(message: String, undo: suspend () -> Unit) = scope.launch {
        if (snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) undo()
    }

    fun edit(entry: FoodLogEntity) = scope.launch {
        val foodId = entry.foodId
        val grams = entry.grams
        editing = if (foodId == null || grams == null || viewModel.food(foodId) == null) {
            Editing.Quick(entry)
        } else {
            // The entry's unit at the weight it was logged with, so a later recalibration never rewrites it.
            val units = viewModel.unitsForEntry(entry)
            val food = viewModel.food(foodId)!!
            Editing.Portion(entry, PortionSheetState(entry.name, food.caveat, units, entry.quantity, units.first { it.label == entry.unitLabel }, entry.origin, entry.mealSlot), foodId)
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = viewModel::previous, modifier = Modifier.semantics { contentDescription = "Previous day" }) {
                        Text("‹", style = MaterialTheme.typography.headlineSmall)
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Food", style = MaterialTheme.typography.headlineMedium)
                        Text(FoodText.dayLong(date, today), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(
                        onClick = viewModel::next,
                        enabled = date.isBefore(today),
                        modifier = Modifier.semantics { contentDescription = "Next day" },
                    ) { Text("›", style = MaterialTheme.typography.headlineSmall) }
                    OverflowMenu("Day options") { close ->
                        MenuItem("Copy from another day") { close(); copying = CopyTarget(null) }
                        if (date != today) MenuItem("Go to today") { close(); viewModel.goTo(today) }
                    }
                }
            }
            val d = day ?: return@LazyColumn
            item { SummaryCard(d) }
            items(d.meals, key = { it.slot.key }) { meal ->
                MealCard(
                    meal,
                    onAdd = { onAdd(date, meal.slot.key) },
                    onEdit = ::edit,
                    onSave = { naming = meal.slot },
                    onCopy = { copying = CopyTarget(meal.slot) },
                )
            }
        }
    }

    when (val e = editing) {
        null -> Unit
        is Editing.Portion -> PortionSheet(
            state = e.state,
            slots = slots,
            macrosFor = { q, unit -> scaled(e.entry, q * unit.grams) },
            onCalibrate = { label, grams ->
                scope.launch {
                    // Changing the unit here is explicit, so this entry takes the new weight too.
                    viewModel.calibrate(e.foodId, label, grams)
                    editing = e.copy(state = e.state.copy(units = viewModel.units(e.foodId)))
                }
            },
            onDismiss = { editing = null },
            onDelete = {
                editing = null
                scope.launch {
                    val rows = viewModel.delete(e.entry.id)
                    undoable("Deleted ${e.entry.name}") { viewModel.restore(rows) }
                }
            },
        ) { q, unit, origin, slot ->
            editing = null
            viewModel.update(e.entry.id, q, unit, origin, slot ?: e.entry.mealSlot)
        }
        is Editing.Quick -> QuickAddSheet(
            initial = QuickAddValues(e.entry.name, e.entry.kcal, e.entry.proteinG, e.entry.carbsG, e.entry.fatG, e.entry.origin, e.entry.mealSlot),
            slots = slots,
            onDismiss = { editing = null },
            onDelete = {
                editing = null
                scope.launch {
                    val rows = viewModel.delete(e.entry.id)
                    undoable("Deleted ${e.entry.name}") { viewModel.restore(rows) }
                }
            },
        ) { values ->
            editing = null
            viewModel.updateQuickAdd(e.entry.id, values)
        }
    }

    copying?.let { target ->
        CopySheet(
            target = target,
            date = date,
            today = today,
            slots = slots,
            loadDays = viewModel::daysBefore,
            loadDay = viewModel::entriesOn,
            onDismiss = { copying = null },
        ) { from, fromSlot ->
            copying = null
            scope.launch {
                val ids = viewModel.copy(from, fromSlot, target.slot?.key)
                val into = target.slot?.name ?: FoodText.day(date, today).lowercase()
                undoable("Copied ${ids.size} ${if (ids.size == 1) "item" else "items"} into $into") { viewModel.undoInsert(ids) }
            }
        }
    }

    naming?.let { slot ->
        TextEntryDialog("Save ${slot.name} as a meal", "", onDismiss = { naming = null }) { name ->
            naming = null
            if (name.isBlank()) return@TextEntryDialog
            scope.launch {
                if (viewModel.mealNamed(name) != null) {
                    replacing = name to slot
                } else {
                    viewModel.saveMeal(name, slot.key)
                    snackbar.showSnackbar("Saved “${name.trim()}” to My meals")
                }
            }
        }
    }
    replacing?.let { (name, slot) ->
        ConfirmDialog(
            "Replace “${name.trim()}”?",
            "My meals already has a meal with this name. Its items will be replaced by today's ${slot.name.lowercase()}.",
            confirm = "Replace",
            onDismiss = { replacing = null },
        ) {
            replacing = null
            scope.launch {
                viewModel.saveMeal(name, slot.key)
                snackbar.showSnackbar("Saved “${name.trim()}” to My meals")
            }
        }
    }
}

/** An edited entry's macros scale from its own snapshot (TRD §4), never from the food's current values. */
private fun scaled(entry: FoodLogEntity, grams: Double): Macros {
    val ratio = entry.grams?.takeIf { it > 0 }?.let { grams / it } ?: 1.0
    return Macros(entry.kcal * ratio, entry.proteinG * ratio, entry.carbsG?.times(ratio), entry.fatG?.times(ratio), entry.fiberG?.times(ratio))
}

/** NU-16: calories and protein remaining, the macro split against targets, fibre. Over is a plain number. */
@Composable
private fun SummaryCard(day: DayLog) {
    val t = day.totals
    val targets = day.targets
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val protein = targets?.protein
                val proteinTarget = protein?.todayG
                Column(Modifier.weight(1f)) {
                    if (proteinTarget != null) {
                        val left = proteinTarget - t.proteinG.roundToInt()
                        Text("Protein left", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (left >= 0) "$left g" else "${abs(left)} g over", style = MaterialTheme.typography.headlineMedium)
                        Text("of $proteinTarget g" + if (protein is ProteinGoal.Ramping) " this week" else "", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("Protein", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(FoodText.g(t.proteinG), style = MaterialTheme.typography.headlineMedium)
                        if (protein is ProteinGoal.Measuring) Text("No target this week: measuring", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(Modifier.weight(1f)) {
                    if (targets != null) {
                        val left = targets.target.kcal - t.kcal.roundToInt()
                        Text("Calories left", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (left >= 0) GoalText.kcal(left) else "${GoalText.kcal(-left)} over", style = MaterialTheme.typography.headlineMedium)
                        Text("of ${GoalText.kcal(targets.target.kcal)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("Calories", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(GoalText.kcal(t.kcal), style = MaterialTheme.typography.headlineMedium)
                    }
                }
            }
            if (targets != null) {
                MacroBar("Protein", t.proteinG, targets.protein.todayG ?: targets.target.proteinG)
                MacroBar("Carbs", t.carbsG, targets.target.carbsG)
                MacroBar("Fat", t.fatG, targets.target.fatG)
            }
            val notes = buildList {
                add("Fibre ${FoodText.g(t.fiberG)}")
                if (t.unsplitKcal > 0) add("${GoalText.kcal(t.unsplitKcal)} from quick adds isn't split")
            }
            Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MacroBar(label: String, eaten: Double, target: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("${eaten.roundToInt()} of $target g", style = MaterialTheme.typography.bodyMedium)
        }
        // A full bar once you're there; over is the same colour, never failure styling.
        LinearProgressIndicator(progress = { if (target > 0) (eaten / target).toFloat().coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun MealCard(meal: MealGroup, onAdd: () -> Unit, onEdit: (FoodLogEntity) -> Unit, onSave: () -> Unit, onCopy: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(meal.slot.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (meal.entries.isEmpty()) "Nothing yet" else FoodText.kcalProtein(Macros(meal.totals.kcal, meal.totals.proteinG)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OverflowMenu("${meal.slot.name} options") { close ->
                    MenuItem("Save as meal", enabled = meal.entries.isNotEmpty()) { close(); onSave() }
                    MenuItem("Copy from another day") { close(); onCopy() }
                }
                if (!meal.slot.removed) {
                    FilledTonalButton(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Add to ${meal.slot.name}" }) { Text("+ Add") }
                }
            }
            meal.entries.forEach { e ->
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = "Edit ${e.name}") { onEdit(e) }.padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(e.name, style = MaterialTheme.typography.bodyLarge)
                        val details = listOf(FoodText.amount(e), if (e.origin != Origin.HOME) FoodText.origin(e.origin) else "").filter { it.isNotEmpty() }
                        if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(GoalText.kcal(e.kcal), style = MaterialTheme.typography.bodyMedium)
                        Text("${FoodText.g(e.proteinG)} protein", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * *Copy from another day* (NU-13): the last 14 days with food logged, or any earlier date. For a meal it
 * copies that day's same meal in one tap; for the whole day it asks whether to copy all of it or one meal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CopySheet(
    target: CopyTarget,
    date: LocalDate,
    today: LocalDate,
    slots: List<MealSlot>,
    loadDays: suspend () -> List<LoggedDayRow>,
    loadDay: suspend (LocalDate) -> List<FoodLogEntity>,
    onDismiss: () -> Unit,
    onCopy: (from: LocalDate, fromSlot: String?) -> Unit,
) {
    var days by remember { mutableStateOf<List<LoggedDayRow>?>(null) }
    var chosen by remember { mutableStateOf<Pair<LocalDate, List<FoodLogEntity>>?>(null) }
    var picking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { days = loadDays() }

    fun choose(d: LocalDate) = scope.launch { chosen = d to loadDay(d) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Text(
                    "Copy into ${target.slot?.name?.let { "$it, " }.orEmpty()}${FoodText.day(date, today).lowercase()}",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            val picked = chosen
            if (picked != null) {
                val (from, rows) = picked
                item { Text(FoodText.dayLong(from, today), style = MaterialTheme.typography.titleMedium) }
                val slotRows = rows.groupBy { it.mealSlot }
                if (target.slot != null) {
                    val mine = slotRows[target.slot.key].orEmpty()
                    item {
                        if (mine.isEmpty()) {
                            Text("Nothing in ${target.slot.name} that day.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            FilledTonalButton(onClick = { onCopy(from, target.slot.key) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                                Text("Copy ${target.slot.name} · ${mine.size} ${if (mine.size == 1) "item" else "items"}")
                            }
                        }
                    }
                } else {
                    item {
                        FilledTonalButton(onClick = { onCopy(from, null) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text("Whole day · ${rows.size} ${if (rows.size == 1) "item" else "items"}")
                        }
                    }
                    items(MealSlots.forDay(slots, slotRows.keys).filter { it.key in slotRows }, key = { it.key }) { slot ->
                        val n = slotRows.getValue(slot.key).size
                        OutlinedButton(onClick = { onCopy(from, slot.key) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("Only ${slot.name} · $n ${if (n == 1) "item" else "items"}")
                        }
                    }
                }
                item { TextButton(onClick = { chosen = null }) { Text("Another day") } }
            } else {
                val list = days
                if (list == null) {
                    item { Text("Loading…") }
                } else {
                    if (list.isEmpty()) item { Text("No earlier days with food logged.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(list, key = { it.logDate.toString() }) { row ->
                        Column(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClickLabel = "Copy from ${FoodText.day(row.logDate, today)}") { choose(row.logDate) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(FoodText.dayLong(row.logDate, today), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${row.entries} ${if (row.entries == 1) "item" else "items"} · ${FoodText.kcalProtein(Macros(row.kcal, row.proteinG))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider()
                    }
                    item { TextButton(onClick = { picking = true }) { Text("Pick a date…") } }
                }
            }
        }
    }

    if (picking) {
        val limit = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.minusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis < limit
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    picking = false
                    state.selectedDateMillis?.let { choose(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}
