package app.recomp.domain.progression

import app.recomp.domain.model.LoadType
import app.recomp.domain.model.SetType.WARMUP
import app.recomp.domain.model.SetType.WORKING
import app.recomp.domain.persona.Experience
import app.recomp.domain.persona.ExperiencePolicy
import app.recomp.domain.training.Loads
import app.recomp.domain.training.LoggedSet
import app.recomp.domain.training.SessionStats
import app.recomp.domain.training.SetValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Assisted lifts (WL-09, PRD §12.2): the load is the assistance, and progress takes it off. Weights are invented. */
class AssistedLiftTest {
    private val pullUp = ExerciseConfig(repMin = 8, repMax = 12, targetSets = 3, incrementG = 5_000, loadType = LoadType.ASSISTED)
    private val policy = ExperiencePolicy.defaultFor(Experience.BEGINNER)
    private val weighedIn = ProgressionContext(policy, bodyWeightG = 80_000)
    private val noWeighIn = ProgressionContext(policy)

    private var clock = 1_000_000L
    private fun exposure(assist: Int, vararg reps: Int, rir: Int? = null): Exposure {
        clock += 86_400_000
        return Exposure(clock, reps.map { LoggedSet(WORKING, assist, it, rir) })
    }

    private fun history(vararg oldestFirst: Exposure) = oldestFirst.reversed()

    @Test
    fun `the first time is a baseline`() {
        assertEquals(Recommendation(Action.BASELINE, null, listOf(12, 12, 12), assisted = true), ProgressionEngine.recommend(pullUp, emptyList(), weighedIn))
    }

    @Test
    fun `every set at the top takes one step of assistance off`() {
        val rec = ProgressionEngine.recommend(pullUp, history(exposure(25_000, 12, 12, 12)), weighedIn)
        assertEquals(Recommendation(Action.INCREASE, 20_000, listOf(8, 8, 8), assisted = true), rec)
        assertEquals("assist 20 × 8", Targets.describe(rec))
        assertEquals("less assistance", Targets.actionLabel(rec))
        assertEquals("Assisted pull-up goes to assist 20 kg", Targets.nextTime("Assisted pull-up", rec, 25_000))
    }

    @Test
    fun `add reps and repeat keep the assistance`() {
        assertEquals(
            Recommendation(Action.ADD_REPS, 25_000, listOf(12, 12, 10), assisted = true),
            ProgressionEngine.recommend(pullUp, history(exposure(25_000, 12, 11, 10)), weighedIn),
        )
        assertEquals(
            Recommendation(Action.REPEAT, 25_000, listOf(8, 8, 8), assisted = true),
            ProgressionEngine.recommend(pullUp, history(exposure(25_000, 9, 7, 6)), weighedIn),
        )
    }

    @Test
    fun `the sets with the least assistance are the ones that count`() {
        clock += 86_400_000
        val sets = listOf(LoggedSet(WORKING, 20_000, 12), LoggedSet(WORKING, 20_000, 12), LoggedSet(WORKING, 30_000, 7))
        val rec = ProgressionEngine.recommend(pullUp, listOf(Exposure(clock, sets)), weighedIn)
        // Two sets at 20 kg isn't three, and the 30 kg back-off set's 7 reps doesn't count as a miss.
        assertEquals(Action.ADD_REPS, rec.action)
        assertEquals(20_000, rec.loadG)
    }

    @Test
    fun `the big-jump guard measures the step against body weight minus the assistance`() {
        // 60 kg with 30 kg of help lifts 30 kg, so a 5 kg step is 17%: 2 extra reps first.
        val light = ProgressionContext(policy, bodyWeightG = 60_000)
        assertEquals(
            Recommendation(Action.ADD_REPS, 30_000, listOf(13, 12, 12), assisted = true),
            ProgressionEngine.recommend(pullUp, history(exposure(30_000, 12, 12, 12)), light),
        )
        assertEquals(Action.INCREASE, ProgressionEngine.recommend(pullUp, history(exposure(30_000, 14, 14, 14)), light).action)
        // 80 kg with the same help lifts 50 kg: 10%, no guard.
        assertEquals(Action.INCREASE, ProgressionEngine.recommend(pullUp, history(exposure(30_000, 12, 12, 12)), weighedIn).action)
        assertEquals(50_000, ProgressionEngine.stepBaseG(LoadType.ASSISTED, 30_000, 80_000))
        assertNull(ProgressionEngine.stepBaseG(LoadType.ASSISTED, 30_000, null))
    }

    @Test
    fun `before the first weigh-in there's no guard and no double step`() {
        val rec = ProgressionEngine.recommend(pullUp.copy(incrementG = 2_500), history(exposure(30_000, 12, 12, 12, rir = 3)), noWeighIn)
        assertEquals(Recommendation(Action.INCREASE, 27_500, listOf(8, 8, 8), assisted = true), rec)
    }

    @Test
    fun `a double step takes two steps of assistance off when one step is at most 5 percent`() {
        // 80 − 25 = 55 kg lifted; 2.5 kg is 4.5% of it.
        val small = pullUp.copy(incrementG = 2_500)
        val rec = ProgressionEngine.recommend(small, history(exposure(25_000, 12, 12, 12, rir = 3)), weighedIn)
        assertEquals(Recommendation(Action.INCREASE, 20_000, listOf(8, 8, 8), steps = 2, assisted = true), rec)
        assertEquals("less assistance (2 steps: every set had 3+ reps left)", Targets.actionLabel(rec))
        // 5 kg is 9% of 55 kg: one step.
        assertEquals(1, ProgressionEngine.recommend(pullUp, history(exposure(25_000, 12, 12, 12, rir = 3)), weighedIn).steps)
        // Only 2.5 kg of help left: the second step has nowhere to go.
        assertEquals(
            Recommendation(Action.INCREASE, 0, listOf(8, 8, 8), assisted = true),
            ProgressionEngine.recommend(small, history(exposure(2_500, 12, 12, 12, rir = 3)), weighedIn),
        )
        // 7.5 kg left with a 5 kg step: the double step takes it all, more than one step, so it says two.
        val bigStep = ProgressionContext(policy, bodyWeightG = 120_000)
        assertEquals(
            Recommendation(Action.INCREASE, 0, listOf(8, 8, 8), steps = 2, assisted = true),
            ProgressionEngine.recommend(pullUp, history(exposure(7_500, 12, 12, 12, rir = 3)), bigStep),
        )
    }

    @Test
    fun `with no assistance left the next step is added load or a harder variation`() {
        val rec = ProgressionEngine.recommend(pullUp, history(exposure(0, 12, 12, 12)), weighedIn)
        assertEquals(Action.INCREASE, rec.action)
        assertTrue(rec.harderVariation)
        assertEquals("add load or a harder variation", Targets.actionLabel(rec))
        assertEquals("BW × 12", Targets.describe(rec))
        assertEquals("BW × 12/12", Loads.describe(listOf(LoggedSet(WORKING, 0, 12), LoggedSet(WORKING, 0, 12)), LoadType.ASSISTED))
        // The step down to none reads as bodyweight, too.
        val last = ProgressionEngine.recommend(pullUp, history(exposure(5_000, 12, 12, 12)), weighedIn)
        assertEquals("Assisted pull-up goes to bodyweight", Targets.nextTime("Assisted pull-up", last, 5_000))
    }

    @Test
    fun `a deload adds assistance worth 10 percent of the lift, rounded up to the step`() {
        // 80 − 25 = 55 kg lifted; 10% is 5.5 kg, so 30.5 kg of help rounds up to 35.
        val rec = ProgressionEngine.recommend(pullUp, history(exposure(25_000, 8, 7, 6), exposure(25_000, 8, 7, 7)), weighedIn)
        assertEquals(Recommendation(Action.DELOAD, 35_000, listOf(8, 8, 8), assisted = true), rec)
        // Before the first weigh-in it's one step more help.
        assertEquals(30_000, ProgressionEngine.deloadLoad(LoadType.ASSISTED, 25_000, 5_000, null))
        // Weighted lifts are unchanged.
        assertEquals(45_000, ProgressionEngine.deloadLoad(LoadType.WEIGHTED, 50_000, 2_500, 80_000))
    }

    @Test
    fun `less assistance scores higher for the stall check, with or without a weigh-in`() {
        val more = exposure(30_000, 10, 10, 10)
        val less = exposure(25_000, 8, 8, 8)
        assertTrue(ProgressionEngine.score(less, LoadType.ASSISTED, null) > ProgressionEngine.score(more, LoadType.ASSISTED, null))
        // Same help, more reps: still a gain.
        assertTrue(ProgressionEngine.score(exposure(25_000, 9), LoadType.ASSISTED, null) > ProgressionEngine.score(less, LoadType.ASSISTED, null))
        // With a weigh-in it's the effective e1RM: 50 × (1 + 10/30) ≈ 66.7 kg.
        assertEquals(66_667.0, ProgressionEngine.score(more, LoadType.ASSISTED, 80_000), 1.0)
        // Four sessions stuck at 30 kg of help with no more reps is a stall.
        val stuck = history(exposure(30_000, 10, 10, 10), exposure(30_000, 10, 10, 10), exposure(30_000, 10, 10, 10), exposure(30_000, 10, 10, 10))
        assertTrue(ProgressionEngine.recommend(pullUp, stuck, noWeighIn).stalled)
        assertFalse(ProgressionEngine.recommend(pullUp, history(*stuck.reversed().toTypedArray(), exposure(25_000, 8, 8, 8)), noWeighIn).stalled)
    }

    // PRs (PO-04)

    private val earlier = listOf(Exposure(1, listOf(LoggedSet(WORKING, 30_000, 10), LoggedSet(WORKING, 30_000, 10))))

    private fun sets(vararg s: LoggedSet) = s.mapIndexed { i, set -> SessionSet(i + 1L, set) }

    @Test
    fun `with a weigh-in, PRs count body weight minus the assistance and keep the assistance`() {
        val today = sets(LoggedSet(WARMUP, 50_000, 5), LoggedSet(WORKING, 25_000, 10), LoggedSet(WORKING, 25_000, 10), LoggedSet(WORKING, 25_000, 10))
        val prs = PersonalRecords.detect(today, earlier, LoadType.ASSISTED, bodyWeightG = 80_000).associateBy { it.type }
        // 55 kg lifted beats 50 kg.
        assertEquals(PersonalRecord(PrType.MAX_LOAD, 55_000, 25_000, 2), prs[PrType.MAX_LOAD])
        // 55 × (1 + 10/30) ≈ 73.3 kg.
        assertEquals(PersonalRecord(PrType.E1RM, 73_333, 25_000, 2), prs[PrType.E1RM])
        assertEquals(55_000L * 30, prs[PrType.SESSION_VOLUME]!!.value)
        assertNull(prs[PrType.REPS_AT_LOAD])
    }

    @Test
    fun `before the first weigh-in less assistance is the record and there's no e1RM or volume`() {
        val today = sets(LoggedSet(WORKING, 25_000, 8), LoggedSet(WORKING, 30_000, 12))
        val prs = PersonalRecords.detect(today, earlier, LoadType.ASSISTED, bodyWeightG = null)
        assertEquals(
            listOf(PersonalRecord(PrType.MAX_LOAD, 25_000, 25_000, 1), PersonalRecord(PrType.REPS_AT_LOAD, 12, 30_000, 2)),
            prs,
        )
    }

    @Test
    fun `more assistance is never a record`() {
        assertEquals(emptyList<PersonalRecord>(), PersonalRecords.detect(sets(LoggedSet(WORKING, 35_000, 12)), earlier, LoadType.ASSISTED, null))
        val prs = PersonalRecords.detect(sets(LoggedSet(WORKING, 35_000, 12)), earlier, LoadType.ASSISTED, 80_000)
        assertTrue(prs.none { it.type == PrType.MAX_LOAD || it.type == PrType.E1RM })
    }

    @Test
    fun `targets, feedback and history lines read as assistance`() {
        val rec = Recommendation(Action.ADD_REPS, 25_000, listOf(12, 12, 10), assisted = true)
        assertTrue(Targets.hit(rec, listOf(LoggedSet(WORKING, 20_000, 12), LoggedSet(WORKING, 25_000, 12), LoggedSet(WORKING, 25_000, 10))))
        assertFalse(Targets.hit(rec, listOf(LoggedSet(WORKING, 30_000, 12), LoggedSet(WORKING, 25_000, 12), LoggedSet(WORKING, 25_000, 10))))
        assertEquals("5 kg less assist", Targets.feedback(SetValues(20_000, 8), LoggedSet(WORKING, 20_000, 8), LoggedSet(WORKING, 25_000, 12), assisted = true))
        assertNull(Targets.feedback(SetValues(20_000, 8), LoggedSet(WORKING, 25_000, 8), null, assisted = true))
        assertEquals("assist 25 × 12/11", Loads.describe(listOf(LoggedSet(WORKING, 25_000, 12), LoggedSet(WORKING, 25_000, 11)), LoadType.ASSISTED))
    }

    @Test
    fun `volume counts what an assisted set lifted, and nothing before the first weigh-in`() {
        val sets = listOf(LoggedSet(WORKING, 20_000, 8), LoggedSet(WORKING, 20_000, 8))
        val lifted = Loads.lifted(sets, LoadType.ASSISTED, bodyWeightG = 80_000)
        assertEquals(listOf(60_000, 60_000), lifted.map { it.loadG })
        assertEquals(60_000L * 16, SessionStats.of(0, 1, lifted).volumeG)
        assertEquals(0L, SessionStats.of(0, 1, Loads.lifted(sets, LoadType.ASSISTED, null)).volumeG)
        // Other load types are untouched.
        assertEquals(sets, Loads.lifted(sets, LoadType.WEIGHTED, 80_000))
    }
}
