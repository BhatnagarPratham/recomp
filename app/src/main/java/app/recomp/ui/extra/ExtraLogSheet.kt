package app.recomp.ui.extra

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.recomp.data.db.RoutineEntity
import app.recomp.data.repo.WorkoutRepository.ExtraEntry
import app.recomp.ui.components.ChoiceChips
import app.recomp.ui.components.NumberEntryDialog
import app.recomp.ui.components.Stepper

/**
 * Log an extra session (YG-01): prefilled from the last one of the same name, so it's Log, then Save.
 * Intensity is optional; tap the selected chip again to clear it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtraLogSheet(routine: RoutineEntity, initial: ExtraEntry, onDismiss: () -> Unit, onSave: (ExtraEntry) -> Unit) {
    var style by remember { mutableStateOf(initial.style) }
    var duration by remember { mutableStateOf(initial.durationMin) }
    var intensity by remember { mutableStateOf(initial.intensity) }
    var notes by remember { mutableStateOf(initial.notes.orEmpty()) }
    var typing by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Log ${routine.name}", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(value = style, onValueChange = { style = it }, label = { Text("Type or style") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Stepper(
                "Minutes",
                "$duration",
                onMinus = { duration = (duration - 5).coerceAtLeast(5) },
                onPlus = { duration += 5 },
                onValueClick = { typing = true },
                minusLabel = "−5",
                plusLabel = "+5",
            )
            Text("Intensity (optional)", style = MaterialTheme.typography.labelLarge)
            ChoiceChips((1..5).toList(), intensity, { "$it" }, { intensity = if (intensity == it) null else it })
            OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = { onSave(ExtraEntry(style.trim().ifEmpty { routine.name }, duration, intensity, notes.trim().ifEmpty { null })) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text("Save") }
        }
    }

    if (typing) {
        NumberEntryDialog("Minutes", "$duration", decimal = false, onDismiss = { typing = false }) { text ->
            typing = false
            text.trim().toIntOrNull()?.takeIf { it in 1..600 }?.let { duration = it }
        }
    }
}
