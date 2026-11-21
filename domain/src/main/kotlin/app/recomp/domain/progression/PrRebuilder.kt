package app.recomp.domain.progression

import app.recomp.domain.model.LoadType

/**
 * One exercise's completed sets in one session (one `session_exercise` row), in set order.
 * [achievedAt] is when the session ended, which is what a PR found at finish is stamped with.
 */
data class RebuildExposure(val sessionId: Long, val startedAt: Long, val achievedAt: Long, val sets: List<SessionSet>)

data class RebuiltRecord(val sessionId: Long, val achievedAt: Long, val record: PersonalRecord)

/**
 * Recomputes an exercise's PRs after a past workout was edited, moved or deleted (WL-10, TRD §5.1).
 *
 * A PR depends only on the sets of earlier sessions, so an edit to a session can change that session's
 * PRs and every later one's, and nothing before it. [rebuild] therefore returns the PRs of the sessions
 * that start at or after `from`, and the caller replaces exactly those rows. A rebuild from
 * [FROM_SCRATCH] gives the whole history's PRs, which is what the tests compare the incremental one
 * with.
 *
 * It uses [PersonalRecords.detect], the same rules that run when a workout finishes, with the same
 * view of history: every session that started earlier, and never another row of the same exercise in
 * the same session.
 */
object PrRebuilder {
    const val FROM_SCRATCH = Long.MIN_VALUE

    /**
     * @param exposures every completed exposure of one exercise, in any order.
     * @param bodyWeightG the same trend weight for every session, for [LoadType.ASSISTED] only (WL-09).
     */
    fun rebuild(exposures: List<RebuildExposure>, from: Long, loadType: LoadType, bodyWeightG: Int?): List<RebuiltRecord> =
        rebuild(exposures, from, loadType) { bodyWeightG }

    /**
     * As above, judging each session at [bodyWeightAt] that session: for an assisted lift, the trend weight
     * when it finished, which is what finishing it used. So a rebuild gives back the records finishing
     * wrote, however the trend has moved since (WL-09).
     */
    fun rebuild(exposures: List<RebuildExposure>, from: Long, loadType: LoadType, bodyWeightAt: (RebuildExposure) -> Int?): List<RebuiltRecord> {
        val ordered = exposures.filter { it.sets.isNotEmpty() }.sortedWith(compareBy({ it.startedAt }, { it.sessionId }))
        return ordered.filter { it.startedAt >= from }.flatMap { current ->
            val previous = ordered
                .filter { it.startedAt < current.startedAt || (it.startedAt == current.startedAt && it.sessionId < current.sessionId) }
                .map { Exposure(it.startedAt, it.sets.map(SessionSet::set)) }
            val bodyWeightG = if (loadType == LoadType.ASSISTED) bodyWeightAt(current) else null
            PersonalRecords.detect(current.sets, previous, loadType, bodyWeightG).map { RebuiltRecord(current.sessionId, current.achievedAt, it) }
        }
    }
}
