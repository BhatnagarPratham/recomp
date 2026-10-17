package app.recomp.domain.progression

import app.recomp.domain.model.SetType
import app.recomp.domain.training.LoggedSet

enum class PrType { MAX_LOAD, REPS_AT_LOAD, E1RM, SESSION_VOLUME }

/** A completed set in the session being finished; [id] ties a PR back to its set. */
data class SessionSet(val id: Long, val set: LoggedSet)

/** [value] is grams (MAX_LOAD, E1RM), reps (REPS_AT_LOAD) or gram-reps (SESSION_VOLUME). */
data class PersonalRecord(val type: PrType, val value: Long, val loadG: Int?, val setId: Long?)

/**
 * PR detection (PO-04), run once when a workout finishes. Warm-ups never count. At most one PR of each
 * type per exercise per session, and none on an exercise's first session, where everything would count.
 */
object PersonalRecords {
    fun detect(session: List<SessionSet>, previous: List<Exposure>): List<PersonalRecord> {
        val now = session.filter { it.set.type != SetType.WARMUP }
        val before = previous.flatMap { it.sets }.filter { it.type != SetType.WARMUP }
        if (now.isEmpty() || before.isEmpty()) return emptyList()
        return listOfNotNull(maxLoad(now, before), repsAtLoad(now, before), e1rm(now, before), volume(now, previous))
    }

    private fun maxLoad(now: List<SessionSet>, before: List<LoggedSet>): PersonalRecord? {
        val best = now.filter { (it.set.loadG ?: 0) > 0 }.maxWithOrNull(compareBy({ it.set.loadG }, { it.set.reps })) ?: return null
        val load = best.set.loadG!!
        return if (load > (before.maxOfOrNull { it.loadG ?: 0 } ?: 0)) PersonalRecord(PrType.MAX_LOAD, load.toLong(), load, best.id) else null
    }

    /** More reps than ever at a weight you've lifted before; the heaviest such weight wins. */
    private fun repsAtLoad(now: List<SessionSet>, before: List<LoggedSet>): PersonalRecord? {
        val bestBefore = before.groupBy { it.loadG ?: 0 }.mapValues { (_, sets) -> sets.maxOf { it.reps } }
        val best = now.filter { s -> bestBefore[s.set.loadG ?: 0]?.let { s.set.reps > it } == true }
            .maxWithOrNull(compareBy({ it.set.loadG ?: 0 }, { it.set.reps })) ?: return null
        return PersonalRecord(PrType.REPS_AT_LOAD, best.set.reps.toLong(), best.set.loadG, best.id)
    }

    private fun e1rm(now: List<SessionSet>, before: List<LoggedSet>): PersonalRecord? {
        fun LoggedSet.e1rm() = if ((loadG ?: 0) > 0 && reps in 1..ProgressionEngine.E1RM_MAX_REPS) ProgressionEngine.e1rmG(loadG!!, reps) else null
        val best = now.mapNotNull { s -> s.set.e1rm()?.let { s to it } }.maxByOrNull { it.second } ?: return null
        val previous = before.mapNotNull { it.e1rm() }.maxOrNull() ?: return null
        return if (best.second > previous) PersonalRecord(PrType.E1RM, best.second.toLong(), best.first.set.loadG, best.first.id) else null
    }

    private fun volume(now: List<SessionSet>, previous: List<Exposure>): PersonalRecord? {
        fun List<LoggedSet>.volume() = filter { it.type != SetType.WARMUP }.sumOf { (it.loadG ?: 0).toLong() * it.reps }
        val today = now.map { it.set }.volume()
        if (today <= 0) return null
        return if (today > (previous.maxOfOrNull { it.sets.volume() } ?: 0)) PersonalRecord(PrType.SESSION_VOLUME, today, null, null) else null
    }
}
