package app.recomp

import android.app.Application
import app.recomp.data.repo.CameraFoodRepository
import app.recomp.workout.NotificationChannels
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class RecompApp : Application() {
    @Inject lateinit var cameraFood: CameraFoodRepository

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.create(this)
        // Meal photos no draft points at any more (NU-20), after a day's grace.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { runCatching { cameraFood.sweepPhotos() } }
    }
}
