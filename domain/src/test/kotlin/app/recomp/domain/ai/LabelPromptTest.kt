package app.recomp.domain.ai

import app.recomp.domain.food.FoodDiet
import app.recomp.domain.food.Origin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NU-19's label request and NU-20's meal-photo prompt. */
class LabelPromptTest {
    @Test
    fun `the label prompt carries no persona and the recogniser's text, capped`() {
        assertFalse(LabelPrompt.system.contains("vegetarian. No meat")) // no diet rule: a label is the same for everyone
        assertTrue(LabelPrompt.system.contains("per 100 g"))
        val user = LabelPrompt.user("Energy (kcal) 456\n" + "x".repeat(5_000))
        assertTrue(user.startsWith("The photo is attached."))
        assertTrue(user.contains("Energy (kcal) 456"))
        assertTrue(user.length < LabelPrompt.MAX_OCR_CHARS + 200)
        assertEquals("The photo is attached.\nThe on-device recogniser read no text.", LabelPrompt.user("  "))
    }

    @Test
    fun `a label reply's impossible values and its -1s become blanks`() {
        val raw = LabelEstimate(" Oat crunch ", "", 402.04, 10.0, -1.0, 140.0, Double.NaN, 30.0, FoodDiet.VEGAN)
        val checked = LabelCheck.check(raw)!!
        assertEquals(LabelEstimate("Oat crunch", null, 402.0, 10.0, null, null, null, 30.0, FoodDiet.VEGAN), checked)
        assertNull(LabelCheck.check(LabelEstimate(null, null, -1.0, -1.0, -1.0, -1.0, -1.0, -1.0, null)))
    }

    @Test
    fun `a meal photo says so, and words are optional`() {
        val withWords = MealPrompt.user("2 rotis, dal", Origin.HOME, photo = true)
        assertTrue(withWords.contains("Photo: the attached photo shows the meal"))
        assertTrue(withWords.contains("Meal: 2 rotis, dal"))
        assertTrue(MealPrompt.user("", Origin.HOME, photo = true).contains("Meal: (no words; use the photo)"))
        assertFalse(MealPrompt.user("2 rotis", Origin.HOME).contains("Photo:"))
    }
}
