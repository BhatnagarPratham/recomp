package app.recomp

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.recomp.data.repo.CameraFoodRepository
import app.recomp.workout.NotificationChannels
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** WorkManager is set up here, on demand, so its workers get their dependencies from Hilt (TRD §10 M13). */
@HiltAndroidApp
class RecompApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var cameraFood: CameraFoodRepository

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.create(this)
        // Meal photos no draft points at any more (NU-20), after a day's grace.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { runCatching { cameraFood.sweepPhotos() } }
    }
}
