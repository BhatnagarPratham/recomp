package app.recomp.domain.nutrition

import app.recomp.domain.persona.ActivityLevel
import app.recomp.domain.persona.FinishLine
import app.recomp.domain.persona.GoalPlan
import app.recomp.domain.persona.Profile
import java.time.LocalDate
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Golden vectors from TRD §5.3. All inputs are illustrative, not anyone's real data. */
class NutritionCalculatorTest {

    private val recomp = TargetInputs(
        sex = Sex.MALE,
        ageYears = 30,
        heightCm = 175.0,
        trendWeightKg = 95.0,
        goalWeightKg = 78.0,
        policy = GoalPolicy.defaultFor(Goal.RECOMP),
    )

    private fun assertTargets(
        t: NutritionTargets, bmr: Int, tdee: Int, adjustment: Int, kcal: Int, protein: Int, fat: Int, carbs: Int,
    ) {
        assertEquals("bmr", bmr, t.bmrKcal.roundToInt())
        assertEquals("tdee", tdee, t.tdeeKcal.roundToInt())
        assertEquals("adjustment", adjustment, t.energyAdjustmentKcal.roundToInt())
        assertEquals("kcal", kcal, t.kcal)
        assertEquals("protein", protein, t.proteinG)
        assertEquals("fat", fat, t.fatG)
        assertEquals("carbs", carbs, t.carbsG)
    }

    @Test
    fun `A recomp at Standard pace uses Mifflin-St Jeor without a scan`() {
        assertTargets(NutritionCalculator.startingTargets(recomp), 1899, 2611, -523, 2090, 125, 58, 267)
    }

    @Test
    fun `B recomp at Aggressive pace with a scan uses Katch-McArdle and caps the deficit at 25 percent of TDEE`() {
        val aggressiveWithScan = recomp.copy(policy = GoalPolicy.defaultFor(Goal.RECOMP, Pace.AGGRESSIVE), leanMassKg = 66.0)
        assertTargets(NutritionCalculator.startingTargets(aggressiveWithScan), 1796, 2469, -617, 1850, 125, 51, 222)
    }

    @Test
    fun `C lean bulk adds a 10 percent surplus and uses current weight for protein`() {
        val bulk = TargetInputs(
            sex = Sex.MALE, ageYears = 30, heightCm = 175.0, trendWeightKg = 70.0,
            activityFactor = 1.55, policy = GoalPolicy.defaultFor(Goal.LEAN_BULK),
        )
        assertTargets(NutritionCalculator.startingTargets(bulk), 1649, 2556, 256, 2810, 112, 78, 415)
    }

    @Test
    fun `D fat loss caps the deficit and uses the higher protein default`() {
        val cut = TargetInputs(
            sex = Sex.FEMALE, ageYears = 35, heightCm = 165.0, trendWeightKg = 80.0, goalWeightKg = 65.0,
            policy = GoalPolicy.defaultFor(Goal.FAT_LOSS),
        )
        assertTargets(NutritionCalculator.startingTargets(cut), 1495, 2056, -514, 1540, 130, 43, 159)
    }

    @Test
    fun `E maintain eats at TDEE`() {
        val maintain = recomp.copy(policy = GoalPolicy.defaultFor(Goal.MAINTAIN))
        assertTargets(NutritionCalculator.startingTargets(maintain), 1899, 2611, 0, 2610, 125, 73, 365)
    }

    @Test
    fun `intake never drops below BMR`() {
        val aggressive = recomp.copy(activityFactor = 1.2, policy = GoalPolicy(Goal.FAT_LOSS, lossRatePctPerWeek = 1.0, proteinGPerKg = 2.0))
        val t = NutritionCalculator.startingTargets(aggressive)

        assertTrue(t.kcal >= t.bmrKcal)
        assertEquals(1900, t.kcal)
    }

    @Test
    fun `surplus is capped at 15 percent of TDEE`() {
        val greedy = recomp.copy(goalWeightKg = null, policy = GoalPolicy(Goal.LEAN_BULK, surplusFraction = 0.30, proteinGPerKg = 1.6))
        val t = NutritionCalculator.startingTargets(greedy)

        assertEquals((t.tdeeKcal * 0.15).roundToInt(), t.energyAdjustmentKcal.roundToInt())
    }

    @Test
    fun `pace presets match the TRD table`() {
        fun loss(goal: Goal, pace: Pace) = GoalPolicy.defaultFor(goal, pace).lossRatePctPerWeek
        fun surplus(pace: Pace) = GoalPolicy.defaultFor(Goal.LEAN_BULK, pace).surplusFraction

        assertEquals(listOf(0.5, 0.75, 1.0), Pace.entries.map { loss(Goal.FAT_LOSS, it) })
        assertEquals(listOf(0.25, 0.5, 0.75), Pace.entries.map { loss(Goal.RECOMP, it) })
        assertEquals(listOf(0.05, 0.10, 0.15), Pace.entries.map { surplus(it) })
        assertEquals(setOf(GoalPolicy.defaultFor(Goal.MAINTAIN)), Pace.entries.map { GoalPolicy.defaultFor(Goal.MAINTAIN, it) }.toSet())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a policy cannot both lose and gain`() {
        GoalPolicy(Goal.RECOMP, lossRatePctPerWeek = 0.5, surplusFraction = 0.1, proteinGPerKg = 1.6)
    }

    // Where each number came from (PR-02)

    @Test
    fun `the breakdown explains vector A`() {
        val t = NutritionCalculator.startingTargets(recomp)
        assertEquals(BmrFormula.MIFFLIN_ST_JEOR, t.bmrFormula)
        assertEquals(1.375, t.activityFactor, 0.0)
        assertEquals(false, t.adjustmentCapped)
        assertEquals(false, t.floored)
        assertEquals(1900, t.floorKcal)
        assertEquals(78.0, t.referenceKg, 0.0)
        assertTrue(t.referenceIsGoalWeight)
        assertEquals(1.6, t.proteinGPerKg, 0.0)
        assertEquals(false, t.fatFromBodyWeight)
    }

    @Test
    fun `the breakdown flags capped deficits and surpluses`() {
        val b = NutritionCalculator.startingTargets(recomp.copy(policy = GoalPolicy.defaultFor(Goal.RECOMP, Pace.AGGRESSIVE), leanMassKg = 66.0))
        assertEquals(BmrFormula.KATCH_MCARDLE, b.bmrFormula)
        assertTrue(b.adjustmentCapped)
        val greedy = NutritionCalculator.startingTargets(recomp.copy(policy = GoalPolicy(Goal.LEAN_BULK, surplusFraction = 0.30, proteinGPerKg = 1.6)))
        assertTrue(greedy.adjustmentCapped)
        assertEquals(false, NutritionCalculator.startingTargets(recomp.copy(policy = GoalPolicy.defaultFor(Goal.MAINTAIN))).adjustmentCapped)
    }

    @Test
    fun `the breakdown flags the floor and fat from body weight`() {
        val floored = NutritionCalculator.startingTargets(recomp.copy(activityFactor = 1.2, policy = GoalPolicy(Goal.FAT_LOSS, lossRatePctPerWeek = 1.0, proteinGPerKg = 2.0)))
        assertTrue(floored.floored)

        // A heavy reference weight with a modest intake: 0.6 g/kg beats 25% of calories.
        val heavy = TargetInputs(Sex.FEMALE, 35, 165.0, trendWeightKg = 120.0, policy = GoalPolicy.defaultFor(Goal.FAT_LOSS))
        val t = NutritionCalculator.startingTargets(heavy)
        assertTrue(t.fatFromBodyWeight)
        assertEquals(72, t.fatG)
        assertEquals(false, t.referenceIsGoalWeight)
    }

    // Overrides (PR-02)

    @Test
    fun `a calorie override rescales fat and carbs fill the rest`() {
        val t = NutritionCalculator.startingTargets(recomp, TargetOverrides(kcal = 2000))
        assertEquals(2000, t.kcal)
        assertEquals(125, t.proteinG)
        assertEquals(56, t.fatG)
        assertEquals(250, t.carbsG)
        assertEquals(TargetOverrides(kcal = 2000), t.overrides)
        // TDEE and the adjustment still describe the computed plan.
        assertEquals(2611, t.tdeeKcal.roundToInt())
    }

    @Test
    fun `a calorie override is held at the floor`() {
        assertEquals(1900, NutritionCalculator.startingTargets(recomp, TargetOverrides(kcal = 1200)).kcal)
    }

    @Test
    fun `protein and fat overrides leave calories alone and move carbs`() {
        val protein = NutritionCalculator.startingTargets(recomp, TargetOverrides(proteinG = 150))
        assertTargets(protein, 1899, 2611, -523, 2090, 150, 58, 242)
        val fat = NutritionCalculator.startingTargets(recomp, TargetOverrides(fatG = 40))
        assertTargets(fat, 1899, 2611, -523, 2090, 125, 40, 308)
        assertEquals(false, fat.fatFromBodyWeight)
    }

    @Test
    fun `carbs never go below zero`() {
        assertEquals(0, NutritionCalculator.startingTargets(recomp, TargetOverrides(proteinG = 600)).carbsG)
    }

    @Test
    fun `inputs come from the profile and goal plan on the day`() {
        val profile = Profile(Sex.MALE, LocalDate.of(1996, 10, 18), 175.0, 95.0, ActivityLevel.MODERATE)
        val plan = GoalPlan.of(Goal.RECOMP, Pace.STANDARD, 78.0, FinishLine.GoalWeight, LocalDate.of(2026, 10, 17))
        // One day before the 30th birthday the age is still 29; on it, vector A exactly.
        assertEquals(29, TargetInputs.from(profile, plan, LocalDate.of(2026, 10, 17)).ageYears)
        assertEquals(recomp, TargetInputs.from(profile, plan, LocalDate.of(2026, 10, 18)))
    }

    @Test
    fun `a maintenance estimate replaces BMR times activity, and nothing else changes`() {
        val t = NutritionCalculator.startingTargets(recomp.copy(maintenanceKcal = 2480.0))
        assertTargets(t, bmr = 1899, tdee = 2480, adjustment = -523, kcal = 1960, protein = 125, fat = 54, carbs = 243)
        assertTrue(t.adaptiveTdee)
        assertFalse(NutritionCalculator.startingTargets(recomp).adaptiveTdee)
    }
}
