package app.recomp.domain.training

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class LogDatesTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `before the day boundary counts toward the previous day`() {
        assertEquals(LocalDate.of(2026, 10, 16), LogDates.of(at(2026, 10, 17, 1, 30), zone))
        assertEquals(LocalDate.of(2026, 10, 16), LogDates.of(at(2026, 10, 17, 3, 59), zone))
    }

    @Test
    fun `from the boundary on it's the calendar day`() {
        assertEquals(LocalDate.of(2026, 10, 17), LogDates.of(at(2026, 10, 17, 4, 0), zone))
        assertEquals(LocalDate.of(2026, 10, 17), LogDates.of(at(2026, 10, 17, 23, 59), zone))
    }

    @Test
    fun `a midnight boundary is the plain calendar date`() {
        assertEquals(LocalDate.of(2026, 10, 17), LogDates.of(at(2026, 10, 17, 0, 10), zone, LocalTime.MIDNIGHT))
    }
}
