package app.recomp.ui.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.recomp.data.repo.WorkoutRepository
import app.recomp.data.repo.toLogged
import app.recomp.domain.training.Loads
import app.recomp.domain.training.SessionStats
import app.recomp.ui.components.formatDuration
import app.recomp.ui.navigation.SummaryRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SummaryExercise(val name: String, val sets: String)

data class SummaryUi(
    val title: String = "",
    val date: String = "",
    val stats: SessionStats? = null,
    val exercises: List<SummaryExercise> = emptyList(),
    val notes: String? = null,
    val canSaveToRoutine: Boolean = false,
    val routineName: String? = null,
)

@HiltViewModel
class SummaryViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val workouts: WorkoutRepository,
) : ViewModel() {
    private val sessionId = savedState.toRoute<SummaryRoute>().sessionId
    private val routineName = MutableStateFlow<String?>(null)
    private val differs = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            val session = workouts.observeSession(sessionId).first()
            routineName.value = session?.routineId?.let { workouts.routine(it)?.name }
            differs.value = workouts.differsFromTemplate(sessionId)
        }
    }

    val ui: StateFlow<SummaryUi> = combine(
        workouts.observeSession(sessionId),
        workouts.observeSessionExercises(sessionId),
        routineName,
        differs,
    ) { session, exercises, routine, differs ->
        if (session == null) return@combine SummaryUi()
        val sets = exercises.flatMap { ex -> ex.sets.filter { it.completedAt != null }.sortedBy { it.setIndex }.map { it.toLogged() } }
        SummaryUi(
            title = routine ?: "Workout",
            date = session.logDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)),
            stats = SessionStats.of(session.startedAt, session.endedAt ?: session.startedAt, sets),
            exercises = exercises.map { ex ->
                val done = ex.sets.filter { it.completedAt != null }.sortedBy { it.setIndex }.map { it.toLogged() }
                SummaryExercise(ex.exercise.name, if (done.isEmpty()) "Skipped" else Loads.describe(done).ifEmpty { "Warm-ups only" })
            },
            notes = session.notes,
            canSaveToRoutine = differs,
            routineName = routine,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SummaryUi())

    fun saveToRoutine() = viewModelScope.launch {
        workouts.saveToTemplate(sessionId)
        differs.value = false
    }
}

/**
 * Workout summary (WL-08). TODO(M2): PRs, targets hit or missed, and what changes next time
 * ("Lat pulldown goes to 50 kg") from the progression engine.
 */
@Composable
fun SummaryScreen(onDone: () -> Unit, viewModel: SummaryViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("${ui.title} done", style = MaterialTheme.typography.headlineMedium)
        Text(ui.date, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ui.stats?.let { stats ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Time", formatDuration(stats.durationMs), Modifier.weight(1f))
                Stat("Sets", "${stats.sets}", Modifier.weight(1f))
                Stat("Reps", "${stats.reps}", Modifier.weight(1f))
                Stat("Volume", "${(stats.volumeG + 500) / 1000} kg", Modifier.weight(1.4f))
            }
        }
        ui.exercises.forEach { ex ->
            Column {
                Text(ex.name, style = MaterialTheme.typography.titleMedium)
                Text(ex.sets, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ui.notes?.let { Text("Notes: $it", style = MaterialTheme.typography.bodyMedium) }
        if (ui.canSaveToRoutine && ui.routineName != null) {
            OutlinedButton(onClick = viewModel::saveToRoutine, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("Save today's changes to ${ui.routineName}")
            }
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Done") }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}
