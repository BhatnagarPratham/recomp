package app.recomp.domain.progression

import app.recomp.domain.model.SetType.WARMUP
import app.recomp.domain.model.SetType.WORKING
import app.recomp.domain.training.LoggedSet
import app.recomp.domain.training.SetValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalRecordsTest {
    private val earlier = listOf(
        Exposure(1, listOf(LoggedSet(WORKING, 40_000, 12), LoggedSet(WORKING, 40_000, 11), LoggedSet(WORKING, 40_000, 10))),
        Exposure(2, listOf(LoggedSet(WORKING, 42_500, 8), LoggedSet(WORKING, 42_500, 8))),
    )

    private fun sets(vararg s: LoggedSet) = s.mapIndexed { i, set -> SessionSet(i + 1L, set) }

    @Test
    fun `first session of an exercise has no PRs`() {
        assertEquals(emptyList<PersonalRecord>(), PersonalRecords.detect(sets(LoggedSet(WORKING, 40_000, 12)), emptyList()))
    }

    @Test
    fun `heavier weight, more reps at a weight, better e1RM and more volume are each one PR`() {
        val today = sets(
            LoggedSet(WARMUP, 60_000, 1), // warm-ups never count
            LoggedSet(WORKING, 45_000, 8),
            LoggedSet(WORKING, 42_500, 10),
            LoggedSet(WORKING, 42_500, 9),
            LoggedSet(WORKING, 42_500, 9),
        )
        val prs = PersonalRecords.detect(today, earlier).associateBy { it.type }
        assertEquals(PersonalRecord(PrType.MAX_LOAD, 45_000, 45_000, 2), prs[PrType.MAX_LOAD])
        assertEquals(PersonalRecord(PrType.REPS_AT_LOAD, 10, 42_500, 3), prs[PrType.REPS_AT_LOAD])
        // 45 × 8 ≈ 57.0 kg beats the old best of 40 × 12 = 56.0 kg (and today's 42.5 × 10 ≈ 56.7).
        assertEquals(PersonalRecord(PrType.E1RM, 57_000, 45_000, 2), prs[PrType.E1RM])
        assertEquals((45_000L * 8 + 42_500L * 28), prs[PrType.SESSION_VOLUME]!!.value)
    }

    @Test
    fun `a first time at a weight isn't a rep PR`() {
        val prs = PersonalRecords.detect(sets(LoggedSet(WORKING, 37_500, 15)), earlier)
        assertNull(prs.firstOrNull { it.type == PrType.REPS_AT_LOAD })
    }

    @Test
    fun `matching your best isn't a PR`() {
        val prs = PersonalRecords.detect(sets(LoggedSet(WORKING, 42_500, 8), LoggedSet(WORKING, 40_000, 12)), earlier)
        assertTrue(prs.none { it.type == PrType.MAX_LOAD || it.type == PrType.REPS_AT_LOAD || it.type == PrType.E1RM })
    }

    @Test
    fun `bodyweight sets can still set a rep PR but not load, e1RM or volume`() {
        val before = listOf(Exposure(1, listOf(LoggedSet(WORKING, null, 12))))
        val prs = PersonalRecords.detect(sets(LoggedSet(WORKING, null, 14)), before)
        assertEquals(listOf(PersonalRecord(PrType.REPS_AT_LOAD, 14, null, 1)), prs)
    }

    @Test
    fun `live feedback compares a set that meets its target with last time`() {
        val target = SetValues(45_000, 12)
        assertEquals("+1 rep", Targets.feedback(target, LoggedSet(WORKING, 45_000, 12), LoggedSet(WORKING, 45_000, 11)))
        assertEquals("+2.5 kg", Targets.feedback(SetValues(47_500, 8), LoggedSet(WORKING, 47_500, 8), LoggedSet(WORKING, 45_000, 12)))
        assertEquals("target hit", Targets.feedback(target, LoggedSet(WORKING, 45_000, 12), LoggedSet(WORKING, 45_000, 12)))
        assertNull(Targets.feedback(target, LoggedSet(WORKING, 45_000, 11), LoggedSet(WORKING, 45_000, 10)))
        assertNull(Targets.feedback(null, LoggedSet(WORKING, 45_000, 12), null))
        assertNull(Targets.feedback(target, LoggedSet(WARMUP, 45_000, 12), null))
    }

    @Test
    fun `targets describe themselves and grade a session`() {
        val rec = Recommendation(Action.ADD_REPS, 45_000, listOf(12, 12, 10))
        assertEquals("45 × 12/12/10", Targets.describe(rec))
        assertEquals("BW × 15", Targets.describe(Recommendation(Action.ADD_REPS, null, listOf(15, 15, 15))))
        assertEquals("× 12", Targets.describe(Recommendation(Action.BASELINE, null, listOf(12, 12, 12))))
        val hit = listOf(LoggedSet(WORKING, 45_000, 12), LoggedSet(WORKING, 45_000, 12), LoggedSet(WORKING, 45_000, 11))
        assertTrue(Targets.hit(rec, hit))
        assertTrue(!Targets.hit(rec, hit.take(2)))
        assertTrue(!Targets.hit(rec, listOf(LoggedSet(WORKING, 42_500, 12), LoggedSet(WORKING, 45_000, 12), LoggedSet(WORKING, 45_000, 10))))
        assertEquals("Lat pulldown goes to 50 kg", Targets.nextTime("Lat pulldown", Recommendation(Action.INCREASE, 50_000, listOf(8, 8, 8)), 47_500))
    }
}
