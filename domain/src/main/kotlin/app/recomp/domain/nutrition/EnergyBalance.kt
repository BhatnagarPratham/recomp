package app.recomp.domain.nutrition

import kotlin.math.roundToInt

/**
 * The ±5% maintenance band (PRD §4.8, TRD §5.3). It's Maintain's calorie range on Today, and the test
 * for "in a deficit", so a rounding gap of a few kcal never lengthens the stall window.
 */
object EnergyBalance {
    const val BAND_FRACTION = 0.05

    fun band(kcal: Number): IntRange {
        val k = kcal.toDouble()
        return roundToTen(k * (1 - BAND_FRACTION))..roundToTen(k * (1 + BAND_FRACTION))
    }

    /** True when [targetKcal] sits below the maintenance band around [tdeeKcal] (TRD §5.1). */
    fun inDeficit(targetKcal: Int, tdeeKcal: Number): Boolean = targetKcal < band(tdeeKcal).first

    private fun roundToTen(kcal: Double): Int = (kcal / 10).roundToInt() * 10
}
