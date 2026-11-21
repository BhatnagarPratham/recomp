package app.recomp.domain.food

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** PRD §12.5: a time belongs to the last meal slot that starts before it. */
class MealTimesTest {
    private fun at(slots: List<MealSlot>, hour: Int, minute: Int = 0) = MealTimes.slotAt(slots, LocalTime.of(hour, minute))?.key

    @Test
    fun `the four default meals have default starts`() {
        val d = MealSlots.DEFAULT
        assertEquals("breakfast", at(d, 5))
        assertEquals("breakfast", at(d, 11, 59))
        assertEquals("lunch", at(d, 12))
        assertEquals("lunch", at(d, 15, 59))
        assertEquals("snacks", at(d, 16))
        assertEquals("dinner", at(d, 19))
        assertEquals("dinner", at(d, 23, 59))
    }

    @Test
    fun `before the day's first start it's the last meal`() {
        assertEquals("dinner", at(MealSlots.DEFAULT, 1, 30))
        assertEquals("dinner", at(MealSlots.DEFAULT, 4, 59))
    }

    @Test
    fun `a start you set moves the boundary, whatever the list order`() {
        val slots = MealSlots.DEFAULT.map { if (it.key == "lunch") it.copy(startMinutes = 13 * 60 + 30) else it }.reversed()
        assertEquals("breakfast", at(slots, 13))
        assertEquals("lunch", at(slots, 13, 30))
    }

    @Test
    fun `removed meals and meals without a start are never picked`() {
        val slots = MealSlots.DEFAULT.map { if (it.key == "snacks") it.copy(removed = true) else it } + MealSlot("meal-1", "Pre-workout")
        assertEquals("lunch", at(slots, 17))
        val timed = slots.map { if (it.key == "meal-1") it.copy(startMinutes = 17 * 60) else it }
        assertEquals("meal-1", at(timed, 17))
        assertEquals("lunch", at(timed, 16, 59))
    }

    @Test
    fun `equal starts go to the later meal, and no starts to the first`() {
        val same = listOf(MealSlot("a", "A", startMinutes = 600), MealSlot("b", "B", startMinutes = 600))
        assertEquals("b", at(same, 11))
        assertEquals("b", at(same, 9))
        val none = listOf(MealSlot("meal-1", "One"), MealSlot("meal-2", "Two"))
        assertEquals("meal-1", at(none, 11))
        assertNull(at(emptyList(), 11))
        assertNull(at(listOf(MealSlot("a", "A", removed = true)), 11))
    }

    @Test
    fun `a range runs to the next meal's start, wrapping past midnight`() {
        val d = MealSlots.DEFAULT
        assertEquals(300 to 720, MealTimes.range(d, d[0]))
        assertEquals(1140 to 300, MealTimes.range(d, d[3]))
        assertNull(MealTimes.range(d, MealSlot("meal-1", "Pre-workout")))
        assertEquals("05:00", MealTimes.clock(300))
        assertEquals("19:05", MealTimes.clock(1145))
    }

    @Test
    fun `a stored start outside the day is ignored`() {
        assertNull(MealTimes.start(MealSlot("meal-1", "X", startMinutes = 5000)))
        assertEquals(300, MealTimes.start(MealSlot("breakfast", "Breakfast")))
    }
}
