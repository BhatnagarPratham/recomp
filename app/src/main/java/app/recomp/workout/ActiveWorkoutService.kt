package app.recomp.workout

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.recomp.MainActivity
import app.recomp.data.db.SessionExerciseWithSets
import app.recomp.data.db.WorkoutSessionEntity
import app.recomp.data.repo.WorkoutRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * The active workout's foreground service (TRD §6.1). It runs only while a session is ACTIVE: it
 * observes the session and the [RestTimer], keeps the ongoing notification current (elapsed time or
 * the rest countdown, the current exercise and set, *Skip rest* and *+30 s*), fires the "rest over"
 * alert, and stops itself once the workout is finished or discarded.
 */
@AndroidEntryPoint
class ActiveWorkoutService : Service() {

    @Inject lateinit var workouts: WorkoutRepository

    @Inject lateinit var restTimer: RestTimer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observing = false
    private var restJob: Job? = null
    private var scheduledRestEnd: Long? = null
    private var lastSnapshot: Snapshot? = null

    private data class Snapshot(
        val session: WorkoutSessionEntity,
        val title: String,
        val exercises: List<SessionExerciseWithSets>,
        val rest: RestState?,
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!observing) {
            // Must be called promptly after startForegroundService; the real content follows.
            startForeground(ONGOING_ID, placeholder(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            observing = true
            isRunning = true
            observe()
        }
        when (intent?.action) {
            ACTION_SKIP_REST -> scope.launch { restTimer.skip() }
            ACTION_ADD_REST -> scope.launch { restTimer.add(EXTRA_REST_SECONDS) }
        }
        return START_NOT_STICKY
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observe() = scope.launch {
        workouts.observeActive()
            .flatMapLatest { session ->
                if (session == null) {
                    flowOf(null)
                } else {
                    val title = session.routineId?.let { workouts.routine(it)?.name } ?: "Workout"
                    combine(workouts.observeSessionExercises(session.id), restTimer.state) { exercises, rest ->
                        Snapshot(session, title, exercises, rest)
                    }
                }
            }
            .collect { snapshot ->
                if (snapshot == null) {
                    stop()
                } else {
                    lastSnapshot = snapshot
                    notificationManager().notify(ONGOING_ID, ongoing(snapshot))
                    scheduleRestAlert(snapshot.rest)
                }
            }
    }

    private fun scheduleRestAlert(rest: RestState?) {
        if (rest?.endsAt == scheduledRestEnd) return
        restJob?.cancel()
        scheduledRestEnd = rest?.endsAt
        if (rest == null) return
        restJob = scope.launch {
            val wait = rest.endsAt - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            // A rest that ended long ago (the process was dead) is cleared without an alert.
            if (System.currentTimeMillis() - rest.endsAt < STALE_ALERT_MS) alertRestOver()
            restTimer.clearIfEndsAt(rest.endsAt)
        }
    }

    /** "Bench press · set 2 of 3" for the first exercise with an unticked set. */
    private fun currentSet(exercises: List<SessionExerciseWithSets>): String {
        for (exercise in exercises) {
            val sets = exercise.sets.sortedBy { it.setIndex }
            val next = sets.indexOfFirst { it.completedAt == null }
            if (next >= 0) return "${exercise.exercise.name} · set ${next + 1} of ${sets.size}"
        }
        return if (exercises.isEmpty()) "Add an exercise to start" else "All sets done"
    }

    private fun ongoing(s: Snapshot): Notification {
        val resting = s.rest != null && s.rest.endsAt > System.currentTimeMillis()
        val builder = NotificationCompat.Builder(this, NotificationChannels.WORKOUT)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (resting) "${s.title} · resting" else s.title)
            .setContentText(if (resting) "Next: ${currentSet(s.exercises)}" else currentSet(s.exercises))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setContentIntent(openWorkoutIntent())
            .setUsesChronometer(true)
            .setShowWhen(true)
        if (resting) {
            builder.setChronometerCountDown(true)
                .setWhen(s.rest!!.endsAt)
                .addAction(0, "Skip rest", serviceIntent(ACTION_SKIP_REST, 1))
                .addAction(0, "+${EXTRA_REST_SECONDS} s", serviceIntent(ACTION_ADD_REST, 2))
        } else {
            builder.setWhen(s.session.startedAt)
        }
        return builder.build()
    }

    private fun placeholder(): Notification = NotificationCompat.Builder(this, NotificationChannels.WORKOUT)
        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
        .setContentTitle("Workout in progress")
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_WORKOUT)
        .setContentIntent(openWorkoutIntent())
        .build()

    private fun alertRestOver() {
        val next = lastSnapshot?.let { currentSet(it.exercises) } ?: "Time for your next set"
        val alert = NotificationCompat.Builder(this, NotificationChannels.REST_TIMER)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Rest over")
            .setContentText(next)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setTimeoutAfter(15_000)
            .setAutoCancel(true)
            .setContentIntent(openWorkoutIntent())
            .build()
        notificationManager().notify(REST_OVER_ID, alert)

        // M0: the channel's vibration supersedes a direct call, so vibrate here only when it can't.
        if (!restChannelVibrates(this)) vibrate(this)
    }

    private fun openWorkoutIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_WORKOUT)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode, Intent(this, ActiveWorkoutService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun stop() {
        restJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ActiveWorkoutService"
        private const val ACTION_SYNC = "app.recomp.action.SYNC_WORKOUT"
        private const val ACTION_SKIP_REST = "app.recomp.action.SKIP_REST"
        private const val ACTION_ADD_REST = "app.recomp.action.ADD_REST"
        private const val ONGOING_ID = 1
        private const val REST_OVER_ID = 2
        private const val STALE_ALERT_MS = 60_000L
        const val EXTRA_REST_SECONDS = 30

        /** Whether the service is alive in this process; the workout screen vibrates itself when it isn't. */
        @Volatile
        var isRunning = false
            private set

        /** The `health` service type needs ACTIVITY_RECOGNITION (TRD §6.1). */
        val requiredPermissions = arrayOf(Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.POST_NOTIFICATIONS)

        fun canRun(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

        /** Starts the service if a workout is active and it may run; it stops itself otherwise. */
        fun sync(context: Context) {
            if (!canRun(context)) return
            try {
                context.startForegroundService(Intent(context, ActiveWorkoutService::class.java).setAction(ACTION_SYNC))
            } catch (e: IllegalStateException) {
                // Not allowed from the background. The resume prompt starts it next time the app is open.
                Log.w(TAG, "Couldn't start the workout service", e)
            }
        }

        /** True when the rest-over channel will vibrate by itself. */
        fun restChannelVibrates(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = manager.getNotificationChannel(NotificationChannels.REST_TIMER) ?: return false
            return manager.areNotificationsEnabled() && channel.importance != NotificationManager.IMPORTANCE_NONE && channel.shouldVibrate()
        }

        fun vibrate(context: Context) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
                .vibrate(VibrationEffect.createWaveform(NotificationChannels.REST_VIBRATION, -1))
        }
    }
}
