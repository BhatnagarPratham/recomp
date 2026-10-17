package app.recomp.domain.persona

import app.recomp.domain.model.SessionKind
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.Pace
import app.recomp.domain.nutrition.Sex
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Presets (PRD §4.4) and the onboarding draft (PS-01). */
class PersonaTest {
    private val today = LocalDate.of(2026, 10, 17)
    private val recomp = GoalPlan.of(Goal.RECOMP, Pace.STANDARD, 78.0, FinishLine.GoalWeight, today)

    @Test
    fun `presets match the PRD table`() {
        assertEquals(5, Presets.all.map { it.key }.toSet().size)
        with(Presets.REFERENCE) {
            assertEquals(Goal.RECOMP, goal)
            assertEquals(Experience.BEGINNER, draft.experience)
            assertEquals(Diet.LACTO_VEGETARIAN, draft.diet)
            assertEquals(FoodRegion.INDIAN, draft.region)
            assertEquals(SplitTemplate.PPL, draft.split)
            assertEquals(listOf(SessionKind.YOGA), draft.extras.map { it.kind })
            assertEquals(WeightSource.HOME_SCALE, draft.weightSource)
        }
        assertEquals(Goal.FAT_LOSS to SplitTemplate.FULL_BODY_AB, Presets.FAT_LOSS_STARTER.goal to Presets.FAT_LOSS_STARTER.draft.split)
        assertEquals(Experience.BEGINNER, Presets.FAT_LOSS_STARTER.draft.experience)
        assertEquals(Goal.LEAN_BULK to SplitTemplate.UPPER_LOWER, Presets.LEAN_BULK.goal to Presets.LEAN_BULK.draft.split)
        assertEquals(Experience.INTERMEDIATE, Presets.LEAN_BULK.draft.experience)
        assertEquals(Goal.MAINTAIN to SplitTemplate.FULL_BODY_AB, Presets.MAINTAIN.goal to Presets.MAINTAIN.draft.split)
        assertNull(Presets.MAINTAIN.draft.experience)
        assertNull(Presets.CUSTOM.goal)
        assertEquals(Presets.LEAN_BULK, Presets.byKey("lean_bulk"))
        assertNull(Presets.byKey("gone"))
    }

    @Test
    fun `asked values are missing until chosen`() {
        assertEquals(listOf("goal", "diet", "region"), Presets.FAT_LOSS_STARTER.draft.missing)
        assertEquals(listOf("goal", "experience", "diet", "region", "split"), Presets.CUSTOM.draft.missing)
        assertNull(Presets.FAT_LOSS_STARTER.draft.complete())
    }

    @Test
    fun `a complete draft becomes a persona with the diet's ramp default and the split's days`() {
        val draft = Presets.LEAN_BULK.draft.copy(goal = recomp, diet = Diet.NON_VEGETARIAN, region = FoodRegion.INTERNATIONAL)
        val persona = draft.complete()!!
        assertFalse(persona.proteinRamp)
        assertEquals(4, persona.strengthDaysPerWeek)
        assertEquals(Experience.INTERMEDIATE, persona.experiencePolicy.level)

        assertTrue(draft.copy(diet = Diet.EGGETARIAN).complete()!!.proteinRamp)
        assertFalse(draft.copy(diet = Diet.VEGAN, proteinRamp = false).complete()!!.proteinRamp)
        assertEquals(5, draft.copy(strengthDaysPerWeek = 5).complete()!!.strengthDaysPerWeek)
        assertEquals(persona, persona.toDraft().complete())
    }

    @Test
    fun `the ramp is on by default for vegan, lacto-vegetarian and eggetarian diets`() {
        assertEquals(setOf(Diet.VEGAN, Diet.LACTO_VEGETARIAN, Diet.EGGETARIAN), Diet.entries.filter { it.rampByDefault }.toSet())
    }

    @Test
    fun `profile age and BMI`() {
        val profile = Profile(Sex.FEMALE, LocalDate.of(1991, 10, 18), heightCm = 160.0, weightKg = 64.0)
        assertEquals(34, profile.ageOn(today))
        assertEquals(35, profile.ageOn(today.plusDays(1)))
        assertEquals(25.0, profile.bmi, 1e-9)
        assertEquals(ActivityLevel.MODERATE, profile.activity)
        assertNotNull(Bmi.weightAt(25.0, 160.0))
        assertEquals(64.0, Bmi.weightAt(25.0, 160.0), 1e-9)
    }

    @Test
    fun `maintain has no pace`() {
        assertNull(GoalPlan.of(Goal.MAINTAIN, Pace.AGGRESSIVE, null, null, today).pace)
        assertEquals(Pace.GENTLE, GoalPlan.of(Goal.FAT_LOSS, Pace.GENTLE, null, null, today).pace)
    }
}
