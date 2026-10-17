package app.recomp.domain.progression

import app.recomp.domain.model.SetType
import app.recomp.domain.training.Loads
import app.recomp.domain.training.LoggedSet
import app.recomp.domain.training.SetValues

/** Turning a [Recommendation] into what the screens show and grade against. */
object Targets {
    /** Per-set ghost values for the working sets (PRD §7.4: working sets prefill from today's target). */
    fun workingSets(rec: Recommendation): List<SetValues> = rec.reps.map { SetValues(rec.loadG, it) }

    /** "45 × 12/12/10", "BW × 15/15/15", or "× 12" when the weight is yours to pick. */
    fun describe(rec: Recommendation): String {
        val load = when {
            rec.loadG != null -> Loads.formatKg(rec.loadG)
            rec.action == Action.BASELINE -> ""
            else -> "BW"
        }
        val reps = if (rec.reps.distinct().size == 1) "${rec.reps.first()}" else rec.reps.joinToString("/")
        return listOf(load, "× $reps").filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** Whether the session's working sets met the target (WL-08 "targets hit or missed"). */
    fun hit(rec: Recommendation, done: List<LoggedSet>): Boolean {
        if (rec.action == Action.BASELINE) return done.any { it.type == SetType.WORKING }
        val working = done.filter { it.type == SetType.WORKING }
        if (working.size < rec.reps.size) return false
        return rec.reps.indices.all { i ->
            val set = working[i]
            set.reps >= rec.reps[i] && (rec.loadG == null || (set.loadG ?: 0) >= rec.loadG)
        }
    }

    /**
     * Live feedback for a ticked working set (PO-03): only when it meets or beats its target, phrased
     * against last time's matching set. A miss says nothing.
     */
    fun feedback(target: SetValues?, done: LoggedSet, lastMatch: LoggedSet?): String? {
        if (target?.reps == null || done.type != SetType.WORKING) return null
        val load = done.loadG ?: 0
        if (load < (target.loadG ?: 0) || done.reps < target.reps) return null
        if (lastMatch == null) return "target hit"
        val loadDiff = load - (lastMatch.loadG ?: 0)
        val repDiff = done.reps - lastMatch.reps
        return when {
            loadDiff > 0 -> "+${Loads.formatKg(loadDiff)} kg"
            loadDiff == 0 && repDiff > 0 -> "+$repDiff rep" + if (repDiff > 1) "s" else ""
            else -> "target hit"
        }
    }

    /** "Lat pulldown goes to 50 kg" and friends, for the summary's "what changes next time". */
    fun nextTime(name: String, rec: Recommendation, lastTopLoadG: Int?): String = when (rec.action) {
        Action.INCREASE -> if (rec.harderVariation) "$name: add load or a harder variation" else "$name goes to ${Loads.formatKg(rec.loadG!!)} kg"
        Action.DELOAD -> "$name deloads to ${Loads.formatKg(rec.loadG!!)} kg"
        Action.ADD_REPS -> "$name: add a rep at ${rec.loadG?.let { "${Loads.formatKg(it)} kg" } ?: "bodyweight"}"
        Action.REPEAT -> "$name: repeat ${lastTopLoadG?.takeIf { it > 0 }?.let { "${Loads.formatKg(it)} kg" } ?: "the same"}, every set in the range"
        Action.BASELINE -> "$name: pick a starting weight"
    }

    fun actionLabel(rec: Recommendation): String = when (rec.action) {
        Action.BASELINE -> "first time"
        Action.INCREASE -> if (rec.harderVariation) "add load or a harder variation" else "weight up"
        Action.ADD_REPS -> "add reps"
        Action.REPEAT -> "repeat"
        Action.DELOAD -> "deload"
    }
}
