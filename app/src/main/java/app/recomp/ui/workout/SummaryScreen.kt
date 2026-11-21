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
import app.recomp.data.db.SessionExerciseWithSets
import app.recomp.data.db.WorkoutSessionEntity
import app.recomp.data.repo.ProgressionRepository
import app.recomp.data.repo.WorkoutRepository
import app.recomp.data.repo.toLogged
import app.recomp.domain.model.LoadType
import app.recomp.domain.model.SetType
import app.recomp.domain.progression.Action
import app.recomp.domain.progression.Targets
import app.recomp.domain.training.Loads
import app.recomp.ui.components.Labels
import app.recomp.ui.components.LocalUnitStyle
import app.recomp.ui.components.unitStyle
import app.recomp.domain.units.UnitStyle
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the app says after a past workout was edited or deleted (PRD §12.6). */
object HistoryText {
    const val RECOMPUTED = "PRs, stall cards and next targets were recomputed from your edited history."
}

/** PRD §4.8's progression message for a goal in a deficit, on the summary and the stall card. */
object DeficitText {
    const val HOLDING = "Holding strength in a deficit is a win."
}

/** [target] grades the frozen target (WL-08): "Target 45 × 12/12/10 · hit". */
data class SummaryExercise(val name: String, val sets: String, val target: String? = null, val hit: Boolean = false)

data class SummaryUi(
    val title: String = "",
    val date: String = "",
    val stats: SessionStats? = null,
    val exercises: List<SummaryExercise> = emptyList(),
    val notes: String? = null,
    val canSaveToRoutine: Boolean = false,
    val routineName: String? = null,
    val records: List<String> = emptyList(),
    val nextTime: List<String> = emptyList(),
    /** Whether the calorie target in force that day was a deficit (PRD §4.8). */
    val inDeficit: Boolean = false,
    /** Opened from History or Today rather than by finishing: it can be edited (WL-10), and doesn't offer to change the routine. */
    val fromHistory: Boolean = false,
    val missing: Boolean = false,
)

@HiltViewModel
class SummaryViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val workouts: WorkoutRepository,
    private val progression: ProgressionRepository,
) : ViewModel() {
    private val route = savedState.toRoute<SummaryRoute>()
    val sessionId = route.sessionId
    private val inDeficit = MutableStateFlow(false)
    private val routineName = MutableStateFlow<String?>(null)
    private val differs = MutableStateFlow(false)
    private val nextTime = MutableStateFlow<List<String>>(emptyList())
    /** For assisted lifts' volume (WL-09); loaded once. */
    private val trendWeightG = MutableStateFlow<Int?>(null)

    init {
        viewModelScope.launch { trendWeightG.value = progression.trendWeightG() }
        // Recomputed whenever the workout changes, so an edit from History shows up here straight away.
        viewModelScope.launch {
            combine(workouts.observeSession(sessionId), workouts.observeSessionExercises(sessionId)) { s, rows -> s to rows }.collectLatest { (session, rows) ->
                load(session, rows)
            }
        }
    }

    private suspend fun load(session: WorkoutSessionEntity?, rows: List<SessionExerciseWithSets>) {
        routineName.value = session?.routineId?.let { workouts.routine(it)?.name }
        // An old workout never offers to overwrite today's routine with its plan.
        differs.value = !route.fromHistory && workouts.differsFromTemplate(sessionId)
        inDeficit.value = session != null && progression.inDeficit(session.startedAt)
        // What changes next time: the next workout starts from the routine's plan, with history as of
        // this session, so an old summary still shows what that workout implied.
        val plan = session?.routineId?.let { workouts.routinePlan(it) }.orEmpty()
        val asOf = session?.startedAt ?: Long.MAX_VALUE
        val style = unitStyle(progression.currentUnits())
        nextTime.value = rows
            .filter { ex -> ex.sets.any { it.completedAt != null } }
            .map { ex ->
                val row = plan[ex.exercise.id]
                val rec = progression.recommend(
                    ex.exercise,
                    targetSets = row?.targetSets ?: ex.entry.targetSets,
                    repMin = row?.repMin ?: ex.entry.repMin,
                    repMax = row?.repMax ?: ex.entry.repMax,
                    asOf = asOf,
                )
                val loads = ex.sets.filter { it.completedAt != null && it.setType == SetType.WORKING }.map { it.loadG ?: 0 }
                val top = if (ex.exercise.loadType == LoadType.ASSISTED) loads.minOrNull() else loads.maxOrNull()
                rec to Targets.nextTime(ex.exercise.name, rec, top, style)
            }
            .sortedBy { (rec, _) -> if (rec.action == Action.INCREASE) 0 else 1 }
            .map { it.second }
    }

    val ui: StateFlow<SummaryUi> = combine(
        workouts.observeSession(sessionId),
        workouts.observeSessionExercises(sessionId),
        combine(routineName, differs, trendWeightG, inDeficit, progression.units) { a, b, c, d, e -> Header(a, b, c, d, unitStyle(e)) },
        progression.observeSessionRecords(sessionId),
        nextTime,
    ) { session, exercises, (routine, differs, trendWeight, inDeficit, style), records, nextTime ->
        if (session == null) return@combine SummaryUi(fromHistory = route.fromHistory, missing = true)
        // Volume counts what each set lifted, so an assisted set adds body weight minus the assistance.
        val sets = exercises.flatMap { ex ->
            Loads.lifted(ex.sets.filter { it.completedAt != null }.sortedBy { it.setIndex }.map { it.toLogged() }, ex.exercise.loadType, trendWeight)
        }
        SummaryUi(
            title = routine ?: "Workout",
            date = session.logDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)),
            stats = SessionStats.of(session.startedAt, session.endedAt ?: session.startedAt, sets),
            exercises = exercises.map { ex ->
                val done = ex.sets.filter { it.completedAt != null }.sortedBy { it.setIndex }.map { it.toLogged() }
                val rec = ProgressionRepository.decode(ex.entry.recommendationJson)?.takeIf { it.action != Action.BASELINE }
                val hit = rec != null && done.isNotEmpty() && Targets.hit(rec, done)
                SummaryExercise(
                    name = ex.exercise.name,
                    sets = if (done.isEmpty()) "Skipped" else Loads.describe(done, style, ex.exercise.loadType).ifEmpty { "Warm-ups only" },
                    target = rec?.takeIf { done.isNotEmpty() }?.let { "Target ${Targets.describe(it, style)} · ${if (hit) "hit" else "missed"}" },
                    hit = hit,
                )
            },
            notes = session.notes,
            canSaveToRoutine = differs,
            routineName = routine,
            records = records.map { pr ->
                val exercise = exercises.firstOrNull { it.exercise.id == pr.exerciseId }?.exercise
                val assisted = exercise?.loadType == LoadType.ASSISTED
                "${exercise?.name ?: "Exercise"}: ${Labels.record(pr.type, assisted)}, ${Labels.recordValue(pr.type, pr.value, pr.loadG, assisted, style)}"
            },
            nextTime = nextTime,
            inDeficit = inDeficit,
            fromHistory = route.fromHistory,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SummaryUi())

    private data class Header(val routine: String?, val differs: Boolean, val trendWeightG: Int?, val inDeficit: Boolean, val style: UnitStyle)

    fun saveToRoutine() = viewModelScope.launch {
        workouts.saveToTemplate(sessionId)
        differs.value = false
    }
}

/**
 * Workout summary (WL-08): totals, PRs, targets hit or missed, and what changes next time. Opened for a
 * past workout it's also the way into editing it (WL-10); [edited] says an edit was just saved.
 */
@Composable
fun SummaryScreen(onDone: () -> Unit, onEdit: (Long) -> Unit = {}, edited: Boolean = false, viewModel: SummaryViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (ui.fromHistory) ui.title else "${ui.title} done", style = MaterialTheme.typography.headlineMedium)
        Text(ui.date, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (edited) {
            Text(HistoryText.RECOMPUTED, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        ui.stats?.let { stats ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Time", formatDuration(stats.durationMs), Modifier.weight(1f))
                Stat("Sets", "${stats.sets}", Modifier.weight(1f))
                Stat("Reps", "${stats.reps}", Modifier.weight(1f))
                Stat("Volume", Labels.wholeLoad(stats.volumeG, LocalUnitStyle.current), Modifier.weight(1.4f))
            }
        }
        if (ui.inDeficit) Text(DeficitText.HOLDING, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ui.records.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("New PRs", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    ui.records.forEach { Text(it) }
                }
            }
        }
        ui.exercises.forEach { ex ->
            Column {
                Text(ex.name, style = MaterialTheme.typography.titleMedium)
                Text(ex.sets, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ex.target?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (ex.hit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (ui.nextTime.isNotEmpty()) {
            Text("Next time", style = MaterialTheme.typography.titleMedium)
            ui.nextTime.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        ui.notes?.let { Text("Notes: $it", style = MaterialTheme.typography.bodyMedium) }
        if (ui.canSaveToRoutine && ui.routineName != null) {
            OutlinedButton(onClick = viewModel::saveToRoutine, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("Save today's changes to ${ui.routineName}")
            }
        }
        if (ui.fromHistory && !ui.missing) {
            OutlinedButton(onClick = { onEdit(viewModel.sessionId) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Edit workout") }
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
