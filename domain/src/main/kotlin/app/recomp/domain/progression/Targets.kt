package app.recomp.domain.progression

import app.recomp.domain.model.SetType
import app.recomp.domain.training.Loads
import app.recomp.domain.training.LoggedSet
import app.recomp.domain.training.SetValues

/** Turning a [Recommendation] into what the screens show and grade against. */
object Targets {
    /** Per-set ghost values for the working sets (PRD §7.4: working sets prefill from today's target). */
    fun workingSets(rec: Recommendation): List<SetValues> = rec.reps.map { SetValues(rec.loadG, it) }

    /** "45 × 12/12/10", "assist 20 × 8", "BW × 15/15/15" (no assistance left, too), or "× 12" when the weight is yours to pick. */
    fun describe(rec: Recommendation): String {
        val load = when {
            rec.assisted && rec.loadG == 0 -> "BW"
            rec.loadG != null -> if (rec.assisted) "assist ${Loads.formatKg(rec.loadG)}" else Loads.formatKg(rec.loadG)
            rec.action == Action.BASELINE -> ""
            else -> "BW"
        }
        val reps = if (rec.reps.distinct().size == 1) "${rec.reps.first()}" else rec.reps.joinToString("/")
        return listOf(load, "× $reps").filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** Whether the session's working sets met the target (WL-08 "targets hit or missed"). Less assistance meets it too. */
    fun hit(rec: Recommendation, done: List<LoggedSet>): Boolean {
        if (rec.action == Action.BASELINE) return done.any { it.type == SetType.WORKING }
        val working = done.filter { it.type == SetType.WORKING }
        if (working.size < rec.reps.size) return false
        return rec.reps.indices.all { i ->
            val set = working[i]
            val load = set.loadG ?: 0
            set.reps >= rec.reps[i] && (rec.loadG == null || if (rec.assisted) load <= rec.loadG else load >= rec.loadG)
        }
    }

    /**
     * Live feedback for a ticked working set (PO-03): only when it meets or beats its target, phrased
     * against last time's matching set. A miss says nothing. For an assisted lift, less assistance is the gain.
     */
    fun feedback(target: SetValues?, done: LoggedSet, lastMatch: LoggedSet?, assisted: Boolean = false): String? {
        if (target?.reps == null || done.type != SetType.WORKING) return null
        val load = done.loadG ?: 0
        val short = if (assisted) target.loadG != null && load > target.loadG else load < (target.loadG ?: 0)
        if (short || done.reps < target.reps) return null
        if (lastMatch == null) return "target hit"
        val loadGain = if (assisted) (lastMatch.loadG ?: 0) - load else load - (lastMatch.loadG ?: 0)
        val repDiff = done.reps - lastMatch.reps
        return when {
            loadGain > 0 -> if (assisted) "${Loads.formatKg(loadGain)} kg less assist" else "+${Loads.formatKg(loadGain)} kg"
            loadGain == 0 && repDiff > 0 -> "+$repDiff rep" + if (repDiff > 1) "s" else ""
            else -> "target hit"
        }
    }

    /** "Lat pulldown goes to 50 kg" and friends, for the summary's "what changes next time". */
    fun nextTime(name: String, rec: Recommendation, lastTopLoadG: Int?): String = when (rec.action) {
        Action.INCREASE -> if (rec.harderVariation) "$name: add load or a harder variation" else "$name goes to ${weight(rec.loadG!!, rec.assisted)}"
        Action.DELOAD -> "$name deloads to ${weight(rec.loadG!!, rec.assisted)}"
        Action.ADD_REPS -> "$name: add a rep at ${rec.loadG?.let { weight(it, rec.assisted) } ?: "bodyweight"}"
        Action.REPEAT -> "$name: repeat ${lastTopLoadG?.takeIf { it > 0 || rec.assisted }?.let { weight(it, rec.assisted) } ?: "the same"}, every set in the range"
        Action.BASELINE -> "$name: pick a starting weight"
    }

    fun actionLabel(rec: Recommendation): String = when (rec.action) {
        Action.BASELINE -> "first time"
        Action.INCREASE -> when {
            rec.harderVariation -> "add load or a harder variation"
            rec.steps > 1 -> "${increaseLabel(rec)} (${rec.steps} steps: every set had 3+ reps left)"
            else -> increaseLabel(rec)
        }
        Action.ADD_REPS -> "add reps"
        Action.REPEAT -> "repeat"
        Action.DELOAD -> if (rec.assisted) "deload: more assistance" else "deload"
    }

    private fun increaseLabel(rec: Recommendation) = if (rec.assisted) "less assistance" else "weight up"

    private fun weight(loadG: Int, assisted: Boolean) = when {
        assisted && loadG == 0 -> "bodyweight"
        assisted -> "assist ${Loads.formatKg(loadG)} kg"
        else -> "${Loads.formatKg(loadG)} kg"
    }
}
