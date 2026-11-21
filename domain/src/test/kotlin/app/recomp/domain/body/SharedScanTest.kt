package app.recomp.domain.body

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** IB-03 with IB-07: which InBody draft a shared screenshot belongs to. Invented times. */
class SharedScanTest {
    private val h = 3_600_000L
    private val now = 1_000 * h

    @Test
    fun `the newest draft from the 12 hours before the share is the same scan`() {
        assertEquals(2L, SharedScan.draftFor(listOf(1L to now - 5 * h, 2L to now - 20 * 60_000L), now))
        // A clock a few minutes ahead still counts; one much later, or over 12 hours before, doesn't.
        assertEquals(3L, SharedScan.draftFor(listOf(3L to now + 5 * 60_000L), now))
        assertNull(SharedScan.draftFor(listOf(4L to now + 30 * 60_000L), now))
        assertNull(SharedScan.draftFor(listOf(5L to now - 13 * h), now))
        assertEquals(6L, SharedScan.draftFor(listOf(6L to now - 12 * h), now))
        assertNull(SharedScan.draftFor(emptyList(), now))
    }
}
