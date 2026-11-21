package app.recomp.domain.training

import app.recomp.domain.model.LoadType
import app.recomp.domain.model.SetType
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SessionEditTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val dayStart = LocalTime.of(4, 0)
    private val date = LocalDate.of(2026, 11, 10)
    private fun at(day: LocalDate, hour: Int, minute: Int = 0) = day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `an evening workout starts and ends on its log date`() {
        val (start, end) = SessionEdit.times(date, LocalTime.of(18, 30), LocalTime.of(19, 45), zone, dayStart)
        assertEquals(at(date, 18, 30), start)
        assertEquals(at(date, 19, 45), end)
        assertEquals(date, LogDates.of(start, zone, dayStart))
    }

    @Test
    fun `a workout that runs past midnight ends the next calendar day`() {
        val (start, end) = SessionEdit.times(date, LocalTime.of(23, 30), LocalTime.of(0, 40), zone, dayStart)
        assertEquals(at(date, 23, 30), start)
        assertEquals(at(date.plusDays(1), 0, 40), end)
    }

    @Test
    fun `a start before the day begins is the log date's late night`() {
        val (start, end) = SessionEdit.times(date, LocalTime.of(1, 0), LocalTime.of(2, 0), zone, dayStart)
        assertEquals(at(date.plusDays(1), 1), start)
        assertEquals(at(date.plusDays(1), 2), end)
        assertEquals(date, LogDates.of(start, zone, dayStart)) // so the log date stays the one picked
    }

    @Test
    fun `times in the future or out of order are refused`() {
        val now = at(date, 20)
        assertNull(SessionEdit.timeProblem(at(date, 18), at(date, 19), now))
        assertNull(SessionEdit.timeProblem(at(date, 18), at(date, 18), now))
        assertNotNull(SessionEdit.timeProblem(at(date, 21), at(date, 22), now))
        assertNotNull(SessionEdit.timeProblem(at(date, 19), at(date, 21), now))
        assertNotNull(SessionEdit.timeProblem(at(date, 19), at(date, 18), now))
    }

    @Test
    fun `a set needs reps, and a weight unless it's bodyweight`() {
        fun set(loadG: Int?, reps: Int, rir: Int? = null) = EditedSet(null, SetType.WORKING, loadG, reps, rir)
        assertNull(SessionEdit.setProblem(set(40_000, 8, 3), LoadType.WEIGHTED))
        assertNull(SessionEdit.setProblem(set(0, 8), LoadType.BODYWEIGHT_PLUS))
        assertNull(SessionEdit.setProblem(set(0, 8), LoadType.ASSISTED))
        assertNull(SessionEdit.setProblem(set(null, 12), LoadType.BODYWEIGHT))
        assertNotNull(SessionEdit.setProblem(set(40_000, 0), LoadType.WEIGHTED))
        assertNotNull(SessionEdit.setProblem(set(null, 8), LoadType.WEIGHTED))
        assertNotNull(SessionEdit.setProblem(set(Loads.MAX_LOAD_G + 1, 8), LoadType.WEIGHTED))
        assertNotNull(SessionEdit.setProblem(set(40_000, 8, 4), LoadType.WEIGHTED))
        assertNotNull(SessionEdit.setProblem(set(null, 1_000), LoadType.BODYWEIGHT))
    }

    @Test
    fun `a bodyweight set drops any typed load`() {
        val typed = EditedSet(null, SetType.WORKING, 5_000, 10)
        assertNull(SessionEdit.normalized(typed, LoadType.BODYWEIGHT).loadG)
        assertEquals(5_000, SessionEdit.normalized(typed, LoadType.BODYWEIGHT_PLUS).loadG)
    }

    @Test
    fun `nothing may start at or after the workout in progress`() {
        val active = at(date, 18)
        assertNull(SessionEdit.activeProblem(active - 1, active))
        assertNotNull(SessionEdit.activeProblem(active, active))
        assertNotNull(SessionEdit.activeProblem(active + 600_000, active))
    }
}
