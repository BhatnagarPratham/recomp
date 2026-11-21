package app.recomp.domain.progression

import app.recomp.domain.model.LoadType
import app.recomp.domain.model.SetType.WARMUP
import app.recomp.domain.model.SetType.WORKING
import app.recomp.domain.training.LoggedSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrRebuilderTest {
    private var nextSetId = 1L

    private fun exposure(session: Long, vararg sets: LoggedSet, startedAt: Long = session * 1_000) =
        RebuildExposure(session, startedAt, startedAt + 500, sets.map { SessionSet(nextSetId++, it) })

    private fun w(loadG: Int?, reps: Int) = LoggedSet(WORKING, loadG, reps)

    private fun history() = listOf(
        exposure(1, w(40_000, 12), w(40_000, 11), w(40_000, 10)),
        exposure(2, w(42_500, 8), w(42_500, 8), w(42_500, 8)),
        exposure(3, w(42_500, 9), w(42_500, 8), w(42_500, 8)),
        exposure(4, w(45_000, 8), w(45_000, 7), w(45_000, 6)),
    )

    private fun scratch(exposures: List<RebuildExposure>, loadType: LoadType = LoadType.WEIGHTED, bodyWeightG: Int? = null) =
        PrRebuilder.rebuild(exposures, PrRebuilder.FROM_SCRATCH, loadType, bodyWeightG)

    private fun List<RebuiltRecord>.types(session: Long) = filter { it.sessionId == session }.map { it.record.type }.toSet()

    @Test
    fun `a rebuild from scratch gives each session what finishing it would have`() {
        val all = history()
        val rebuilt = scratch(all)
        all.forEachIndexed { i, current ->
            val expected = PersonalRecords.detect(current.sets, all.take(i).map { Exposure(it.startedAt, it.sets.map(SessionSet::set)) })
            assertEquals(expected, rebuilt.filter { it.sessionId == current.sessionId }.map { it.record })
        }
        assertTrue(rebuilt.none { it.sessionId == 1L }) // never on the first session
        assertEquals(setOf(PrType.MAX_LOAD), rebuilt.types(2))
        assertEquals(setOf(PrType.REPS_AT_LOAD), rebuilt.types(3))
        assertEquals(setOf(PrType.MAX_LOAD, PrType.E1RM), rebuilt.types(4))
    }

    @Test
    fun `records are stamped with their own session's end`() {
        assertTrue(scratch(history()).all { it.achievedAt == it.sessionId * 1_000 + 500 })
    }

    @Test
    fun `raising an old set removes a later PR, and lowering it brings one back`() {
        val before = history()
        assertTrue(PrType.MAX_LOAD in scratch(before).types(4))
        // Session 2's top set becomes 47.5 kg: session 4's 45 kg is no longer the heaviest ever.
        val raised = before.map { if (it.sessionId == 2L) it.copy(sets = it.sets.mapIndexed { i, s -> if (i == 0) s.copy(set = w(47_500, 8)) else s }) else it }
        val after = PrRebuilder.rebuild(raised, from = 2_000, LoadType.WEIGHTED, null)
        assertFalse(PrType.MAX_LOAD in after.types(4))
        assertTrue(PrType.MAX_LOAD in after.types(2))
        // And back again.
        assertTrue(PrType.MAX_LOAD in PrRebuilder.rebuild(before, from = 2_000, LoadType.WEIGHTED, null).types(4))
    }

    @Test
    fun `adding a set to an old session can take a later volume PR away`() {
        val before = listOf(
            exposure(1, w(40_000, 10), w(40_000, 10), w(40_000, 10)),
            exposure(2, w(40_000, 11), w(40_000, 11), w(40_000, 11)),
            exposure(3, w(40_000, 12), w(40_000, 12), w(40_000, 12)),
        )
        assertTrue(PrType.SESSION_VOLUME in scratch(before).types(3))
        // A fourth set in session 2 makes it the bigger session.
        val added = before.map { if (it.sessionId == 2L) it.copy(sets = it.sets + SessionSet(99, w(40_000, 11))) else it }
        assertFalse(PrType.SESSION_VOLUME in scratch(added).types(3))
        assertTrue(PrType.SESSION_VOLUME in scratch(added).types(2))
    }

    @Test
    fun `deleting the first session leaves the second without PRs`() {
        val rest = history().drop(1)
        val rebuilt = scratch(rest)
        assertTrue(rebuilt.none { it.sessionId == 2L })
        assertTrue(rebuilt.any { it.sessionId == 3L })
    }

    @Test
    fun `sessions before the edit are left out of an incremental rebuild`() {
        val rebuilt = PrRebuilder.rebuild(history(), from = 3_000, LoadType.WEIGHTED, null)
        assertEquals(setOf(3L, 4L), rebuilt.map { it.sessionId }.toSet())
    }

    @Test
    fun `an incremental rebuild plus the untouched earlier records equals one from scratch`() {
        val before = history()
        val stored = scratch(before)
        val edits: List<Pair<Long, List<RebuildExposure>>> = listOf(
            2_000L to before.map { if (it.sessionId == 2L) it.copy(sets = it.sets.drop(1)) else it }, // a set deleted
            3_000L to before.map { if (it.sessionId == 3L) it.copy(sets = it.sets.map { s -> s.copy(set = s.set.copy(reps = s.set.reps + 3)) }) else it },
            1_000L to before.filter { it.sessionId != 1L }, // the first session deleted
            2_000L to before.filter { it.sessionId != 2L },
            // Session 4 moved to before session 2: everything from its new time on is rebuilt.
            1_500L to before.map { if (it.sessionId == 4L) it.copy(startedAt = 1_500, achievedAt = 2_000) else it },
            // Session 1 moved to the end: everything from its old time on is rebuilt.
            1_000L to before.map { if (it.sessionId == 1L) it.copy(startedAt = 9_000, achievedAt = 9_500) else it },
        )
        edits.forEach { (from, edited) ->
            val kept = stored.filter { r -> edited.any { it.sessionId == r.sessionId && it.startedAt < from } }
            val incremental = kept + PrRebuilder.rebuild(edited, from, LoadType.WEIGHTED, null)
            assertEquals(scratch(edited).toSet(), incremental.toSet())
        }
    }

    @Test
    fun `two rows of one exercise in a session don't count as each other's history`() {
        val twice = listOf(
            exposure(1, w(40_000, 10)),
            RebuildExposure(2, 2_000, 2_500, listOf(SessionSet(50, w(45_000, 5)))),
            RebuildExposure(2, 2_000, 2_500, listOf(SessionSet(51, w(45_000, 5)))),
        )
        // Both rows beat session 1 on load; neither is judged against the other.
        assertEquals(2, scratch(twice).count { it.record.type == PrType.MAX_LOAD })
    }

    @Test
    fun `sessions that start together are ordered by id`() {
        val tied = listOf(exposure(1, w(40_000, 10), startedAt = 1_000), exposure(2, w(45_000, 10), startedAt = 1_000))
        assertEquals(setOf(2L), scratch(tied).map { it.sessionId }.toSet())
    }

    @Test
    fun `warm-up-only and empty exposures are never history`() {
        val rows = listOf(
            RebuildExposure(1, 1_000, 1_500, emptyList()),
            exposure(2, LoggedSet(WARMUP, 20_000, 5)),
            exposure(3, w(40_000, 10)),
            exposure(4, w(42_500, 10)),
        )
        val rebuilt = scratch(rows)
        assertTrue(rebuilt.none { it.sessionId == 3L }) // its first counted session
        assertTrue(PrType.MAX_LOAD in rebuilt.types(4))
    }

    @Test
    fun `bodyweight and assisted lifts rebuild with their own rules`() {
        val bodyweight = listOf(exposure(1, w(null, 10)), exposure(2, w(null, 12)))
        assertEquals(setOf(PrType.REPS_AT_LOAD), scratch(bodyweight, LoadType.BODYWEIGHT).types(2))

        val assisted = listOf(exposure(1, w(30_000, 8)), exposure(2, w(25_000, 8)))
        val withWeight = scratch(assisted, LoadType.ASSISTED, bodyWeightG = 80_000)
        val least = withWeight.single { it.record.type == PrType.MAX_LOAD }.record
        assertEquals(55_000L, least.value) // 80 kg minus 25 kg of assistance
        assertEquals(25_000, least.loadG) // the record keeps the assistance
        assertEquals(25_000L, scratch(assisted, LoadType.ASSISTED, bodyWeightG = null).single { it.record.type == PrType.MAX_LOAD }.record.value)
    }

    @Test
    fun `each assisted session is judged at its own body weight, so a later weight never rewrites it`() {
        val assisted = listOf(exposure(1, w(30_000, 12)), exposure(2, w(20_000, 6)))
        // Finished at an 80 kg trend, session 2 set the least-assistance and e1RM records.
        val atFinish = PrRebuilder.rebuild(assisted, PrRebuilder.FROM_SCRATCH, LoadType.ASSISTED) { 80_000 }
        assertEquals(setOf(PrType.MAX_LOAD, PrType.E1RM), atFinish.types(2))
        // At 120 kg it wouldn't have had the e1RM record; judged at its own weight it keeps it.
        assertEquals(setOf(PrType.MAX_LOAD), PrRebuilder.rebuild(assisted, PrRebuilder.FROM_SCRATCH, LoadType.ASSISTED, bodyWeightG = 120_000).types(2))
        val ownWeight = mapOf(1L to 80_000, 2L to 80_000)
        assertEquals(atFinish, PrRebuilder.rebuild(assisted, PrRebuilder.FROM_SCRATCH, LoadType.ASSISTED) { ownWeight[it.sessionId] })

        // A session finished before the first weigh-in keeps the assistance as its record.
        val beforeWeighIn = PrRebuilder.rebuild(assisted, PrRebuilder.FROM_SCRATCH, LoadType.ASSISTED) { null }
        assertEquals(20_000L, beforeWeighIn.single { it.record.type == PrType.MAX_LOAD }.record.value)

        // Other load types never read a body weight.
        var asked = false
        PrRebuilder.rebuild(history(), PrRebuilder.FROM_SCRATCH, LoadType.WEIGHTED) { asked = true; 80_000 }
        assertFalse(asked)
    }
}
