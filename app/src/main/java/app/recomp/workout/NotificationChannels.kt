package app.recomp.workout

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.provider.Settings

object NotificationChannels {
    const val WORKOUT = "workout"
    const val REST_TIMER = "rest_timer"

    val REST_VIBRATION = longArrayOf(0, 400, 200, 400, 200, 400)

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(WORKOUT, "Workout in progress", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Ongoing notification with the rest countdown"
                    setShowBadge(false)
                },
                NotificationChannel(REST_TIMER, "Rest timer", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Alerts you when rest is over"
                    enableVibration(true)
                    vibrationPattern = REST_VIBRATION
                    setSound(
                        Settings.System.DEFAULT_NOTIFICATION_URI,
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build(),
                    )
                },
            ),
        )
    }
}
