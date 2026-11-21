package app.recomp.domain.coach

import app.recomp.domain.persona.Experience
import app.recomp.domain.persona.ExperiencePolicy
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TRD §10 M11: every suggestion kind validated, unknown exercises and values outside the guardrails downgraded to advice. */
class SuggestionCheckTest {
    private val ctx = CheckContext(
        terms = TargetTerms.RELATIVE,
        exerciseRefs = mapOf("e1" to 1L, "e2" to 2L, "e3" to 3L, "e4" to 4L, "e9" to 9L),
        routineRefs = mapOf("r1" to 11L, "r2" to 12L),
        routineExercises = mapOf(11L to listOf(1L, 2L), 12L to listOf(3L)),
        library = setOf(1L, 2L, 3L, 4L),
        maintenanceKcal = 2500.0,
        referenceKg = 80.0,
        kcalRange = 1880..2870,
        proteinGPerKg = 1.2..2.4,
    )

    private fun check(kind: String, routine: String = "", exercise: String = "", replacement: String = "", min: Double = 0.0, max: Double = min, c: CheckContext = ctx) =
        SuggestionCheck.check(RawSuggestion(kind, routine, exercise, replacement, min, max, "  because  "), c)

    @Test
    fun `calories and protein convert from the report's terms, inside the guardrails`() {
        assertEquals(Change.Calories(2130), check("CALORIES", min = -15.0).change) // 2500 × 0.85 = 2125 → 2130
        assertEquals(Change.Protein(144), check("PROTEIN", min = 1.8).change)
        val absolute = ctx.copy(terms = TargetTerms.ABSOLUTE)
        assertEquals(Change.Calories(2200), check("CALORIES", min = 2195.0, c = absolute).change)
        assertEquals(Change.Protein(150), check("PROTEIN", min = 150.0, c = absolute).change)
        // Too deep a deficit, too big a surplus, too much or too little protein: advice only.
        assertEquals(AdviceOnly.OUTSIDE_GUARDRAILS, check("CALORIES", min = -30.0).adviceOnly)
        assertNull(check("CALORIES", min = -30.0).change)
        assertEquals(AdviceOnly.OUTSIDE_GUARDRAILS, check("CALORIES", min = 20.0).adviceOnly)
        assertEquals(AdviceOnly.OUTSIDE_GUARDRAILS, check("PROTEIN", min = 3.0).adviceOnly)
        assertEquals(AdviceOnly.OUTSIDE_GUARDRAILS, check("PROTEIN", min = 0.5).adviceOnly)
        assertEquals(AdviceOnly.OUTSIDE_GUARDRAILS, check("CALORIES", min = Double.NaN).adviceOnly)
    }

    @Test
    fun `no deficit when the guardrails rule one out`() {
        // Below BMI 18.5 the range starts at maintenance.
        val noDeficit = ctx.copy(kcalRange = 2500..2870)
        assertEquals(AdviceOnly.OUTSIDE_GUARDRAILS, check("CALORIES", min = -10.0, c = noDeficit).adviceOnly)
        assertEquals(Change.Calories(2630), check("CALORIES", min = 5.0, c = noDeficit).change)
    }

    @Test
    fun `routine changes need exercises from the report, in the routine named`() {
        assertEquals(Change.Swap(11, 2, 4), check("SWAP", "r1", "e2", "e4").change)
        assertEquals(Change.Add(12, 4), check("ADD", "r2", "e4").change)
        assertEquals(Change.Remove(11, 2), check("REMOVE", "r1", "e2").change)
        assertEquals(Change.Sets(11, 1, 4), check("SETS", "r1", "e1", min = 4.0).change)
        assertEquals(Change.RepRange(12, 3, 5, 8), check("REP_RANGE", "r2", "e3", min = 5.0, max = 8.0).change)
        assertEquals(Change.StrengthDays(4), check("STRENGTH_DAYS", min = 4.0).change)
        assertEquals(Change.DeloadExercise(3), check("DELOAD_EXERCISE", exercise = "e3").change)
        assertEquals(Change.DeloadAll, check("DELOAD_ALL").change)
        assertEquals("because", check("DELOAD_ALL").reason)

        // Unknown refs, an archived exercise (e9 isn't in the library) or the wrong routine: advice only.
        assertEquals(AdviceOnly.UNKNOWN_EXERCISE, check("SWAP", "r1", "e2", "e99").adviceOnly)
        assertEquals(AdviceOnly.UNKNOWN_EXERCISE, check("SWAP", "r1", "e2", "e9").adviceOnly)
        assertEquals(AdviceOnly.UNKNOWN_EXERCISE, check("SETS", "r2", "e1", min = 4.0).adviceOnly)
        assertEquals(AdviceOnly.UNKNOWN_EXERCISE, check("ADD", "r9", "e4").adviceOnly)
        assertEquals(AdviceOnly.UNKNOWN_EXERCISE, check("DELOAD_EXERCISE", exercise = "Bench press").adviceOnly)
        // A library exercise in no routine (e4): a deload mark would sit unseen, so it's advice.
        assertEquals(AdviceOnly.UNKNOWN_EXERCISE, check("DELOAD_EXERCISE", exercise = "e4").adviceOnly)
        // Changes that don't fit.
        assertEquals(AdviceOnly.DOESNT_FIT, check("SWAP", "r1", "e2", "e1").adviceOnly)
        assertEquals(AdviceOnly.DOESNT_FIT, check("ADD", "r1", "e1").adviceOnly)
        assertEquals(AdviceOnly.DOESNT_FIT, check("REMOVE", "r2", "e3").adviceOnly)
        // Ranges.
        assertEquals(AdviceOnly.OUTSIDE_GUARDRAILS, check("SETS", "r1", "e1", min = 0.0).adviceOnly)
        assertEquals(AdviceOnly.OUTSIDE_GUARDRAILS, check("SETS", "r1", "e1", min = 12.0).adviceOnly)
        assertEquals(AdviceOnly.OUTSIDE_GUARDRAILS, check("REP_RANGE", "r1", "e1", min = 10.0, max = 6.0).adviceOnly)
        assertEquals(AdviceOnly.OUTSIDE_GUARDRAILS, check("STRENGTH_DAYS", min = 9.0).adviceOnly)
    }

    @Test
    fun `advice stays advice, an unknown kind is advice, and at most five are kept`() {
        assertEquals(Suggestion(SuggestionKind.ADVICE, null, "because"), check("ADVICE"))
        assertEquals(SuggestionKind.ADVICE, check("TEMPO").kind)
        assertNull(check("TEMPO").adviceOnly)
        val reply = CoachReply("ok", List(8) { RawSuggestion("DELOAD_ALL") })
        assertEquals(SuggestionCheck.MAX_SUGGESTIONS, SuggestionCheck.check(reply, ctx).size)
        assertEquals(Change.DeloadAll, check("deload_all").change)
    }

    @Test
    fun `the scheduled deload is offered from the policy's first week, never to beginners`() {
        val start = LocalDate.of(2026, 9, 7)
        val intermediate = ExperiencePolicy.defaultFor(Experience.INTERMEDIATE)
        val advanced = ExperiencePolicy.defaultFor(Experience.ADVANCED)
        val beginner = ExperiencePolicy.defaultFor(Experience.BEGINNER)
        assertFalse(DeloadSchedule.due(intermediate, start, start.plusWeeks(6).minusDays(1)))
        assertTrue(DeloadSchedule.due(intermediate, start, start.plusWeeks(6)))
        assertFalse(DeloadSchedule.due(advanced, start, start.plusWeeks(4).minusDays(1)))
        assertTrue(DeloadSchedule.due(advanced, start, start.plusWeeks(4)))
        assertFalse(DeloadSchedule.due(beginner, start, start.plusWeeks(52)))
        assertFalse(DeloadSchedule.due(intermediate, null, start.plusWeeks(52)))
        // "Not now" hides it for a week.
        val today = start.plusWeeks(7)
        assertFalse(DeloadSchedule.due(intermediate, start, today, snoozedUntil = today.plusDays(1)))
        assertTrue(DeloadSchedule.due(intermediate, start, today, snoozedUntil = today))
    }

    @Test
    fun `weekly sets count a secondary muscle as half`() {
        val sets = MuscleSets.weekly(listOf(MuscleSets.Work("chest", listOf("triceps", "shoulders"), 3), MuscleSets.Work("triceps", emptyList(), 2), MuscleSets.Work("back", emptyList(), 0)))
        assertEquals(mapOf("chest" to 3.0, "triceps" to 3.5, "shoulders" to 1.5), sets)
    }
}
