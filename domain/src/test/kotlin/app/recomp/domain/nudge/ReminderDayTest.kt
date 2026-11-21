package app.recomp.domain.nudge

import app.recomp.domain.body.ScanSlot
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderDayTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val dayStart = LocalTime.of(4, 0)
    private val monday = LocalDate.of(2026, 11, 16)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Instant = date.atTime(hour, minute).atZone(zone).toInstant()

    private val all = Reminders(
        weighIn = Reminder(true, LocalTime.of(7, 30)),
        lunch = Reminder(true, LocalTime.of(14, 30)),
        evening = Reminder(true, LocalTime.of(20, 30)),
        workout = Reminder(true, LocalTime.of(8, 0)),
        workoutDays = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
        checkin = Reminder(true, LocalTime.of(9, 0)),
        scan = true,
    )
    private val slot = ScanSlot(DayOfWeek.MONDAY, LocalTime.of(10, 0))

    /** Runs a log day the way the app does: a wake at each ring, with the ledger carried between them. */
    private fun simulate(
        date: LocalDate,
        reminders: Reminders,
        needed: (ReminderKind, Instant) -> Boolean,
        canNotify: Boolean = true,
    ): Pair<List<ReminderKind>, ReminderLedger> {
        val rings = ReminderSchedule.forDay(date, dayStart, zone, ReminderSchedule.timed(reminders, slot))
        var ledger: ReminderLedger? = null
        val sent = mutableListOf<ReminderKind>()
        rings.forEach { ring ->
            val step = ReminderDay.step(ledger, date, ring.at, rings, canNotify) { needed(it, ring.at) }
            sent += step.send
            ledger = step.ledger
        }
        return sent to ledger!!
    }

    @Test
    fun `nothing is on by default`() {
        assertTrue(ReminderSchedule.timed(Reminders(), slot).isEmpty())
        assertNull(ReminderSchedule.nextWake(at(monday, 6), monday, dayStart, zone, emptyList(), emptySet()))
        assertEquals(LocalTime.of(7, 30), Reminders().weighIn.time)
        assertEquals(LocalTime.of(14, 30), Reminders().lunch.time)
        assertEquals(LocalTime.of(20, 30), Reminders().evening.time)
        assertEquals(LocalTime.of(8, 0), Reminders().workout.time)
        assertEquals(LocalTime.of(9, 0), Reminders().checkin.time)
    }

    @Test
    fun `a day's rings come in time order, each on its own weekdays`() {
        val mondayRings = ReminderSchedule.forDay(monday, dayStart, zone, ReminderSchedule.timed(all, slot))
        assertEquals(
            listOf(ReminderKind.SCAN, ReminderKind.WEIGH_IN, ReminderKind.WORKOUT, ReminderKind.CHECKIN, ReminderKind.LUNCH, ReminderKind.EVENING),
            mondayRings.map { it.kind },
        )
        assertEquals(at(monday, 7), mondayRings.first().at) // 3 h before the 10:00 slot
        val tuesday = ReminderSchedule.forDay(monday.plusDays(1), dayStart, zone, ReminderSchedule.timed(all, slot))
        assertEquals(listOf(ReminderKind.WEIGH_IN, ReminderKind.LUNCH, ReminderKind.EVENING), tuesday.map { it.kind })
    }

    @Test
    fun `the first two still needed are sent, in time order`() {
        // Everything is needed all day: the scan reminder and the weigh-in go, and nothing after them.
        val (sent, ledger) = simulate(monday, all, needed = { _, _ -> true })
        assertEquals(listOf(ReminderKind.SCAN, ReminderKind.WEIGH_IN), sent)
        assertEquals(ReminderKind.entries.toSet(), ledger.handled)
    }

    @Test
    fun `a skipped reminder doesn't use up the cap`() {
        // Weighed in and scanned before 7, no workout yet, check-in opened, lunch logged, protein short.
        val needed = setOf(ReminderKind.WORKOUT, ReminderKind.EVENING)
        val (sent, _) = simulate(monday, all, needed = { kind, _ -> kind in needed })
        assertEquals(listOf(ReminderKind.WORKOUT, ReminderKind.EVENING), sent)
    }

    @Test
    fun `a skip rule is read when its reminder rings, not earlier`() {
        // Lunch is logged at 13:00, so the 14:30 reminder is skipped and the evening one is the second sent.
        val lunchLogged = at(monday, 13)
        val (sent, _) = simulate(
            monday,
            all.copy(scan = false, checkin = all.checkin.copy(on = false), workout = all.workout.copy(on = false)),
            needed = { kind, now -> kind != ReminderKind.LUNCH || now.isBefore(lunchLogged) },
        )
        assertEquals(listOf(ReminderKind.WEIGH_IN, ReminderKind.EVENING), sent)
    }

    @Test
    fun `nothing needed, nothing sent`() {
        val (sent, ledger) = simulate(monday, all, needed = { _, _ -> false })
        assertTrue(sent.isEmpty())
        assertTrue(ledger.sent.isEmpty())
    }

    @Test
    fun `with notifications off nothing is sent or counted`() {
        val (sent, ledger) = simulate(monday, all, needed = { _, _ -> true }, canNotify = false)
        assertTrue(sent.isEmpty())
        assertTrue(ledger.sent.isEmpty())
    }

    @Test
    fun `a reminder is looked at once a day, even if the wake repeats`() {
        val rings = ReminderSchedule.forDay(monday, dayStart, zone, ReminderSchedule.timed(all, slot))
        val first = ReminderDay.step(null, monday, at(monday, 7, 31), rings) { true }
        assertEquals(listOf(ReminderKind.SCAN, ReminderKind.WEIGH_IN), first.send)
        val again = ReminderDay.step(first.ledger, monday, at(monday, 7, 40), rings) { true }
        assertTrue(again.send.isEmpty())
        assertEquals(first.ledger, again.ledger)
    }

    @Test
    fun `a new day starts a fresh ledger`() {
        val (_, mondayLedger) = simulate(monday, all, needed = { _, _ -> true })
        val tuesday = monday.plusDays(1)
        val rings = ReminderSchedule.forDay(tuesday, dayStart, zone, ReminderSchedule.timed(all, slot))
        val step = ReminderDay.step(mondayLedger, tuesday, at(tuesday, 7, 30), rings) { true }
        assertEquals(listOf(ReminderKind.WEIGH_IN), step.send)
        assertEquals(tuesday, step.ledger.date)
    }

    @Test
    fun `a ring the phone slept through is dropped, not sent hours late`() {
        val rings = ReminderSchedule.forDay(monday, dayStart, zone, ReminderSchedule.timed(all, slot))
        // Switched on at 13:00: the morning's four are long past. Lunch and the evening nudge still ring.
        val boot = ReminderDay.step(null, monday, at(monday, 13), rings) { true }
        assertTrue(boot.send.isEmpty())
        val lunch = ReminderDay.step(boot.ledger, monday, at(monday, 14, 30), rings) { true }
        assertEquals(listOf(ReminderKind.LUNCH), lunch.send)
        // A few minutes of drift is still sent.
        val drift = ReminderDay.step(lunch.ledger, monday, at(monday, 20, 30).plus(Duration.ofMinutes(12)), rings) { true }
        assertEquals(listOf(ReminderKind.EVENING), drift.send)
    }

    @Test
    fun `turning a reminder on after its time doesn't ring it today`() {
        val rings = ReminderSchedule.forDay(monday, dayStart, zone, ReminderSchedule.timed(all, slot))
        val ledger = ReminderDay.skipChanged(null, monday, at(monday, 15), before = emptyList(), after = rings)
        assertTrue(ledger.sent.isEmpty())
        assertEquals(ReminderKind.entries.toSet() - ReminderKind.EVENING, ledger.handled)
        assertEquals(at(monday, 20, 30), ReminderSchedule.nextWake(at(monday, 15), monday, dayStart, zone, ReminderSchedule.timed(all, slot), ledger.handled))
    }

    @Test
    fun `a settings change leaves a due ring alone unless it moved that ring`() {
        // Review S1: the evening ring is due at 20:30 and the inexact alarm hasn't fired by 20:33.
        val before = Reminders(weighIn = Reminder(true, LocalTime.of(7, 30)), evening = Reminder(true, LocalTime.of(20, 30)))
        val sent = ReminderLedger(monday, sent = listOf(ReminderKind.WEIGH_IN), handled = setOf(ReminderKind.WEIGH_IN))
        fun rings(r: Reminders) = ReminderSchedule.forDay(monday, dayStart, zone, ReminderSchedule.timed(r, slot))
        val now = at(monday, 20, 33)
        // Turning on the lunch reminder (14:30, past) skips lunch only.
        val after = before.copy(lunch = Reminder(true, LocalTime.of(14, 30)))
        val ledger = ReminderDay.skipChanged(sent, monday, now, rings(before), rings(after))
        assertEquals(setOf(ReminderKind.WEIGH_IN, ReminderKind.LUNCH), ledger.handled)
        assertEquals(listOf(ReminderKind.EVENING), ReminderDay.step(ledger, monday, now, rings(after)) { true }.send)
        // Picking the lunch reminder's meal, or a workout weekday, moves no ring at all.
        assertEquals(sent, ReminderDay.skipChanged(sent, monday, now, rings(before), rings(before.copy(lunchSlot = "snacks", workoutDays = setOf(DayOfWeek.FRIDAY)))))
        // Moving the evening reminder to 20:00 (past) does skip it; moving it later leaves it to ring then.
        assertTrue(ReminderKind.EVENING in ReminderDay.skipChanged(sent, monday, now, rings(before), rings(before.with(ReminderKind.EVENING, LocalTime.of(20, 0)))).handled)
        assertTrue(ReminderKind.EVENING !in ReminderDay.skipChanged(sent, monday, now, rings(before), rings(before.with(ReminderKind.EVENING, LocalTime.of(21, 0)))).handled)
    }

    // Reminder times across the day boundary

    @Test
    fun `a reminder set after midnight belongs to the day before and rings last`() {
        val late = Reminders(evening = Reminder(true, LocalTime.of(1, 0)), lunch = Reminder(true, LocalTime.of(14, 30)), weighIn = Reminder(true, LocalTime.of(7, 30)))
        val rings = ReminderSchedule.forDay(monday, dayStart, zone, ReminderSchedule.timed(late, null))
        assertEquals(listOf(ReminderKind.WEIGH_IN, ReminderKind.LUNCH, ReminderKind.EVENING), rings.map { it.kind })
        assertEquals(at(monday.plusDays(1), 1), rings.last().at)
        // The cap counts it with Monday: two went earlier, so the 01:00 one doesn't.
        val (sent, _) = simulate(monday, late, needed = { _, _ -> true })
        assertEquals(listOf(ReminderKind.WEIGH_IN, ReminderKind.LUNCH), sent)
    }

    @Test
    fun `a weekday is the calendar day the reminder rings on`() {
        // A check-in reminder at 01:00 rings in the small hours of Monday, which is still Sunday's log day.
        val early = Reminders(checkin = Reminder(true, LocalTime.of(1, 0)))
        val timed = ReminderSchedule.timed(early, null)
        val sunday = monday.minusDays(1)
        assertEquals(listOf(at(monday, 1)), ReminderSchedule.forDay(sunday, dayStart, zone, timed).map { it.at })
        assertTrue(ReminderSchedule.forDay(monday, dayStart, zone, timed).isEmpty())
    }

    @Test
    fun `the next wake is the next ring, today or on a later day`() {
        val timed = ReminderSchedule.timed(Reminders(weighIn = Reminder(true, LocalTime.of(7, 30)), checkin = Reminder(true, LocalTime.of(9, 0))), null)
        assertEquals(at(monday, 7, 30), ReminderSchedule.nextWake(at(monday, 5), monday, dayStart, zone, timed, emptySet()))
        // Both handled: tomorrow's weigh-in.
        val handled = setOf(ReminderKind.WEIGH_IN, ReminderKind.CHECKIN)
        assertEquals(at(monday.plusDays(1), 7, 30), ReminderSchedule.nextWake(at(monday, 9, 1), monday, dayStart, zone, timed, handled))
        // Only the weekly one is on: next Monday.
        val weekly = ReminderSchedule.timed(Reminders(checkin = Reminder(true, LocalTime.of(9, 0))), null)
        assertEquals(at(monday.plusDays(7), 9), ReminderSchedule.nextWake(at(monday, 9, 1), monday, dayStart, zone, weekly, setOf(ReminderKind.CHECKIN)))
        // Due and unhandled: wake now.
        val now = at(monday, 7, 33)
        assertEquals(now, ReminderSchedule.nextWake(now, monday, dayStart, zone, timed, emptySet()))
        // Due but too late to send: on to the next.
        assertEquals(at(monday.plusDays(1), 7, 30), ReminderSchedule.nextWake(at(monday, 12), monday, dayStart, zone, timed, emptySet()))
    }

    @Test
    fun `before the day starts, the wake is still the previous log day's`() {
        // 02:00 on Tuesday is Monday's log day (day starts at 4). Tuesday's 07:30 comes next.
        val timed = ReminderSchedule.timed(Reminders(weighIn = Reminder(true, LocalTime.of(7, 30))), null)
        val now = at(monday.plusDays(1), 2)
        assertEquals(at(monday.plusDays(1), 7, 30), ReminderSchedule.nextWake(now, monday, dayStart, zone, timed, setOf(ReminderKind.WEIGH_IN)))
    }

    // A time-zone change

    @Test
    fun `after a time-zone change reminders ring at the same clock time in the new zone`() {
        val timed = ReminderSchedule.timed(Reminders(evening = Reminder(true, LocalTime.of(20, 30))), null)
        val london = ZoneId.of("Europe/London")
        // 15:00 in India; then the phone moves to London, where it's 09:30.
        val now = at(monday, 15)
        assertEquals(at(monday, 20, 30), ReminderSchedule.nextWake(now, monday, dayStart, zone, timed, emptySet()))
        val there = ReminderSchedule.nextWake(now, monday, dayStart, london, timed, emptySet())
        assertEquals(monday.atTime(20, 30).atZone(london).toInstant(), there)
        assertEquals(Duration.ofMinutes(330), Duration.between(at(monday, 20, 30), there))
    }

    @Test
    fun `a reminder already sent isn't sent again when the zone change brings its time round twice`() {
        // Sent at 20:30 in India; flying west makes it 20:30 again the same log day.
        val timed = ReminderSchedule.timed(Reminders(evening = Reminder(true, LocalTime.of(20, 30))), null)
        val first = ReminderDay.step(null, monday, at(monday, 20, 30), ReminderSchedule.forDay(monday, dayStart, zone, timed)) { true }
        assertEquals(listOf(ReminderKind.EVENING), first.send)
        val london = ZoneId.of("Europe/London")
        val again = monday.atTime(20, 30).atZone(london).toInstant()
        val second = ReminderDay.step(first.ledger, monday, again, ReminderSchedule.forDay(monday, dayStart, london, timed)) { true }
        assertTrue(second.send.isEmpty())
        // The next wake is tomorrow's.
        assertEquals(monday.plusDays(1).atTime(20, 30).atZone(london).toInstant(), ReminderSchedule.nextWake(again, monday, dayStart, london, timed, second.ledger.handled))
    }

    @Test
    fun `a time that doesn't exist on a clock-change day moves forward, and the day still has one ring`() {
        val london = ZoneId.of("Europe/London")
        val springForward = LocalDate.of(2027, 3, 28) // 01:00 → 02:00
        val timed = ReminderSchedule.timed(Reminders(weighIn = Reminder(true, LocalTime.of(1, 30))), null)
        val rings = ReminderSchedule.forDay(springForward.minusDays(1), dayStart, london, timed)
        assertEquals(1, rings.size)
        assertEquals(springForward.atTime(2, 30).atZone(london).toInstant(), rings.single().at)
    }

    // Settings' warning and the scan reminder

    @Test
    fun `Settings can tell when more than two could be due on one day`() {
        assertEquals(6, ReminderSchedule.mostOnOneDay(dayStart, ReminderSchedule.timed(all, slot)))
        val two = Reminders(weighIn = Reminder(true, LocalTime.of(7, 30)), evening = Reminder(true, LocalTime.of(20, 30)))
        assertEquals(2, ReminderSchedule.mostOnOneDay(dayStart, ReminderSchedule.timed(two, slot)))
        // A weekly one makes three on its day.
        assertEquals(3, ReminderSchedule.mostOnOneDay(dayStart, ReminderSchedule.timed(two.copy(checkin = Reminder(true, LocalTime.of(9, 0))), slot)))
        assertEquals(0, ReminderSchedule.mostOnOneDay(dayStart, emptyList()))
    }

    @Test
    fun `the scan reminder needs a slot, and one soon after midnight rings the evening before`() {
        assertTrue(ReminderSchedule.timed(Reminders(scan = true), null).isEmpty())
        assertEquals(DayOfWeek.MONDAY to LocalTime.of(7, 0), ReminderSchedule.scanReminder(slot))
        assertEquals(DayOfWeek.SUNDAY to LocalTime.of(23, 0), ReminderSchedule.scanReminder(ScanSlot(DayOfWeek.MONDAY, LocalTime.of(2, 0))))
    }

    @Test
    fun `the workout reminder needs at least one weekday`() {
        val none = Reminders(workout = Reminder(true, LocalTime.of(8, 0)))
        assertTrue(ReminderSchedule.timed(none, null).isEmpty())
        val thursday = ReminderSchedule.timed(none.copy(workoutDays = setOf(DayOfWeek.THURSDAY)), null)
        assertTrue(ReminderSchedule.forDay(monday, dayStart, zone, thursday).isEmpty())
        assertEquals(1, ReminderSchedule.forDay(monday.plusDays(3), dayStart, zone, thursday).size)
    }
}
