package app.recomp

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.recomp.workout.NotificationChannels
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** WorkManager is set up here, on demand, so its workers get their dependencies from Hilt (TRD §10 M13). */
@HiltAndroidApp
class RecompApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.create(this)
    }
}
