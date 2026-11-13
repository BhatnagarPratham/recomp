package app.recomp.ui.exercises

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.recomp.data.db.ExerciseEntity
import app.recomp.data.repo.TrainingRepository
import app.recomp.domain.model.Equipment
import app.recomp.domain.model.LoadType
import app.recomp.domain.model.Mechanic
import app.recomp.domain.training.Loads
import app.recomp.ui.components.ChoiceChips
import app.recomp.ui.components.Labels
import app.recomp.ui.components.NewExercise
import app.recomp.ui.components.NewExerciseDialog
import app.recomp.ui.components.NumberEntryDialog
import app.recomp.ui.components.Stepper
import app.recomp.ui.components.formatRest
import app.recomp.ui.navigation.ExerciseRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class LibraryViewModel @Inject constructor(private val training: TrainingRepository) : ViewModel() {
    val library: StateFlow<List<ExerciseEntity>> =
        training.observeLibrary().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun create(new: NewExercise): Long = training.createCustomExercise(new.name, new.mechanic, new.equipment)
}

/** The exercise library (EX-01, EX-02): search, open an exercise's settings, or add your own. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onBack: () -> Unit, onOpenExercise: (Long) -> Unit, viewModel: LibraryViewModel = hiltViewModel()) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val shown = library.filter { ex ->
        val text = "${ex.name} ${ex.primaryMuscle} ${Labels.equipment(ex.equipment)}".lowercase().replace('_', ' ')
        terms.all { it in text }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Exercises") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = { TextButton(onClick = { creating = true }) { Text("+ New") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search by name, muscle or equipment") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.id }) { ex ->
                    Column(Modifier.fillMaxWidth().clickable { onOpenExercise(ex.id) }.heightIn(min = 56.dp).padding(vertical = 8.dp)) {
                        Text(ex.name + if (ex.isCustom) " · custom" else "", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${Labels.muscle(ex.primaryMuscle)} · ${Labels.equipment(ex.equipment)} · step ${Labels.kg(ex.incrementG)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (creating) {
        NewExerciseDialog(query.trim(), onDismiss = { creating = false }) { new ->
            creating = false
            scope.launch { onOpenExercise(viewModel.create(new)) }
        }
    }
}

@HiltViewModel
class ExerciseViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val training: TrainingRepository,
) : ViewModel() {
    private val id = savedState.toRoute<ExerciseRoute>().exerciseId
    val exercise = training.observeExercise(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun save(updated: ExerciseEntity) = viewModelScope.launch { training.updateExercise(updated) }
}

private val restChoices = listOf(45, 60, 90, 120, 150, 180, 240)

/** Per-exercise settings (EX-03): equipment, step, rep range, rest, unilateral, setup notes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseScreen(onBack: () -> Unit, onOpenHistory: (Long) -> Unit, viewModel: ExerciseViewModel = hiltViewModel()) {
    val loaded by viewModel.exercise.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf<ExerciseEntity?>(null) }
    LaunchedEffect(loaded) { if (draft == null) draft = loaded }
    var typing by remember { mutableStateOf<String?>(null) }
    val ex = draft

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ex?.name ?: "") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = { ex?.let { TextButton(onClick = { onOpenHistory(it.id) }) { Text("History") } } },
            )
        },
    ) { padding ->
        if (ex == null) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(value = ex.name, onValueChange = { draft = ex.copy(name = it) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = ex.setupNotes.orEmpty(),
                onValueChange = { draft = ex.copy(setupNotes = it.ifEmpty { null }) },
                label = { Text("Setup notes, e.g. seat 4, pin 3") },
                modifier = Modifier.fillMaxWidth(),
            )

            Label("Equipment")
            ChoiceChips(Equipment.entries, ex.equipment, Labels::equipment, { draft = ex.copy(equipment = it) })
            Label("Load")
            ChoiceChips(listOf(LoadType.WEIGHTED, LoadType.BODYWEIGHT, LoadType.BODYWEIGHT_PLUS, LoadType.ASSISTED), ex.loadType, Labels::loadType, { draft = ex.copy(loadType = it) })

            if (ex.loadType != LoadType.BODYWEIGHT) {
                Label(if (ex.equipment == Equipment.DUMBBELL) "Smallest step (per dumbbell)" else "Smallest step")
                val choices = (Loads.incrementChoicesG + ex.incrementG).distinct().sorted()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChoiceChips(choices, ex.incrementG, { Labels.kg(it) }, { draft = ex.copy(incrementG = it) }, Modifier.weight(1f))
                }
                OutlinedButton(onClick = { typing = "increment" }) { Text("Other step…") }
            }

            Label("Default rep range")
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Stepper("Min", "${ex.defaultRepMin}", { draft = ex.copy(defaultRepMin = (ex.defaultRepMin - 1).coerceAtLeast(1)) },
                    { draft = ex.copy(defaultRepMin = (ex.defaultRepMin + 1).coerceAtMost(ex.defaultRepMax)) }, { typing = "min" })
                Stepper("Max", "${ex.defaultRepMax}", { draft = ex.copy(defaultRepMax = (ex.defaultRepMax - 1).coerceAtLeast(ex.defaultRepMin)) },
                    { draft = ex.copy(defaultRepMax = ex.defaultRepMax + 1) }, { typing = "max" })
            }
            Label("Default rest")
            ChoiceChips((restChoices + ex.defaultRestSec).distinct().sorted(), ex.defaultRestSec, { formatRest(it) }, { draft = ex.copy(defaultRestSec = it) })

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("One side at a time")
                    Text("Reps are logged per side", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = ex.isUnilateral, onCheckedChange = { draft = ex.copy(isUnilateral = it) })
            }
            Label("Type")
            ChoiceChips(Mechanic.entries, ex.mechanic, Labels::mechanic, { draft = ex.copy(mechanic = it) })
            Text(
                "Changes apply to routines and workouts from now on. Routines keep their own rep ranges; edit those in the routine.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { viewModel.save(ex.copy(name = ex.name.trim().ifEmpty { loaded?.name ?: ex.name })); onBack() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text("Save") }
        }
    }

    typing?.let { field ->
        val current = ex ?: return@let
        val initial = when (field) {
            "increment" -> Loads.formatKg(current.incrementG)
            "min" -> "${current.defaultRepMin}"
            else -> "${current.defaultRepMax}"
        }
        NumberEntryDialog(
            if (field == "increment") "Smallest step" else "Reps",
            initial,
            decimal = field == "increment",
            suffix = if (field == "increment") "kg" else null,
            onDismiss = { typing = null },
        ) { text ->
            typing = null
            when (field) {
                "increment" -> Loads.parseKg(text)?.takeIf { it > 0 }?.let { draft = current.copy(incrementG = it) }
                "min" -> text.trim().toIntOrNull()?.takeIf { it > 0 }?.let { draft = current.copy(defaultRepMin = it, defaultRepMax = maxOf(it, current.defaultRepMax)) }
                else -> text.trim().toIntOrNull()?.takeIf { it > 0 }?.let { draft = current.copy(defaultRepMax = it, defaultRepMin = minOf(it, current.defaultRepMin)) }
            }
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge)
