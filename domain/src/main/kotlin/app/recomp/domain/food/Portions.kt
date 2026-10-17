package app.recomp.domain.food

import app.recomp.domain.persona.FoodRegion
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max

/** A unit a food can be logged in, with its weight. Liquids count 1 ml as 1 g. */
data class PortionUnit(val label: String, val grams: Double, val kind: Kind) {
    enum class Kind {
        /** Plain grams. */
        GRAMS,

        /** The food's own unit (INDB's serving, a custom food's serving, or a unit you calibrated for it). */
        OWN,

        /** A household measure for the region, at your calibrated weight (`portion_sizes`). */
        HOUSEHOLD,
    }
}

/** Units and amounts (NU-12, TRD §6.4). */
object Portions {
    const val GRAMS = "g"

    /** Household measures every food offers, with their default weights. Calibrated in Settings → Food. */
    fun householdDefaults(region: FoodRegion): Map<String, Double> = when (region) {
        FoodRegion.INDIAN -> linkedMapOf("katori" to 150.0, "glass" to 250.0, "tbsp" to 15.0)
        FoodRegion.INTERNATIONAL -> linkedMapOf("cup" to 240.0, "tbsp" to 15.0)
    }

    /** The household measures at your calibrated weights. Calibrations for other regions' measures are ignored. */
    fun household(region: FoodRegion, calibrated: Map<String, Double>): Map<String, Double> =
        householdDefaults(region).mapValues { (label, grams) -> calibrated[label]?.takeIf { it > 0 } ?: grams }

    /** The region's serving units for a custom food (NU-12). */
    fun servingUnits(region: FoodRegion): List<String> = when (region) {
        FoodRegion.INDIAN -> listOf("roti", "katori", "glass", "piece", "tbsp", "plate")
        FoodRegion.INTERNATIONAL -> listOf("cup", "slice", "piece", "tbsp")
    }

    /**
     * The units a food offers: its own units first, then the household measures it doesn't define
     * itself, then grams. A food's own unit (or one you calibrated for it) beats the household default.
     */
    fun unitsFor(own: List<Pair<String, Double>>, household: Map<String, Double>): List<PortionUnit> {
        val ownUnits = own.filter { (label, grams) -> label != GRAMS && grams > 0 }.distinctBy { it.first }
            .map { (label, grams) -> PortionUnit(label, grams, PortionUnit.Kind.OWN) }
        val taken = ownUnits.map { it.label }.toSet()
        val home = household.filterKeys { it !in taken }.map { (label, grams) -> PortionUnit(label, grams, PortionUnit.Kind.HOUSEHOLD) }
        return ownUnits + home + PortionUnit(GRAMS, 1.0, PortionUnit.Kind.GRAMS)
    }

    /** ½ a unit, or 10 g. */
    fun step(unit: String): Double = if (unit == GRAMS) 10.0 else 0.5

    /** Up one step, snapping to the step grid so a typed 1.3 goes to 1.5. */
    fun up(quantity: Double, unit: String): Double {
        val step = step(unit)
        return (floor(quantity / step + 1e-9) + 1) * step
    }

    /** Down one step, never below one step. */
    fun down(quantity: Double, unit: String): Double {
        val step = step(unit)
        val snapped = floor(quantity / step + 1e-9) * step
        return max(step, if (snapped < quantity - 1e-9) snapped else snapped - step)
    }

    /** "1.5", "2", "½": amounts as people say them. */
    fun format(quantity: Double): String = when {
        quantity == 0.5 -> "½"
        quantity % 1.0 == 0.0 -> quantity.toLong().toString()
        quantity * 2 % 1.0 == 0.0 -> "${quantity.toLong()}½"
        else -> "%.2f".format(Locale.ROOT, quantity).trimEnd('0').trimEnd('.')
    }
}
