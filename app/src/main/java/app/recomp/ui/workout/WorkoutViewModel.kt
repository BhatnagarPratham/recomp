package app.recomp.ui.workout

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.recomp.data.db.ExerciseEntity
import app.recomp.data.db.SessionExerciseWithSets
import app.recomp.data.repo.ProgressionRepository
import app.recomp.data.repo.TrainingRepository
import app.recomp.data.repo.toLogged
import app.recomp.data.repo.WorkoutRepository
import app.recomp.domain.model.Equipment
import app.recomp.domain.model.LoadType
import app.recomp.domain.model.Mechanic
import app.recomp.domain.model.SessionStatus
import app.recomp.domain.model.SetType
import app.recomp.domain.progression.Action
import app.recomp.domain.progression.Recommendation
import app.recomp.domain.progression.Targets
import app.recomp.domain.training.DraftSet
import app.recomp.domain.training.Loads
import app.recomp.domain.training.LoggedSet
import app.recomp.domain.training.Prefill
import app.recomp.domain.training.SetValues
import app.recomp.domain.training.Supersets
import app.recomp.domain.training.WarmupPlanner
import app.recomp.ui.components.NewExercise
import app.recomp.ui.navigation.WorkoutRoute
import app.recomp.workout.RestState
import app.recomp.workout.RestTimer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One set row. [load] and [reps] are what a tick would save: what you entered, else the ghost value. */
data class SetUi(
    val id: Long,
    val label: String,
    val type: SetType,
    val load: Int?,
    val reps: Int?,
    val loadIsGhost: Boolean,
    val repsIsGhost: Boolean,
    val enteredLoad: Int?,
    val enteredReps: Int?,
    val rir: Int?,
    val done: Boolean,
    /** Live feedback on a ticked working set that met its target (PO-03), e.g. "+1 rep". */
    val feedback: String? = null,
)

data class ExerciseUi(
    val id: Long,
    val exerciseId: Long,
    val name: String,
    val equipment: Equipment,
    val mechanic: Mechanic,
    val loadType: LoadType,
    val incrementG: Int,
    val unilateral: Boolean,
    val setupNotes: String?,
    val repMin: Int,
    val repMax: Int,
    val restSec: Int,
    val notes: String?,
    /** "45 × 12/11/10" from the last completed session, or null the first time. */
    val last: String?,
    /** Today's frozen target (PO-02); null only for sessions started before M2. */
    val target: Recommendation?,
    val sets: List<SetUi>,
    /** The normalized superset group (RT-05), or null on its own. */
    val supersetGroup: Int? = null,
    /** Place in its superset and the superset's size, for the bracket; null on its own. */
    val superset: Pair<Int, Int>? = null,
    val canSupersetWithNext: Boolean = false,
    /** WL-11's suggestion for the first heavy lift, lightest first; empty when there's none to show. */
    val warmup: List<SetValues> = emptyList(),
) {
    /** "45 × 12/12/10 · add reps", or null for a baseline. */
    val targetText: String? get() = target?.takeIf { it.action != Action.BASELINE }?.let { "${Targets.describe(it)} · ${Targets.actionLabel(it)}" }

    /** The stall card shows until the exercise's first set is ticked (PO-05). */
    val showStall: Boolean get() = target?.stalled == true && canSwap

    val logsLoad: Boolean get() = Loads.logsLoad(loadType)
    val assisted: Boolean get() = loadType == LoadType.ASSISTED
    val canSwap: Boolean get() = sets.none { it.done }

    /** "Warm-up: 20 × 8 · 30 × 5 · 40 × 3", or null. */
    val warmupText: String? get() = warmup.takeIf { it.isNotEmpty() }?.let(WarmupPlanner::describe)

    fun ref() = Supersets.ExerciseRef(supersetGroup, sets.map { Supersets.SetRef(it.id, it.type == SetType.WARMUP, it.done) })
}

data class WorkoutUi(
    val loaded: Boolean = false,
    val active: Boolean = true,
    val title: String = "",
    val startedAt: Long = 0,
    val notes: String? = null,
    val exercises: List<ExerciseUi> = emptyList(),
    val selectedSetId: Long? = null,
    val rest: RestState? = null,
    val keepScreenOn: Boolean = false,
) {
    private val allSets get() = exercises.flatMap { ex -> ex.sets.map { ex to it } }
    val selected: Pair<ExerciseUi, SetUi>? get() = allSets.firstOrNull { it.second.id == selectedSetId }
    val untickedCount: Int get() = allSets.count { !it.second.done }
    val doneCount: Int get() = allSets.count { it.second.done }
}

sealed interface TickResult {
    data object Saved : TickResult
    /** A weighted exercise with no weight entered and no history: ask for one. */
    data class NeedsWeight(val setId: Long) : TickResult
}

/**
 * The active workout (WL-01 to WL-09, TRD §6.1). The screen is a Flow over Room: every edit and tick
 * is written immediately, so process death loses nothing. Ghost values come from the last completed
 * exposure of each exercise through [Prefill].
 */
@HiltViewModel
class WorkoutViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val workouts: WorkoutRepository,
    private val training: TrainingRepository,
    private val progression: ProgressionRepository,
    private val restTimer: RestTimer,
) : ViewModel() {
    val sessionId = savedState.toRoute<WorkoutRoute>().sessionId

    private val history = MutableStateFlow<Map<Long, List<LoggedSet>>>(emptyMap())
    private val requestedSelection = MutableStateFlow<Long?>(null)
    private val title = MutableStateFlow("")
    private var routineId: Long? = null

    val library: StateFlow<List<ExerciseEntity>> =
        training.observeLibrary().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            routineId = workouts.observeSession(sessionId).first()?.routineId
            title.value = routineId?.let { workouts.routine(it)?.name } ?: "Workout"
        }
    }

    private val exercises = workouts.observeSessionExercises(sessionId).onEach { rows ->
        val missing = rows.map { it.exercise.id }.filter { it !in history.value }.distinct()
        if (missing.isNotEmpty()) {
            val loaded = missing.associateWith { workouts.lastExposure(it) }
            history.update { it + loaded }
        }
    }

    val ui: StateFlow<WorkoutUi> = combine(
        combine(workouts.observeSession(sessionId), title, training.settings) { s, t, settings -> Triple(s, t, settings.keepScreenOn) },
        exercises,
        history,
        requestedSelection,
        restTimer.state,
    ) { (session, title, keepScreenOn), rows, history, requested, rest ->
        val groups = Supersets.normalize(rows.map { it.entry.supersetGroup })
        val heavy = rows.indexOfFirst { it.isHeavyLift() }
        val exercises = rows.mapIndexed { i, row ->
            row.toUi(history[row.exercise.id]).copy(
                supersetGroup = groups[i],
                superset = Supersets.position(groups, i),
                canSupersetWithNext = Supersets.canJoinNext(groups, i),
            ).let { if (i == heavy) it.withWarmup() else it }
        }
        // Ticking walks supersets round by round, so the first set to select follows the same order.
        val order = Supersets.order(exercises.map { it.ref() })
        val selected = requested?.takeIf { id -> order.any { it.setId == id } } ?: order.firstOrNull { !it.done }?.setId
        WorkoutUi(
            loaded = session != null,
            active = session?.status == SessionStatus.ACTIVE,
            title = title,
            startedAt = session?.startedAt ?: 0,
            notes = session?.notes,
            exercises = exercises,
            selectedSetId = selected,
            rest = rest,
            keepScreenOn = keepScreenOn,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkoutUi())

    /** WL-11: the first compound lift with a target load. Bodyweight and assisted loads aren't warmed up as a share. */
    private fun SessionExerciseWithSets.isHeavyLift(): Boolean =
        exercise.mechanic == Mechanic.COMPOUND && exercise.loadType == LoadType.WEIGHTED &&
            ProgressionRepository.decode(entry.recommendationJson)?.takeIf { it.action != Action.BASELINE }?.loadG != null

    /** The warm-up line shows until the exercise has warm-up sets or its first set is ticked. */
    private fun ExerciseUi.withWarmup(): ExerciseUi {
        if (sets.any { it.type == SetType.WARMUP || it.done }) return this
        val load = target?.loadG ?: return this
        return copy(warmup = WarmupPlanner.plan(load, incrementG))
    }

    private fun SessionExerciseWithSets.toUi(last: List<LoggedSet>?): ExerciseUi {
        val ordered = sets.sortedBy { it.setIndex }
        val logsLoad = Loads.logsLoad(exercise.loadType)
        val target = ProgressionRepository.decode(entry.recommendationJson)
        val plannedSets = target?.takeIf { it.action != Action.BASELINE }?.let(Targets::workingSets).orEmpty()
        val ghosts = Prefill.ghosts(
            ordered.map { DraftSet(it.setType, if (logsLoad) it.loadG else null, it.reps) },
            last.orEmpty(),
            defaultReps = entry.repMax,
            target = plannedSets,
        )
        val lastWorking = last.orEmpty().filter { it.type == SetType.WORKING }
        var working = 0
        return ExerciseUi(
            id = entry.id,
            exerciseId = exercise.id,
            name = exercise.name,
            equipment = exercise.equipment,
            mechanic = exercise.mechanic,
            loadType = exercise.loadType,
            incrementG = exercise.incrementG,
            unilateral = exercise.isUnilateral,
            setupNotes = exercise.setupNotes,
            repMin = entry.repMin,
            repMax = entry.repMax,
            restSec = entry.restSec,
            notes = entry.notes,
            last = last?.let { Loads.describe(it, exercise.loadType) }?.ifEmpty { null },
            target = target,
            sets = ordered.mapIndexed { i, set ->
                val ordinal = if (set.setType == SetType.WORKING) working else -1
                SetUi(
                    id = set.id,
                    label = when (set.setType) {
                        SetType.WORKING -> "${++working}"
                        SetType.WARMUP -> "W"
                        SetType.DROP -> "D"
                        SetType.FAILURE -> "F"
                    },
                    type = set.setType,
                    load = if (logsLoad) ghosts[i].loadG else null,
                    reps = ghosts[i].reps,
                    loadIsGhost = logsLoad && set.loadG == null,
                    repsIsGhost = set.reps == null,
                    enteredLoad = set.loadG,
                    enteredReps = set.reps,
                    rir = set.rir,
                    done = set.completedAt != null,
                    feedback = if (set.completedAt != null && ordinal >= 0) {
                        Targets.feedback(plannedSets.getOrNull(ordinal), set.toLogged(), lastWorking.getOrNull(ordinal), exercise.loadType == LoadType.ASSISTED)
                    } else {
                        null
                    },
                )
            },
        )
    }

    private fun find(setId: Long): Pair<ExerciseUi, SetUi>? =
        ui.value.exercises.firstNotNullOfOrNull { ex -> ex.sets.firstOrNull { it.id == setId }?.let { ex to it } }

    fun select(setId: Long) {
        requestedSelection.value = setId
    }

    // Steppers and direct entry (WL-03)

    fun stepLoad(setId: Long, up: Boolean) {
        val (ex, set) = find(setId) ?: return
        val next = Loads.step(set.load ?: 0, ex.incrementG, up)
        viewModelScope.launch { workouts.setValues(setId, next, set.enteredReps) }
    }

    fun stepReps(setId: Long, up: Boolean) {
        val (ex, set) = find(setId) ?: return
        val next = ((set.reps ?: ex.repMax) + if (up) 1 else -1).coerceAtLeast(0)
        viewModelScope.launch { workouts.setValues(setId, set.enteredLoad, next) }
    }

    fun enterLoad(setId: Long, grams: Int) {
        val (_, set) = find(setId) ?: return
        viewModelScope.launch { workouts.setValues(setId, grams, set.enteredReps) }
    }

    fun enterReps(setId: Long, reps: Int) {
        val (_, set) = find(setId) ?: return
        viewModelScope.launch { workouts.setValues(setId, set.enteredLoad, reps) }
    }

    /** Optional effort per set (WL-04): tapping the selected value again clears it. */
    fun toggleRir(setId: Long, rir: Int) {
        val (_, set) = find(setId) ?: return
        viewModelScope.launch { workouts.setRir(setId, if (set.rir == rir) null else rir) }
    }

    // Ticking (WL-02, WL-05, WL-06)

    /**
     * Ticks a set with its shown values, selects the next unticked set and starts the exercise's rest.
     * In a superset the next exercise's set follows with no rest, and rest starts after the round
     * ([Supersets.afterTick]). Ticking a done set unticks it. An assisted set needs its assistance typed
     * the first time, like a weight.
     */
    fun tick(setId: Long, loadOverride: Int? = null): TickResult {
        val (ex, set) = find(setId) ?: return TickResult.Saved
        if (set.done) {
            viewModelScope.launch { workouts.uncompleteSet(setId) }
            return TickResult.Saved
        }
        val load = when (ex.loadType) {
            LoadType.BODYWEIGHT -> null
            LoadType.BODYWEIGHT_PLUS -> loadOverride ?: set.load ?: 0
            LoadType.WEIGHTED, LoadType.ASSISTED -> loadOverride ?: set.load ?: return TickResult.NeedsWeight(setId)
        }
        val reps = set.reps ?: ex.repMax
        val after = Supersets.afterTick(ui.value.exercises.map { it.ref() }, setId)
        requestedSelection.value = after.nextSetId
        viewModelScope.launch {
            workouts.completeSet(setId, load, reps, set.rir)
            when {
                after.rest -> restTimer.start(ex.restSec)
                // Mid-round in a superset: going straight on ends any rest still running.
                after.nextSetId != null -> restTimer.skip()
            }
        }
        return TickResult.Saved
    }

    // Sets

    fun setType(setId: Long, type: SetType) = viewModelScope.launch { workouts.setType(setId, type) }
    fun deleteSet(setId: Long) = viewModelScope.launch { workouts.deleteSet(setId) }
    fun addSet(sessionExerciseId: Long) = viewModelScope.launch { workouts.addSet(sessionExerciseId) }

    /** WL-11's *Add*: the warm-ups go in as sets to tick, and the first is selected. */
    fun addWarmups(exercise: ExerciseUi) = viewModelScope.launch {
        workouts.addWarmups(exercise.id, exercise.warmup)?.let { requestedSelection.value = it }
    }

    // The plan (RT-03)

    fun addExercise(exercise: ExerciseEntity) = viewModelScope.launch { workouts.addExercise(sessionId, exercise.id) }

    fun createExercise(new: NewExercise, swapFor: Long?) = viewModelScope.launch {
        val id = training.createCustomExercise(new.name, new.mechanic, new.equipment)
        if (swapFor != null) workouts.swapExercise(swapFor, id) else workouts.addExercise(sessionId, id)
    }

    fun swap(sessionExerciseId: Long, exercise: ExerciseEntity) = viewModelScope.launch { workouts.swapExercise(sessionExerciseId, exercise.id) }
    fun remove(sessionExerciseId: Long) = viewModelScope.launch { workouts.removeExercise(sessionExerciseId) }
    fun move(sessionExerciseId: Long, up: Boolean) = viewModelScope.launch { workouts.moveExercise(sessionId, sessionExerciseId, up) }
    fun supersetWithNext(sessionExerciseId: Long) = viewModelScope.launch { workouts.supersetWithNext(sessionId, sessionExerciseId) }
    fun splitSuperset(sessionExerciseId: Long) = viewModelScope.launch { workouts.splitSuperset(sessionId, sessionExerciseId) }
    fun setExerciseNotes(sessionExerciseId: Long, notes: String) = viewModelScope.launch { workouts.setExerciseNotes(sessionExerciseId, notes) }
    fun setSessionNotes(notes: String) = viewModelScope.launch { workouts.setSessionNotes(sessionId, notes) }

    // Stall choices (PO-05)

    fun deloadToday(sessionExerciseId: Long) = viewModelScope.launch { progression.deloadToday(sessionExerciseId) }

    fun holdStall(sessionExerciseId: Long) = viewModelScope.launch { progression.holdStall(sessionExerciseId) }

    /** *Swap* from the stall card changes today's workout and the routine. */
    fun swapForStall(exercise: ExerciseUi, replacement: ExerciseEntity) = viewModelScope.launch {
        workouts.swapExercise(exercise.id, replacement.id)
        routineId?.let { training.swapInRoutine(it, exercise.exerciseId, replacement.id) }
    }

    // Rest and settings

    fun skipRest() = viewModelScope.launch { restTimer.skip() }
    fun addRest() = viewModelScope.launch { restTimer.add(30) }
    fun restEnded(endsAt: Long) = viewModelScope.launch { restTimer.clearIfEndsAt(endsAt) }
    fun setKeepScreenOn(on: Boolean) = viewModelScope.launch { training.setKeepScreenOn(on) }

    // Ending

    /** Finishes the workout. False means nothing was logged, so the caller offers to discard instead. */
    suspend fun finish(): Boolean {
        if (!workouts.hasCompletedSets(sessionId)) return false
        restTimer.skip()
        workouts.finish(sessionId)
        return true
    }

    suspend fun discard() {
        restTimer.skip()
        workouts.discard(sessionId)
    }
}
