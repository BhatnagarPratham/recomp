package app.recomp.domain.training

import app.recomp.domain.model.SetType

/**
 * Workout summary numbers (WL-08). Warm-ups don't count toward sets or volume. Volume is load × reps
 * with dumbbell loads per dumbbell, so it compares sessions rather than measuring total tonnage.
 *
 * TODO(M2): PRs, targets hit or missed and "what changes next time" join this from the progression engine.
 */
data class SessionStats(val durationMs: Long, val sets: Int, val reps: Int, val volumeG: Long) {
    companion object {
        fun of(startedAt: Long, endedAt: Long, sets: List<LoggedSet>): SessionStats {
            val counted = sets.filter { it.type != SetType.WARMUP }
            return SessionStats(
                durationMs = (endedAt - startedAt).coerceAtLeast(0),
                sets = counted.size,
                reps = counted.sumOf { it.reps },
                volumeG = counted.sumOf { (it.loadG ?: 0).toLong() * it.reps },
            )
        }
    }
}

/** A routine's plan for one exercise, used to tell whether a workout changed the template (RT-03). */
data class PlanRow(val exerciseId: Long, val targetSets: Int, val repMin: Int, val repMax: Int, val restSec: Int)
