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
 * Pick an exercise from the library, or create a custom one from the search text. Used to add or swap
 * exercises in routines and mid-workout (RT-03, RT-04).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExercisePickerSheet(
    title: String,
    library: List<ExerciseEntity>,
    onDismiss: () -> Unit,
    onPick: (ExerciseEntity) -> Unit,
    onCreate: (NewExercise) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf<String?>(null) }
    val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val matches = library.filter { ex ->
        val haystack = "${ex.name} ${ex.primaryMuscle} ${Labels.equipment(ex.equipment)}".lowercase().replace('_', ' ')
        terms.all { it in haystack }
    }

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
                items(matches, key = { it.id }) { ex ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onPick(ex) }.heightIn(min = 56.dp).padding(vertical = 8.dp),
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
            }
        }
    }

    creating?.let { initial ->
        NewExerciseDialog(initial, onDismiss = { creating = null }, onCreate = { creating = null; onCreate(it) })
    }
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
