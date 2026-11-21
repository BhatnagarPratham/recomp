package app.recomp.domain.ai

import app.recomp.domain.persona.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GL-03's prompts (TRD §5.7): only the typed text goes out, and the reply names an intent, never a goal. */
class GoalPromptTest {
    @Test
    fun `the user message is the typed text, tidied and capped`() {
        assertEquals("Goal, in their words: get leaner but keep my lifts", GoalPrompt.user("  get leaner\n but   keep my lifts "))
        val long = "x".repeat(2_000)
        assertEquals("Goal, in their words: ".length + GoalPrompt.MAX_TEXT, GoalPrompt.user(long).length)
    }

    @Test
    fun `the second message adds the one question and its answer`() {
        val text = GoalPrompt.user("look better", Clarification("Leaner, bigger, or both?", " both really "))
        assertEquals("Goal, in their words: look better\nYou asked: Leaner, bigger, or both?\nThey answered: both really", text)
    }

    @Test
    fun `the system prompt asks for an intent and gives no advice or body data`() {
        GoalPrompt.INTENTS.forEach { assertTrue(it, GoalPrompt.SYSTEM.contains(it)) }
        assertTrue(GoalPrompt.SYSTEM.contains("follow_up_question"))
        listOf("kg", "kcal", "BMI", "cm").forEach { assertFalse(it, GoalPrompt.SYSTEM.contains(it)) }
    }

    @Test
    fun `the reply's intent maps to the questions' intents, and unclear to none`() {
        assertEquals(Intent.LOSE_FAT, GoalPrompt.intent("lose_fat"))
        assertEquals(Intent.BUILD_MUSCLE, GoalPrompt.intent(" Build_Muscle "))
        assertEquals(Intent.BOTH, GoalPrompt.intent("both"))
        assertEquals(Intent.MAINTAIN, GoalPrompt.intent("maintain"))
        assertNull(GoalPrompt.intent(GoalPrompt.UNCLEAR))
        assertNull(GoalPrompt.intent("recomp"))
        assertEquals(5, GoalPrompt.INTENTS.size)
    }
}
