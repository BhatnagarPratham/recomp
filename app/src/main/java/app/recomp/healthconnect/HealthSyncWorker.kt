package app.recomp.healthconnect

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.recomp.data.repo.HealthRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * The daily background read (TRD §6.3). It runs only while Health Connect's background-read permission is granted;
 * without it a read from the background fails, so the job does nothing and the launch sync carries on alone.
 */
@HiltWorker
class HealthSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val health: HealthRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if (health.backgroundSyncWanted()) health.sync()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Tomorrow's run, or the next launch, tries again; a retry storm buys nothing.
        Result.success()
    }

    companion object {
        const val NAME = "health-sync"
    }
}

/** Keeps the daily job scheduled exactly while it has something it may read. */
@Singleton
class HealthSyncScheduler @Inject constructor(@ApplicationContext private val context: Context, private val health: HealthRepository) {
    suspend fun update() {
        val wanted = runCatching { health.backgroundSyncWanted() }.getOrDefault(false)
        val work = WorkManager.getInstance(context)
        if (wanted) {
            work.enqueueUniquePeriodicWork(
                HealthSyncWorker.NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<HealthSyncWorker>(24, TimeUnit.HOURS).build(),
            )
        } else {
            work.cancelUniqueWork(HealthSyncWorker.NAME)
        }
    }
}
