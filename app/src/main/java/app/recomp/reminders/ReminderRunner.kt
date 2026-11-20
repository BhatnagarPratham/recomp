package app.recomp.reminders

import app.recomp.data.prefs.DayBoundary
import app.recomp.data.prefs.DevicePrefs
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.data.repo.CheckinRepository
import app.recomp.data.repo.EveningNudge
import app.recomp.data.repo.TodaySnapshotRepository
import app.recomp.domain.nudge.ReminderDay
import app.recomp.domain.nudge.ReminderKind
import app.recomp.domain.nudge.ReminderSchedule
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where reminders are shown. Android's notifications in the app; a list in tests. */
interface ReminderNotifier {
    /** Whether a reminder could be shown at all: the permission and the app's notifications are on. */
    fun canNotify(): Boolean

    /** Whether [kind]'s channel is on. */
    fun canShow(kind: ReminderKind): Boolean = true

    fun show(content: ReminderContent)
}

/**
 * RM-01 at run time (TRD §6.6): at each wake, the reminders due are read against the day's ledger, their skip
 * rules are checked against a fresh snapshot, and at most two a day are sent. The ledger is device state.
 */
@Singleton
class ReminderRunner @Inject constructor(
    private val prefs: SettingsPrefs,
    private val device: DevicePrefs,
    private val days: DayBoundary,
    private val snapshots: TodaySnapshotRepository,
    private val checkins: CheckinRepository,
    private val notifier: ReminderNotifier,
) {
    private val mutex = Mutex()

    private suspend fun timed() = ReminderSchedule.timed(prefs.reminders.first(), prefs.scanSlot.first())

    /** Sends what's due at [now] and returns it. Safe to call at any time: with nothing due it does nothing. */
    suspend fun run(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): List<ReminderContent> = mutex.withLock {
        val timed = timed()
        if (timed.isEmpty()) return emptyList()
        val today = days.of(now)
        val rings = ReminderSchedule.forDay(today, prefs.currentDayStart(), zone, timed)
        val ledger = device.ledger()
        val handled = ledger?.takeIf { it.date == today }?.handled.orEmpty()
        val instant = Instant.ofEpochMilli(now)
        // Nothing new is due: don't touch the database at all.
        if (rings.none { !it.at.isAfter(instant) && it.kind !in handled }) return emptyList()

        val contents = contents(rings.filter { !it.at.isAfter(instant) && it.kind !in handled }.map { it.kind }.toSet(), now)
        val step = ReminderDay.step(ledger, today, instant, rings, notifier.canNotify()) { it in contents && notifier.canShow(it) }
        // The ledger first: a crash after this can lose a reminder, never send one twice.
        device.setLedger(step.ledger)
        step.send.mapNotNull(contents::get).onEach(notifier::show)
    }

    /** What each of [kinds] would say now; a reminder whose skip rule applies is left out. */
    private suspend fun contents(kinds: Set<ReminderKind>, now: Long): Map<ReminderKind, ReminderContent> {
        // The week that just ended is computed first, so Monday's reminder has a check-in to point at.
        if (ReminderKind.CHECKIN in kinds) runCatching { checkins.catchUp(now = now) }
        val snapshot = snapshots.load(now)
        val nudge = if (ReminderKind.EVENING in kinds) snapshots.evening(snapshot) else EveningNudge.NONE
        val reminders = prefs.reminders.first()
        val slots = prefs.mealSlots.first()
        val scanSlot = prefs.scanSlot.first()
        return kinds.mapNotNull { kind -> ReminderText.content(kind, snapshot, nudge, reminders, slots, scanSlot)?.let { kind to it } }.toMap()
    }

    /** After a reminder, "day starts at" or the scan slot changes: a time already past doesn't ring today. */
    suspend fun settingsChanged(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) = mutex.withLock {
        val today = days.of(now)
        val rings = ReminderSchedule.forDay(today, prefs.currentDayStart(), zone, timed())
        device.setLedger(ReminderDay.skipPast(device.ledger(), today, Instant.ofEpochMilli(now), rings))
    }

    /** When the alarm should next fire for a reminder; null when none is on. */
    suspend fun nextWake(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Instant? {
        val today = days.of(now)
        val handled = device.ledger()?.takeIf { it.date == today }?.handled.orEmpty()
        return ReminderSchedule.nextWake(Instant.ofEpochMilli(now), today, prefs.currentDayStart(), zone, timed(), handled)
    }
}
