package app.recomp.domain.checkin

import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.GoalPolicy
import app.recomp.domain.nutrition.Sex
import app.recomp.domain.nutrition.TargetInputs
import app.recomp.domain.nutrition.TargetOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The check-in's proposal and *Adjust* (PRD §12.1), from TRD §5.3 vector A's illustrative inputs. */
class CheckinProposalTest {
    private val recomp = TargetInputs(Sex.MALE, 30, 175.0, trendWeightKg = 95.0, goalWeightKg = 78.0, policy = GoalPolicy.defaultFor(Goal.RECOMP))
    private val none = TargetOverrides()

    @Test
    fun `the proposal is the starting targets with the new maintenance estimate at the current pace`() {
        val t = CheckinProposal.of(CheckinFlag.FLAT, recomp, 2500.0, none, currentKcal = 2090)!!
        // 2,500 − 522.5 for 0.5% of 95 kg a week; BMR stays Mifflin-St Jeor's 1,899.
        assertEquals(1980, t.kcal)
        assertEquals(125, t.proteinG)
        assertEquals(55, t.fatG)
        assertEquals(246, t.carbsG)
        assertEquals(2500.0, t.tdeeKcal, 0.0)
        assertEquals(1898.75, t.bmrKcal, 1e-9)
        assertTrue(t.adaptiveTdee)
    }

    @Test
    fun `the floor still applies`() {
        val t = CheckinProposal.of(CheckinFlag.FLAT, recomp, 2400.0, none, currentKcal = 2090)!!
        assertEquals(1900, t.kcal)
        assertTrue(t.floored)
    }

    @Test
    fun `a lean bulk's surplus follows the new estimate`() {
        val bulk = recomp.copy(goalWeightKg = null, trendWeightKg = 70.0, policy = GoalPolicy.defaultFor(Goal.LEAN_BULK))
        assertEquals(3080, CheckinProposal.of(CheckinFlag.GAIN_SLOW, bulk, 2800.0, none, currentKcal = 2810)!!.kcal)
    }

    @Test
    fun `no flag, no proposal`() {
        assertNull(CheckinProposal.of(null, recomp, 2500.0, none, currentKcal = 2090))
    }

    @Test
    fun `a change under 50 kcal isn't proposed`() {
        assertNull(CheckinProposal.of(CheckinFlag.FLAT, recomp, 2500.0, none, currentKcal = 2020))
        assertNull(CheckinProposal.of(CheckinFlag.FLAT, recomp, 2500.0, none, currentKcal = 1940))
        assertEquals(1980, CheckinProposal.of(CheckinFlag.FLAT, recomp, 2500.0, none, currentKcal = 2030)!!.kcal)
        assertEquals(1980, CheckinProposal.of(CheckinFlag.FLAT, recomp, 2500.0, none, currentKcal = 1930)!!.kcal)
    }

    @Test
    fun `your protein and fat overrides carry over, your calorie override doesn't`() {
        val own = TargetOverrides(kcal = 2200, proteinG = 140, fatG = 60)
        val t = CheckinProposal.of(CheckinFlag.FLAT, recomp, 2500.0, own, currentKcal = 2200)!!
        assertEquals(1980, t.kcal)
        assertEquals(140, t.proteinG)
        assertEquals(60, t.fatG)
        assertNull(t.overrides.kcal)
    }

    @Test
    fun `adjust is held to the floor and the caps`() {
        // Floor: BMR 1,899 → 1,900, above the 25% deficit cap's 1,875. Surplus cap: 2,875 → 2,870.
        val range = CheckinProposal.adjustRange(recomp, 2500.0)
        assertEquals(1900..2870, range)
        assertEquals(1930, CheckinProposal.step(1980, up = false, range))
        assertEquals(1900, CheckinProposal.step(1930, up = false, range))
        assertEquals(1900, CheckinProposal.step(1900, up = false, range))
        assertEquals(2030, CheckinProposal.step(1980, up = true, range))
        assertEquals(2870, CheckinProposal.step(2850, up = true, range))
        // A bigger estimate moves the deficit cap above the floor.
        assertEquals(2400..3680, CheckinProposal.adjustRange(recomp, 3200.0))
    }

    @Test
    fun `an adjusted target recomputes fat and carbs from its calories`() {
        val t = CheckinProposal.targets(recomp, 2500.0, none, kcal = 1930)
        assertEquals(1930, t.kcal)
        assertEquals(125, t.proteinG)
        assertEquals(54, t.fatG)
        assertEquals(237, t.carbsG)
        assertEquals(2500.0, t.tdeeKcal, 0.0)
    }
}
