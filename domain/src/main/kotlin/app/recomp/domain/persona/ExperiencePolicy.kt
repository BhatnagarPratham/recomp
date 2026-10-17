package app.recomp.domain.persona

import app.recomp.domain.model.RepClass

/** Training age (PRD §4.1). */
enum class Experience { BEGINNER, INTERMEDIATE, ADVANCED }

/**
 * Experience-driven training defaults (PRD §4.3, TRD §5.0). M1 reads the rep ranges; the progression
 * engine (M2) reads the stall window, jump rule and deload cadence.
 */
data class ExperiencePolicy(
    val level: Experience,
    val compoundReps: IntRange,
    val isolationReps: IntRange,
    val highRepReps: IntRange,
    /** Sessions without an estimated-1RM gain before a stall is offered. One more in a deficit. */
    val stallWindow: Int,
    val allowsDoubleJump: Boolean,
    /** Suggested deload cadence in weeks; null means only on a stall. */
    val deloadEveryWeeks: IntRange?,
) {
    fun repsFor(repClass: RepClass): IntRange = when (repClass) {
        RepClass.COMPOUND -> compoundReps
        RepClass.ISOLATION -> isolationReps
        RepClass.HIGH_REP -> highRepReps
    }

    companion object {
        fun defaultFor(level: Experience): ExperiencePolicy = when (level) {
            Experience.BEGINNER -> ExperiencePolicy(level, 8..12, 10..15, 12..20, stallWindow = 3, allowsDoubleJump = true, deloadEveryWeeks = null)
            Experience.INTERMEDIATE -> ExperiencePolicy(level, 6..10, 10..15, 12..20, stallWindow = 4, allowsDoubleJump = false, deloadEveryWeeks = 6..8)
            Experience.ADVANCED -> ExperiencePolicy(level, 5..8, 8..12, 10..15, stallWindow = 5, allowsDoubleJump = false, deloadEveryWeeks = 4..6)
        }
    }
}

/** Training split (PRD §4.1). Each non-custom split seeds its routines from the bundled templates. */
enum class SplitTemplate(val label: String, val defaultStrengthDaysPerWeek: Int) {
    PPL("Push/Pull/Legs", 3),
    FULL_BODY_AB("Full body A/B", 3),
    UPPER_LOWER("Upper/Lower", 4),
    CUSTOM("Custom", 3),
}
