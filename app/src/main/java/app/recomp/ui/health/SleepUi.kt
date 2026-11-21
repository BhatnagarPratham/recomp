package app.recomp.ui.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.recomp.domain.health.SleepNight
import app.recomp.domain.health.Steps
import app.recomp.ui.body.BodyText
import app.recomp.ui.components.ChoiceChips
import app.recomp.ui.components.NumberEntryDialog
import app.recomp.ui.components.Stepper
import app.recomp.ui.onboarding.GoalText

/**
 * *Slept* (AR-02, PRD §12.9): 5 or less, 6, 7, 8 and 9+ hours, or typed to the half hour. Tapping the chosen chip
 * again clears it. A value between the chips (typed, or from a tracker) shows beside *Type it*.
 */
@Composable
fun SleepChips(hours: Double?, onChange: (Double?) -> Unit, modifier: Modifier = Modifier) {
    var typing by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Slept (hours)", style = MaterialTheme.typography.labelLarge)
        ChoiceChips(SleepNight.CHIPS, hours?.takeIf { it in SleepNight.CHIPS }, HealthText::chip, { onChange(if (hours == it) null else it) })
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (hours != null && hours !in SleepNight.CHIPS) Text(HealthText.hours(hours), Modifier.padding(end = 8.dp), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { typing = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Type it") }
        }
    }
    if (typing) {
        NumberEntryDialog("Slept", hours?.let { if (it % 1.0 == 0.0) "${it.toInt()}" else "$it" }.orEmpty(), decimal = true, onDismiss = { typing = false }, suffix = "h") { text ->
            typing = false
            BodyText.parse(text)?.takeIf { it in SleepNight.TYPED_RANGE }?.let { onChange(SleepNight.toHalfHours(it)) }
        }
    }
}

/** Last night's sleep on its own, from Today's weight card. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepSheet(initial: Double?, onDismiss: () -> Unit, onSave: (Double?) -> Unit) {
    var hours by remember { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Last night's sleep", style = MaterialTheme.typography.titleLarge)
            Text("For the night that ended this morning.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            SleepChips(hours, { hours = it })
            Button(onClick = { onSave(hours) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Save") }
        }
    }
}

/** The daily step goal (AR-01): 2,000 to 20,000 in steps of 500. */
@Composable
fun StepsGoalDialog(goal: Int, onDismiss: () -> Unit, onTurnOff: () -> Unit, onSave: (Int) -> Unit) {
    var value by remember { mutableIntStateOf(goal) }
    var typing by remember { mutableStateOf(false) }
    if (typing) {
        NumberEntryDialog("Steps a day", "$value", decimal = false, onDismiss = { typing = false }) { text ->
            typing = false
            text.trim().toIntOrNull()?.let { value = Steps.clampGoal(it) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Step goal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Stepper(
                    "Steps a day",
                    GoalText.n(value),
                    onMinus = { value = Steps.clampGoal(value - Steps.GOAL_STEP) },
                    onPlus = { value = Steps.clampGoal(value + Steps.GOAL_STEP) },
                    onValueClick = { typing = true },
                    modifier = Modifier.fillMaxWidth(),
                    minusLabel = "−500",
                    plusLabel = "+500",
                )
                Text("Steps count by calendar day, so they match Google Fit.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onTurnOff, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Turn steps off") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
