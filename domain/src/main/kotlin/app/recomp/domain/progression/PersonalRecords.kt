package app.recomp.domain.progression

import app.recomp.domain.model.LoadType
import app.recomp.domain.model.SetType
import app.recomp.domain.training.LoggedSet

enum class PrType { MAX_LOAD, REPS_AT_LOAD, E1RM, SESSION_VOLUME }

/** A completed set in the session being finished; [id] ties a PR back to its set. */
data class SessionSet(val id: Long, val set: LoggedSet)

/**
 * [value] is grams (MAX_LOAD, E1RM), reps (REPS_AT_LOAD) or gram-reps (SESSION_VOLUME). For an assisted
 * lift [loadG] is the assistance, and the grams are body weight minus it (or the assistance itself
 * before the first weigh-in).
 */
data class PersonalRecord(val type: PrType, val value: Long, val loadG: Int?, val setId: Long?)

/**
 * PR detection (PO-04), run once when a workout finishes. Warm-ups never count. At most one PR of each
 * type per exercise per session, and none on an exercise's first session, where everything would count.
 */
object PersonalRecords {
    /** [bodyWeightG] is the trend weight, used only for [LoadType.ASSISTED]. */
    fun detect(
        session: List<SessionSet>,
        previous: List<Exposure>,
        loadType: LoadType = LoadType.WEIGHTED,
        bodyWeightG: Int? = null,
    ): List<PersonalRecord> {
        if (loadType == LoadType.ASSISTED) return assisted(session, previous, bodyWeightG)
        val now = session.filter { it.set.type != SetType.WARMUP }
        val before = previous.flatMap { it.sets }.filter { it.type != SetType.WARMUP }
        if (now.isEmpty() || before.isEmpty()) return emptyList()
        return listOfNotNull(maxLoad(now, before), repsAtLoad(now, before), e1rm(now, before), volume(now, previous))
    }

    /**
     * WL-09: with a trend weight, each set counts as body weight minus the assistance, so the usual
     * records apply and keep the assistance in [PersonalRecord.loadG]. The same trend weight is used for
     * every session, so less assistance or more reps always beat the old record. Before the first
     * weigh-in, less assistance is the heaviest-weight record and there's no e1RM or volume record.
     */
    private fun assisted(session: List<SessionSet>, previous: List<Exposure>, bodyWeightG: Int?): List<PersonalRecord> {
        if (bodyWeightG != null) {
            fun LoggedSet.effective() = copy(loadG = (bodyWeightG - (loadG ?: 0)).coerceAtLeast(0))
            val assistance = session.associate { it.id to it.set.loadG }
            return detect(session.map { SessionSet(it.id, it.set.effective()) }, previous.map { e -> Exposure(e.startedAt, e.sets.map { it.effective() }) })
                .map { pr -> pr.setId?.let { id -> pr.copy(loadG = assistance[id]) } ?: pr }
        }
        val now = session.filter { it.set.type != SetType.WARMUP }
        val before = previous.flatMap { it.sets }.filter { it.type != SetType.WARMUP }
        if (now.isEmpty() || before.isEmpty()) return emptyList()
        val least = now.minWith(compareBy<SessionSet> { it.set.loadG ?: 0 }.thenByDescending { it.set.reps })
        val assist = least.set.loadG ?: 0
        val leastAssist = if (assist < before.minOf { it.loadG ?: 0 }) PersonalRecord(PrType.MAX_LOAD, assist.toLong(), assist, least.id) else null
        return listOfNotNull(leastAssist, repsAtLoad(now, before, leastLoadWins = true))
    }

    private fun maxLoad(now: List<SessionSet>, before: List<LoggedSet>): PersonalRecord? {
        val best = now.filter { (it.set.loadG ?: 0) > 0 }.maxWithOrNull(compareBy({ it.set.loadG }, { it.set.reps })) ?: return null
        val load = best.set.loadG!!
        return if (load > (before.maxOfOrNull { it.loadG ?: 0 } ?: 0)) PersonalRecord(PrType.MAX_LOAD, load.toLong(), load, best.id) else null
    }

    /**
     * More reps than ever at a weight you've lifted before; the heaviest such weight wins, or the least
     * assistance with [leastLoadWins].
     */
    private fun repsAtLoad(now: List<SessionSet>, before: List<LoggedSet>, leastLoadWins: Boolean = false): PersonalRecord? {
        val bestBefore = before.groupBy { it.loadG ?: 0 }.mapValues { (_, sets) -> sets.maxOf { it.reps } }
        val beaten = now.filter { s -> bestBefore[s.set.loadG ?: 0]?.let { s.set.reps > it } == true }
        val best = (if (leastLoadWins) beaten.minWithOrNull(compareBy<SessionSet> { it.set.loadG ?: 0 }.thenByDescending { it.set.reps })
        else beaten.maxWithOrNull(compareBy({ it.set.loadG ?: 0 }, { it.set.reps }))) ?: return null
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
