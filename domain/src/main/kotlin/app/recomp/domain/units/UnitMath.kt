package app.recomp.domain.units

import app.recomp.domain.model.Equipment
import app.recomp.domain.persona.Units
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * How numbers are shown (PR-05, PRD §12.16): the units and the decimal mark of the phone's language. Storage is
 * always grams and cm; every conversion happens at the edge, through these functions.
 */
data class UnitStyle(val units: Units = Units.METRIC, val decimal: Char = '.') {
    val imperial: Boolean get() = units == Units.IMPERIAL

    /** "kg" or "lb". */
    val weight: String get() = if (imperial) "lb" else "kg"

    /** "cm" or "in". */
    val length: String get() = if (imperial) "in" else "cm"

    /** [plain] ("42.5") with this style's decimal mark ("42,5"). */
    fun mark(plain: String): String = if (decimal == '.') plain else plain.replace('.', decimal)

    companion object {
        val METRIC = UnitStyle()
        val IMPERIAL = UnitStyle(Units.IMPERIAL)
    }
}

/** Exact conversions. A pound is 453.59237 g and an inch 2.54 cm, by definition. */
object UnitMath {
    val G_PER_LB: BigDecimal = BigDecimal("453.59237")
    const val CM_PER_IN = 2.54
    const val IN_PER_FT = 12

    /** Whole grams for [lb], rounded half up. */
    fun lbToG(lb: BigDecimal): Int = lb.multiply(G_PER_LB).setScale(0, RoundingMode.HALF_UP).intValueExact()

    /** Pounds for [grams], to [scale] decimals. */
    fun gToLb(grams: Int, scale: Int = 2, mode: RoundingMode = RoundingMode.HALF_UP): BigDecimal =
        BigDecimal(grams).divide(G_PER_LB, scale, mode)

    fun kgToLb(kg: Double): Double = kg * 1000 / G_PER_LB.toDouble()

    fun lbToKg(lb: Double): Double = lb * G_PER_LB.toDouble() / 1000

    fun cmToIn(cm: Double): Double = cm / CM_PER_IN

    fun inToCm(inches: Double): Double = inches * CM_PER_IN

    /** A length in the style's unit. */
    fun lengthOf(cm: Double, units: Units): Double = if (units == Units.IMPERIAL) cmToIn(cm) else cm

    /** A length typed in the style's unit, back to cm. */
    fun lengthToCm(value: Double, units: Units): Double = if (units == Units.IMPERIAL) inToCm(value) else value

    /** A body weight in kg shown in the style's unit (lb or kg). */
    fun weightOf(kg: Double, units: Units): Double = if (units == Units.IMPERIAL) kgToLb(kg) else kg

    /** Whole feet and the inches left over, rounded to the nearest inch: 175 cm → 5 ft 9 in. */
    fun feetAndInches(cm: Double): Pair<Int, Int> {
        val total = Math.round(cmToIn(cm)).toInt()
        return total / IN_PER_FT to total % IN_PER_FT
    }

    /** Feet and inches back to cm. Null when inches aren't 0 to under 12, or the total isn't positive. */
    fun heightCm(feet: Int, inches: Double): Double? =
        if (feet < 0 || inches < 0 || inches >= IN_PER_FT || feet * IN_PER_FT + inches <= 0) null else inToCm(feet * IN_PER_FT + inches)
}

/**
 * Lift loads and weight steps in either unit, in grams in and out (PR-05). In lb, every value comes from a lb
 * number, never from adding grams, so equal loads on screen are equal grams: 45 presses of +5 lb give 225 lb =
 * 102,058 g, not 45 × 2,268 g = 102,060 g.
 */
object LoadUnits {
    /** The weight steps offered in lb: 1, 2.5, 5 and 10 lb. */
    val LB_STEPS: List<BigDecimal> = listOf("1", "2.5", "5", "10").map(::BigDecimal)

    /** Typed loads above this (in either unit) are typos. */
    const val MAX_LOAD_G = 1_000_000

    /** The weight steps offered in the walkthrough and the exercise editor. */
    fun incrementChoicesG(units: Units): List<Int> =
        if (units == Units.IMPERIAL) LB_STEPS.map(UnitMath::lbToG) else listOf(1000, 1250, 2000, 2500, 4000, 5000)

    /** A new exercise's step. In lb: 10 lb for machines and cables, 5 lb for everything else (PRD §12.16). */
    fun defaultIncrementG(equipment: Equipment, units: Units): Int = when (units) {
        Units.METRIC -> when (equipment) {
            Equipment.BARBELL, Equipment.PLATE_LOADED, Equipment.DUMBBELL, Equipment.BODYWEIGHT, Equipment.BAND -> 2500
            Equipment.MACHINE, Equipment.CABLE -> 5000
            Equipment.KETTLEBELL -> 4000
        }
        Units.IMPERIAL -> UnitMath.lbToG(BigDecimal(if (equipment == Equipment.MACHINE || equipment == Equipment.CABLE) 10 else 5))
    }

    /** The load in the style's unit, as a number: 42500 → "42.5" (kg) or "93.7" (lb); trailing zeros dropped. */
    fun format(grams: Int, style: UnitStyle): String {
        val value = if (style.imperial) UnitMath.gToLb(grams, 1) else BigDecimal(grams).divide(BigDecimal(1000))
        return style.mark(value.stripTrailingZeros().toPlainString())
    }

    /** A weight step, to 2 decimals: "1.25" (kg), "2.5" or "5.51" (lb). */
    fun formatStep(grams: Int, style: UnitStyle): String {
        val value = if (style.imperial) UnitMath.gToLb(grams, 2) else BigDecimal(grams).divide(BigDecimal(1000))
        return style.mark(value.stripTrailingZeros().toPlainString())
    }

    /** A typed load in the style's unit, in grams. Either decimal mark. Null for blanks, negatives, junk and over 1,000 kg. */
    fun parse(text: String, units: Units): Int? {
        val number = text.trim().replace(',', '.').toBigDecimalOrNull() ?: return null
        if (number.signum() < 0) return null
        val grams = if (units == Units.IMPERIAL) {
            if (number > UnitMath.gToLb(MAX_LOAD_G, 2)) return null
            UnitMath.lbToG(number.setScale(2, RoundingMode.HALF_UP))
        } else {
            number.multiply(BigDecimal(1000)).setScale(0, RoundingMode.HALF_UP).takeIf { it <= BigDecimal(MAX_LOAD_G) }?.intValueExact() ?: return null
        }
        return grams.takeIf { it <= MAX_LOAD_G }
    }

    /** One step up or down, never below zero. In lb the step and the load are taken to 0.01 lb first, so steps never drift. */
    fun step(loadG: Int, incrementG: Int, up: Boolean, units: Units): Int {
        if (units == Units.METRIC) return if (up) loadG + incrementG else (loadG - incrementG).coerceAtLeast(0)
        val load = UnitMath.gToLb(loadG)
        val inc = UnitMath.gToLb(incrementG)
        val next = if (up) load + inc else (load - inc).max(BigDecimal.ZERO)
        return UnitMath.lbToG(next)
    }

    /** [grams] as the nearest 0.01 lb in lb, unchanged in kg: what a lb screen can show and type. */
    fun snap(grams: Int, units: Units): Int = if (units == Units.IMPERIAL) UnitMath.lbToG(UnitMath.gToLb(grams)) else grams

    /**
     * The nearest point of the exercise's lb grid (multiples of its step) to [grams], in the direction of [mode]:
     * UP for an increase, DOWN for a deload and warm-ups. In kg it's unchanged: the engine's grams are already on the kg grid.
     */
    fun toGrid(grams: Int, incrementG: Int, units: Units, mode: RoundingMode): Int {
        if (units == Units.METRIC || incrementG <= 0) return grams
        val inc = UnitMath.gToLb(incrementG)
        if (inc.signum() == 0) return snap(grams, units)
        val steps = UnitMath.gToLb(grams).divide(inc, 0, mode)
        return UnitMath.lbToG(steps.multiply(inc).max(BigDecimal.ZERO))
    }

    /**
     * An increase's target on the lb grid (PRD §12.16): the grid point nearest [targetG] that's still above [topG],
     * what you last lifted. 100 kg (220.46 lb) + 5 lb suggests 225 lb, not 225.46 or 230. In kg it's [targetG].
     */
    fun increase(topG: Int, targetG: Int, incrementG: Int, units: Units): Int {
        if (units == Units.METRIC || incrementG <= 0) return targetG
        val near = toGrid(targetG, incrementG, units, RoundingMode.HALF_UP)
        return if (near > topG) near else step(toGrid(topG, incrementG, units, RoundingMode.DOWN), incrementG, up = true, units).let { up ->
            if (up > topG) up else step(up, incrementG, up = true, units)
        }
    }

    /**
     * A step that's one of the other unit's offered steps, moved to the nearest one in [to]; any other step as it is.
     * For onboarding: the library is seeded before you pick units, so its default kg steps follow a choice of lb
     * (2.5 kg → 5 lb), while a step you typed stays yours.
     */
    fun defaultIn(incrementG: Int, to: Units): Int {
        val other = if (to == Units.IMPERIAL) Units.METRIC else Units.IMPERIAL
        return if (incrementG in incrementChoicesG(other)) nearestIncrementG(incrementG, to) else incrementG
    }

    /**
     * [defaultIn] for an exercise of [equipment]: a step still on the other unit's default for that equipment takes
     * [to]'s default (a 4 kg kettlebell step becomes 5 lb, as PRD §12.16 lists, not the nearer 10 lb).
     */
    fun defaultIn(incrementG: Int, equipment: Equipment, to: Units): Int {
        val other = if (to == Units.IMPERIAL) Units.METRIC else Units.IMPERIAL
        return if (incrementG == defaultIncrementG(equipment, other)) defaultIncrementG(equipment, to) else defaultIn(incrementG, to)
    }

    /**
     * The switch dialog's mapping (PRD §12.16): a step moves to the nearest step offered in the other unit, the
     * larger on a tie. 2.5 kg → 5 lb; 5 lb → 2.5 kg.
     */
    fun nearestIncrementG(incrementG: Int, to: Units): Int =
        incrementChoicesG(to).minWith(compareBy<Int> { kotlin.math.abs(it - incrementG) }.thenByDescending { it })
}
