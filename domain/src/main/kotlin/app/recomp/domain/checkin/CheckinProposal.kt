package app.recomp.domain.checkin

import app.recomp.domain.nutrition.KcalRounding
import app.recomp.domain.nutrition.NutritionCalculator
import app.recomp.domain.nutrition.NutritionTargets
import app.recomp.domain.nutrition.TargetInputs
import app.recomp.domain.nutrition.TargetOverrides
import kotlin.math.abs
import kotlin.math.max

/**
 * The check-in's proposal (PRD §12.1, TRD §5.4): the §5.3 targets recomputed with the new maintenance
 * estimate at the current pace. Your protein and fat overrides carry over; a calorie override doesn't, because
 * the proposal is the computed number (choosing *Use the new target* resets it).
 */
object CheckinProposal {
    /** A proposal that moves the target by less than this isn't shown. */
    const val MIN_CHANGE_KCAL = 50

    /** Adjust's stepper step. */
    const val ADJUST_STEP_KCAL = 50

    private const val MAX_DEFICIT_FRACTION = 0.25
    private const val MAX_SURPLUS_FRACTION = 0.15

    /** The targets at [maintenanceKcal]; [kcal] fixes the calories, as *Adjust* does (fat and carbs follow it). */
    fun targets(inputs: TargetInputs, maintenanceKcal: Double, overrides: TargetOverrides, kcal: Int? = null): NutritionTargets =
        NutritionCalculator.startingTargets(inputs.copy(maintenanceKcal = maintenanceKcal), overrides.copy(kcal = kcal))

    /**
     * The proposal for a week whose [flag] fired, or null when none fired or it would move [currentKcal] (the
     * target in force) by less than 50 kcal.
     */
    fun of(flag: CheckinFlag?, inputs: TargetInputs, maintenanceKcal: Double, overrides: TargetOverrides, currentKcal: Int): NutritionTargets? {
        if (flag == null) return null
        return targets(inputs, maintenanceKcal, overrides).takeIf { abs(it.kcal - currentKcal) >= MIN_CHANGE_KCAL }
    }

    /**
     * What *Adjust* can reach (PRD §4.9): never below the floor or a 25% deficit, never above a 15% surplus,
     * on the 10 kcal grid.
     */
    fun adjustRange(inputs: TargetInputs, maintenanceKcal: Double): IntRange {
        val bmr = targets(inputs, maintenanceKcal, TargetOverrides()).bmrKcal
        val low = max(KcalRounding.up(NutritionCalculator.floor(inputs.sex, bmr)), KcalRounding.up(maintenanceKcal * (1 - MAX_DEFICIT_FRACTION)))
        val high = KcalRounding.down(maintenanceKcal * (1 + MAX_SURPLUS_FRACTION))
        return low..max(low, high)
    }

    /** One step of *Adjust* from [kcal], held to [range]. */
    fun step(kcal: Int, up: Boolean, range: IntRange): Int =
        (kcal + if (up) ADJUST_STEP_KCAL else -ADJUST_STEP_KCAL).coerceIn(range.first, range.last)
}
