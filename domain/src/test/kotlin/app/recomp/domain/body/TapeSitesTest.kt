package app.recomp.domain.body

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TapeSitesTest {
    private val today = LocalDate.of(2026, 11, 21)

    @Test
    fun `waist always comes first, and the others only when chosen and 4 weeks old or never taken`() {
        assertEquals(listOf(TapeSite.WAIST), TapeSites.due(emptySet(), emptyMap(), today))
        val chosen = setOf(TapeSite.ARM, TapeSite.CHEST, TapeSite.HIP)
        val last = mapOf(TapeSite.CHEST to today.minusDays(27), TapeSite.ARM to today.minusDays(28), TapeSite.WAIST to today)
        assertEquals(listOf(TapeSite.WAIST, TapeSite.ARM, TapeSite.HIP), TapeSites.due(chosen, last, today))
    }

    @Test
    fun `the latest reading per site comes with its change`() {
        val rows = listOf(
            Triple(TapeSite.ARM, today.minusDays(30), 35.0),
            Triple(TapeSite.WAIST, today, 84.5),
            Triple(TapeSite.ARM, today, 35.5),
        )
        val latest = TapeSites.latest(rows)
        assertEquals(listOf(TapeSite.WAIST, TapeSite.ARM), latest.map { it.site })
        assertNull(latest[0].changeCm)
        assertEquals(0.5, latest[1].changeCm!!, 1e-9)
    }

    @Test
    fun `every site has a how-to`() {
        TapeSite.entries.forEach { assert(TapeSites.how(it).isNotBlank()) }
    }
}
