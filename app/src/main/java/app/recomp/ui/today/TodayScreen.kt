package app.recomp.ui.today

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.recomp.data.db.RoutineEntity
import app.recomp.data.repo.NutritionRepository
import app.recomp.data.repo.PreviewLine
import app.recomp.data.repo.ProgressionRepository
import app.recomp.data.repo.TrainingRepository
import app.recomp.data.repo.WorkoutAlreadyActiveException
import app.recomp.data.repo.WorkoutRepository
import app.recomp.data.repo.TodayNutrition
import app.recomp.data.repo.WorkoutRepository.ExtraEntry
import app.recomp.domain.nutrition.MainNumber
import app.recomp.domain.nutrition.ProteinGoal
import app.recomp.domain.training.WeekProgress
import app.recomp.domain.training.WeeklyPlan
import app.recomp.ui.components.Labels
import app.recomp.ui.components.formatDuration
import app.recomp.ui.extra.ExtraLogSheet
import app.recomp.ui.onboarding.GoalText
import app.recomp.workout.ActiveWorkoutService
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ActiveCard(val sessionId: Long, val title: String, val startedAt: Long, val setsDone: Int)

data class LastCard(val sessionId: Long, val title: String, val date: LocalDate)

data class TodayUi(
    val loaded: Boolean = false,
    val setupDone: Boolean = false,
    val active: ActiveCard? = null,
    val next: RoutineEntity? = null,
    val nextExercises: List<PreviewLine> = emptyList(),
    val rotation: List<RoutineEntity> = emptyList(),
    val dueExtras: List<RoutineEntity> = emptyList(),
    val otherExtras: List<RoutineEntity> = emptyList(),
    val week: WeekProgress? = null,
    val last: LastCard? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val training: TrainingRepository,
    private val workouts: WorkoutRepository,
    private val progression: ProgressionRepository,
    nutrition: NutritionRepository,
) : ViewModel() {
    /** GL-07, GL-10: the goal's main number and the 7-day average. */
    val food: StateFlow<TodayNutrition?> = nutrition.observeToday().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { nutrition.lockRampBaseline() }
    }


    private val active: Flow<ActiveCard?> = workouts.observeActive().flatMapLatest { session ->
        if (session == null) {
            flowOf(null)
        } else {
            val title = session.routineId?.let { workouts.routine(it)?.name } ?: "Workout"
            workouts.observeSessionExercises(session.id).map { exercises ->
                ActiveCard(session.id, title, session.startedAt, exercises.sumOf { ex -> ex.sets.count { it.completedAt != null } })
            }
        }
    }

    /** The next routine with each exercise's target (PRD §7.5), refreshed when the routine or history changes. */
    private val next: Flow<Pair<RoutineEntity?, List<PreviewLine>>> = training.observeNextRoutine().flatMapLatest { routine ->
        if (routine == null) flowOf(null to emptyList())
        else combine(training.observeRoutineExercises(routine.id), workouts.observeLatestStrength()) { _, _ -> routine }
            .map { routine to progression.preview(it.id) }
    }

    private val last: Flow<LastCard?> = workouts.observeLatestStrength().map { session ->
        session?.let { LastCard(it.id, it.routineId?.let { id -> workouts.routine(id)?.name } ?: "Workout", it.logDate) }
    }

    private val week = workouts.observeToday().flatMapLatest { today -> workouts.observeWeek(today).map { today to it } }

    private val extras = combine(training.settings, training.observeExtras(), week) { settings, extras, (today, week) ->
        val plans = settings.extras.map { it.toPlan() }
        val due = WeeklyPlan.dueToday(today, week, plans).toSet()
        Triple(
            extras.filter { it.id in due } to extras.filter { it.id !in due },
            WeeklyPlan.progress(today, week, settings.strengthDaysPerWeek, plans),
            settings.setupDone,
        )
    }

    val ui: StateFlow<TodayUi> = combine(active, next, training.observeRotation(), extras, last) { active, next, rotation, extras, last ->
        val (split, week, setupDone) = extras
        TodayUi(
            loaded = true,
            setupDone = setupDone,
            active = active,
            next = next.first,
            nextExercises = next.second,
            rotation = rotation,
            dueExtras = split.first,
            otherExtras = split.second,
            week = week,
            last = last,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUi())

    /** Starts a routine, or returns the workout that's already active (one at a time, TRD §6.1). */
    suspend fun start(routineId: Long?): Long = try {
        if (routineId == null) workouts.startEmpty() else workouts.startRoutine(routineId)
    } catch (e: WorkoutAlreadyActiveException) {
        e.sessionId
    }

    suspend fun extraPrefill(routine: RoutineEntity): ExtraEntry =
        workouts.lastExtra(routine.id) ?: ExtraEntry(style = Labels.kind(routine.kind), durationMin = 60, intensity = null, notes = null)

    fun logExtra(routine: RoutineEntity, entry: ExtraEntry) = viewModelScope.launch { workouts.logExtra(routine, entry) }
}

/** Today (HM-01): the goal's main number, next workout or the active one, extra sessions due, weekly plan progress. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayScreen(
    onOpenWorkout: (Long) -> Unit,
    onOpenSummary: (Long) -> Unit,
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val food by viewModel.food.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingStart by rememberSaveable { mutableStateOf<Long?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var logging by remember { mutableStateOf<Pair<RoutineEntity, ExtraEntry>?>(null) }

    fun begin(routineId: Long?) = scope.launch {
        val id = viewModel.start(routineId)
        ActiveWorkoutService.sync(context)
        onOpenWorkout(id)
    }

    // The workout service needs these (TRD §6.1). The workout starts either way; without them the rest
    // timer stays in the app.
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        begin(pendingStart?.takeIf { it >= 0 })
        pendingStart = null
    }

    fun start(routineId: Long?) {
        if (ActiveWorkoutService.canRun(context)) {
            begin(routineId)
        } else {
            pendingStart = routineId ?: -1
            permissions.launch(ActiveWorkoutService.requiredPermissions)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Today", style = MaterialTheme.typography.headlineMedium)
        Text(
            LocalDate.now().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!ui.loaded) return@Column

        food?.let { NutritionCard(it) }

        val active = ui.active
        if (active != null) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${active.title} in progress", style = MaterialTheme.typography.titleMedium)
                    Text("${formatDuration(System.currentTimeMillis() - active.startedAt)} · ${active.setsDone} sets logged")
                    Button(onClick = { onOpenWorkout(active.sessionId) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Resume") }
                }
            }
        } else if (ui.setupDone) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val next = ui.next
                    if (next != null) {
                        Text("${next.name} is next", style = MaterialTheme.typography.titleLarge)
                        ui.nextExercises.forEach { line ->
                            Row(Modifier.fillMaxWidth()) {
                                Text(line.name, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    line.target + if (line.weightUp) " ↑" else "",
                                    color = if (line.weightUp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Button(onClick = { start(next.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                            Text("Start ${next.name}", style = MaterialTheme.typography.titleMedium)
                        }
                    } else {
                        Text("No routines in the rotation", style = MaterialTheme.typography.titleMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (ui.rotation.size > 1) {
                            OutlinedButton(onClick = { choosing = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Choose another") }
                        }
                        OutlinedButton(onClick = { start(null) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Empty workout") }
                    }
                }
            }
        }

        ui.dueExtras.forEach { routine ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${routine.name} today", style = MaterialTheme.typography.titleMedium)
                    Button(
                        onClick = { scope.launch { logging = routine to viewModel.extraPrefill(routine) } },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) { Text("Log ${routine.name}") }
                }
            }
        }
        if (ui.otherExtras.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.otherExtras.forEach { routine ->
                    OutlinedButton(onClick = { scope.launch { logging = routine to viewModel.extraPrefill(routine) } }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Log ${routine.name}")
                    }
                }
            }
        }

        ui.week?.takeIf { ui.setupDone }?.let { week ->
            val names = (ui.dueExtras + ui.otherExtras).associate { it.id to it.name }
            val parts = listOf("Strength ${week.strengthDays} of ${week.strengthTarget} days") +
                week.extras.mapNotNull { e -> names[e.routineId]?.let { if (e.planned > 0) "$it ${e.done} of ${e.planned}" else "$it ${e.done}" } }
            Text("This week: " + parts.joinToString(" · "), style = MaterialTheme.typography.bodyLarge)
        }

        ui.last?.let { last ->
            Text(
                "Last: ${last.title}, ${last.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onOpenSummary(last.sessionId) }.padding(vertical = 12.dp),
            )
        }
    }

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Start a routine") },
            text = {
                Column {
                    ui.rotation.forEach { routine ->
                        TextButton(onClick = { choosing = false; start(routine.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(routine.name + if (routine.id == ui.next?.id) " (next)" else "")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } },
        )
    }

    logging?.let { (routine, entry) ->
        ExtraLogSheet(routine, entry, onDismiss = { logging = null }) { saved ->
            logging = null
            viewModel.logExtra(routine, saved)
        }
    }
}

/**
 * The goal's main number (GL-07, PRD §4.8), protein first among the rest (NU-02), and the 7-day average
 * (GL-10). Being over a target is a plain number, never failure styling.
 * TODO(M3b): food logging adds a "Log food" entry here and fills in what you've eaten.
 */
@Composable
private fun NutritionCard(n: TodayNutrition) {
    val t = n.target
    val (label, value, detail) = when (val m = n.main) {
        is MainNumber.CaloriesLeft -> Triple(
            "Calories left",
            if (m.left >= 0) GoalText.kcal(m.left) else "${GoalText.kcal(-m.left)} over",
            "of ${GoalText.kcal(m.target)}",
        )
        is MainNumber.ProteinLeft -> if (m.target == null) {
            Triple("Protein so far", "${m.eaten} g", "Measuring what you usually eat this week, so there's no target yet.")
        } else {
            Triple("Protein left", "${m.left} g", "of ${m.target} g" + rampNote(n.protein))
        }
        is MainNumber.CaloriesInRange -> Triple(
            "Calories within your range",
            when {
                m.inRange -> "In range"
                m.aboveBy > 0 -> "${GoalText.kcal(m.aboveBy)} over the range"
                else -> "${GoalText.n(m.belowBy)}–${GoalText.kcal(m.band.last - m.eaten)} to go"
            },
            "Range ${GoalText.n(m.band.first)}–${GoalText.kcal(m.band.last)}",
        )
        is MainNumber.CaloriesToEat -> Triple(
            "Calories still to eat",
            if (m.left > 0) GoalText.kcal(m.left) else "Target reached",
            "of ${GoalText.kcal(m.target)}",
        )
    }
    val proteinTarget = n.protein.todayG
    val others = buildList {
        if (n.main !is MainNumber.ProteinLeft) add(if (proteinTarget != null) "Protein ${(proteinTarget - n.eaten.proteinG).coerceAtLeast(0)} g left" else "Protein ${n.eaten.proteinG} g")
        if (n.main is MainNumber.ProteinLeft) add("Calories ${GoalText.kcal(t.kcal - n.eaten.kcal)} left")
        add("fat ${t.fatG} g")
        add("carbs ${t.carbsG} g")
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(others.joinToString(" · "), style = MaterialTheme.typography.bodyLarge)
            val week = n.week
            Text(
                if (week == null) "7-day average appears once you log food."
                else "7-day average: ${GoalText.n(week.kcal)} of ${GoalText.kcal(week.kcalTarget)}" +
                    (week.proteinTargetG?.let { " · protein ${week.proteinG} of $it g" } ?: " · protein ${week.proteinG} g") +
                    " · ${week.daysLogged} ${if (week.daysLogged == 1) "day" else "days"} logged",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun rampNote(goal: ProteinGoal): String = when (goal) {
    is ProteinGoal.NotStarted -> ". The protein ramp starts when you log food."
    is ProteinGoal.Ramping -> " this week, rising to ${goal.fullG} g (ramp week ${goal.week})."
    else -> ""
}
