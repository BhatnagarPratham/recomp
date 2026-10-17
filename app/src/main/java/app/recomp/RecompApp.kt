package app.recomp

import android.app.Application
import app.recomp.workout.NotificationChannels
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class RecompApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationChannels.create(this)
    }
}
