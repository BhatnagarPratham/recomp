package app.recomp.domain.nutrition

import app.recomp.domain.persona.GoalPlan
import app.recomp.domain.persona.Profile
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Sex { MALE, FEMALE }

/** Inputs for the starting targets (TRD §5.3). Weights in kg, height in cm. */
data class TargetInputs(
    val sex: Sex,
    val ageYears: Int,
    val heightCm: Double,
    val trendWeightKg: Double,
    /** Protein and the fat floor scale with this when set; otherwise with current weight. */
    val goalWeightKg: Double? = null,
    val activityFactor: Double = 1.375,
    val policy: GoalPolicy = GoalPolicy.defaultFor(Goal.RECOMP),
    /** Lean mass from a body-composition scan no older than 60 days. When present, BMR uses Katch-McArdle. */
    val leanMassKg: Double? = null,
    /** The check-in's maintenance estimate (NU-05, TRD §5.4). When set, it replaces BMR × activity as TDEE. */
    val maintenanceKcal: Double? = null,
) {
    companion object {
        /** The persona's view of the inputs on [today]. Lean mass waits for M4's scans (IB-05). */
        fun from(profile: Profile, plan: GoalPlan, today: LocalDate, leanMassKg: Double? = null) = TargetInputs(
            sex = profile.sex,
            ageYears = profile.ageOn(today),
            heightCm = profile.heightCm,
            trendWeightKg = profile.weightKg,
            goalWeightKg = plan.goalWeightKg,
            activityFactor = profile.activity.factor,
            policy = plan.policy,
            leanMassKg = leanMassKg,
        )
    }
}

/** Values you set yourself (PR-02). Carbs always fill the remaining calories, so they're never overridden. */
data class TargetOverrides(val kcal: Int? = null, val proteinG: Int? = null, val fatG: Int? = null) {
    val isEmpty: Boolean get() = kcal == null && proteinG == null && fatG == null
}

enum class BmrFormula { MIFFLIN_ST_JEOR, KATCH_MCARDLE }

data class NutritionTargets(
    val bmrKcal: Double,
    val tdeeKcal: Double,
    /** Signed: negative for a deficit, positive for a surplus, zero at maintenance. */
    val energyAdjustmentKcal: Double,
    val kcal: Int,
    val proteinG: Int,
    val fatG: Int,
    val carbsG: Int,
    // Where each number came from, for the Targets screen (PR-02).
    val bmrFormula: BmrFormula = BmrFormula.MIFFLIN_ST_JEOR,
    val activityFactor: Double = 1.375,
    /** The goal's deficit or surplus hit its cap (25% / 15% of TDEE). */
    val adjustmentCapped: Boolean = false,
    /** The computed intake was raised to the floor: BMR, or 1,500 / 1,200 kcal. */
    val floored: Boolean = false,
    val floorKcal: Int = 0,
    val referenceKg: Double = 0.0,
    val referenceIsGoalWeight: Boolean = false,
    val proteinGPerKg: Double = 0.0,
    /** Fat came from its 0.6 g/kg floor rather than 25% of calories. */
    val fatFromBodyWeight: Boolean = false,
    val overrides: TargetOverrides = TargetOverrides(),
    /** TDEE came from the check-in's maintenance estimate rather than BMR × activity (TRD §5.4). */
    val adaptiveTdee: Boolean = false,
    /** BMR × activity, whatever TDEE came from: "the formula says 2,610". */
    val formulaTdeeKcal: Double = tdeeKcal,
)

object NutritionCalculator {
    const val KCAL_PER_KG_FAT = 7700.0
    private const val MAX_DEFICIT_FRACTION = 0.25
    private const val MAX_SURPLUS_FRACTION = 0.15
    private const val FAT_SHARE = 0.25
    private const val FAT_FLOOR_G_PER_KG = 0.6

    fun startingTargets(input: TargetInputs, overrides: TargetOverrides = TargetOverrides()): NutritionTargets {
        val formula = if (input.leanMassKg != null) BmrFormula.KATCH_MCARDLE else BmrFormula.MIFFLIN_ST_JEOR
        val bmr = input.leanMassKg?.let(::katchMcArdle) ?: mifflinStJeor(input)
        val tdee = input.maintenanceKcal ?: (bmr * input.activityFactor)
        val adjustment = energyAdjustment(input.policy, tdee, input.trendWeightKg)
        val floor = floor(input.sex, bmr)
        val computedKcal = KcalRounding.nearest(max(tdee + adjustment, floor))
        // An override is still held at the floor (PRD §4.9).
        val kcal = overrides.kcal?.let { max(it, KcalRounding.up(floor)) } ?: computedKcal

        val referenceKg = input.goalWeightKg ?: input.trendWeightKg
        val protein = overrides.proteinG?.toDouble() ?: (input.policy.proteinGPerKg * referenceKg)
        val fatShare = FAT_SHARE * kcal / 9
        val fatFloor = FAT_FLOOR_G_PER_KG * referenceKg
        val fat = overrides.fatG?.toDouble() ?: max(fatShare, fatFloor)
        val carbs = ((kcal - 4 * protein - 9 * fat) / 4).coerceAtLeast(0.0)

        return NutritionTargets(
            bmrKcal = bmr,
            tdeeKcal = tdee,
            energyAdjustmentKcal = adjustment,
            kcal = kcal,
            proteinG = protein.roundToInt(),
            fatG = fat.roundToInt(),
            carbsG = carbs.roundToInt(),
            bmrFormula = formula,
            activityFactor = input.activityFactor,
            adjustmentCapped = adjustment != 0.0 && adjustment != uncappedAdjustment(input.policy, tdee, input.trendWeightKg),
            floored = overrides.kcal == null && tdee + adjustment < floor,
            floorKcal = KcalRounding.up(floor),
            referenceKg = referenceKg,
            referenceIsGoalWeight = input.goalWeightKg != null,
            proteinGPerKg = input.policy.proteinGPerKg,
            fatFromBodyWeight = overrides.fatG == null && fatFloor > fatShare,
            overrides = overrides,
            adaptiveTdee = input.maintenanceKcal != null,
            formulaTdeeKcal = bmr * input.activityFactor,
        )
    }

    /** Deficits come from a weekly loss rate and are capped at 25% of TDEE; surpluses are capped at 15%. */
    fun energyAdjustment(policy: GoalPolicy, tdee: Double, trendWeightKg: Double): Double = when {
        policy.surplusFraction > 0 -> tdee * min(policy.surplusFraction, MAX_SURPLUS_FRACTION)
        policy.lossRatePctPerWeek > 0 -> -min(lossKcalPerDay(policy, trendWeightKg), MAX_DEFICIT_FRACTION * tdee)
        else -> 0.0
    }

    private fun uncappedAdjustment(policy: GoalPolicy, tdee: Double, trendWeightKg: Double): Double = when {
        policy.surplusFraction > 0 -> tdee * policy.surplusFraction
        policy.lossRatePctPerWeek > 0 -> -lossKcalPerDay(policy, trendWeightKg)
        else -> 0.0
    }

    private fun lossKcalPerDay(policy: GoalPolicy, trendWeightKg: Double) =
        policy.lossRatePctPerWeek / 100 * trendWeightKg * KCAL_PER_KG_FAT / 7

    /** The safety floor (PRD §4.9): never below BMR, or below 1,500 kcal for men and 1,200 for women. */
    fun floor(sex: Sex, bmr: Double): Double = max(bmr, if (sex == Sex.MALE) 1500.0 else 1200.0)

    fun mifflinStJeor(input: TargetInputs): Double {
        val base = 10 * input.trendWeightKg + 6.25 * input.heightCm - 5 * input.ageYears
        return if (input.sex == Sex.MALE) base + 5 else base - 161
    }

    fun katchMcArdle(leanMassKg: Double): Double = 370 + 21.6 * leanMassKg

}
