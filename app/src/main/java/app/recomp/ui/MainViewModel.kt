package app.recomp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.recomp.data.backup.BackupManager
import app.recomp.data.backup.PendingOutcome
import app.recomp.data.db.WorkoutSessionEntity
import app.recomp.data.food.FoodSeeder
import app.recomp.data.repo.CheckinRepository
import app.recomp.data.repo.PersonaRepository
import app.recomp.data.repo.TrainingRepository
import app.recomp.data.repo.WorkoutRepository
import app.recomp.workout.RestTimer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** "Resume workout?" (WL-06, TRD §6.1). [stale] sessions are more than 4 hours old. */
data class ResumePrompt(
    val session: WorkoutSessionEntity,
    val title: String,
    val setsDone: Int,
    val lastSetAt: Long,
    val stale: Boolean,
)

sealed interface AppNav {
    data class Workout(val sessionId: Long) : AppNav
    data class Summary(val sessionId: Long) : AppNav
}

/**
 * Activity-wide work: seeding the library and the foods, whether onboarding has run, the resume prompt, and opening the
 * workout from its notification.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val training: TrainingRepository,
    private val workouts: WorkoutRepository,
    private val restTimer: RestTimer,
    personas: PersonaRepository,
    foodSeeder: FoodSeeder,
    backups: BackupManager,
    checkins: CheckinRepository,
) : ViewModel() {
    /** Null while settings load. False opens onboarding, on a fresh install or the first launch after an upgrade. */
    val onboarded: StateFlow<Boolean?> = personas.onboarded.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val unreadableSeen = MutableStateFlow(false)

    /** Settings that couldn't be read, shown once per launch. Their raw values are kept aside. */
    val unreadable: StateFlow<List<String>> = combine(personas.unreadable, unreadableSeen) { keys, seen -> if (seen) emptyList() else keys }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun dismissUnreadable() {
        unreadableSeen.value = true
    }

    private var resumeChecked = false
    private val _prompt = MutableStateFlow<ResumePrompt?>(null)
    val prompt: StateFlow<ResumePrompt?> = _prompt
    private val _nav = Channel<AppNav>(Channel.BUFFERED)
    val nav = _nav.receiveAsFlow()

    /** An import interrupted last time, finished or dropped at this launch (TRD §6.5); shown once. */
    private val _restoreNotice = MutableStateFlow<PendingOutcome?>(null)
    val restoreNotice: StateFlow<PendingOutcome?> = _restoreNotice

    fun dismissRestoreNotice() {
        _restoreNotice.value = null
    }

    init {
        viewModelScope.launch {
            _restoreNotice.value = backups.finishPendingRestore()
            // Weeks that ended while the app was closed, against the data as the restore left it (PRD §12.1).
            checkins.catchUp()
        }
        viewModelScope.launch { training.ensureLibrarySeeded() }
        viewModelScope.launch { foodSeeder.ensureSeeded() }
    }

    /** Runs once per process: an ACTIVE session found at launch means the app was killed mid-workout. */
    fun onLaunch(openWorkout: Boolean) {
        if (resumeChecked) {
            if (openWorkout) openActive()
            return
        }
        resumeChecked = true
        viewModelScope.launch {
            val session = workouts.active() ?: return@launch
            if (openWorkout) {
                _nav.send(AppNav.Workout(session.id))
                return@launch
            }
            val exercises = workouts.observeSessionExercises(session.id).first()
            val lastSetAt = workouts.staleEndTime(session)
            _prompt.value = ResumePrompt(
                session = session,
                title = session.routineId?.let { workouts.routine(it)?.name } ?: "Workout",
                setsDone = exercises.sumOf { ex -> ex.sets.count { it.completedAt != null } },
                lastSetAt = lastSetAt,
                stale = System.currentTimeMillis() - session.startedAt > STALE_AFTER_MS,
            )
        }
    }

    fun openActive() = viewModelScope.launch {
        workouts.active()?.let { _nav.send(AppNav.Workout(it.id)) }
    }

    fun resume() {
        val prompt = _prompt.value ?: return
        _prompt.value = null
        viewModelScope.launch { _nav.send(AppNav.Workout(prompt.session.id)) }
    }

    /** Finishing from the prompt ends a stale session at its last ticked set, otherwise now. */
    fun finish() {
        val prompt = _prompt.value ?: return
        _prompt.value = null
        viewModelScope.launch {
            restTimer.skip()
            if (!workouts.hasCompletedSets(prompt.session.id)) {
                workouts.discard(prompt.session.id)
                return@launch
            }
            workouts.finish(prompt.session.id, endedAt = if (prompt.stale) prompt.lastSetAt else System.currentTimeMillis())
            _nav.send(AppNav.Summary(prompt.session.id))
        }
    }

    fun discard() {
        val prompt = _prompt.value ?: return
        _prompt.value = null
        viewModelScope.launch {
            restTimer.skip()
            workouts.discard(prompt.session.id)
        }
    }

    fun dismissPrompt() {
        _prompt.value = null
    }

    companion object {
        const val STALE_AFTER_MS = 4 * 60 * 60 * 1000L
    }
}
