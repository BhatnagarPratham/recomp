package app.recomp.ui

import app.recomp.data.repo.DescribedMeal
import app.recomp.data.repo.ItemFrom
import app.recomp.domain.ai.AiProviderId
import app.recomp.domain.ai.AiState
import app.recomp.domain.ai.AiStatus
import app.recomp.domain.food.Macros
import app.recomp.domain.food.MealSlot
import app.recomp.domain.food.MealSlots
import app.recomp.domain.nutrition.FitCheck
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.Intake
import app.recomp.domain.nutrition.ProteinGoal
import app.recomp.ui.food.AiText
import app.recomp.ui.food.DescribeText
import app.recomp.ui.food.FitText
import app.recomp.ui.food.FoodText
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** M9's sentences (PRD §12.5): Will it fit's before and after for each goal, the snack allowance and slot times. */
class FitTextTest {
    @Before
    fun locale() = Locale.setDefault(Locale.US)

    private val dish = Macros(kcal = 420.0, proteinG = 32.0)
    private val eaten = Intake(1_720, 58)

    @Test
    fun `recomp leads with protein, as the PRD words it`() {
        val fit = FitCheck.of(Goal.RECOMP, 2_100, ProteinGoal.Full(120), eaten, dish)
        assertEquals("Protein left 62 g → 30 g · calories 380 left → 40 over", FitText.line(fit))
        assertEquals("Protein left 62 g → 30 g" to "Calories 380 left → 40 over", FitText.lines(fit))
    }

    @Test
    fun `fat loss leads with calories left`() {
        val fit = FitCheck.of(Goal.FAT_LOSS, 2_100, ProteinGoal.Full(120), eaten, dish)
        assertEquals("Calories 380 left → 40 over · protein left 62 g → 30 g", FitText.line(fit))
        val under = FitCheck.of(Goal.FAT_LOSS, 2_400, ProteinGoal.Full(120), eaten, dish)
        assertEquals("Calories 680 left → 260 left", FitText.calories(under))
    }

    @Test
    fun `maintain shows calories against its range`() {
        val fit = FitCheck.of(Goal.MAINTAIN, 2_200, ProteinGoal.Full(110), Intake(1_850, 70), Macros(380.0, 20.0))
        assertEquals("Calories 1,850 → 2,230 (range 2,090–2,310) · protein left 40 g → 20 g", FitText.line(fit))
    }

    @Test
    fun `lean bulk shows calories still to eat`() {
        val fit = FitCheck.of(Goal.LEAN_BULK, 2_800, ProteinGoal.Full(140), Intake(2_200, 100), Macros(380.0, 20.0))
        assertEquals("Calories to eat 600 → 220 · protein left 40 g → 20 g", FitText.line(fit))
        val past = FitCheck.of(Goal.LEAN_BULK, 2_800, ProteinGoal.Full(140), Intake(2_600, 100), Macros(380.0, 20.0))
        assertEquals("Calories to eat 200 → 180 over", FitText.calories(past))
        // N4: exactly at the target is 0 to eat, not "0 over".
        val at = FitCheck.of(Goal.LEAN_BULK, 2_800, ProteinGoal.Full(140), Intake(2_800, 100), Macros(300.0, 20.0))
        assertEquals("Calories to eat 0 → 300 over", FitText.calories(at))
    }

    @Test
    fun `protein with no target, past its target, and on the ramp`() {
        val measuring = FitCheck.of(Goal.RECOMP, 2_100, ProteinGoal.Measuring(120, 3), eaten, dish)
        assertEquals("Protein 58 g → 90 g", FitText.protein(measuring))
        val past = FitCheck.of(Goal.RECOMP, 2_100, ProteinGoal.Full(80), eaten, dish)
        assertEquals("Protein left 22 g → 10 g over", FitText.protein(past))
        val already = FitCheck.of(Goal.RECOMP, 2_100, ProteinGoal.Full(50), eaten, dish)
        assertEquals("Protein 8 g over → 40 g over", FitText.protein(already))
        val ramping = FitCheck.of(Goal.RECOMP, 2_100, ProteinGoal.Ramping(120, 100, 2), eaten, dish)
        assertEquals("Protein left 42 g → 10 g", FitText.protein(ramping))
    }

    @Test
    fun `the snack allowance's lines`() {
        assertEquals("includes a 150 kcal snack allowance", FoodText.allowanceIncluded(150))
        assertEquals("60 of 150 kcal", FoodText.allowanceUsed(60.4, 150))
        assertEquals("over is a plain number", "210 of 150 kcal", FoodText.allowanceUsed(210.0, 150))
    }

    @Test
    fun `a meal's usual time is its start to the next meal's`() {
        val slots = MealSlots.DEFAULT + MealSlot("meal-1", "Pre-workout")
        assertEquals("05:00–12:00", FoodText.slotTime(slots, slots[0]))
        assertEquals("19:00–05:00", FoodText.slotTime(slots, slots[3]))
        assertEquals("No usual time", FoodText.slotTime(slots, slots[4]))
        val one = listOf(MealSlot("breakfast", "Breakfast"))
        assertEquals("From 05:00", FoodText.slotTime(one, one[0]))
    }

    @Test
    fun `where an item's numbers came from`() {
        assertEquals(listOf("Your food", "Database", "AI", "Quick add"), ItemFrom.entries.map(FoodText::from))
    }

    @Test
    fun `the review's notice says why the AI's part is missing, or nothing`() {
        assertNull(DescribeText.notice(DescribedMeal(emptyList()), AiProviderId.ANTHROPIC))
        assertNull("AI off needs no excuse", DescribeText.notice(DescribedMeal(emptyList(), aiState = AiState.OFF), AiProviderId.ANTHROPIC))
        assertEquals(
            "Matched from your own foods and the database, without AI.",
            DescribeText.notice(DescribedMeal(emptyList(), aiState = AiState.OFFLINE), AiProviderId.ANTHROPIC),
        )
        val failed = DescribeText.notice(DescribedMeal(emptyList(), requestId = 4, aiFailure = AiStatus.TIMEOUT), AiProviderId.OPENAI)!!
        assertTrue(failed, failed.startsWith("OpenAI didn't answer in time, so these are only the foods matched"))
        assertNull("already-counted items have their own rows now", DescribeText.notice(DescribedMeal(emptyList(), requestId = 4), AiProviderId.ANTHROPIC))
    }

    @Test
    fun `the reason alone, for a screen that keeps no drafts`() {
        assertEquals("You're offline.", AiText.firstSentence("You're offline. Keep this as a draft and estimate it later; search works offline."))
        assertEquals("One sentence.", AiText.firstSentence("One sentence."))
    }

    @Test
    fun `a best guess's line, and why a save failed`() {
        val item = app.recomp.domain.ai.EstimatedItem("Stewed apple", 1.0, "bowl", 150.0, 120.4, 0.5, 30.0, 0.2, 3.0, app.recomp.domain.ai.Confidence.LOW, "")
        assertEquals("Best guess: Stewed apple · 1 bowl · 120 kcal", DescribeText.guess(item))
        assertEquals("Best guess: Stewed apple · 150 g · 120 kcal", DescribeText.guess(item.copy(unit = "g", quantity = 150.0)))
        assertTrue(DescribeText.saveFailed(IllegalArgumentException("That food is gone")).startsWith("Couldn't save: one of these foods is no longer"))
        assertEquals("Couldn't save: there's nothing to log. Add an item, or go back.", DescribeText.saveFailed(IllegalArgumentException("Nothing to log")))
        assertEquals("Couldn't save this. Nothing was logged; try again.", DescribeText.saveFailed(IllegalStateException("disk")))
    }
}
