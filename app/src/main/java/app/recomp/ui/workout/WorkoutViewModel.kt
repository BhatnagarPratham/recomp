package app.recomp.ui.workout

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.recomp.data.db.ExerciseEntity
import app.recomp.data.db.SessionExerciseWithSets
import app.recomp.data.repo.TrainingRepository
import app.recomp.data.repo.WorkoutRepository
import app.recomp.domain.model.Equipment
import app.recomp.domain.model.LoadType
import app.recomp.domain.model.SessionStatus
import app.recomp.domain.model.SetType
import app.recomp.domain.training.DraftSet
import app.recomp.domain.training.Loads
import app.recomp.domain.training.LoggedSet
import app.recomp.domain.training.Prefill
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
)

data class ExerciseUi(
    val id: Long,
    val exerciseId: Long,
    val name: String,
    val equipment: Equipment,
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
    val sets: List<SetUi>,
) {
    val logsLoad: Boolean get() = Loads.logsLoad(loadType)
    val canSwap: Boolean get() = sets.none { it.done }
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
    private val restTimer: RestTimer,
) : ViewModel() {
    val sessionId = savedState.toRoute<WorkoutRoute>().sessionId

    private val history = MutableStateFlow<Map<Long, List<LoggedSet>>>(emptyMap())
    private val requestedSelection = MutableStateFlow<Long?>(null)
    private val title = MutableStateFlow("")

    val library: StateFlow<List<ExerciseEntity>> =
        training.observeLibrary().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            title.value = workouts.observeSession(sessionId).first()?.routineId?.let { workouts.routine(it)?.name } ?: "Workout"
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
        val exercises = rows.map { it.toUi(history[it.exercise.id]) }
        val sets = exercises.flatMap { it.sets }
        val selected = requested?.takeIf { id -> sets.any { it.id == id } } ?: sets.firstOrNull { !it.done }?.id
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

    private fun SessionExerciseWithSets.toUi(last: List<LoggedSet>?): ExerciseUi {
        val ordered = sets.sortedBy { it.setIndex }
        val logsLoad = Loads.logsLoad(exercise.loadType)
        val ghosts = Prefill.ghosts(
            ordered.map { DraftSet(it.setType, if (logsLoad) it.loadG else null, it.reps) },
            last.orEmpty(),
            defaultReps = entry.repMax,
        )
        var working = 0
        return ExerciseUi(
            id = entry.id,
            exerciseId = exercise.id,
            name = exercise.name,
            equipment = exercise.equipment,
            loadType = exercise.loadType,
            incrementG = exercise.incrementG,
            unilateral = exercise.isUnilateral,
            setupNotes = exercise.setupNotes,
            repMin = entry.repMin,
            repMax = entry.repMax,
            restSec = entry.restSec,
            notes = entry.notes,
            last = last?.let { Loads.describe(it) }?.ifEmpty { null },
            sets = ordered.mapIndexed { i, set ->
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
     * Ticks a set with its shown values, starts the exercise's rest and selects the next unticked set.
     * Ticking a done set unticks it.
     */
    fun tick(setId: Long, loadOverride: Int? = null): TickResult {
        val (ex, set) = find(setId) ?: return TickResult.Saved
        if (set.done) {
            viewModelScope.launch { workouts.uncompleteSet(setId) }
            return TickResult.Saved
        }
        val load = when (ex.loadType) {
            LoadType.BODYWEIGHT -> null
            LoadType.BODYWEIGHT_PLUS, LoadType.ASSISTED -> loadOverride ?: set.load ?: 0
            LoadType.WEIGHTED -> loadOverride ?: set.load ?: return TickResult.NeedsWeight(setId)
        }
        val reps = set.reps ?: ex.repMax
        val order = ui.value.exercises.flatMap { it.sets }
        val after = order.dropWhile { it.id != setId }.drop(1) + order.takeWhile { it.id != setId }
        val next = after.firstOrNull { !it.done }
        requestedSelection.value = next?.id
        viewModelScope.launch {
            workouts.completeSet(setId, load, reps, set.rir)
            // No rest after the last set of the workout.
            if (next != null) restTimer.start(ex.restSec)
        }
        return TickResult.Saved
    }

    // Sets

    fun setType(setId: Long, type: SetType) = viewModelScope.launch { workouts.setType(setId, type) }
    fun deleteSet(setId: Long) = viewModelScope.launch { workouts.deleteSet(setId) }
    fun addSet(sessionExerciseId: Long) = viewModelScope.launch { workouts.addSet(sessionExerciseId) }

    // The plan (RT-03)

    fun addExercise(exercise: ExerciseEntity) = viewModelScope.launch { workouts.addExercise(sessionId, exercise.id) }

    fun createExercise(new: NewExercise, swapFor: Long?) = viewModelScope.launch {
        val id = training.createCustomExercise(new.name, new.mechanic, new.equipment)
        if (swapFor != null) workouts.swapExercise(swapFor, id) else workouts.addExercise(sessionId, id)
    }

    fun swap(sessionExerciseId: Long, exercise: ExerciseEntity) = viewModelScope.launch { workouts.swapExercise(sessionExerciseId, exercise.id) }
    fun remove(sessionExerciseId: Long) = viewModelScope.launch { workouts.removeExercise(sessionExerciseId) }
    fun move(sessionExerciseId: Long, up: Boolean) = viewModelScope.launch { workouts.moveExercise(sessionId, sessionExerciseId, up) }
    fun setExerciseNotes(sessionExerciseId: Long, notes: String) = viewModelScope.launch { workouts.setExerciseNotes(sessionExerciseId, notes) }
    fun setSessionNotes(notes: String) = viewModelScope.launch { workouts.setSessionNotes(sessionId, notes) }

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
