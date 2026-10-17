package app.recomp.workout

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import app.recomp.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * M0 spike for TRD §6.1: a "health" foreground service with a lock-screen rest countdown.
 * The rest end is an absolute timestamp, so the notification's chronometer counts down on its
 * own without the app redrawing it every second.
 */
class ActiveWorkoutService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var restJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_REST -> startRest(intent.getIntExtra(EXTRA_SECONDS, 90))
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startRest(seconds: Int) {
        val endAt = System.currentTimeMillis() + seconds * 1000L
        startForeground(ONGOING_ID, ongoing(restEndsAt = endAt), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)

        restJob?.cancel()
        restJob = scope.launch {
            delay(endAt - System.currentTimeMillis())
            alertRestOver()
            notificationManager().notify(ONGOING_ID, ongoing(restEndsAt = null))
        }
    }

    private fun ongoing(restEndsAt: Long?): Notification {
        val builder = NotificationCompat.Builder(this, NotificationChannels.WORKOUT)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (restEndsAt != null) "Resting" else "Workout in progress")
            .setContentText(if (restEndsAt != null) "Next set when the timer ends" else "Rest over. Go!")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setContentIntent(openAppIntent())
            .addAction(0, "End", stopIntent())
        if (restEndsAt != null) {
            builder.setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setWhen(restEndsAt)
                .setShowWhen(true)
        }
        return builder.build()
    }

    private fun alertRestOver() {
        val alert = NotificationCompat.Builder(this, NotificationChannels.REST_TIMER)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Rest over")
            .setContentText("Time for your next set")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setTimeoutAfter(15_000)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        notificationManager().notify(REST_OVER_ID, alert)

        // Vibrate directly as well: channel vibration can be muted by the user's notification settings.
        getSystemService(VibratorManager::class.java).defaultVibrator
            .vibrate(VibrationEffect.createWaveform(NotificationChannels.REST_VIBRATION, -1))
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this, 1, Intent(this, ActiveWorkoutService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START_REST = "app.recomp.action.START_REST"
        private const val ACTION_STOP = "app.recomp.action.STOP_WORKOUT"
        private const val EXTRA_SECONDS = "seconds"
        private const val ONGOING_ID = 1
        private const val REST_OVER_ID = 2

        fun startRest(context: Context, seconds: Int) {
            context.startForegroundService(
                Intent(context, ActiveWorkoutService::class.java)
                    .setAction(ACTION_START_REST)
                    .putExtra(EXTRA_SECONDS, seconds),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ActiveWorkoutService::class.java).setAction(ACTION_STOP))
        }
    }
}
