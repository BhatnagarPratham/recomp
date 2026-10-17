package app.recomp.domain.nutrition

/** The persona's goal (PRD §4.2). It decides energy balance, protein and progression pacing. */
enum class Goal { FAT_LOSS, RECOMP, MAINTAIN, LEAN_BULK }

/** Pace presets (PRD §4.2). Custom values under Advanced are a hand-built [GoalPolicy] instead. */
enum class Pace {
    GENTLE, STANDARD, AGGRESSIVE;

    fun <T> pick(gentle: T, standard: T, aggressive: T): T = when (this) {
        GENTLE -> gentle
        STANDARD -> standard
        AGGRESSIVE -> aggressive
    }
}

/**
 * Energy and protein rules for a goal. [defaultFor] gives the preset values; every field is
 * user-editable, so callers pass a policy rather than a bare goal.
 */
data class GoalPolicy(
    val goal: Goal,
    /** Target loss per week as a percentage of body weight. Zero unless the goal loses weight. */
    val lossRatePctPerWeek: Double = 0.0,
    /** Surplus as a fraction of maintenance calories. Zero unless the goal gains weight. */
    val surplusFraction: Double = 0.0,
    /** Grams of protein per kg of reference weight (goal weight if set, else current weight). */
    val proteinGPerKg: Double,
) {
    init {
        require(lossRatePctPerWeek >= 0 && surplusFraction >= 0) { "Rates are magnitudes; the goal sets the direction" }
        require(lossRatePctPerWeek == 0.0 || surplusFraction == 0.0) { "A policy can't both lose and gain" }
    }

    companion object {
        /** Pace table from TRD §5.3. Maintain has no pace, so [pace] is ignored for it. */
        fun defaultFor(goal: Goal, pace: Pace = Pace.STANDARD): GoalPolicy = when (goal) {
            Goal.FAT_LOSS -> GoalPolicy(goal, lossRatePctPerWeek = pace.pick(0.5, 0.75, 1.0), proteinGPerKg = 2.0)
            Goal.RECOMP -> GoalPolicy(goal, lossRatePctPerWeek = pace.pick(0.25, 0.5, 0.75), proteinGPerKg = 1.6)
            Goal.MAINTAIN -> GoalPolicy(goal, proteinGPerKg = 1.6)
            Goal.LEAN_BULK -> GoalPolicy(goal, surplusFraction = pace.pick(0.05, 0.10, 0.15), proteinGPerKg = 1.6)
        }
    }
}
