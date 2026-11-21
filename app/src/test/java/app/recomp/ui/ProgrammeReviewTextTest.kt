package app.recomp.ui

import app.recomp.domain.coach.AdviceOnly
import app.recomp.domain.coach.Change
import app.recomp.domain.coach.Suggestion
import app.recomp.domain.coach.SuggestionKind
import app.recomp.ui.checkin.ProgrammeReviewText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The programme review's suggestion lines (PRD §12.7). Invented names. */
class ProgrammeReviewTextTest {
    private val names = mapOf(1L to "Bench press", 2L to "Incline press")
    private fun title(c: Change?, kind: SuggestionKind = SuggestionKind.ADVICE) =
        ProgrammeReviewText.title(Suggestion(kind, c, "why"), { names.getValue(it) }, { "Push" })

    @Test
    fun `each change reads as what it does`() {
        assertEquals("Push: swap Bench press for Incline press", title(Change.Swap(9, 1, 2)))
        assertEquals("Push: Bench press for 4 sets", title(Change.Sets(9, 1, 4)))
        assertEquals("Push: Bench press at 5–8 reps", title(Change.RepRange(9, 1, 5, 8)))
        assertEquals("4 strength days a week", title(Change.StrengthDays(4)))
        assertEquals("Deload Bench press next workout", title(Change.DeloadExercise(1)))
        assertEquals("Deload the whole week", title(Change.DeloadAll))
        assertEquals("Protein: 150 g a day", title(Change.Protein(150)))
        assertEquals("Advice", title(null))
    }

    @Test
    fun `advice only says why`() {
        assertNull(ProgrammeReviewText.adviceOnly(Suggestion(SuggestionKind.ADVICE, null, "x")))
        assertTrue("library" in ProgrammeReviewText.adviceOnly(Suggestion(SuggestionKind.SWAP, null, "x", AdviceOnly.UNKNOWN_EXERCISE))!!)
        assertTrue("safe ranges" in ProgrammeReviewText.adviceOnly(Suggestion(SuggestionKind.CALORIES, null, "x", AdviceOnly.OUTSIDE_GUARDRAILS))!!)
        assertEquals("It's been 7 weeks of training. A lighter week now helps you keep progressing: each lift at 90% for a week.", ProgrammeReviewText.deloadLine(7))
    }
}
