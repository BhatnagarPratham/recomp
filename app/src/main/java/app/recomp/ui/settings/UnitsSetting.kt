package app.recomp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.data.repo.StepMove
import app.recomp.data.repo.UnitSwitch
import app.recomp.domain.persona.Units
import app.recomp.domain.units.UnitStyle
import app.recomp.ui.components.Labels
import app.recomp.ui.components.unitStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class UnitsViewModel @Inject constructor(private val switch: UnitSwitch, prefs: SettingsPrefs) : ViewModel() {
    val units: StateFlow<Units> = prefs.persona.map { it.units }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Units.METRIC)

    suspend fun preview(to: Units): List<StepMove> = switch.preview(to)

    fun switch(to: Units, moveSteps: Boolean) = viewModelScope.launch { switch.switch(to, moveSteps) }
}

/** What the switch dialog says about each step: "Bench press and 11 more: 2.5 kg → 5 lb". */
object UnitsText {
    fun move(m: StepMove, from: UnitStyle, to: UnitStyle): String {
        val who = if (m.exercises.size == 1) m.exercises.single() else "${m.exercises.first()} and ${m.exercises.size - 1} more"
        return "$who: ${Labels.step(m.fromG, from)} → ${Labels.step(m.toG, to)}"
    }

    fun title(to: Units) = if (to == Units.IMPERIAL) "Move your weight steps to lb plates?" else "Move your weight steps to kg plates?"

    const val HISTORY = "Your logged workouts, weigh-ins and measurements stay exactly as they are."

    /** Moving is by nearest step, so going back doesn't always return the old step (1 kg → 2.5 lb → 1.25 kg). */
    const val ROUND_TRIP = "Switching back later moves steps to the nearest step again, which may not be the one you had."

    fun keep(to: Units) = "If you keep them, they show converted, e.g. 2.5 kg as ${Labels.step(2_500, unitStyle(to))}."
}

/**
 * Settings → Body data → *Units* (PR-05, PRD §12.16): a row, a choice of kg and cm or lb and in, and, when any
 * weight step would move, the dialog that offers to move them.
 */
@Composable
fun UnitsSetting(viewModel: UnitsViewModel = hiltViewModel()) {
    val units by viewModel.units.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf<Pair<Units, List<StepMove>>?>(null) }
    val scope = rememberCoroutineScope()

    SettingRow("Units", Labels.units(units)) { choosing = true }

    if (choosing) {
        ChoiceDialog("Units", Units.entries, units, Labels::units, note = "Food stays in grams and household measures.", onDismiss = { choosing = false }) { to ->
            choosing = false
            if (to != units) scope.launch {
                val moves = viewModel.preview(to)
                if (moves.isEmpty()) viewModel.switch(to, moveSteps = false) else asking = to to moves
            }
        }
    }

    asking?.let { (to, moves) ->
        val from = unitStyle(units)
        val toStyle = unitStyle(to)
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(UnitsText.title(to)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    moves.forEach { Text(UnitsText.move(it, from, toStyle)) }
                    Text(UnitsText.HISTORY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(UnitsText.ROUND_TRIP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(UnitsText.keep(to), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { asking = null; viewModel.switch(to, moveSteps = true) }) { Text("Move steps") } },
            dismissButton = {
                Column {
                    TextButton(onClick = { asking = null; viewModel.switch(to, moveSteps = false) }) { Text("Keep my steps") }
                    TextButton(onClick = { asking = null }) { Text("Cancel") }
                }
            },
        )
    }
}
