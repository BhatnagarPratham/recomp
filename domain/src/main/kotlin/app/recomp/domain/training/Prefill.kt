package app.recomp.domain.training

import app.recomp.domain.model.SetType

/** A set in today's workout. Null values haven't been entered yet, so the UI shows ghost values. */
data class DraftSet(val type: SetType, val loadG: Int?, val reps: Int?)

data class SetValues(val loadG: Int?, val reps: Int?)

/**
 * Ghost values (WL-02): each set is prefilled from the matching set last session, and ticking accepts
 * them. "Matching" pairs the nth set of a type with the nth set of that type last time, so warm-ups
 * never prefill working sets.
 *
 * TODO(M2): when the progression engine has a frozen recommendation for the exercise (PO-02), working
 * sets take their ghost values from its target instead of from last session.
 */
object Prefill {

    /**
     * Sets created when a workout starts: last session's warm-ups, then the target number of working sets.
     * Drop and failure sets are one-offs, so they aren't recreated.
     */
    fun initialTypes(last: List<LoggedSet>, targetSets: Int): List<SetType> =
        List(last.count { it.type == SetType.WARMUP }) { SetType.WARMUP } + List(targetSets) { SetType.WORKING }

    /**
     * Each field falls back separately: what you entered, else the matching set last session, else the
     * previous set today, else [defaultReps] and no load.
     */
    fun ghosts(sets: List<DraftSet>, last: List<LoggedSet>, defaultReps: Int): List<SetValues> {
        val lastByType = last.groupBy { it.type }
        val seenOfType = mutableMapOf<SetType, Int>()
        val out = ArrayList<SetValues>(sets.size)
        sets.forEach { set ->
            val ordinal = seenOfType.merge(set.type, 1, Int::plus)!! - 1
            val match = lastByType[set.type]?.getOrNull(ordinal)
            val previous = out.lastOrNull()
            out += SetValues(
                loadG = set.loadG ?: match?.loadG ?: previous?.loadG,
                reps = set.reps ?: match?.reps ?: previous?.reps ?: defaultReps,
            )
        }
        return out
    }
}
