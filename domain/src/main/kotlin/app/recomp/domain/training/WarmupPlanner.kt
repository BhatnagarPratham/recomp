package app.recomp.domain.training

import app.recomp.domain.persona.Units
import app.recomp.domain.units.LoadUnits
import app.recomp.domain.units.UnitMath
import app.recomp.domain.units.UnitStyle
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Warm-up sets for the first heavy lift of the day (WL-11, PRD §12.2): about 40%, 60% and 80% of today's
 * target, rounded down to the exercise's step. Sets under two steps are left out, so light lifts get
 * fewer warm-ups or none. On a barbell nothing is lighter than the empty bar.
 */
object WarmupPlanner {
    /** Percent of today's target and reps: a 50 kg target gives 20 × 8 · 30 × 5 · 40 × 3. */
    val SCHEME = listOf(40 to 8, 60 to 5, 80 to 3)
    const val MIN_STEPS = 2
    /** The empty Olympic bar: you can't load a barbell lighter than this. */
    const val BAR_G = 20_000

    /** The empty bar in [units]: 20 kg, or the 45 lb bar of a lb gym (PR-05). */
    fun barG(units: Units): Int = if (units == Units.IMPERIAL) UnitMath.lbToG(BigDecimal(45)) else BAR_G

    /**
     * Warm-up loads and reps, lightest first; empty when the target is too light for any. [floorG] is the
     * lightest load the equipment allows ([barG] for a barbell): lighter warm-ups are raised to it, and
     * any that end up at today's target or on the same load as another are dropped. In lb ([units]) each load
     * rounds down to the exercise's lb grid, so it's a number a lb screen shows and types exactly (PR-05).
     */
    fun plan(targetLoadG: Int, incrementG: Int, floorG: Int = 0, units: Units = Units.METRIC): List<SetValues> {
        if (targetLoadG <= 0 || incrementG <= 0) return emptyList()
        return SCHEME.map { (percent, reps) ->
            val share = targetLoadG.toLong() * percent / 100
            val load = if (units == Units.IMPERIAL) {
                LoadUnits.toGrid(share.toInt(), incrementG, units, RoundingMode.DOWN)
            } else {
                // Integer maths, so 60% of a 50 kg target is exactly 30 kg and never rounds down a step.
                (share / incrementG * incrementG).toInt()
            }
            SetValues(load, reps)
        }
            .filter { it.loadG!! >= MIN_STEPS * incrementG }
            .map { it.copy(loadG = maxOf(it.loadG!!, floorG)) }
            .filter { it.loadG!! < targetLoadG }
            // A coarse step, or the bar, can put two warm-ups on the same load; one is enough.
            .distinctBy { it.loadG }
    }

    /** "Warm-up: 20 × 8 · 30 × 5 · 40 × 3", in [style]'s unit. */
    fun describe(plan: List<SetValues>, style: UnitStyle): String =
        "Warm-up: " + plan.joinToString(" · ") { "${LoadUnits.format(it.loadG ?: 0, style)} × ${it.reps}" }
}
