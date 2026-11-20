package app.recomp.domain.nudge

import app.recomp.domain.body.ScanSlot
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** RM-01's reminders, in the order Settings lists them. */
enum class ReminderKind { WEIGH_IN, LUNCH, EVENING, WORKOUT, CHECKIN, SCAN }

/** One reminder: off until you turn it on (PRD §12.8). */
data class Reminder(val on: Boolean = false, val time: LocalTime)

/**
 * Settings → Reminders (RM-01), stored under `reminders`. The scan reminder has no time of its own: it follows
 * your usual scan slot. The evening time also decides when Today's evening card appears, on or off.
 */
data class Reminders(
    val weighIn: Reminder = Reminder(time = LocalTime.of(7, 30)),
    val lunch: Reminder = Reminder(time = LocalTime.of(14, 30)),
    /** The meal the lunch reminder watches, a slot key; null is the meal for the reminder's time of day. */
    val lunchSlot: String? = null,
    val evening: Reminder = Reminder(time = LocalTime.of(20, 30)),
    val workout: Reminder = Reminder(time = LocalTime.of(8, 0)),
    /** The weekdays you pick for the workout reminder. The rotation itself stays calendar-free (RT-02). */
    val workoutDays: Set<DayOfWeek> = emptySet(),
    val checkin: Reminder = Reminder(time = LocalTime.of(9, 0)),
    val scan: Boolean = false,
) {
    fun of(kind: ReminderKind): Reminder? = when (kind) {
        ReminderKind.WEIGH_IN -> weighIn
        ReminderKind.LUNCH -> lunch
        ReminderKind.EVENING -> evening
        ReminderKind.WORKOUT -> workout
        ReminderKind.CHECKIN -> checkin
        ReminderKind.SCAN -> null
    }

    fun isOn(kind: ReminderKind): Boolean = of(kind)?.on ?: scan

    fun with(kind: ReminderKind, on: Boolean): Reminders = when (kind) {
        ReminderKind.WEIGH_IN -> copy(weighIn = weighIn.copy(on = on))
        ReminderKind.LUNCH -> copy(lunch = lunch.copy(on = on))
        ReminderKind.EVENING -> copy(evening = evening.copy(on = on))
        ReminderKind.WORKOUT -> copy(workout = workout.copy(on = on))
        ReminderKind.CHECKIN -> copy(checkin = checkin.copy(on = on))
        ReminderKind.SCAN -> copy(scan = on)
    }

    fun with(kind: ReminderKind, time: LocalTime): Reminders = when (kind) {
        ReminderKind.WEIGH_IN -> copy(weighIn = weighIn.copy(time = time))
        ReminderKind.LUNCH -> copy(lunch = lunch.copy(time = time))
        ReminderKind.EVENING -> copy(evening = evening.copy(time = time))
        ReminderKind.WORKOUT -> copy(workout = workout.copy(time = time))
        ReminderKind.CHECKIN -> copy(checkin = checkin.copy(time = time))
        ReminderKind.SCAN -> this
    }

    val anyOn: Boolean get() = ReminderKind.entries.any(::isOn)
}

/** A reminder that's on, as a clock time on the calendar weekdays it rings. */
data class TimedReminder(val kind: ReminderKind, val time: LocalTime, val days: Set<DayOfWeek>)

/** One ring of a reminder. */
data class Occurrence(val kind: ReminderKind, val at: Instant)

/**
 * When reminders ring (TRD §6.6). Times are wall-clock times on calendar weekdays ("Monday 09:00"). The
 * 2-a-day cap counts per log day (PR-03's "day starts at"), so a reminder set for 01:00 belongs to the day
 * before, after that day's evening ones.
 */
object ReminderSchedule {
    /** IB-06: the scan reminder rings this long before your usual slot. */
    const val SCAN_LEAD_HOURS = 3L

    /** The weekly check-in is ready on Monday, when the week before has ended (PRD §12.1). */
    val CHECKIN_DAY: DayOfWeek = DayOfWeek.MONDAY

    private val EVERY_DAY = DayOfWeek.entries.toSet()

    /** When the scan reminder rings for a slot: 3 hours before, on the day before when that crosses midnight. */
    fun scanReminder(slot: ScanSlot): Pair<DayOfWeek, LocalTime> {
        val time = slot.time.minusHours(SCAN_LEAD_HOURS)
        return (if (time.isAfter(slot.time)) slot.day.minus(1) else slot.day) to time
    }

    /** The reminders that can ring at all: on, and with a day to ring on. */
    fun timed(reminders: Reminders, scanSlot: ScanSlot?): List<TimedReminder> = buildList {
        if (reminders.weighIn.on) add(TimedReminder(ReminderKind.WEIGH_IN, reminders.weighIn.time, EVERY_DAY))
        if (reminders.lunch.on) add(TimedReminder(ReminderKind.LUNCH, reminders.lunch.time, EVERY_DAY))
        if (reminders.evening.on) add(TimedReminder(ReminderKind.EVENING, reminders.evening.time, EVERY_DAY))
        if (reminders.workout.on && reminders.workoutDays.isNotEmpty()) add(TimedReminder(ReminderKind.WORKOUT, reminders.workout.time, reminders.workoutDays))
        if (reminders.checkin.on) add(TimedReminder(ReminderKind.CHECKIN, reminders.checkin.time, setOf(CHECKIN_DAY)))
        if (reminders.scan && scanSlot != null) scanReminder(scanSlot).let { (day, time) -> add(TimedReminder(ReminderKind.SCAN, time, setOf(day))) }
    }

    /**
     * The rings that belong to [logDate], in time order. A time before [dayStart] falls on the next calendar
     * date, which is also the date its weekday is checked against. Equal times ring in Settings' order.
     */
    fun forDay(logDate: LocalDate, dayStart: LocalTime, zone: ZoneId, timed: List<TimedReminder>): List<Occurrence> =
        timed.mapNotNull { r ->
            val date = if (r.time.isBefore(dayStart)) logDate.plusDays(1) else logDate
            if (date.dayOfWeek !in r.days) null else Occurrence(r.kind, date.atTime(r.time).atZone(zone).toInstant())
        }.sortedWith(compareBy<Occurrence> { it.at }.thenBy { it.kind.ordinal })

    /** The most reminders that could be due on one day, for Settings' warning when it's more than [ReminderDay.MAX_PER_DAY]. */
    fun mostOnOneDay(dayStart: LocalTime, timed: List<TimedReminder>): Int {
        val monday = LocalDate.of(2026, 1, 5)
        return (0L until 7).maxOf { forDay(monday.plusDays(it), dayStart, ZoneId.of("UTC"), timed).size }
    }

    /**
     * When to wake next: now, if a ring of [logDate] (today's log date) is due, unhandled and not too late to
     * send; else the next ring after [now]. Null when no reminder is on.
     */
    fun nextWake(
        now: Instant,
        logDate: LocalDate,
        dayStart: LocalTime,
        zone: ZoneId,
        timed: List<TimedReminder>,
        handledToday: Set<ReminderKind>,
        lateLimit: Duration = ReminderDay.LATE_LIMIT,
    ): Instant? {
        if (timed.isEmpty()) return null
        val today = forDay(logDate, dayStart, zone, timed).filter { it.kind !in handledToday }
        if (today.any { !it.at.isAfter(now) && Duration.between(it.at, now) <= lateLimit }) return now
        today.firstOrNull { it.at.isAfter(now) }?.let { return it.at }
        // A weekly reminder is at most 7 log days away; one more covers a clock change.
        return (1L..8).firstNotNullOfOrNull { d -> forDay(logDate.plusDays(d), dayStart, zone, timed).firstOrNull { it.at.isAfter(now) }?.at }
    }
}

/** What's been done about a log day's reminders. Device state: it never leaves the phone (TRD §4). */
data class ReminderLedger(
    val date: LocalDate,
    /** The reminders sent, in order. Never more than [ReminderDay.MAX_PER_DAY]. */
    val sent: List<ReminderKind> = emptyList(),
    /** Every reminder dealt with today: sent, skipped, over the cap or missed. Each is looked at once a day. */
    val handled: Set<ReminderKind> = emptySet(),
)

/** RM-01's cap and skip rules: in time order, the first two reminders still needed that day are sent. */
object ReminderDay {
    const val MAX_PER_DAY = 2

    /** A ring this late (the phone was off) is dropped rather than sent at an odd hour. Inexact alarms drift by minutes. */
    val LATE_LIMIT: Duration = Duration.ofMinutes(90)

    data class Step(val send: List<ReminderKind>, val ledger: ReminderLedger)

    /**
     * Deals with every ring of [logDate] that's due by [now] and not yet handled, in time order. [needed] is each
     * reminder's skip rule, asked only when the reminder could still be sent. A reminder that isn't needed, or can't
     * be shown ([canNotify] false), never counts toward the cap. A ledger from another day starts fresh.
     */
    fun step(
        ledger: ReminderLedger?,
        logDate: LocalDate,
        now: Instant,
        occurrences: List<Occurrence>,
        canNotify: Boolean = true,
        needed: (ReminderKind) -> Boolean,
    ): Step {
        val start = ledger?.takeIf { it.date == logDate } ?: ReminderLedger(logDate)
        val sent = start.sent.toMutableList()
        val handled = start.handled.toMutableSet()
        val send = mutableListOf<ReminderKind>()
        occurrences.sortedWith(compareBy<Occurrence> { it.at }.thenBy { it.kind.ordinal }).forEach { o ->
            if (o.at.isAfter(now) || !handled.add(o.kind)) return@forEach
            val late = Duration.between(o.at, now) > LATE_LIMIT
            if (!late && canNotify && sent.size < MAX_PER_DAY && needed(o.kind)) {
                sent += o.kind
                send += o.kind
            }
        }
        return Step(send, ReminderLedger(logDate, sent, handled))
    }

    /** A changed setting never rings for a time already past: those rings count as handled, unsent. */
    fun skipPast(ledger: ReminderLedger?, logDate: LocalDate, now: Instant, occurrences: List<Occurrence>): ReminderLedger =
        step(ledger, logDate, now, occurrences, canNotify = false) { false }.ledger
}
