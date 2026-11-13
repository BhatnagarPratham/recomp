package app.recomp.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.recomp.data.db.ExerciseEntity
import app.recomp.domain.model.Equipment
import app.recomp.domain.model.Mechanic

/** What a new custom exercise needs up front (EX-02); the rest is edited on its settings screen. */
data class NewExercise(val name: String, val mechanic: Mechanic, val equipment: Equipment)

/**
 * EX-04's *Same movement*: exercises with [exercise]'s movement pattern and primary muscle on other
 * equipment, e.g. the machine row when the dumbbell rack is taken. Custom exercises start as "other",
 * which says nothing about the movement, so they get none.
 */
fun sameMovement(exercise: ExerciseEntity, library: List<ExerciseEntity>): List<ExerciseEntity> {
    if (exercise.movementPattern == UNKNOWN || exercise.primaryMuscle == UNKNOWN) return emptyList()
    return library.filter {
        it.id != exercise.id && it.movementPattern == exercise.movementPattern &&
            it.primaryMuscle == exercise.primaryMuscle && it.equipment != exercise.equipment
    }
}

private const val UNKNOWN = "other"

/**
 * Pick an exercise from the library, or create a custom one from the search text. Used to add or swap
 * exercises in routines and mid-workout (RT-03, RT-04). A swap passes [swapping], so *Same movement*
 * comes first and everything else follows (EX-04).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExercisePickerSheet(
    title: String,
    library: List<ExerciseEntity>,
    onDismiss: () -> Unit,
    onPick: (ExerciseEntity) -> Unit,
    onCreate: (NewExercise) -> Unit,
    swapping: ExerciseEntity? = null,
) {
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf<String?>(null) }
    val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val matches = library.filter { ex ->
        val haystack = "${ex.name} ${ex.primaryMuscle} ${Labels.equipment(ex.equipment)}".lowercase().replace('_', ' ')
        ex.id != swapping?.id && terms.all { it in haystack }
    }
    val same = swapping?.let { sameMovement(it, matches) }.orEmpty()
    val rest = if (same.isEmpty()) matches else matches - same.toSet()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.9f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search by name, muscle or equipment") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(Modifier.fillMaxWidth()) {
                item {
                    val name = query.trim()
                    TextButton(onClick = { creating = name }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (name.isEmpty()) "+ New exercise" else "+ New exercise \"$name\"")
                    }
                }
                if (same.isNotEmpty()) {
                    item(key = "same") { PickerHeading("Same movement") }
                    items(same, key = { "same-${it.id}" }) { ex -> PickerRow(ex) { onPick(ex) } }
                    item(key = "rest") { PickerHeading("Everything else") }
                }
                items(rest, key = { it.id }) { ex -> PickerRow(ex) { onPick(ex) } }
            }
        }
    }

    creating?.let { initial ->
        NewExerciseDialog(initial, onDismiss = { creating = null }, onCreate = { creating = null; onCreate(it) })
    }
}

@Composable
private fun PickerHeading(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))

@Composable
private fun PickerRow(ex: ExerciseEntity, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(vertical = 8.dp),
    ) {
        Text(ex.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            "${Labels.muscle(ex.primaryMuscle)} · ${Labels.equipment(ex.equipment)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
}

@Composable
fun NewExerciseDialog(initialName: String, onDismiss: () -> Unit, onCreate: (NewExercise) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    var mechanic by remember { mutableStateOf(Mechanic.COMPOUND) }
    var equipment by remember { mutableStateOf(Equipment.DUMBBELL) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New exercise") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                Text("Type", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(Mechanic.entries, mechanic, Labels::mechanic, { mechanic = it })
                Text("Equipment", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(Equipment.entries, equipment, Labels::equipment, { equipment = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(NewExercise(name.trim(), mechanic, equipment)) }, enabled = name.isNotBlank()) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
