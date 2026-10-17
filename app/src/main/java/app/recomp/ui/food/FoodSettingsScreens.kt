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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.data.repo.FoodRepository
import app.recomp.domain.food.MealSlot
import app.recomp.domain.food.MealSlots
import app.recomp.domain.food.Portions
import app.recomp.domain.persona.FoodRegion
import app.recomp.ui.components.MenuItem
import app.recomp.ui.components.NumberEntryDialog
import app.recomp.ui.components.OverflowMenu
import app.recomp.ui.components.TextEntryDialog
import app.recomp.ui.onboarding.GoalText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Settings → Food (PRD §7.1): meals, portion sizes, hidden foods and where the food data comes from. */
@HiltViewModel
class FoodSettingsViewModel @Inject constructor(
    private val prefs: SettingsPrefs,
    private val foods: FoodRepository,
) : ViewModel() {
    val slots: StateFlow<List<MealSlot>> = prefs.mealSlots.stateIn(viewModelScope, SharingStarted.Eagerly, MealSlots.DEFAULT)

    val region: StateFlow<FoodRegion> = foods.filter.map { it.region }.stateIn(viewModelScope, SharingStarted.Eagerly, FoodRegion.INDIAN)

    val household: StateFlow<Map<String, Double>> = foods.household.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val hidden: StateFlow<List<FoodEntity>> = foods.observeHidden().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val indbCount: StateFlow<Int?> = foods.observeIndbCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun editSlots(transform: (List<MealSlot>) -> List<MealSlot>) = viewModelScope.launch { prefs.edit { mealSlots = transform(mealSlots) } }

    fun rename(key: String, name: String) = editSlots { slots -> slots.map { if (it.key == key) it.copy(name = name.trim()) else it } }

    fun add(name: String) = editSlots { slots -> slots + MealSlot(MealSlots.newKey(slots), name.trim()) }

    /** Keeps at least one meal you can log to. A removed meal keeps its past entries. */
    fun setRemoved(key: String, removed: Boolean) = editSlots { slots ->
        if (removed && slots.count { !it.removed } <= 1) slots else slots.map { if (it.key == key) it.copy(removed = removed) else it }
    }

    fun move(key: String, by: Int) = editSlots { slots ->
        val i = slots.indexOfFirst { it.key == key }
        val j = i + by
        if (i < 0 || j !in slots.indices) slots else slots.toMutableList().apply { add(j, removeAt(i)) }
    }

    fun setMeasure(label: String, grams: Double?) = viewModelScope.launch { foods.setHouseholdMeasure(label, grams) }

    fun restore(id: Long) = viewModelScope.launch { foods.restore(id) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { content(it) }
}

/** NU-10: rename, add, remove and reorder meals. Entries follow a meal's key, so renaming keeps history. */
@Composable
fun MealSlotsScreen(onBack: () -> Unit, viewModel: FoodSettingsViewModel = hiltViewModel()) {
    val slots by viewModel.slots.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<MealSlot?>(null) }
    var adding by remember { mutableStateOf(false) }

    SettingsPage("Meals", onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
            item {
                Text(
                    "A removed meal isn't offered for new food, but its past entries stay where they are.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            itemsIndexed(slots, key = { _, s -> s.key }) { i, slot ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable(onClickLabel = "Rename ${slot.name}") { renaming = slot }.padding(vertical = 8.dp)) {
                        Text(slot.name, style = MaterialTheme.typography.bodyLarge, color = if (slot.removed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        if (slot.removed) Text("Removed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { viewModel.move(slot.key, -1) }, enabled = i > 0, modifier = Modifier.semantics { contentDescription = "Move ${slot.name} up" }) { Text("↑") }
                    TextButton(onClick = { viewModel.move(slot.key, 1) }, enabled = i < slots.lastIndex, modifier = Modifier.semantics { contentDescription = "Move ${slot.name} down" }) { Text("↓") }
                    OverflowMenu("${slot.name} options") { close ->
                        MenuItem("Rename") { close(); renaming = slot }
                        if (slot.removed) {
                            MenuItem("Bring back") { close(); viewModel.setRemoved(slot.key, false) }
                        } else {
                            MenuItem("Remove", enabled = slots.count { !it.removed } > 1) { close(); viewModel.setRemoved(slot.key, true) }
                        }
                    }
                }
                HorizontalDivider()
            }
            item {
                OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp)) { Text("Add a meal") }
            }
        }
    }

    renaming?.let { slot ->
        TextEntryDialog("Rename ${slot.name}", slot.name, onDismiss = { renaming = null }) { name ->
            renaming = null
            if (name.isNotBlank()) viewModel.rename(slot.key, name)
        }
    }
    if (adding) {
        TextEntryDialog("Add a meal", "", onDismiss = { adding = false }) { name ->
            adding = false
            if (name.isNotBlank()) viewModel.add(name)
        }
    }
}

/** NU-12: the region's household measures, calibrated to your utensils. */
@Composable
fun PortionSizesScreen(onBack: () -> Unit, viewModel: FoodSettingsViewModel = hiltViewModel()) {
    val region by viewModel.region.collectAsStateWithLifecycle()
    val household by viewModel.household.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<String?>(null) }
    val defaults = Portions.householdDefaults(region)

    SettingsPage("Portion sizes", onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
            item {
                Text(
                    "Every food offers these measures. Weigh one of yours once to make them match your utensils; 1 ml counts as 1 g. " +
                        "A food's own unit, or one you change from its portion sheet, takes precedence.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            items(household.keys.toList(), key = { it }) { label ->
                val grams = household.getValue(label)
                val yours = grams != defaults[label]
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClickLabel = "Change $label") { editing = label },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("1 $label", style = MaterialTheme.typography.bodyLarge)
                        if (yours) Text("Default ${defaults[label]?.roundToInt()} g", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (yours) TextButton(onClick = { viewModel.setMeasure(label, null) }) { Text("Reset") }
                    Text("${grams.roundToInt()} g", color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider()
            }
        }
    }

    editing?.let { label ->
        NumberEntryDialog("Grams in 1 $label", "${(household[label] ?: 0.0).roundToInt()}", decimal = true, onDismiss = { editing = null }, suffix = "g") { text ->
            editing = null
            parseNumber(text)?.takeIf { it in 1.0..3000.0 }?.let { viewModel.setMeasure(label, it) }
        }
    }
}

/** Foods you hid from search, e.g. one tagged with the wrong diet (PRD §7.7). */
@Composable
fun HiddenFoodsScreen(onBack: () -> Unit, viewModel: FoodSettingsViewModel = hiltViewModel()) {
    val hidden by viewModel.hidden.collectAsStateWithLifecycle()
    SettingsPage("Hidden foods", onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
            if (hidden.isEmpty()) item { Text("Nothing hidden. Hide a food from its menu in search.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(hidden, key = { it.id }) { food ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(food.name, style = MaterialTheme.typography.bodyLarge)
                        Text(FoodText.diet(food.diet), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { viewModel.restore(food.id) }) { Text("Restore") }
                }
                HorizontalDivider()
            }
        }
    }
}

/** Where the food data comes from (TRD §6.4), including INDB's citation. */
@Composable
fun FoodDataDialog(indbCount: Int?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Food data") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when (indbCount) {
                        null -> ""
                        0 -> "This build has no Indian dishes: it was built without INDB."
                        else -> "${GoalText.n(indbCount)} Indian dishes from INDB, the Indian Nutrient Databank by Lindsay Jaacks and colleagues " +
                            "(github.com/lindsayjaacks/Indian-Nutrient-Databank-INDB-). It's downloaded when the app is built and never shipped with the code."
                    },
                )
                Text(
                    "INDB counts all the oil used for deep frying as eaten, so fried dishes are marked and likely read high. " +
                        "Diet tags come from each recipe's ingredients; hide any food that's tagged wrongly.",
                )
                Text("The protein-rich basics are rounded from USDA FoodData Central (public domain) or typical pack labels.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
