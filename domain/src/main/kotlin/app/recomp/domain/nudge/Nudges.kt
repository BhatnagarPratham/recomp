package app.recomp.domain.nudge

import app.recomp.domain.food.Per100g
import app.recomp.domain.food.PortionUnit
import app.recomp.domain.food.Portions
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.GoalPolicy
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What the evening nudge can be about (GL-08). A goal policy lists the ones it uses. */
enum class NudgeKind {
    PROTEIN, STEPS, CALORIES;

    companion object {
        /** PRD §12.8: fat loss nudges protein and steps, recomp protein, maintain nothing, lean bulk calories still to eat. */
        fun defaultsFor(goal: Goal): Set<NudgeKind> = when (goal) {
            Goal.FAT_LOSS -> setOf(PROTEIN, STEPS)
            Goal.RECOMP -> setOf(PROTEIN)
            Goal.MAINTAIN -> emptySet()
            Goal.LEAN_BULK -> setOf(CALORIES)
        }
    }
}

/** Today so far, as the nudge sees it. [proteinTargetG] is null while the ramp measures; [steps] until M13 tracks them. */
data class DayNumbers(
    val kcalTarget: Int,
    val kcalEaten: Int,
    val proteinTargetG: Int?,
    val proteinEatenG: Int,
    val steps: Int? = null,
    val stepGoal: Int? = null,
)

sealed interface NudgeLine {
    data class ProteinLeft(val grams: Int, val targetG: Int) : NudgeLine
    data class StepsLeft(val steps: Int, val goal: Int) : NudgeLine
    data class CaloriesShort(val kcal: Int, val target: Int) : NudgeLine
}

/** One of your foods the protein nudge can offer. */
data class NudgeFood(
    val id: Long,
    val name: String,
    val per100g: Per100g,
    val useCount: Int,
    /** The unit to offer it in: the one you logged it with last, else its own serving, else grams. */
    val unit: PortionUnit,
    /** What you usually have: your last portion's weight, else one serving, else 100 g. */
    val usualGrams: Double,
)

/** "Hung curd · 200 g · 22 g protein · 210 kcal" (NU-23). */
data class Suggestion(
    val foodId: Long,
    val name: String,
    val quantity: Double,
    val unit: PortionUnit,
    val grams: Double,
    val proteinG: Int,
    val kcal: Int,
)

/** The evening nudge (GL-08, NU-23; PRD §12.8). */
object Nudges {
    /** NU-23: the protein nudge fires when at least a quarter of the day's target is left. */
    const val PROTEIN_THRESHOLD = 0.25

    /** Steps follow the same rule once M13 tracks them. */
    const val STEPS_THRESHOLD = 0.25

    /** Lean bulk: more than a tenth of the day's calories still to eat, i.e. clearly outside a normal day's wobble. */
    const val CALORIES_THRESHOLD = 0.10

    /** AR-01's example goal, until M13 adds the setting. */
    const val DEFAULT_STEP_GOAL = 8_000

    /** Suggestions come from this many of your most-logged foods. */
    const val POOL = 15

    /** A food worth suggesting for protein: at least 5 g per 100 kcal (a fifth of its calories). */
    const val MIN_PROTEIN_PER_100_KCAL = 5.0

    const val MAX_SUGGESTIONS = 3

    /** What tonight's nudge says, in the policy's order. Empty means there's nothing to nudge about. */
    fun evening(policy: GoalPolicy, day: DayNumbers): List<NudgeLine> = buildList {
        if (NudgeKind.PROTEIN in policy.nudges) proteinLeft(day)?.let(::add)
        if (NudgeKind.STEPS in policy.nudges) stepsLeft(day)?.let(::add)
        if (NudgeKind.CALORIES in policy.nudges) caloriesShort(day)?.let(::add)
    }

    private fun proteinLeft(day: DayNumbers): NudgeLine.ProteinLeft? {
        val target = day.proteinTargetG?.takeIf { it > 0 } ?: return null
        val left = target - day.proteinEatenG
        return if (left >= target * PROTEIN_THRESHOLD) NudgeLine.ProteinLeft(left, target) else null
    }

    private fun stepsLeft(day: DayNumbers): NudgeLine.StepsLeft? {
        val steps = day.steps ?: return null
        val goal = day.stepGoal?.takeIf { it > 0 } ?: return null
        val left = goal - steps
        return if (left >= goal * STEPS_THRESHOLD) NudgeLine.StepsLeft(left, goal) else null
    }

    private fun caloriesShort(day: DayNumbers): NudgeLine.CaloriesShort? {
        if (day.kcalTarget <= 0) return null
        val left = day.kcalTarget - day.kcalEaten
        return if (left > day.kcalTarget * CALORIES_THRESHOLD) NudgeLine.CaloriesShort(left, day.kcalTarget) else null
    }

    fun proteinPer100Kcal(per: Per100g): Double = if (per.kcal <= 0) 0.0 else per.proteinG * 100.0 / per.kcal

    /**
     * NU-23's suggestions: from your [POOL] most-logged foods (the caller passes only foods your diet allows),
     * the 2–3 with the most protein per 100 kcal, each with a portion that fits what's left.
     */
    fun suggest(foods: List<NudgeFood>, proteinLeftG: Int, kcalLeft: Int, limit: Int = MAX_SUGGESTIONS): List<Suggestion> {
        if (proteinLeftG <= 0) return emptyList()
        return foods.filter { it.useCount > 0 }
            .sortedByDescending { it.useCount }
            .take(POOL)
            .filter { it.per100g.proteinG > 0 && proteinPer100Kcal(it.per100g) >= MIN_PROTEIN_PER_100_KCAL }
            .sortedWith(compareByDescending<NudgeFood> { proteinPer100Kcal(it.per100g) }.thenByDescending { it.useCount }.thenBy { it.name })
            .take(limit)
            .map { portion(it, proteinLeftG, kcalLeft) }
    }

    /**
     * A portion that fits what's left (NU-23, PRD §12.8). It covers the protein left, but never goes over an upper
     * bound: twice what you usually have, [Portions.MAX_UNIT_GRAMS], and the calories left, though the calories never
     * push it under half your usual portion. With no calories left (or over), that half portion is the bound.
     * Rounded to the nearest step of the unit (½ a unit, or 10 g), then stepped down while it's over the bound,
     * keeping one step at least.
     */
    fun portion(food: NudgeFood, proteinLeftG: Int, kcalLeft: Int): Suggestion {
        val per = food.per100g
        val usual = food.usualGrams.takeIf { it > 0 } ?: 100.0
        var upper = min(usual * 2, Portions.MAX_UNIT_GRAMS)
        if (per.kcal > 0) upper = min(upper, if (kcalLeft > 0) max(kcalLeft / per.kcal * 100.0, usual / 2) else usual / 2)
        val grams = min(proteinLeftG / per.proteinG * 100.0, upper)
        val unit = food.unit.takeIf { it.grams > 0 } ?: PortionUnit(Portions.GRAMS, 1.0, PortionUnit.Kind.GRAMS)
        val step = Portions.step(unit.label)
        var quantity = max(step, (grams / unit.grams / step).roundToInt() * step)
        while (quantity > step && quantity * unit.grams > upper + 1e-9) quantity -= step
        val rounded = quantity * unit.grams
        val macros = per.forGrams(rounded)
        return Suggestion(food.id, food.name, quantity, unit, rounded, macros.proteinG.roundToInt(), macros.kcal.roundToInt())
    }
}
