package app.recomp.domain.persona

import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.Sex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every branch of TRD §5.7, with body fat known (scan or typed) and with the BMI fallback. Illustrative inputs. */
class GoalRecommenderTest {
    private fun advise(intent: Intent, experience: Experience = Experience.INTERMEDIATE, sex: Sex = Sex.MALE, bmi: Double = 24.0, bodyFat: Double? = null) =
        GoalRecommender.recommend(intent, experience, sex, bmi, bodyFat)

    private fun assertAdvice(goal: Goal, reason: AdviceReason, advice: GoalAdvice) {
        assertEquals("goal", goal, advice.goal)
        assertEquals("reason", reason, advice.reason)
    }

    @Test
    fun `lose fat recommends fat loss, or maintain when underweight`() {
        assertAdvice(Goal.FAT_LOSS, AdviceReason.LOSE_FAT, advise(Intent.LOSE_FAT, bmi = 29.0))
        assertAdvice(Goal.MAINTAIN, AdviceReason.UNDERWEIGHT, advise(Intent.LOSE_FAT, bmi = 18.4))
        assertAdvice(Goal.FAT_LOSS, AdviceReason.LOSE_FAT, advise(Intent.LOSE_FAT, bmi = 18.5))
    }

    @Test
    fun `both is a recomp for a new lifter whatever the body fat`() {
        assertAdvice(Goal.RECOMP, AdviceReason.BOTH_NEW_TO_LIFTING, advise(Intent.BOTH, Experience.BEGINNER, bmi = 22.0))
        assertAdvice(Goal.RECOMP, AdviceReason.BOTH_NEW_TO_LIFTING, advise(Intent.BOTH, Experience.BEGINNER, bodyFat = 12.0))
    }

    @Test
    fun `both is a recomp for a trained lifter carrying more fat`() {
        assertAdvice(Goal.RECOMP, AdviceReason.BOTH_HIGHER_FAT, advise(Intent.BOTH, bodyFat = 20.0))
        assertAdvice(Goal.RECOMP, AdviceReason.BOTH_HIGHER_FAT, advise(Intent.BOTH, Experience.ADVANCED, sex = Sex.FEMALE, bodyFat = 28.0))
        // BMI fallback when body fat is unknown.
        assertAdvice(Goal.RECOMP, AdviceReason.BOTH_HIGHER_FAT, advise(Intent.BOTH, bmi = 27.0))
    }

    @Test
    fun `both is a lean bulk for a lean trained lifter`() {
        assertAdvice(Goal.LEAN_BULK, AdviceReason.BOTH_LEAN_AND_TRAINED, advise(Intent.BOTH, bodyFat = 19.9))
        assertAdvice(Goal.LEAN_BULK, AdviceReason.BOTH_LEAN_AND_TRAINED, advise(Intent.BOTH, sex = Sex.FEMALE, bodyFat = 27.9))
        assertAdvice(Goal.LEAN_BULK, AdviceReason.BOTH_LEAN_AND_TRAINED, advise(Intent.BOTH, bmi = 26.9))
    }

    @Test
    fun `both is maintain when underweight, even for a new lifter`() {
        assertAdvice(Goal.MAINTAIN, AdviceReason.UNDERWEIGHT, advise(Intent.BOTH, Experience.BEGINNER, bmi = 18.0))
    }

    @Test
    fun `build muscle is a lean bulk unless body fat is very high`() {
        assertAdvice(Goal.LEAN_BULK, AdviceReason.MUSCLE, advise(Intent.BUILD_MUSCLE, bodyFat = 24.9))
        assertAdvice(Goal.RECOMP, AdviceReason.MUSCLE_HIGH_FAT, advise(Intent.BUILD_MUSCLE, bodyFat = 25.0))
        assertAdvice(Goal.LEAN_BULK, AdviceReason.MUSCLE, advise(Intent.BUILD_MUSCLE, sex = Sex.FEMALE, bodyFat = 31.9))
        assertAdvice(Goal.RECOMP, AdviceReason.MUSCLE_HIGH_FAT, advise(Intent.BUILD_MUSCLE, sex = Sex.FEMALE, bodyFat = 32.0))
        assertAdvice(Goal.LEAN_BULK, AdviceReason.MUSCLE, advise(Intent.BUILD_MUSCLE, bmi = 29.9))
        assertAdvice(Goal.RECOMP, AdviceReason.MUSCLE_HIGH_FAT, advise(Intent.BUILD_MUSCLE, bmi = 30.0))
    }

    @Test
    fun `build muscle never suggests a deficit below BMI 18_5`() {
        assertAdvice(Goal.LEAN_BULK, AdviceReason.MUSCLE, advise(Intent.BUILD_MUSCLE, bmi = 18.0, bodyFat = 30.0))
    }

    @Test
    fun `stay as I am is maintain`() {
        assertAdvice(Goal.MAINTAIN, AdviceReason.STAY, advise(Intent.MAINTAIN, bmi = 33.0))
    }

    @Test
    fun `known body fat decides over BMI and the basis says which was used`() {
        val lean = advise(Intent.BUILD_MUSCLE, bmi = 31.0, bodyFat = 15.0)
        assertAdvice(Goal.LEAN_BULK, AdviceReason.MUSCLE, lean)
        assertTrue(lean.basis.usesBodyFat)

        val byBmi = advise(Intent.BUILD_MUSCLE, bmi = 31.0)
        assertAdvice(Goal.RECOMP, AdviceReason.MUSCLE_HIGH_FAT, byBmi)
        assertFalse(byBmi.basis.usesBodyFat)
        assertEquals(31.0, byBmi.basis.bmi, 0.0)
    }
}
