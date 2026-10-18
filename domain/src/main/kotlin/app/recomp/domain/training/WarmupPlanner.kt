package app.recomp.domain.training

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

    /**
     * Warm-up loads and reps, lightest first; empty when the target is too light for any. [floorG] is the
     * lightest load the equipment allows ([BAR_G] for a barbell): lighter warm-ups are raised to it, and
     * any that end up at today's target or on the same load as another are dropped.
     */
    fun plan(targetLoadG: Int, incrementG: Int, floorG: Int = 0): List<SetValues> {
        if (targetLoadG <= 0 || incrementG <= 0) return emptyList()
        return SCHEME.map { (percent, reps) ->
            // Integer maths, so 60% of 50 kg is exactly 30 kg and never rounds down a step.
            val load = (targetLoadG.toLong() * percent / 100 / incrementG * incrementG).toInt()
            SetValues(load, reps)
        }
            .filter { it.loadG!! >= MIN_STEPS * incrementG }
            .map { it.copy(loadG = maxOf(it.loadG!!, floorG)) }
            .filter { it.loadG!! < targetLoadG }
            // A coarse step, or the bar, can put two warm-ups on the same load; one is enough.
            .distinctBy { it.loadG }
    }

    /** "Warm-up: 20 × 8 · 30 × 5 · 40 × 3". */
    fun describe(plan: List<SetValues>): String =
        "Warm-up: " + plan.joinToString(" · ") { "${Loads.formatKg(it.loadG ?: 0)} × ${it.reps}" }
}
