package app.recomp.domain.health

import app.recomp.domain.model.SessionKind
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class HealthImportTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val morning = LocalDate.of(2026, 11, 16)

    private fun at(day: Int, hour: Int, minute: Int = 0): Long = LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun night(vararg sessions: SleepSession) = SleepNight.hours(sessions.toList(), SleepNight.window(morning, zone))

    // Sleep

    @Test
    fun `one session is its length to the half hour`() {
        assertEquals(7.5, night(SleepSession(Span(at(15, 23), at(16, 6, 40)))))
    }

    @Test
    fun `two apps recording the same night are merged, not added up`() {
        val watch = SleepSession(Span(at(15, 23), at(16, 6, 30)))
        val phone = SleepSession(Span(at(15, 23, 20), at(16, 7)))
        assertEquals(8.0, night(watch, phone))
    }

    @Test
    fun `awake stretches are left out`() {
        val session = SleepSession(Span(at(15, 23), at(16, 7)), awake = listOf(Span(at(16, 2), at(16, 3)), Span(at(16, 6, 45), at(16, 7))))
        assertEquals(7.0, night(session))
    }

    @Test
    fun `an awake stretch one app saw doesn't erase what another slept through it`() {
        val a = SleepSession(Span(at(15, 23), at(16, 3)), awake = listOf(Span(at(16, 1), at(16, 3))))
        val b = SleepSession(Span(at(16, 1), at(16, 6)))
        assertEquals(7.0, night(a, b))
    }

    @Test
    fun `a nap after noon and the night before last stay out`() {
        val before = SleepSession(Span(at(14, 23), at(15, 7)))
        val nap = SleepSession(Span(at(16, 14), at(16, 15)))
        assertNull(night(before, nap))
        assertEquals(6.0, night(before, nap, SleepSession(Span(at(16, 0), at(16, 6)))))
    }

    @Test
    fun `an early evening sleep and the night after it both count for the morning`() {
        assertEquals(8.0, night(SleepSession(Span(at(15, 18, 30), at(15, 19, 30))), SleepSession(Span(at(15, 23), at(16, 6)))))
    }

    @Test
    fun `the window stops at the weigh-in when that comes before noon`() {
        val window = SleepNight.window(morning, zone, until = at(16, 6))
        assertEquals(at(15, 18), window.start)
        assertEquals(at(16, 6), window.end)
        assertNull(SleepNight.hours(listOf(SleepSession(Span(at(15, 23), at(16, 7)))), window))
        assertEquals(at(16, 12), SleepNight.window(morning, zone, until = at(16, 15)).end)
    }

    @Test
    fun `a session with no length is nothing`() {
        assertNull(night(SleepSession(Span(at(16, 5), at(16, 5)))))
    }

    // InBody drafts

    private fun reading(id: String, metric: BodyMetric, at: Long, value: Double) = BodyReading(id, metric, at, value)

    @Test
    fun `records sharing a timestamp become one draft`() {
        val t = at(16, 10, 5)
        val drafts = InBodyDrafts.group(
            listOf(reading("f", BodyMetric.BODY_FAT, t, 21.44), reading("w", BodyMetric.WEIGHT, t, 78_400.0), reading("b", BodyMetric.BMR, t, 1712.4)),
        )
        assertEquals(listOf(DraftScan(t, 78_400, 21.4, 1712, listOf("b", "f", "w"))), drafts)
    }

    @Test
    fun `two measurements make two drafts, and one without body fat keeps what it has`() {
        val first = at(9, 10)
        val second = at(16, 10, 5)
        val drafts = InBodyDrafts.group(
            listOf(
                reading("w2", BodyMetric.WEIGHT, second, 78_000.0),
                reading("w1", BodyMetric.WEIGHT, first, 78_900.0),
                reading("f1", BodyMetric.BODY_FAT, first + 2_000, 22.0),
            ),
        )
        assertEquals(listOf(DraftScan(first, 78_900, 22.0, null, listOf("f1", "w1")), DraftScan(second, 78_000, null, null, listOf("w2"))), drafts)
    }

    @Test
    fun `a second weight within the minute is a second measurement`() {
        val t = at(16, 10)
        val drafts = InBodyDrafts.group(listOf(reading("a", BodyMetric.WEIGHT, t, 78_000.0), reading("b", BodyMetric.WEIGHT, t + 30_000, 78_100.0)))
        assertEquals(2, drafts.size)
    }

    @Test
    fun `records with no weight make no draft`() {
        assertEquals(emptyList<DraftScan>(), InBodyDrafts.group(listOf(reading("f", BodyMetric.BODY_FAT, at(16, 10), 22.0), reading("b", BodyMetric.BMR, at(16, 10), 1700.0))))
    }

    @Test
    fun `a scan typed within ten minutes is the same scan`() {
        val t = at(16, 10, 5)
        assertTrue(InBodyDrafts.matchesScan(t, listOf(at(9, 10), at(16, 10, 15))))
        assertTrue(InBodyDrafts.matchesScan(t, listOf(at(16, 9, 55))))
        assertFalse(InBodyDrafts.matchesScan(t, listOf(at(16, 10, 16))))
        assertFalse(InBodyDrafts.matchesScan(t, emptyList()))
    }

    // Other apps' workouts

    private val extras = listOf(
        ExtraSlot(1, SessionKind.YOGA, setOf(DayOfWeek.TUESDAY)),
        ExtraSlot(2, SessionKind.CARDIO, emptySet()),
        ExtraSlot(3, SessionKind.YOGA, setOf(DayOfWeek.SATURDAY)),
    )

    @Test
    fun `an import counts toward the extra scheduled that day, else the first of its kind`() {
        assertEquals(3L, ImportedWorkouts.routineFor(SessionKind.YOGA, DayOfWeek.SATURDAY, extras))
        assertEquals(1L, ImportedWorkouts.routineFor(SessionKind.YOGA, DayOfWeek.MONDAY, extras))
        assertEquals(2L, ImportedWorkouts.routineFor(SessionKind.CARDIO, DayOfWeek.MONDAY, extras))
        assertNull(ImportedWorkouts.routineFor(SessionKind.SPORT, DayOfWeek.MONDAY, extras))
    }

    @Test
    fun `a session overlapping your own workout would count twice`() {
        val own = listOf(Span(at(16, 18), at(16, 19)))
        assertTrue(ImportedWorkouts.overlapsOwn(Span(at(16, 18, 30), at(16, 19, 30)), own))
        assertTrue(ImportedWorkouts.overlapsOwn(Span(at(16, 17), at(16, 20)), own))
        assertFalse(ImportedWorkouts.overlapsOwn(Span(at(16, 19), at(16, 19, 40)), own))
        assertFalse(ImportedWorkouts.overlapsOwn(Span(at(16, 7), at(16, 7, 35)), own))
    }

    // Steps

    @Test
    fun `the 7-day average leaves today out and skips days with no count`() {
        val today = LocalDate.of(2026, 11, 16)
        val byDay = mapOf(today to 900, today.minusDays(1) to 8_000, today.minusDays(3) to 6_000, today.minusDays(8) to 20_000)
        assertEquals(7_000, Steps.weekAverage(byDay, today))
        assertNull(Steps.weekAverage(mapOf(today to 900), today))
    }

    @Test
    fun `the step goal stays between 2,000 and 20,000`() {
        assertEquals(2_000, Steps.clampGoal(500))
        assertEquals(20_000, Steps.clampGoal(50_000))
        assertEquals(8_000, Steps.clampGoal(Steps.DEFAULT_GOAL))
    }
}
