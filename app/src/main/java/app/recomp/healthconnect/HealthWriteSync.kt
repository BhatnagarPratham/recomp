package app.recomp.healthconnect

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.recomp.data.db.AppDatabase
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.data.repo.HealthWriteRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** The daily write (DA-04), for the days the app isn't opened. Writing needs no background permission. */
@HiltWorker
class HealthWriteWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val writes: HealthWriteRepository,
) : CoroutineWorker(context, params) {
    // A failure is shown in Settings; tomorrow's run, the next launch or the next change tries again.
    override suspend fun doWork(): Result {
        writes.sync()
        return Result.success()
    }

    companion object {
        const val NAME = "health-write"
    }
}

/**
 * When Recomp writes to Health Connect (TRD §10 M16): at launch, daily, and soon after any change to the tables it
 * writes from, found through Room's invalidation tracker. So the save paths themselves never change.
 */
@Singleton
class HealthWriteSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val prefs: SettingsPrefs,
    private val writes: HealthWriteRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    /** The tables the records are built from. */
    fun changes(): Flow<Set<String>> = db.invalidationTracker.createFlow(*TABLES, emitInitialState = false)

    @OptIn(FlowPreview::class)
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch { runCatching { writes.sync() } }
        scope.launch {
            prefs.healthShare.collect { on ->
                runCatching { schedule(on) }
                if (on) runCatching { writes.sync() }
            }
        }
        scope.launch {
            // A burst of changes (a workout's sets, a meal's items) is written once, a little after it settles.
            changes().debounce(DEBOUNCE_MS).collect { runCatching { writes.sync() } }
        }
    }

    private fun schedule(on: Boolean) {
        val work = WorkManager.getInstance(context)
        if (on) {
            work.enqueueUniquePeriodicWork(HealthWriteWorker.NAME, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<HealthWriteWorker>(24, TimeUnit.HOURS).build())
        } else {
            work.cancelUniqueWork(HealthWriteWorker.NAME)
        }
    }

    companion object {
        const val DEBOUNCE_MS = 20_000L

        val TABLES = arrayOf("workout_session", "session_exercise", "set_entry", "yoga_detail", "routine", "exercise", "food_log", "body_weight")
    }
}
