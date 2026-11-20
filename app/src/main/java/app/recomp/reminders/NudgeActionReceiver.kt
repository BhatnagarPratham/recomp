package app.recomp.reminders

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.recomp.data.repo.TodaySnapshotRepository
import app.recomp.domain.nudge.Suggestion
import app.recomp.reminders.AndroidReminderNotifier.Companion.toExtras
import app.recomp.widget.WidgetUpdater
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The evening nudge's buttons (NU-23): one tap logs a suggested portion, and *Undo* takes it back. */
@AndroidEntryPoint
class NudgeActionReceiver : BroadcastReceiver() {
    @Inject lateinit var snapshots: TodaySnapshotRepository
    @Inject lateinit var notifier: AndroidReminderNotifier
    @Inject lateinit var widgets: WidgetUpdater

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    ACTION_LOG -> {
                        val logged = AndroidReminderNotifier.suggestionOf(intent)?.let { snapshots.log(it) }
                        if (logged == null) notifier.showMessage("Not logged", "That food isn't available any more. Open Recomp to log something else.")
                        else notifier.showLogged(ReminderText.logged(logged.suggestion, logged.slot), logged.entryId)
                    }
                    ACTION_UNDO -> {
                        intent.getLongExtra(EXTRA_ENTRY, 0).takeIf { it > 0 }?.let { snapshots.undo(it) }
                        notifier.showMessage("Removed", "The entry was taken back.")
                    }
                }
                widgets.update()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_LOG = "app.recomp.action.NUDGE_LOG"
        const val ACTION_UNDO = "app.recomp.action.NUDGE_UNDO"
        private const val EXTRA_ENTRY = "entry_id"
        private const val REQUEST_BASE = 2_100

        fun logIntent(context: Context, index: Int, suggestion: Suggestion): PendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_BASE + index,
            suggestion.toExtras(Intent(context, NudgeActionReceiver::class.java).setAction(ACTION_LOG)),
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
