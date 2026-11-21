package app.recomp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.recomp.data.backup.BackupManager
import app.recomp.data.backup.PendingOutcome
import app.recomp.data.db.WorkoutSessionEntity
import app.recomp.data.food.FoodSeeder
import app.recomp.data.repo.CheckinRepository
import app.recomp.data.repo.GoalRepository
import app.recomp.data.repo.HealthRepository
import app.recomp.healthconnect.HealthSyncScheduler
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

    /** A reminder or the widget opened the app on a tab (PRD §12.8). */
    data object Today : AppNav
    data object Food : AppNav
    data object Body : AppNav
    data class Checkin(val weekStart: java.time.LocalDate) : AppNav
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
    private val checkins: CheckinRepository,
    reminders: app.recomp.reminders.ReminderScheduler,
    goals: GoalRepository,
    private val health: HealthRepository,
    private val healthScheduler: HealthSyncScheduler,
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
            // GL-06: a maintenance break whose end date has passed resumes now, and the finish line is checked.
            runCatching { goals.refresh() }
            // After the restore, so an import's data is what Health Connect is read against (TRD §6.3).
            syncHealthNow()
        }
        viewModelScope.launch { training.ensureLibrarySeeded() }
        viewModelScope.launch { foodSeeder.ensureSeeded() }
        // M12: the reminder alarm and the widget follow the app from its first screen (TRD §6.6).
        reminders.start()
    }

    /**
     * Opens the screen a reminder or the widget points at. Nothing happens before onboarding, which has no tabs;
     * a check-in reminder with no check-in to show opens Today.
     */
    fun open(action: String?) {
        if (action !in OPEN_ACTIONS) return
        viewModelScope.launch {
            if (onboarded.first { it != null } != true) return@launch
            _nav.send(
                when (action) {
                    app.recomp.MainActivity.ACTION_OPEN_FOOD -> AppNav.Food
                    app.recomp.MainActivity.ACTION_OPEN_BODY -> AppNav.Body
                    app.recomp.MainActivity.ACTION_OPEN_CHECKIN -> checkins.observeLatest().first()?.let { AppNav.Checkin(it.weekStart) } ?: AppNav.Today
                    else -> AppNav.Today
                },
            )
        }
    }

    private var healthSyncedAt = 0L

    /** Health Connect is read when the app opens (TRD §6.3): at launch, and when it comes back after a while. */
    fun syncHealth() {
        if (healthSyncedAt == 0L || System.currentTimeMillis() - healthSyncedAt < HEALTH_SYNC_EVERY_MS) return
        viewModelScope.launch { syncHealthNow() }
    }

    private suspend fun syncHealthNow() {
        healthSyncedAt = System.currentTimeMillis()
        // Health Connect can be missing, out of date or mid-update; nothing else waits on it.
        runCatching { health.sync() }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        healthScheduler.update()
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
        const val HEALTH_SYNC_EVERY_MS = 15 * 60 * 1000L

        private val OPEN_ACTIONS = setOf(
            app.recomp.MainActivity.ACTION_OPEN_TODAY,
            app.recomp.MainActivity.ACTION_OPEN_FOOD,
            app.recomp.MainActivity.ACTION_OPEN_BODY,
            app.recomp.MainActivity.ACTION_OPEN_CHECKIN,
        )
    }
}
