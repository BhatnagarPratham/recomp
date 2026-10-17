package app.recomp.domain.progression

import app.recomp.domain.model.LoadType
import app.recomp.domain.model.SetType
import app.recomp.domain.persona.Experience
import app.recomp.domain.persona.ExperiencePolicy
import app.recomp.domain.training.LoggedSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every branch of TRD §5.1. Loads are illustrative. */
class ProgressionEngineTest {
    private val beginner = ProgressionContext(ExperiencePolicy.defaultFor(Experience.BEGINNER))
    private val pulldown = ExerciseConfig(repMin = 8, repMax = 12, targetSets = 3, incrementG = 2_500, loadType = LoadType.WEIGHTED)

    private var clock = 1_000_000L
    /** Newest first, like the hot query. Each call is a later session. */
    private fun exposure(load: Int?, vararg reps: Int, warmup: Boolean = false): Exposure {
        clock += 86_400_000
        val sets = (if (warmup) listOf(LoggedSet(SetType.WARMUP, 20_000, 10)) else emptyList()) +
            reps.map { LoggedSet(SetType.WORKING, load, it) }
        return Exposure(clock, sets)
    }

    private fun history(vararg oldestFirst: Exposure) = oldestFirst.reversed()

    @Test
    fun `first time is a baseline at the top of the range`() {
        val rec = ProgressionEngine.recommend(pulldown, emptyList(), beginner)
        assertEquals(Recommendation(Action.BASELINE, null, listOf(12, 12, 12)), rec)
    }

    @Test
    fun `add reps puts one more rep on the first set below the top`() {
        val rec = ProgressionEngine.recommend(pulldown, history(exposure(45_000, 12, 11, 10)), beginner)
        assertEquals(Recommendation(Action.ADD_REPS, 45_000, listOf(12, 12, 10)), rec)
    }

    @Test
    fun `every set at the top increases the weight and resets reps`() {
        val rec = ProgressionEngine.recommend(pulldown, history(exposure(45_000, 12, 12, 12)), beginner)
        assertEquals(Recommendation(Action.INCREASE, 47_500, listOf(8, 8, 8)), rec)
    }

    @Test
    fun `too few sets at the top is not an increase`() {
        val rec = ProgressionEngine.recommend(pulldown, history(exposure(45_000, 12, 12)), beginner)
        // The missing third set is padded from the last one.
        assertEquals(Recommendation(Action.ADD_REPS, 45_000, listOf(12, 12, 12)), rec)
    }

    @Test
    fun `big-jump guard asks for 2 extra reps when the step is over 10 percent`() {
        val raise = ExerciseConfig(10, 15, 3, incrementG = 2_500, loadType = LoadType.WEIGHTED)
        // 2.5 on 7.5 is a 33% jump: 15s aren't enough, 17s are.
        val atTop = ProgressionEngine.recommend(raise, history(exposure(7_500, 15, 15, 15)), beginner)
        assertEquals(Recommendation(Action.ADD_REPS, 7_500, listOf(16, 15, 15)), atTop)
        val past = ProgressionEngine.recommend(raise, history(exposure(7_500, 17, 17, 17)), beginner)
        assertEquals(Recommendation(Action.INCREASE, 10_000, listOf(10, 10, 10)), past)
        // 2.5 on 40 is 6%: no guard.
        assertEquals(15, ProgressionEngine.effectiveRepMax(raise, 40_000))
    }

    @Test
    fun `a missed bottom repeats the weight with every set at the bottom`() {
        val rec = ProgressionEngine.recommend(pulldown, history(exposure(50_000, 9, 7, 6)), beginner)
        assertEquals(Recommendation(Action.REPEAT, 50_000, listOf(8, 8, 8)), rec)
    }

    @Test
    fun `missing the bottom twice at the same weight deloads 10 percent rounded down to the step`() {
        val rec = ProgressionEngine.recommend(pulldown, history(exposure(50_000, 8, 7, 6), exposure(50_000, 8, 7, 7)), beginner)
        assertEquals(Recommendation(Action.DELOAD, 45_000, listOf(8, 8, 8)), rec)
        assertEquals(35_000, ProgressionEngine.deloadLoad(40_000, 2_500)) // 36 rounds down to 35
        assertEquals(30_000, ProgressionEngine.deloadLoad(35_000, 5_000)) // 31.5 rounds down to 30
    }

    @Test
    fun `missing the bottom twice at different weights only repeats`() {
        val rec = ProgressionEngine.recommend(pulldown, history(exposure(47_500, 8, 7, 7), exposure(50_000, 8, 7, 6)), beginner)
        assertEquals(Action.REPEAT, rec.action)
    }

    @Test
    fun `only working sets at the top load count, so back-off and warm-up sets are ignored`() {
        val sets = listOf(
            LoggedSet(SetType.WARMUP, 20_000, 5),
            LoggedSet(SetType.WORKING, 45_000, 12), LoggedSet(SetType.WORKING, 45_000, 12), LoggedSet(SetType.WORKING, 45_000, 12),
            LoggedSet(SetType.WORKING, 35_000, 6), // back-off
            LoggedSet(SetType.DROP, 30_000, 4),
        )
        val rec = ProgressionEngine.recommend(pulldown, listOf(Exposure(1, sets)), beginner)
        assertEquals(Action.INCREASE, rec.action)
    }

    @Test
    fun `stall after the beginner window of 3 sessions without an e1RM gain`() {
        val best = exposure(50_000, 10, 10, 9)
        val flat = history(best, exposure(50_000, 10, 9, 9), exposure(50_000, 10, 9, 9), exposure(50_000, 10, 10, 9))
        assertTrue(ProgressionEngine.recommend(pulldown, flat, beginner).stalled)
        // Three sessions isn't enough history to compare against.
        assertFalse(ProgressionEngine.recommend(pulldown, flat.take(3), beginner).stalled)
    }

    @Test
    fun `any gain in the window means no stall`() {
        val h = history(exposure(50_000, 10, 10, 9), exposure(50_000, 10, 9, 9), exposure(50_000, 11, 10, 9), exposure(50_000, 10, 10, 9))
        assertFalse(ProgressionEngine.recommend(pulldown, h, beginner).stalled)
    }

    @Test
    fun `a deficit adds one session to the stall window`() {
        val flat = history(exposure(50_000, 10, 10, 9), exposure(50_000, 10, 9, 9), exposure(50_000, 10, 9, 9), exposure(50_000, 10, 10, 9))
        assertFalse(ProgressionEngine.recommend(pulldown, flat, beginner.copy(inDeficit = true)).stalled)
        val longer = listOf(exposure(50_000, 10, 9, 9)) + flat
        assertTrue(ProgressionEngine.recommend(pulldown, longer, beginner.copy(inDeficit = true)).stalled)
    }

    @Test
    fun `the experience policy sets the stall window`() {
        val flat = history(exposure(50_000, 10, 10, 9), exposure(50_000, 10, 9, 9), exposure(50_000, 10, 9, 9), exposure(50_000, 10, 10, 9))
        val intermediate = ProgressionContext(ExperiencePolicy.defaultFor(Experience.INTERMEDIATE))
        assertFalse(ProgressionEngine.recommend(pulldown, flat, intermediate).stalled)
    }

    @Test
    fun `high-rep work falls back to every set for the stall metric`() {
        val raise = ExerciseConfig(12, 20, 3, 2_500, LoadType.WEIGHTED)
        val flat = history(exposure(7_500, 16, 16, 15), exposure(7_500, 15, 15, 15), exposure(7_500, 16, 15, 15), exposure(7_500, 15, 15, 15))
        assertTrue(ProgressionEngine.recommend(raise, flat, beginner).stalled)
    }

    @Test
    fun `a session straddling 12 reps scores on its best set, so rising reps never look like a stall`() {
        val straddle = Exposure(1, listOf(LoggedSet(SetType.WORKING, 10_000, 15), LoggedSet(SetType.WORKING, 10_000, 14), LoggedSet(SetType.WORKING, 10_000, 12)))
        val above = Exposure(2, listOf(LoggedSet(SetType.WORKING, 10_000, 14), LoggedSet(SetType.WORKING, 10_000, 13), LoggedSet(SetType.WORKING, 10_000, 13)))
        assertTrue(ProgressionEngine.score(straddle, LoadType.WEIGHTED) > ProgressionEngine.score(above, LoadType.WEIGHTED))
        // Reps climbing above 12 at the same weight keep scoring higher.
        val raise = ExerciseConfig(12, 20, 3, 2_500, LoadType.WEIGHTED)
        val climbing = history(exposure(10_000, 13, 13, 13), exposure(10_000, 14, 13, 13), exposure(10_000, 15, 14, 13), exposure(10_000, 16, 15, 14))
        assertFalse(ProgressionEngine.recommend(raise, climbing, beginner).stalled)
    }

    @Test
    fun `after a deload only sessions since the reset count`() {
        val before = listOf(exposure(50_000, 10, 10, 9), exposure(50_000, 10, 9, 9), exposure(50_000, 10, 9, 9), exposure(50_000, 10, 10, 9))
        val resetAt = clock
        val after = listOf(exposure(45_000, 8, 8, 8), exposure(45_000, 9, 8, 8))
        val h = (before + after).reversed()
        val ctx = beginner.copy(stallReset = StallReset(resetAt, keepsBaseline = false))
        assertFalse(ProgressionEngine.recommend(pulldown, h, ctx).stalled)
        assertTrue(ProgressionEngine.recommend(pulldown, h, beginner).stalled)
    }

    @Test
    fun `after a hold the card returns only when a whole window fails to beat the old best`() {
        val before = listOf(exposure(50_000, 11, 10, 9), exposure(50_000, 10, 9, 9), exposure(50_000, 10, 9, 9), exposure(50_000, 10, 10, 9))
        val resetAt = clock
        val ctx = beginner.copy(stallReset = StallReset(resetAt, keepsBaseline = true))
        val two = (before + listOf(exposure(50_000, 10, 10, 9), exposure(50_000, 10, 10, 10))).reversed()
        assertFalse(ProgressionEngine.recommend(pulldown, two, ctx).stalled)
        val three = listOf(exposure(50_000, 10, 10, 10)) + two
        assertTrue(ProgressionEngine.recommend(pulldown, three, ctx).stalled)
        val beat = listOf(exposure(50_000, 12, 10, 10)) + two
        assertFalse(ProgressionEngine.recommend(pulldown, beat, ctx).stalled)
    }

    @Test
    fun `baselines never show a stall`() {
        assertFalse(ProgressionEngine.recommend(pulldown, emptyList(), beginner).stalled)
    }

    @Test
    fun `bodyweight climbs reps then asks for load or a harder variation`() {
        val raise = ExerciseConfig(10, 15, 3, 2_500, LoadType.BODYWEIGHT)
        assertEquals(Recommendation(Action.ADD_REPS, null, listOf(14, 13, 12)), ProgressionEngine.recommend(raise, history(exposure(null, 13, 13, 12)), beginner))
        val top = ProgressionEngine.recommend(raise, history(exposure(null, 15, 15, 15)), beginner)
        assertEquals(Action.INCREASE, top.action)
        assertTrue(top.harderVariation)
        assertEquals(null, top.loadG)
        assertEquals(Recommendation(Action.REPEAT, null, listOf(10, 10, 10)), ProgressionEngine.recommend(raise, history(exposure(null, 12, 9, 8)), beginner))
    }

    @Test
    fun `weighted bodyweight needs 2 extra reps before the first plate, then progresses the plate`() {
        val backExtension = ExerciseConfig(10, 15, 3, 2_500, LoadType.BODYWEIGHT_PLUS)
        assertEquals(Action.ADD_REPS, ProgressionEngine.recommend(backExtension, history(exposure(0, 15, 15, 15)), beginner).action)
        assertEquals(Recommendation(Action.INCREASE, 2_500, listOf(10, 10, 10)), ProgressionEngine.recommend(backExtension, history(exposure(0, 17, 17, 17)), beginner))
        assertEquals(Recommendation(Action.ADD_REPS, 5_000, listOf(13, 12, 12)), ProgressionEngine.recommend(backExtension, history(exposure(5_000, 12, 12, 12)), beginner))
    }

    @Test
    fun `sessions with no working sets are ignored`() {
        val h = history(exposure(45_000, 12, 11, 10), Exposure(clock + 1, listOf(LoggedSet(SetType.WARMUP, 20_000, 10))))
        assertEquals(Action.ADD_REPS, ProgressionEngine.recommend(pulldown, h, beginner).action)
    }
}
