package app.recomp.domain.nudge

import app.recomp.domain.food.MealSlot
import app.recomp.domain.food.MealSlots
import app.recomp.domain.food.Per100g
import app.recomp.domain.food.PortionUnit
import app.recomp.domain.food.Portions
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.GoalPolicy
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NudgesTest {
    private fun policy(goal: Goal) = GoalPolicy.defaultFor(goal)
    private val grams = PortionUnit(Portions.GRAMS, 1.0, PortionUnit.Kind.GRAMS)

    private fun food(id: Long, name: String, kcal: Double, protein: Double, uses: Int, unit: PortionUnit = grams, usual: Double = 100.0) =
        NudgeFood(id, name, Per100g(kcal, protein, 0.0, 0.0), uses, unit, usual)

    // Nudge content for each goal

    @Test
    fun `the nudge list comes from the goal policy`() {
        assertEquals(setOf(NudgeKind.PROTEIN, NudgeKind.STEPS), policy(Goal.FAT_LOSS).nudges)
        assertEquals(setOf(NudgeKind.PROTEIN), policy(Goal.RECOMP).nudges)
        assertEquals(emptySet<NudgeKind>(), policy(Goal.MAINTAIN).nudges)
        assertEquals(setOf(NudgeKind.CALORIES), policy(Goal.LEAN_BULK).nudges)
    }

    @Test
    fun `fat loss nudges protein, and steps once they're tracked`() {
        val day = DayNumbers(kcalTarget = 1_900, kcalEaten = 1_200, proteinTargetG = 140, proteinEatenG = 80)
        assertEquals(listOf(NudgeLine.ProteinLeft(60, 140)), Nudges.evening(policy(Goal.FAT_LOSS), day))
        val withSteps = day.copy(steps = 4_500, stepGoal = 8_000)
        assertEquals(listOf(NudgeLine.ProteinLeft(60, 140), NudgeLine.StepsLeft(3_500, 8_000)), Nudges.evening(policy(Goal.FAT_LOSS), withSteps))
        // Steps alone still make a nudge; nearly there doesn't.
        assertEquals(listOf(NudgeLine.StepsLeft(3_500, 8_000)), Nudges.evening(policy(Goal.FAT_LOSS), withSteps.copy(proteinEatenG = 135)))
        assertTrue(Nudges.evening(policy(Goal.FAT_LOSS), withSteps.copy(proteinEatenG = 135, steps = 7_000)).isEmpty())
    }

    @Test
    fun `recomp nudges protein only`() {
        val day = DayNumbers(kcalTarget = 2_200, kcalEaten = 900, proteinTargetG = 120, proteinEatenG = 60, steps = 1_000, stepGoal = 8_000)
        assertEquals(listOf(NudgeLine.ProteinLeft(60, 120)), Nudges.evening(policy(Goal.RECOMP), day))
    }

    @Test
    fun `maintain has no nudge by default, but a policy can ask for one`() {
        val day = DayNumbers(kcalTarget = 2_400, kcalEaten = 500, proteinTargetG = 120, proteinEatenG = 10)
        assertTrue(Nudges.evening(policy(Goal.MAINTAIN), day).isEmpty())
        assertEquals(listOf(NudgeLine.ProteinLeft(110, 120)), Nudges.evening(policy(Goal.MAINTAIN).copy(nudges = setOf(NudgeKind.PROTEIN)), day))
    }

    @Test
    fun `lean bulk nudges the calories still to eat`() {
        val day = DayNumbers(kcalTarget = 2_800, kcalEaten = 2_200, proteinTargetG = 130, proteinEatenG = 40)
        assertEquals(listOf(NudgeLine.CaloriesShort(600, 2_800)), Nudges.evening(policy(Goal.LEAN_BULK), day))
        // Within a tenth of the target is a normal day.
        assertTrue(Nudges.evening(policy(Goal.LEAN_BULK), day.copy(kcalEaten = 2_600)).isEmpty())
        assertTrue(Nudges.evening(policy(Goal.LEAN_BULK), day.copy(kcalEaten = 3_000)).isEmpty())
    }

    @Test
    fun `the protein nudge fires at a quarter of the target left, not under`() {
        val p = policy(Goal.RECOMP)
        fun eaten(g: Int) = DayNumbers(2_000, 0, proteinTargetG = 120, proteinEatenG = g)
        assertEquals(listOf(NudgeLine.ProteinLeft(30, 120)), Nudges.evening(p, eaten(90))) // exactly 25%
        assertTrue(Nudges.evening(p, eaten(91)).isEmpty())
        assertTrue(Nudges.evening(p, eaten(150)).isEmpty())
        // While the ramp measures there's no target, so nothing to be short of.
        assertTrue(Nudges.evening(p, DayNumbers(2_000, 0, proteinTargetG = null, proteinEatenG = 0)).isEmpty())
    }

    // Ranking and portions

    @Test
    fun `suggestions are your most-logged foods, ranked by protein per 100 kcal`() {
        val foods = listOf(
            food(1, "Roti", kcal = 300.0, protein = 9.0, uses = 60), // 3 g per 100 kcal: not a protein food
            food(2, "Paneer", kcal = 265.0, protein = 18.0, uses = 20), // 6.8
            food(3, "Hung curd", kcal = 105.0, protein = 11.0, uses = 8, usual = 200.0), // 10.5
            food(4, "Whey", kcal = 400.0, protein = 80.0, uses = 5, usual = 30.0), // 20
            food(5, "Toned milk", kcal = 58.0, protein = 3.1, uses = 30), // 5.3
            food(6, "Soya chunks", kcal = 345.0, protein = 52.0, uses = 0), // never logged
        )
        val out = Nudges.suggest(foods, proteinLeftG = 22, kcalLeft = 900)
        assertEquals(listOf("Whey", "Hung curd", "Paneer"), out.map { it.name })
        val curd = out[1]
        assertEquals(200.0, curd.grams, 0.0)
        assertEquals(22, curd.proteinG)
        assertEquals(210, curd.kcal)
    }

    @Test
    fun `only the most-logged foods are in the pool`() {
        val staples = (1..Nudges.POOL).map { food(it.toLong(), "Staple $it", kcal = 200.0, protein = 12.0, uses = 50 - it) }
        val rare = food(99, "Rare isolate", kcal = 380.0, protein = 90.0, uses = 1)
        assertTrue(Nudges.suggest(staples + rare, 40, 800).none { it.name == "Rare isolate" })
        assertEquals("Rare isolate", Nudges.suggest(staples.drop(1) + rare, 40, 800).first().name)
    }

    @Test
    fun `nothing is suggested without protein left or protein foods`() {
        val paneer = food(2, "Paneer", kcal = 265.0, protein = 18.0, uses = 20)
        assertTrue(Nudges.suggest(listOf(paneer), proteinLeftG = 0, kcalLeft = 500).isEmpty())
        assertTrue(Nudges.suggest(listOf(food(1, "Roti", 300.0, 9.0, 60)), proteinLeftG = 50, kcalLeft = 500).isEmpty())
        assertTrue(Nudges.suggest(emptyList(), 50, 500).isEmpty())
    }

    @Test
    fun `a portion covers the protein left, up to twice your usual`() {
        val curd = food(3, "Hung curd", kcal = 105.0, protein = 11.0, uses = 8, usual = 200.0)
        assertEquals(200.0, Nudges.portion(curd, proteinLeftG = 22, kcalLeft = 900).grams, 0.0)
        // 60 g left would need 545 g: capped at twice the usual 200 g.
        val big = Nudges.portion(curd, proteinLeftG = 60, kcalLeft = 900)
        assertEquals(400.0, big.grams, 0.0)
        assertEquals(44, big.proteinG)
        // Never a whole pot.
        val pot = Nudges.portion(curd.copy(usualGrams = 600.0), proteinLeftG = 200, kcalLeft = 5_000)
        assertEquals(Portions.MAX_UNIT_GRAMS, pot.grams, 0.0)
    }

    @Test
    fun `a portion stays within the calories left, but not under half your usual`() {
        val paneer = food(2, "Paneer", kcal = 265.0, protein = 18.0, uses = 20, usual = 100.0)
        // 40 g protein wants 222 g (589 kcal), capped at 200 g; 300 kcal left allows 113 g.
        val fits = Nudges.portion(paneer, proteinLeftG = 40, kcalLeft = 300)
        assertEquals(110.0, fits.grams, 0.0)
        assertTrue(fits.kcal <= 300)
        // 50 kcal left would be 19 g: half the usual instead.
        assertEquals(50.0, Nudges.portion(paneer, proteinLeftG = 40, kcalLeft = 50).grams, 0.0)
        // Over on calories already: sized by protein alone.
        assertEquals(200.0, Nudges.portion(paneer, proteinLeftG = 40, kcalLeft = -200).grams, 0.0)
    }

    @Test
    fun `a food logged in its own unit is offered in it, in half steps`() {
        val egg = food(7, "Boiled egg", kcal = 155.0, protein = 13.0, uses = 12, unit = PortionUnit("piece", 50.0, PortionUnit.Kind.OWN), usual = 100.0)
        val s = Nudges.portion(egg, proteinLeftG = 18, kcalLeft = 700) // 138 g → 3 pieces is 150 g
        assertEquals(3.0, s.quantity, 0.0)
        assertEquals("piece", s.unit.label)
        assertEquals(150.0, s.grams, 0.0)
        assertEquals(20, s.proteinG) // 19.5
        // At least half a unit, and grams in tens.
        assertEquals(0.5, Nudges.portion(egg, proteinLeftG = 1, kcalLeft = 700).quantity, 0.0)
        assertEquals(10.0, Nudges.portion(food(4, "Whey", 400.0, 80.0, 5, usual = 30.0), proteinLeftG = 1, kcalLeft = 700).grams, 0.0)
    }

    // The meal for the time of day (M9's rule, stood in for until it merges)

    @Test
    fun `the meal for a time is lunch by day and the last meal in the evening`() {
        val slots = MealSlots.DEFAULT
        assertEquals("lunch", MealClock.slotAt(slots, LocalTime.of(14, 30))?.key)
        assertEquals("dinner", MealClock.slotAt(slots, LocalTime.of(20, 30))?.key)
        // A suggestion tapped after midnight still goes with the evening's meal.
        assertEquals("dinner", MealClock.slotAt(slots, LocalTime.of(1, 0))?.key)
    }

    @Test
    fun `a renamed or removed Lunch still works`() {
        val renamed = MealSlots.DEFAULT.map { if (it.key == "lunch") it.copy(name = "Tiffin") else it }
        assertEquals("Tiffin", MealClock.watched(renamed, picked = null, time = LocalTime.of(14, 30))?.name)
        val removed = MealSlots.DEFAULT.map { if (it.key == "lunch") it.copy(removed = true) else it }
        assertEquals("breakfast", MealClock.watched(removed, picked = null, time = LocalTime.of(14, 30))?.key)
        // The slot you picked wins while it exists; once removed, the time decides again.
        assertEquals("snacks", MealClock.watched(MealSlots.DEFAULT, picked = "snacks", time = LocalTime.of(14, 30))?.key)
        assertEquals("breakfast", MealClock.watched(removed, picked = "lunch", time = LocalTime.of(14, 30))?.key)
        assertEquals("dinner", MealClock.slotAt(removed + MealSlot("old", "Old", removed = true), LocalTime.of(21, 0))?.key)
        assertNull(MealClock.slotAt(emptyList(), LocalTime.of(21, 0)))
    }
}
