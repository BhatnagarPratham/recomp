package app.recomp.domain.training

import app.recomp.domain.model.SetType

/** A set in today's workout. Null values haven't been entered yet, so the UI shows ghost values. */
data class DraftSet(val type: SetType, val loadG: Int?, val reps: Int?)

data class SetValues(val loadG: Int?, val reps: Int?)

/**
 * Ghost values (WL-02): ticking a set accepts them. Working sets take today's target (PO-02) when there
 * is one. Otherwise each set takes the matching set from last session: the nth set of a type pairs with
 * the nth set of that type last time, so warm-ups never prefill working sets.
 */
object Prefill {

    /**
     * Sets created when a workout starts: last session's warm-ups, then the target number of working sets.
     * Drop and failure sets are one-offs, so they aren't recreated.
     */
    fun initialTypes(last: List<LoggedSet>, targetSets: Int): List<SetType> =
        List(last.count { it.type == SetType.WARMUP }) { SetType.WARMUP } + List(targetSets) { SetType.WORKING }

    /**
     * Each field falls back separately: what you entered, else the target for that working set, else the
     * matching set last session, else the previous set today, else [defaultReps] and no load. Once you
     * enter a working weight other than the target's, later working sets follow your weight.
     */
    fun ghosts(sets: List<DraftSet>, last: List<LoggedSet>, defaultReps: Int, target: List<SetValues> = emptyList()): List<SetValues> {
        val lastByType = last.groupBy { it.type }
        val seenOfType = mutableMapOf<SetType, Int>()
        val out = ArrayList<SetValues>(sets.size)
        var chosenLoad: Int? = null
        sets.forEach { set ->
            val ordinal = seenOfType.merge(set.type, 1, Int::plus)!! - 1
            val match = lastByType[set.type]?.getOrNull(ordinal)
            val planned = if (set.type == SetType.WORKING) target.getOrNull(ordinal) else null
            val previous = out.lastOrNull()
            if (planned?.loadG != null && set.loadG != null && set.loadG != planned.loadG) chosenLoad = set.loadG
            out += SetValues(
                loadG = set.loadG ?: (if (planned != null) chosenLoad else null) ?: planned?.loadG ?: match?.loadG ?: previous?.loadG,
                reps = set.reps ?: planned?.reps ?: match?.reps ?: previous?.reps ?: defaultReps,
            )
        }
        return out
    }
}
