package app.recomp.ui.routines

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.recomp.data.db.ExerciseEntity
import app.recomp.data.db.RoutineExerciseEntity
import app.recomp.data.db.RoutineExerciseWithExercise
import app.recomp.data.repo.TrainingRepository
import app.recomp.ui.components.ChoiceChips
import app.recomp.ui.components.ConfirmDialog
import app.recomp.ui.components.ExercisePickerSheet
import app.recomp.ui.components.MenuItem
import app.recomp.ui.components.NewExercise
import app.recomp.ui.components.NumberEntryDialog
import app.recomp.ui.components.OverflowMenu
import app.recomp.ui.components.Stepper
import app.recomp.ui.components.TextEntryDialog
import app.recomp.ui.components.formatRest
import app.recomp.ui.navigation.RoutineRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class RoutineViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val training: TrainingRepository,
) : ViewModel() {
    private val routineId = savedState.toRoute<RoutineRoute>().routineId

    val routine = training.observeRoutine(routineId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val rows: StateFlow<List<RoutineExerciseWithExercise>> =
        training.observeRoutineExercises(routineId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val library: StateFlow<List<ExerciseEntity>> =
        training.observeLibrary().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun edit(transform: (MutableList<RoutineExerciseEntity>) -> Unit) = viewModelScope.launch {
        val list = rows.value.map { it.entry }.toMutableList()
        transform(list)
        training.saveRoutineExercises(routineId, list)
    }

    fun add(exercise: ExerciseEntity) = edit { it += training.planRowFor(exercise) }

    fun create(new: NewExercise) = viewModelScope.launch {
        val id = training.createCustomExercise(new.name, new.mechanic, new.equipment)
        val list = rows.value.map { it.entry } + training.planRowFor(training.exercise(id) ?: return@launch)
        training.saveRoutineExercises(routineId, list)
    }

    fun remove(index: Int) = edit { it.removeAt(index) }

    fun move(index: Int, up: Boolean) = edit { list ->
        val to = if (up) index - 1 else index + 1
        if (to in list.indices) list.add(to, list.removeAt(index))
    }

    fun swap(index: Int, exercise: ExerciseEntity) = edit { list ->
        list[index] = training.planRowFor(exercise, sets = list[index].targetSets)
    }

    fun update(index: Int, row: RoutineExerciseEntity) = edit { it[index] = row }

    fun rename(name: String) = viewModelScope.launch { training.renameRoutine(routineId, name) }

    fun archive() = viewModelScope.launch { training.archiveRoutine(routineId) }
}

/** Edit a routine (RT-01, RT-04): add, remove, reorder or swap exercises and change their targets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineScreen(onBack: () -> Unit, onOpenExercise: (Long) -> Unit, viewModel: RoutineViewModel = hiltViewModel()) {
    val routine by viewModel.routine.collectAsStateWithLifecycle()
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<Int?>(null) } // -1 = add, else swap at index
    var editing by remember { mutableStateOf<Int?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var archiving by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(routine?.name ?: "", modifier = Modifier.clickable { renaming = true }) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    OverflowMenu("Routine options") { close ->
                        MenuItem("Rename") { close(); renaming = true }
                        MenuItem("Archive routine") { close(); archiving = true }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(rows, key = { _, row -> row.entry.id }) { index, row ->
                Card(Modifier.fillMaxWidth().clickable { editing = index }) {
                    Row(Modifier.padding(start = 16.dp).heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(row.exercise.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${row.entry.targetSets} × ${row.entry.repMin}–${row.entry.repMax} · rest ${formatRest(row.entry.restSec)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OverflowMenu("More options for ${row.exercise.name}") { close ->
                            MenuItem("Sets, reps and rest") { close(); editing = index }
                            MenuItem("Swap exercise") { close(); picking = index }
                            MenuItem("Exercise settings") { close(); onOpenExercise(row.exercise.id) }
                            MenuItem("Move up", enabled = index > 0) { close(); viewModel.move(index, up = true) }
                            MenuItem("Move down", enabled = index < rows.lastIndex) { close(); viewModel.move(index, up = false) }
                            MenuItem("Remove") { close(); viewModel.remove(index) }
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { picking = -1 }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("+ Add exercise") }
            }
        }
    }

    picking?.let { target ->
        ExercisePickerSheet(
            title = if (target < 0) "Add exercise" else "Swap ${rows.getOrNull(target)?.exercise?.name}",
            library = library,
            onDismiss = { picking = null },
            onPick = { ex -> picking = null; if (target < 0) viewModel.add(ex) else viewModel.swap(target, ex) },
            onCreate = { new -> picking = null; viewModel.create(new) },
        )
    }
    editing?.let { index ->
        rows.getOrNull(index)?.let { row ->
            TargetsDialog(row, onDismiss = { editing = null }, onSave = { editing = null; viewModel.update(index, it) })
        }
    }
    if (renaming) {
        TextEntryDialog("Rename routine", routine?.name.orEmpty(), onDismiss = { renaming = false }) {
            renaming = false
            if (it.isNotBlank()) viewModel.rename(it.trim())
        }
    }
    if (archiving) {
        ConfirmDialog(
            "Archive ${routine?.name}?",
            "It leaves the rotation. Its workout history stays.",
            "Archive",
            onDismiss = { archiving = false },
            onConfirm = { archiving = false; viewModel.archive(); onBack() },
        )
    }
}

private val restChoices = listOf(45, 60, 90, 120, 150, 180, 240)

/** Target sets, rep range and rest for one exercise in a routine (RT-01). */
@Composable
fun TargetsDialog(row: RoutineExerciseWithExercise, onDismiss: () -> Unit, onSave: (RoutineExerciseEntity) -> Unit) {
    var sets by remember { mutableStateOf(row.entry.targetSets) }
    var repMin by remember { mutableStateOf(row.entry.repMin) }
    var repMax by remember { mutableStateOf(row.entry.repMax) }
    var rest by remember { mutableStateOf(row.entry.restSec) }
    var typing by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.exercise.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Stepper("Sets", "$sets", { sets = (sets - 1).coerceAtLeast(1) }, { sets = (sets + 1).coerceAtMost(10) }, { typing = "sets" })
                Stepper("Min reps", "$repMin", { repMin = (repMin - 1).coerceAtLeast(1) }, { repMin = (repMin + 1).coerceAtMost(repMax) }, { typing = "min" })
                Stepper("Max reps", "$repMax", { repMax = (repMax - 1).coerceAtLeast(repMin) }, { repMax += 1 }, { typing = "max" })
                Text("Rest", style = MaterialTheme.typography.labelLarge)
                ChoiceChips((restChoices + rest).distinct().sorted(), rest, { formatRest(it) }, { rest = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(row.entry.copy(targetSets = sets, repMin = repMin, repMax = repMax, restSec = rest)) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    typing?.let { field ->
        val initial = when (field) { "sets" -> sets; "min" -> repMin; else -> repMax }
        NumberEntryDialog("Enter value", "$initial", decimal = false, onDismiss = { typing = null }) { text ->
            typing = null
            val n = text.trim().toIntOrNull()?.takeIf { it > 0 } ?: return@NumberEntryDialog
            when (field) {
                "sets" -> sets = n.coerceAtMost(20)
                "min" -> { repMin = n; if (repMax < n) repMax = n }
                else -> { repMax = n; if (repMin > n) repMin = n }
            }
        }
    }
}
