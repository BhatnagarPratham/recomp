package app.recomp.ui.train

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.recomp.data.db.RoutineEntity
import app.recomp.data.prefs.TrainingSettings
import app.recomp.data.repo.TrainingRepository
import app.recomp.ui.components.Labels
import app.recomp.ui.components.MenuItem
import app.recomp.ui.components.OverflowMenu
import app.recomp.ui.components.SectionTitle
import app.recomp.ui.components.TextEntryDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.format.TextStyle
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TrainUi(
    val settings: TrainingSettings = TrainingSettings(),
    val rotation: List<RoutineEntity> = emptyList(),
    val nextId: Long? = null,
    val extras: List<Pair<RoutineEntity, Set<Int>>> = emptyList(),
    val librarySize: Int = 0,
)

@HiltViewModel
class TrainViewModel @Inject constructor(private val training: TrainingRepository) : ViewModel() {
    val ui: StateFlow<TrainUi> = combine(
        training.settings,
        training.observeRotation(),
        training.observeNextRoutine(),
        training.observeExtras(),
        training.observeLibrary(),
    ) { settings, rotation, next, extras, library ->
        val days = settings.extras.associate { it.routineId to it.days }
        TrainUi(settings, rotation, next?.id, extras.map { it to (days[it.id] ?: emptySet()) }, library.size)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrainUi())

    fun move(id: Long, up: Boolean) = viewModelScope.launch { training.moveRoutine(id, up) }

    fun archive(id: Long) = viewModelScope.launch { training.archiveRoutine(id) }

    suspend fun create(name: String): Long = training.createRoutine(name)
}

/** Routines in rotation order, extra sessions, the exercise library and the training setup (RT-01, RT-04, EX). */
@Composable
fun TrainScreen(
    onOpenRoutine: (Long) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSetup: () -> Unit,
    viewModel: TrainViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val scope = rememberCoroutineScope()
    var naming by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Train", style = MaterialTheme.typography.headlineMedium)

        Card(Modifier.fillMaxWidth().clickable(onClick = onOpenSetup)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Training setup", style = MaterialTheme.typography.titleMedium)
                val s = ui.settings
                Text(
                    if (s.split == null) "Not set up yet" else "${s.split.label} · ${Labels.experience(s.experience)} · ${s.strengthDaysPerWeek} strength days a week",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Split, experience, weekly plan, equipment", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SectionTitle("Rotation")
        if (ui.rotation.isEmpty()) {
            Text("No routines yet. Run the training setup, or add one.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ui.rotation.forEachIndexed { index, routine ->
            Card(Modifier.fillMaxWidth().clickable { onOpenRoutine(routine.id) }) {
                Row(Modifier.padding(start = 16.dp).heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(routine.name, style = MaterialTheme.typography.titleMedium)
                        if (routine.id == ui.nextId) Text("Next up", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    }
                    OverflowMenu("More options for ${routine.name}") { close ->
                        MenuItem("Move earlier", enabled = index > 0) { close(); viewModel.move(routine.id, up = true) }
                        MenuItem("Move later", enabled = index < ui.rotation.lastIndex) { close(); viewModel.move(routine.id, up = false) }
                        MenuItem("Archive") { close(); viewModel.archive(routine.id) }
                    }
                }
            }
        }
        OutlinedButton(onClick = { naming = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("+ New routine") }

        SectionTitle("Extra sessions")
        if (ui.extras.isEmpty()) {
            Text("None. Add yoga, mobility, cardio or sport in the training setup.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ui.extras.forEach { (routine, days) ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(routine.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${Labels.kind(routine.kind)} · " + if (days.isEmpty()) "no fixed days" else days.sorted().joinToString { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, locale) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionTitle("Exercises")
        Card(Modifier.fillMaxWidth().clickable(onClick = onOpenLibrary)) {
            Column(Modifier.padding(16.dp)) {
                Text("Exercise library", style = MaterialTheme.typography.titleMedium)
                Text("${ui.librarySize} exercises · add your own, set equipment, steps and setup notes", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (naming) {
        TextEntryDialog("New routine", "Day ${ui.rotation.size + 1}", onDismiss = { naming = false }) { name ->
            naming = false
            if (name.isNotBlank()) scope.launch { onOpenRoutine(viewModel.create(name.trim())) }
        }
    }
}
