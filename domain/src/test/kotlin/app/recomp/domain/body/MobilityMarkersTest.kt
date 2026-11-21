package app.recomp.domain.body

import app.recomp.domain.model.SessionKind
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobilityMarkersTest {
    private val today = LocalDate.of(2026, 11, 21)

    @Test
    fun `on by default with a yoga or mobility session, and your choice wins`() {
        assertTrue(MobilityMarkers.enabled(null, listOf(SessionKind.CARDIO, SessionKind.YOGA)))
        assertTrue(MobilityMarkers.enabled(null, listOf(SessionKind.MOBILITY)))
        assertFalse(MobilityMarkers.enabled(null, listOf(SessionKind.CARDIO)))
        assertFalse(MobilityMarkers.enabled(false, listOf(SessionKind.YOGA)))
        assertTrue(MobilityMarkers.enabled(true, emptyList()))
    }

    @Test
    fun `due monthly, and at once the first time`() {
        assertTrue(MobilityMarkers.due(null, today))
        assertFalse(MobilityMarkers.due(today.minusDays(27), today))
        assertTrue(MobilityMarkers.due(today.minusDays(28), today))
    }

    @Test
    fun `short of the toes is a valid reach, and the hold has a ceiling`() {
        assertTrue(MobilityMarkers.validReach(-8.5))
        assertFalse(MobilityMarkers.validReach(75.0))
        assertTrue(MobilityMarkers.validHold(0))
        assertFalse(MobilityMarkers.validHold(-1))
        assertFalse(MobilityMarkers.validHold(1_801))
    }
}
