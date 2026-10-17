package app.recomp.data

import app.recomp.data.prefs.DayBoundary
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Today rolls over at the day boundary while it's on screen (PR-03), on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class DayBoundaryTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val fourAm = LocalTime.of(4, 0)
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `the date changes at the boundary, not at midnight`() = runTest {
        val start = at(17, 23, 30)
        val dates = DayBoundary.ticks(fourAm, zone) { start + currentTime }.take(2).toList()
        assertEquals(listOf(LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 18)), dates)
        // It moved on at 4 AM: four and a half hours after 23:30.
        assertEquals(at(18, 4), start + currentTime)
    }

    @Test
    fun `it keeps rolling over day after day`() = runTest {
        val start = at(17, 12)
        val dates = DayBoundary.ticks(fourAm, zone) { start + currentTime }.take(3).toList()
        assertEquals(listOf(17, 18, 19), dates.map { it.dayOfMonth })
        assertTrue(start + currentTime >= at(19, 4))
    }
}
