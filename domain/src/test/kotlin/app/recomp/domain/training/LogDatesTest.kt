package app.recomp.domain.training

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class LogDatesTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val fourAm = LogDates.DEFAULT_DAY_START
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `before the day boundary counts toward the previous day`() {
        assertEquals(LocalDate.of(2026, 10, 16), LogDates.of(at(2026, 10, 17, 1, 30), zone, fourAm))
        assertEquals(LocalDate.of(2026, 10, 16), LogDates.of(at(2026, 10, 17, 3, 59), zone, fourAm))
    }

    @Test
    fun `from the boundary on it's the calendar day`() {
        assertEquals(LocalDate.of(2026, 10, 17), LogDates.of(at(2026, 10, 17, 4, 0), zone, fourAm))
        assertEquals(LocalDate.of(2026, 10, 17), LogDates.of(at(2026, 10, 17, 23, 59), zone, fourAm))
    }

    @Test
    fun `a later boundary moves more of the night to the previous day`() {
        val sixAm = LocalTime.of(6, 0)
        assertEquals(LocalDate.of(2026, 10, 16), LogDates.of(at(2026, 10, 17, 5, 30), zone, sixAm))
        assertEquals(LocalDate.of(2026, 10, 17), LogDates.of(at(2026, 10, 17, 6, 0), zone, sixAm))
    }

    @Test
    fun `the day start choices run from midnight to 6 AM and include the default`() {
        assertEquals(7, LogDates.DAY_START_CHOICES.size)
        assertEquals(LocalTime.MIDNIGHT, LogDates.DAY_START_CHOICES.first())
        assert(LogDates.DEFAULT_DAY_START in LogDates.DAY_START_CHOICES)
    }

    @Test
    fun `a midnight boundary is the plain calendar date`() {
        assertEquals(LocalDate.of(2026, 10, 17), LogDates.of(at(2026, 10, 17, 0, 10), zone, LocalTime.MIDNIGHT))
    }
}
