package app.recomp.reminders

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.recomp.MainActivity
import app.recomp.domain.food.PortionUnit
import app.recomp.domain.nudge.ReminderKind
import app.recomp.domain.nudge.Suggestion
import app.recomp.workout.NotificationChannels
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Reminders as notifications (TRD §6.6): one per kind, replaced in place, each opening the screen it's about. */
@Singleton
class AndroidReminderNotifier @Inject constructor(@ApplicationContext private val context: Context) : ReminderNotifier, NudgeMessages {
    private val manager get() = NotificationManagerCompat.from(context)

    override fun canNotify(): Boolean = permitted(context)

    override fun canShow(kind: ReminderKind): Boolean =
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(channel(kind))?.importance != NotificationManager.IMPORTANCE_NONE

    override fun show(content: ReminderContent) {
        if (!canNotify()) return
        val builder = base(content.kind, content.title, content.text)
        // NU-23: one tap logs a suggestion. Android shows three actions at most.
        val tapKey = "${content.kind}-${System.currentTimeMillis()}"
        content.suggestions.take(MAX_ACTIONS).forEachIndexed { i, s ->
            builder.addAction(0, ReminderText.suggestionAction(s), NudgeActionReceiver.logIntent(context, i, s, content.date, tapKey))
        }
        notify(content.kind, builder)
    }

    /** After a suggestion is logged from the notification: says where it went, with *Undo*. */
    override fun logged(text: String, entryId: Long) {
        if (!canNotify()) return
        notify(ReminderKind.EVENING, base(ReminderKind.EVENING, "Logged", text).addAction(0, "Undo", NudgeActionReceiver.undoIntent(context, entryId)).setSilent(true))
    }

    override fun message(title: String, text: String) {
        if (!canNotify()) return
        notify(ReminderKind.EVENING, base(ReminderKind.EVENING, title, text).setSilent(true))
    }

    private fun base(kind: ReminderKind, title: String, text: String) = NotificationCompat.Builder(context, channel(kind))
        .setSmallIcon(android.R.drawable.ic_popup_reminder)
        .setContentTitle(title)
        .setContentText(text.lineSequence().first())
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setContentIntent(open(kind))

    @Suppress("MissingPermission") // canNotify() is checked by every caller
    private fun notify(kind: ReminderKind, builder: NotificationCompat.Builder) = manager.notify(id(kind), builder.build())

    private fun open(kind: ReminderKind): PendingIntent = PendingIntent.getActivity(
        context,
        id(kind),
        Intent(context, MainActivity::class.java).setAction(
            when (kind) {
                ReminderKind.LUNCH -> MainActivity.ACTION_OPEN_FOOD
                ReminderKind.CHECKIN -> MainActivity.ACTION_OPEN_CHECKIN
                ReminderKind.SCAN -> MainActivity.ACTION_OPEN_BODY
                ReminderKind.WEIGH_IN, ReminderKind.EVENING, ReminderKind.WORKOUT -> MainActivity.ACTION_OPEN_TODAY
            },
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val MAX_ACTIONS = 3
        private const val ID_BASE = 2_000

        fun id(kind: ReminderKind): Int = ID_BASE + kind.ordinal

        fun channel(kind: ReminderKind): String = if (kind == ReminderKind.CHECKIN) NotificationChannels.CHECKIN else NotificationChannels.REMINDERS

        /** POST_NOTIFICATIONS is granted and the app's notifications aren't switched off. */
        fun permitted(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
                NotificationManagerCompat.from(context).areNotificationsEnabled()

        internal fun unit(label: String, grams: Double) = PortionUnit(label, grams, PortionUnit.Kind.OWN)

        internal fun Suggestion.toExtras(intent: Intent): Intent = intent
            .putExtra("food_id", foodId).putExtra("name", name).putExtra("quantity", quantity)
            .putExtra("unit", unit.label).putExtra("unit_grams", unit.grams).putExtra("grams", grams)
            .putExtra("protein_g", proteinG).putExtra("kcal", kcal)

        internal fun suggestionOf(intent: Intent): Suggestion? {
            val id = intent.getLongExtra("food_id", 0)
            val label = intent.getStringExtra("unit") ?: return null
            val quantity = intent.getDoubleExtra("quantity", 0.0)
            val unitGrams = intent.getDoubleExtra("unit_grams", 0.0)
            if (id <= 0 || quantity <= 0 || unitGrams <= 0) return null
            return Suggestion(
                id, intent.getStringExtra("name").orEmpty(), quantity, unit(label, unitGrams), intent.getDoubleExtra("grams", quantity * unitGrams),
                intent.getIntExtra("protein_g", 0), intent.getIntExtra("kcal", 0),
            )
        }
    }
}
