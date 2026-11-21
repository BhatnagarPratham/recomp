package app.recomp.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.recomp.domain.model.LoadType
import app.recomp.domain.model.SetType
import app.recomp.domain.training.Loads
import app.recomp.ui.components.ConfirmDialog
import app.recomp.ui.components.DateDialog
import app.recomp.ui.components.Labels
import app.recomp.ui.components.SupersetLabel
import app.recomp.ui.components.TimeDialog
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private enum class EditDialog { DATE, START, END, DISCARD, DELETE }

/**
 * Edit a past workout (WL-10, PRD §12.6): laid out like the summary, with editable rows. Weight, reps,
 * RIR and set type; add or delete sets; remove an exercise; the date, times and notes; and delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionEditScreen(onBack: () -> Unit, onSaved: () -> Unit, onDeleted: () -> Unit, viewModel: SessionEditViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val done by viewModel.done.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<EditDialog?>(null) }
    var removing by remember { mutableStateOf<EditExerciseUi?>(null) }
    val dates = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL) }
    val times = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }

    LaunchedEffect(done) {
        when (done) {
            EditDone.Saved -> onSaved()
            EditDone.Deleted -> onDeleted()
            null -> Unit
        }
    }
    val leave = { if (ui.dirty) dialog = EditDialog.DISCARD else onBack() }
    BackHandler(enabled = ui.dirty) { dialog = EditDialog.DISCARD }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (ui.title.isEmpty()) "Edit workout" else "Edit ${ui.title}") },
                navigationIcon = { TextButton(onClick = leave) { Text("Cancel") } },
                actions = { TextButton(onClick = viewModel::save, enabled = ui.loaded && !ui.missing && !ui.saving) { Text("Save") } },
            )
        },
    ) { padding ->
        if (!ui.loaded) return@Scaffold
        if (ui.missing) {
            Text("This workout can't be edited here.", Modifier.padding(padding).padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
                    PickRow("Date", ui.date.format(dates)) { dialog = EditDialog.DATE }
                    PickRow("Started", ui.start.format(times)) { dialog = EditDialog.START }
                    PickRow("Ended", ui.end.format(times) + if (ui.end < ui.start) " (next day)" else "") { dialog = EditDialog.END }
                }
            }

            ui.exercises.forEach { exercise ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SupersetLabel(exercise.superset)
                        Text(exercise.name, style = MaterialTheme.typography.titleMedium)
                        if (exercise.sets.isEmpty()) Text("No sets: it's kept as skipped.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        exercise.sets.forEachIndexed { index, set ->
                            SetRow(
                                number = index + 1,
                                exercise = exercise,
                                set = set,
                                onLoad = { viewModel.setLoad(exercise.sessionExerciseId, set.key, it) },
                                onReps = { viewModel.setReps(exercise.sessionExerciseId, set.key, it) },
                                onType = { viewModel.setType(exercise.sessionExerciseId, set.key, it) },
                                onRir = { viewModel.cycleRir(exercise.sessionExerciseId, set.key) },
                                onDelete = { viewModel.deleteSet(exercise.sessionExerciseId, set.key) },
                            )
                        }
                        Row {
                            TextButton(onClick = { viewModel.addSet(exercise.sessionExerciseId) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("+ Add set") }
                            TextButton(onClick = { removing = exercise }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove exercise") }
                        }
                    }
                }
            }
            if (ui.exercises.isEmpty()) Text("No exercises left in this workout.", color = MaterialTheme.colorScheme.onSurfaceVariant)

            OutlinedTextField(value = ui.notes, onValueChange = viewModel::setNotes, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())

            Text(
                "Saving recomputes PRs, stall cards and next targets from the edited history. The targets you were shown that day stay as they were.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = viewModel::save, enabled = !ui.saving, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Save") }
            OutlinedButton(onClick = { dialog = EditDialog.DELETE }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("Delete workout", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    when (dialog) {
        EditDialog.DATE -> DateDialog(ui.date, onDismiss = { dialog = null }) { dialog = null; viewModel.setDate(it) }
        EditDialog.START -> TimeDialog(ui.start, onDismiss = { dialog = null }) { t: LocalTime -> dialog = null; viewModel.setStart(t) }
        EditDialog.END -> TimeDialog(ui.end, onDismiss = { dialog = null }) { t: LocalTime -> dialog = null; viewModel.setEnd(t) }
        EditDialog.DISCARD -> ConfirmDialog("Discard your changes?", "The workout stays as it was.", "Discard", onDismiss = { dialog = null }, dismiss = "Keep editing") {
            dialog = null
            onBack()
        }
        EditDialog.DELETE -> ConfirmDialog(
            "Delete this workout?",
            "Its sets and PRs are removed, later PRs are recomputed, and if it was your last workout the rotation moves back. This can't be undone.",
            "Delete",
            onDismiss = { dialog = null },
        ) {
            dialog = null
            viewModel.delete()
        }
        null -> Unit
    }
    removing?.let { exercise ->
        ConfirmDialog("Remove ${exercise.name}?", "Its sets leave this workout when you save.", "Remove", onDismiss = { removing = null }) {
            removing = null
            viewModel.removeExercise(exercise.sessionExerciseId)
        }
    }
}

@Composable
private fun PickRow(label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(72.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = "Change ${label.lowercase()}" }) { Text("Change") }
    }
}

/** One set: its type, weight (unless bodyweight), reps, optional RIR, and delete. */
@Composable
private fun SetRow(
    number: Int,
    exercise: EditExerciseUi,
    set: EditSetUi,
    onLoad: (String) -> Unit,
    onReps: (String) -> Unit,
    onType: (SetType) -> Unit,
    onRir: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            TextButton(
                onClick = { menu = true },
                modifier = Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp).semantics { contentDescription = "Set $number, ${Labels.setType(set.type)}. Change type" },
            ) { Text(typeBadge(set.type)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                SetType.entries.forEach { type ->
                    DropdownMenuItem(text = { Text(Labels.setType(type)) }, onClick = { menu = false; onType(type) })
                }
            }
        }
        if (Loads.logsLoad(exercise.loadType)) {
            OutlinedTextField(
                value = set.load,
                onValueChange = onLoad,
                singleLine = true,
                label = { Text(loadLabel(exercise.loadType)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1.3f).semantics { contentDescription = "Set $number weight in kg" },
            )
        }
        OutlinedTextField(
            value = set.reps,
            onValueChange = onReps,
            singleLine = true,
            label = { Text("Reps") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f).semantics { contentDescription = "Set $number reps" },
        )
        TextButton(
            onClick = onRir,
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Set $number reps in reserve, ${rirText(set.rir)}. Change" },
        ) { Text("RIR ${rirText(set.rir)}") }
        TextButton(onClick = onDelete, modifier = Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp).semantics { contentDescription = "Delete set $number" }) { Text("✕") }
    }
}

private fun typeBadge(type: SetType) = when (type) {
    SetType.WARMUP -> "Warm"
    SetType.WORKING -> "Work"
    SetType.DROP -> "Drop"
    SetType.FAILURE -> "Fail"
}

private fun loadLabel(loadType: LoadType) = when (loadType) {
    LoadType.ASSISTED -> "Assist kg"
    LoadType.BODYWEIGHT_PLUS -> "+ kg"
    else -> "kg"
}

private fun rirText(rir: Int?) = when (rir) {
    null -> "–"
    3 -> "3+"
    else -> "$rir"
}
