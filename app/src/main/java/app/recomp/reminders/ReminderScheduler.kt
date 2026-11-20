package app.recomp.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.domain.training.LogDates
import app.recomp.widget.WidgetUpdater
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The one alarm behind reminders and the widget's day rollover (TRD §6.6): an inexact AlarmManager alarm that
 * may fire while the phone dozes (`setAndAllowWhileIdle`, no exact-alarm permission), set for the next ring or,
 * with a widget on the home screen, the next day boundary if that's sooner. It's set again after each firing, at
 * boot, after an app update, and when the clock, the time zone or a setting it depends on changes.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runner: ReminderRunner,
    private val prefs: SettingsPrefs,
    private val widgets: WidgetUpdater,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    private val alarm: PendingIntent
        get() = PendingIntent.getBroadcast(
            context, 0, Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_WAKE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** When the alarm should fire next, or null when nothing needs it. */
    suspend fun nextAlarm(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val ring = runner.nextWake(now, zone)?.toEpochMilli()
        val rollover = if (widgets.any()) LogDates.nextBoundary(now, zone, prefs.currentDayStart()) else null
        return listOfNotNull(ring, rollover).minOrNull()
    }

    suspend fun reschedule(now: Long = System.currentTimeMillis()) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val at = nextAlarm(now)
        if (at == null) manager.cancel(alarm) else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, max(at, now), alarm)
    }

    /**
     * Once per process, when the app opens: sets the alarm (a force-stop clears it), keeps the widget current, and
     * sets the alarm again whenever a reminder, "day starts at" or the scan slot changes.
     */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch { runCatching { reschedule() } }
        scope.launch {
            combine(prefs.reminders, prefs.dayStart, prefs.scanSlot) { _, _, _ -> }.drop(1).collect {
                runCatching {
                    runner.settingsChanged()
                    reschedule()
                }
            }
        }
        widgets.start(scope)
    }
}

/** Wakes for reminders: the alarm itself, and everything that can make the alarm stale. */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {
    @Inject lateinit var runner: ReminderRunner
    @Inject lateinit var scheduler: ReminderScheduler
    @Inject lateinit var widgets: WidgetUpdater

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                // Whatever woke us, what's due is sent: after a boot or a clock change that may be a ring the alarm missed.
                runCatching { runner.run() }
                runCatching { widgets.update() }
                runCatching { scheduler.reschedule() }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_WAKE = "app.recomp.action.REMINDER_WAKE"

        val ACTIONS = setOf(
            ACTION_WAKE,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
        )
    }
}
