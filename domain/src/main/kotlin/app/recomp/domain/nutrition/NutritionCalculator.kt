package app.recomp.domain.nutrition

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
)

data class NutritionTargets(
    val bmrKcal: Double,
    val tdeeKcal: Double,
    /** Signed: negative for a deficit, positive for a surplus, zero at maintenance. */
    val energyAdjustmentKcal: Double,
    val kcal: Int,
    val proteinG: Int,
    val fatG: Int,
    val carbsG: Int,
)

object NutritionCalculator {
    const val KCAL_PER_KG_FAT = 7700.0
    private const val MAX_DEFICIT_FRACTION = 0.25
    private const val MAX_SURPLUS_FRACTION = 0.15

    fun startingTargets(input: TargetInputs): NutritionTargets {
        val bmr = input.leanMassKg?.let(::katchMcArdle) ?: mifflinStJeor(input)
        val tdee = bmr * input.activityFactor
        val adjustment = energyAdjustment(input.policy, tdee, input.trendWeightKg)
        val floor = max(bmr, if (input.sex == Sex.MALE) 1500.0 else 1200.0)
        val kcal = roundToTen(max(tdee + adjustment, floor))

        val referenceKg = input.goalWeightKg ?: input.trendWeightKg
        val protein = input.policy.proteinGPerKg * referenceKg
        val fat = max(0.25 * kcal / 9, 0.6 * referenceKg)
        val carbs = (kcal - 4 * protein - 9 * fat) / 4

        return NutritionTargets(
            bmrKcal = bmr,
            tdeeKcal = tdee,
            energyAdjustmentKcal = adjustment,
            kcal = kcal,
            proteinG = protein.roundToInt(),
            fatG = fat.roundToInt(),
            carbsG = carbs.roundToInt(),
        )
    }

    /** Deficits come from a weekly loss rate and are capped at 25% of TDEE; surpluses are capped at 15%. */
    fun energyAdjustment(policy: GoalPolicy, tdee: Double, trendWeightKg: Double): Double = when {
        policy.surplusFraction > 0 -> tdee * min(policy.surplusFraction, MAX_SURPLUS_FRACTION)
        policy.lossRatePctPerWeek > 0 -> {
            val wanted = policy.lossRatePctPerWeek / 100 * trendWeightKg * KCAL_PER_KG_FAT / 7
            -min(wanted, MAX_DEFICIT_FRACTION * tdee)
        }
        else -> 0.0
    }

    fun mifflinStJeor(input: TargetInputs): Double {
        val base = 10 * input.trendWeightKg + 6.25 * input.heightCm - 5 * input.ageYears
        return if (input.sex == Sex.MALE) base + 5 else base - 161
    }

    fun katchMcArdle(leanMassKg: Double): Double = 370 + 21.6 * leanMassKg

    private fun roundToTen(kcal: Double): Int = (kcal / 10).roundToInt() * 10
}
