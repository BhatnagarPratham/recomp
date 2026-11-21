package app.recomp.domain.food

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NU-06: the snack allowance is inside the calorie target. */
class SnackAllowanceTest {
    @Test
    fun `100 to 300 kcal in steps of 50`() {
        assertEquals(listOf(100, 150, 200, 250, 300), SnackAllowance.OPTIONS)
        assertTrue(SnackAllowance(150).valid)
        assertFalse(SnackAllowance(125).valid)
        assertFalse(SnackAllowance(350).valid)
        assertFalse(SnackAllowance(0).valid)
        assertFalse(SnackAllowance(150, " ").valid)
    }

    @Test
    fun `it is part of the target, not extra`() {
        val totals = DayTotals.of(listOf(Macros(1_500.0, 80.0)))
        val allowance = SnackAllowance(150)
        // What's left of the day is the same with the allowance on or off: it only sets some of the target aside.
        assertEquals(Remaining(600, 40), Remaining.of(totals, 2_100, 120))
        assertEquals(150, allowance.kcal)
    }

    @Test
    fun `it counts against snacks, or your first meal when there's no snacks`() {
        assertEquals("snacks", SnackAllowance.defaultSlot(MealSlots.DEFAULT))
        val noSnacks = MealSlots.DEFAULT.map { if (it.key == "snacks") it.copy(removed = true) else it }
        assertEquals("breakfast", SnackAllowance.defaultSlot(noSnacks))
        assertNull(SnackAllowance.defaultSlot(emptyList()))
    }

    @Test
    fun `an allowance whose meal was removed moves to the default meal, so its line always has a meal`() {
        assertEquals("lunch", SnackAllowance(150, "lunch").slotIn(MealSlots.DEFAULT))
        val noLunch = MealSlots.DEFAULT.map { if (it.key == "lunch") it.copy(removed = true) else it }
        assertEquals("snacks", SnackAllowance(150, "lunch").slotIn(noLunch))
        val gone = MealSlots.DEFAULT.map { if (it.key == "snacks") it.copy(removed = true) else it }
        assertEquals("breakfast", SnackAllowance(150).slotIn(gone))
        assertEquals("a meal that no longer exists at all", "snacks", SnackAllowance(150, "meal-9").slotIn(MealSlots.DEFAULT))
        assertNull(SnackAllowance(150).slotIn(emptyList()))
    }
}
