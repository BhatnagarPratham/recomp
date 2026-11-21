package app.recomp.workout

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.provider.Settings

object NotificationChannels {
    const val WORKOUT = "workout"
    const val REST_TIMER = "rest_timer"

    /** RM-01's reminders and the evening nudge (TRD §6.6). */
    const val REMINDERS = "reminders"
    const val CHECKIN = "weekly_checkin"

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
                NotificationChannel(REMINDERS, "Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "The reminders you turn on in Settings, at most 2 a day"
                },
                NotificationChannel(CHECKIN, "Weekly check-in", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When your weekly check-in is ready"
                },
            ),
        )
    }
}
