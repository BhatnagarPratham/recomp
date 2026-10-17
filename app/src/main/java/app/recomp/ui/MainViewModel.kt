package app.recomp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.recomp.data.db.WorkoutSessionEntity
import app.recomp.data.repo.TrainingRepository
import app.recomp.data.repo.WorkoutRepository
import app.recomp.workout.RestTimer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
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

/** Activity-wide work: seeding the library, the resume prompt, and opening the workout from its notification. */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val training: TrainingRepository,
    private val workouts: WorkoutRepository,
    private val restTimer: RestTimer,
) : ViewModel() {
    private var resumeChecked = false
    private val _prompt = MutableStateFlow<ResumePrompt?>(null)
    val prompt: StateFlow<ResumePrompt?> = _prompt
    private val _nav = Channel<AppNav>(Channel.BUFFERED)
    val nav = _nav.receiveAsFlow()

    init {
        viewModelScope.launch { training.ensureLibrarySeeded() }
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
