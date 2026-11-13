package app.recomp.ui.exercises

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.recomp.data.repo.HistoryEntry
import app.recomp.data.repo.ProgressionRepository
import app.recomp.data.repo.TrainingRepository
import app.recomp.domain.model.LoadType
import app.recomp.domain.training.Loads
import app.recomp.ui.components.Labels
import app.recomp.ui.navigation.ExerciseHistoryRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ExerciseHistoryViewModel @Inject constructor(
    savedState: SavedStateHandle,
    training: TrainingRepository,
    progression: ProgressionRepository,
) : ViewModel() {
    private val exerciseId = savedState.toRoute<ExerciseHistoryRoute>().exerciseId
    val exercise = training.observeExercise(exerciseId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _entries = MutableStateFlow<List<HistoryEntry>?>(null)
    val entries: StateFlow<List<HistoryEntry>?> = _entries

    init {
        viewModelScope.launch { _entries.value = progression.exerciseHistory(exerciseId) }
    }
}

/** HM-02: every past session of an exercise, newest first, with its best estimated 1RM and PRs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseHistoryScreen(onBack: () -> Unit, viewModel: ExerciseHistoryViewModel = hiltViewModel()) {
    val exercise by viewModel.exercise.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(exercise?.name ?: "History") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        val list = entries ?: return@Scaffold
        if (list.isEmpty()) {
            Text("No sessions yet.", Modifier.padding(padding).padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(list, key = { it.sessionId }) { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            entry.date.format(dates) + (entry.routineName?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(entry.sets, style = MaterialTheme.typography.titleMedium)
                        entry.bestE1rmG?.let { Text("Estimated 1RM ${Loads.formatKg((it + 50) / 100 * 100)} kg", style = MaterialTheme.typography.bodySmall) }
                        if (entry.records.isNotEmpty()) {
                            Text("PR: " + entry.records.joinToString { Labels.record(it, exercise?.loadType == LoadType.ASSISTED) }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
