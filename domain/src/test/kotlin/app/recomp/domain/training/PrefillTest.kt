package app.recomp.domain.training

import app.recomp.domain.model.SetType.DROP
import app.recomp.domain.model.SetType.WARMUP
import app.recomp.domain.model.SetType.WORKING
import org.junit.Assert.assertEquals
import org.junit.Test

class PrefillTest {

    private fun blank(vararg types: app.recomp.domain.model.SetType) = types.map { DraftSet(it, null, null) }

    @Test
    fun `working sets match last session's working sets in order`() {
        val last = listOf(LoggedSet(WORKING, 45_000, 12), LoggedSet(WORKING, 45_000, 11), LoggedSet(WORKING, 45_000, 10))
        val ghosts = Prefill.ghosts(blank(WORKING, WORKING, WORKING), last, defaultReps = 12)
        assertEquals(listOf(SetValues(45_000, 12), SetValues(45_000, 11), SetValues(45_000, 10)), ghosts)
    }

    @Test
    fun `warm-ups never prefill working sets`() {
        val last = listOf(LoggedSet(WARMUP, 20_000, 10), LoggedSet(WORKING, 50_000, 8), LoggedSet(WORKING, 50_000, 8))
        val ghosts = Prefill.ghosts(blank(WORKING, WORKING), last, defaultReps = 12)
        assertEquals(listOf(SetValues(50_000, 8), SetValues(50_000, 8)), ghosts)
    }

    @Test
    fun `a set beyond last session's count copies the set before it`() {
        val last = listOf(LoggedSet(WORKING, 30_000, 10))
        val ghosts = Prefill.ghosts(blank(WORKING, WORKING, DROP), last, defaultReps = 12)
        assertEquals(listOf(SetValues(30_000, 10), SetValues(30_000, 10), SetValues(30_000, 10)), ghosts)
    }

    @Test
    fun `entered values win, field by field`() {
        val last = listOf(LoggedSet(WORKING, 45_000, 12), LoggedSet(WORKING, 45_000, 11))
        val sets = listOf(DraftSet(WORKING, 47_500, null), DraftSet(WORKING, null, 9))
        assertEquals(listOf(SetValues(47_500, 12), SetValues(45_000, 9)), Prefill.ghosts(sets, last, defaultReps = 12))
    }

    @Test
    fun `first time on an exercise later sets follow what you entered for the first`() {
        val sets = listOf(DraftSet(WORKING, 20_000, 10), DraftSet(WORKING, null, null), DraftSet(WORKING, null, null))
        val ghosts = Prefill.ghosts(sets, last = emptyList(), defaultReps = 12)
        assertEquals(listOf(SetValues(20_000, 10), SetValues(20_000, 10), SetValues(20_000, 10)), ghosts)
    }

    @Test
    fun `with no history and nothing entered there is no load and reps default`() {
        assertEquals(listOf(SetValues(null, 12)), Prefill.ghosts(blank(WORKING), emptyList(), defaultReps = 12))
    }

    @Test
    fun `bodyweight sets carry reps only`() {
        val last = listOf(LoggedSet(WORKING, null, 15), LoggedSet(WORKING, null, 13))
        assertEquals(listOf(SetValues(null, 15), SetValues(null, 13)), Prefill.ghosts(blank(WORKING, WORKING), last, 12))
    }

    @Test
    fun `working sets take today's target before last session`() {
        val last = listOf(LoggedSet(WARMUP, 20_000, 10), LoggedSet(WORKING, 45_000, 12), LoggedSet(WORKING, 45_000, 11))
        val target = listOf(SetValues(45_000, 12), SetValues(45_000, 12), SetValues(45_000, 10))
        val ghosts = Prefill.ghosts(blank(WARMUP, WORKING, WORKING, WORKING, WORKING), last, defaultReps = 12, target = target)
        // Warm-ups still come from last session; a set beyond the target copies the one above.
        assertEquals(
            listOf(SetValues(20_000, 10), SetValues(45_000, 12), SetValues(45_000, 12), SetValues(45_000, 10), SetValues(45_000, 10)),
            ghosts,
        )
    }

    @Test
    fun `a working weight you choose carries to later working sets`() {
        val target = List(3) { SetValues(47_500, 8) }
        val sets = listOf(DraftSet(WORKING, 45_000, null), DraftSet(WORKING, null, null), DraftSet(WORKING, null, null))
        assertEquals(List(3) { SetValues(45_000, 8) }, Prefill.ghosts(sets, emptyList(), defaultReps = 12, target = target))
    }

    @Test
    fun `initial sets repeat last session's warm-ups then the target working sets`() {
        val last = listOf(LoggedSet(WARMUP, 20_000, 10), LoggedSet(WORKING, 50_000, 8), LoggedSet(DROP, 40_000, 8))
        assertEquals(listOf(WARMUP, WORKING, WORKING, WORKING), Prefill.initialTypes(last, targetSets = 3))
        assertEquals(listOf(WORKING, WORKING), Prefill.initialTypes(emptyList(), targetSets = 2))
    }
}
