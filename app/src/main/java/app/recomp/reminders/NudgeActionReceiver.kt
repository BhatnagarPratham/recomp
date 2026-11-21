package app.recomp.reminders

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import app.recomp.data.prefs.DayBoundary
import app.recomp.data.repo.TodaySnapshotRepository
import app.recomp.domain.nudge.ReminderKind
import app.recomp.domain.nudge.Suggestion
import app.recomp.reminders.AndroidReminderNotifier.Companion.toExtras
import app.recomp.widget.WidgetUpdater
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the evening notification says after one of its buttons. Android's notification in the app; a list in tests. */
interface NudgeMessages {
    /** "Logged …", with *Undo* for [entryId]. */
    fun logged(text: String, entryId: Long)

    fun message(title: String, text: String)
}

/** What a button did, for the tests. */
enum class NudgeOutcome { LOGGED, UNAVAILABLE, STALE, REPEAT, FAILED, UNDONE, NOTHING_TO_UNDO }

/**
 * The evening notification's buttons (NU-23): one tap logs a suggested portion, and *Undo* takes it back. Nothing here
 * can crash the app (review S4): a failure says so in the notification. A suggestion from a day that has ended isn't
 * logged into today (N2), a second tap on the same notification is ignored (N3), and *Undo* says when there was
 * nothing left to take back (N4).
 */
@Singleton
class NudgeActions @Inject constructor(
    private val snapshots: TodaySnapshotRepository,
    private val days: DayBoundary,
    private val messages: NudgeMessages,
) {
    private val mutex = Mutex()
    private val used = mutableSetOf<String>()

    suspend fun log(suggestion: Suggestion?, logDate: LocalDate?, tapKey: String?, now: Long = System.currentTimeMillis()): NudgeOutcome = guarded {
        if (tapKey != null && !mutex.withLock { used.add(tapKey) }) return@guarded NudgeOutcome.REPEAT
        if (logDate != null && days.of(now) != logDate) {
            messages.message("Not logged", "That suggestion was for an earlier day. Open Recomp to log for today.")
            return@guarded NudgeOutcome.STALE
        }
        val logged = suggestion?.let { snapshots.log(it, now) }
        if (logged == null) {
            messages.message("Not logged", "That food isn't available any more. Open Recomp to log something else.")
            NudgeOutcome.UNAVAILABLE
        } else {
            messages.logged(ReminderText.logged(logged.suggestion, logged.slot), logged.entryId)
            NudgeOutcome.LOGGED
        }
    }

    suspend fun undo(entryId: Long, now: Long = System.currentTimeMillis()): NudgeOutcome = guarded {
        if (entryId > 0 && snapshots.undo(entryId)) {
            messages.message("Removed", "The entry was taken back.")
            NudgeOutcome.UNDONE
        } else {
            messages.message("Nothing to undo", "That entry isn't in your log any more.")
            NudgeOutcome.NOTHING_TO_UNDO
        }
    }

    private suspend fun guarded(block: suspend () -> NudgeOutcome): NudgeOutcome = try {
        block()
    } catch (e: Exception) {
        // Our own cancellation goes on up; a database closed under us (an import) also arrives as a cancellation.
        if (e is CancellationException && !currentCoroutineContext().isActive) throw e
        runCatching { messages.message("Couldn't log it", "Open Recomp to log it there.") }
        NudgeOutcome.FAILED
    }
}

@AndroidEntryPoint
class NudgeActionReceiver : BroadcastReceiver() {
    @Inject lateinit var actions: NudgeActions
    @Inject lateinit var widgets: WidgetUpdater

    override fun onReceive(context: Context, intent: Intent) {
        // The buttons go at once, so a second quick tap has nothing to press (N3).
        if (intent.action == ACTION_LOG) NotificationManagerCompat.from(context).cancel(AndroidReminderNotifier.id(ReminderKind.EVENING))
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    ACTION_LOG -> actions.log(
                        AndroidReminderNotifier.suggestionOf(intent),
                        intent.getLongExtra(EXTRA_LOG_DATE, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let(LocalDate::ofEpochDay),
                        intent.getStringExtra(EXTRA_TAP_KEY),
                    )
                    ACTION_UNDO -> actions.undo(intent.getLongExtra(EXTRA_ENTRY, 0))
                }
                runCatching { widgets.update() }
            } catch (e: Exception) {
                // NudgeActions catches its own failures; this only guards the receiver itself.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_LOG = "app.recomp.action.NUDGE_LOG"
        const val ACTION_UNDO = "app.recomp.action.NUDGE_UNDO"
        private const val EXTRA_ENTRY = "entry_id"
        private const val EXTRA_LOG_DATE = "log_date"
        private const val EXTRA_TAP_KEY = "tap_key"
        private const val REQUEST_BASE = 2_100

        /** [tapKey] is the same for every button of one notification, so only its first tap logs. */
        fun logIntent(context: Context, index: Int, suggestion: Suggestion, logDate: LocalDate?, tapKey: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_BASE + index,
            suggestion.toExtras(Intent(context, NudgeActionReceiver::class.java).setAction(ACTION_LOG))
                .putExtra(EXTRA_TAP_KEY, tapKey)
                .apply { if (logDate != null) putExtra(EXTRA_LOG_DATE, logDate.toEpochDay()) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun undoIntent(context: Context, entryId: Long): PendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_BASE + 10,
            Intent(context, NudgeActionReceiver::class.java).setAction(ACTION_UNDO).putExtra(EXTRA_ENTRY, entryId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
