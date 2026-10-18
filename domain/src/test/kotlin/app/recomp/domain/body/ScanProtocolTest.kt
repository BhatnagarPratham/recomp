package app.recomp.domain.body

import java.time.DayOfWeek
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** IB-02, TRD §5.6: protocol_ok = within 90 min of the usual time, ≥ 120 min since eating, no workout before. */
class ScanProtocolTest {
    private val slot = ScanSlot(DayOfWeek.MONDAY, LocalTime.of(10, 0))

    private fun check(time: String, sinceMeal: Int = 180, workout: Boolean = false, s: ScanSlot? = slot) =
        ScanProtocol.check(LocalTime.parse(time), s, sinceMeal, workout)

    @Test
    fun `on protocol at the usual time, fed long enough ago, before training`() {
        val c = check("10:20")
        assertTrue(c.ok)
        assertEquals(20, c.minutesFromSlot)
    }

    @Test
    fun `90 minutes either side is the edge`() {
        assertTrue(check("08:30").ok)
        assertTrue(check("11:30").ok)
        assertEquals(setOf(ProtocolIssue.TIME), check("11:31").issues)
        assertEquals(setOf(ProtocolIssue.TIME), check("08:29").issues)
    }

    @Test
    fun `time of day is compared the short way round midnight`() {
        assertEquals(60, ScanProtocol.minutesApart(LocalTime.of(23, 30), LocalTime.of(0, 30)))
        assertTrue(check("23:45", s = ScanSlot(DayOfWeek.FRIDAY, LocalTime.of(0, 30))).ok)
        assertEquals(720, ScanProtocol.minutesApart(LocalTime.NOON, LocalTime.MIDNIGHT))
    }

    @Test
    fun `eating within 2 hours is off protocol`() {
        assertEquals(setOf(ProtocolIssue.MEAL), check("10:00", sinceMeal = SinceMeal.UNDER_2H.minutes).issues)
        assertTrue(check("10:00", sinceMeal = 120).ok)
        assertFalse(check("10:00", sinceMeal = 119).ok)
        SinceMeal.entries.filter { it != SinceMeal.UNDER_2H }.forEach { assertTrue(it.name, check("10:00", sinceMeal = it.minutes).ok) }
    }

    @Test
    fun `a workout before the scan is off protocol`() {
        assertEquals(setOf(ProtocolIssue.WORKOUT), check("10:00", workout = true).issues)
    }

    @Test
    fun `every reason is reported at once`() {
        assertEquals(ProtocolIssue.entries.toSet(), check("18:00", sinceMeal = 30, workout = true).issues)
    }

    @Test
    fun `without a usual slot only the meal and workout count`() {
        val c = check("18:00", s = null)
        assertTrue(c.ok)
        assertNull(c.minutesFromSlot)
    }

    @Test
    fun `last-ate answers round-trip through their stored minutes`() {
        SinceMeal.entries.forEach { assertEquals(it, SinceMeal.of(it.minutes)) }
        assertEquals(SinceMeal.OVER_3H, SinceMeal.of(200))
    }

    @Test
    fun `body fat percent comes from fat mass over weight`() {
        assertEquals(25.0, ScanProtocol.bodyFatPct(80_000, 20_000)!!, 0.0)
        assertEquals(31.6, ScanProtocol.bodyFatPct(95_000, 30_000)!!, 0.0)
        assertNull(ScanProtocol.bodyFatPct(0, 10_000))
        assertNull(ScanProtocol.bodyFatPct(50_000, 60_000))
    }
}
