package app.recomp.domain.progression

import app.recomp.domain.model.LoadType
import app.recomp.domain.model.SetType
import app.recomp.domain.persona.ExperiencePolicy
import app.recomp.domain.training.LoggedSet
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** What the engine needs to know about one exercise in a routine (TRD §5.1). */
data class ExerciseConfig(
    val repMin: Int,
    val repMax: Int,
    val targetSets: Int,
    val incrementG: Int,
    val loadType: LoadType,
)

/** One completed session's sets for an exercise. Only working sets at the top load drive progression. */
data class Exposure(val startedAt: Long, val sets: List<LoggedSet>) {
    val working: List<LoggedSet> = sets.filter { it.type == SetType.WORKING }
    val topLoadG: Int = working.maxOfOrNull { it.loadG ?: 0 } ?: 0
    val topSets: List<LoggedSet> = working.filter { (it.loadG ?: 0) == topLoadG }

    /** The load progression counts: the heaviest working load, or the least assistance for [LoadType.ASSISTED]. */
    fun topLoadG(loadType: LoadType): Int =
        if (loadType == LoadType.ASSISTED) working.minOfOrNull { it.loadG ?: 0 } ?: 0 else topLoadG

    fun topSets(loadType: LoadType): List<LoggedSet> =
        if (loadType == LoadType.ASSISTED) working.filter { (it.loadG ?: 0) == topLoadG(loadType) } else topSets

    fun missedBottom(repMin: Int, loadType: LoadType = LoadType.WEIGHTED) = topSets(loadType).any { it.reps < repMin }
}

enum class Action { BASELINE, INCREASE, ADD_REPS, REPEAT, DELOAD }

/**
 * Today's target for one exercise (PO-02). [loadG] is null for a baseline and for pure bodyweight;
 * [reps] has one entry per target set. [harderVariation] means a bodyweight exercise is at the top of
 * its range, so the next step is added load or a harder variation. [steps] is 2 when an increase earned
 * the double step (PO-08). With [assisted], [loadG] is the assistance, so less of it is harder (WL-09).
 */
data class Recommendation(
    val action: Action,
    val loadG: Int?,
    val reps: List<Int>,
    val stalled: Boolean = false,
    val harderVariation: Boolean = false,
    val steps: Int = 1,
    val assisted: Boolean = false,
)

/** A Hold or Deload choice (PO-05): stall detection restarts from [at]. */
data class StallReset(val at: Long, val keepsBaseline: Boolean)

data class ProgressionContext(
    val experience: ExperiencePolicy,
    /** True when today's calorie target is below the maintenance band around TDEE (TRD §5.1); false with no target. */
    val inDeficit: Boolean = false,
    val stallReset: StallReset? = null,
    /** Your trend weight (TRD §5.2), for assisted lifts; null before the primary source's first weigh-in. */
    val bodyWeightG: Int? = null,
)

/**
 * Double progression with the big-jump guard, the double step, deloads and stall detection (TRD §5.1,
 * PO-01 to PO-08). Pure rules, no AI, always offline (PRD §7.12).
 *
 * Assisted lifts (WL-09) progress by taking assistance off. The big-jump guard and the double step
 * measure the step against the effective load, your trend weight minus the assistance; before the first
 * weigh-in that's unknown, so neither applies and the lift progresses one step at a time.
 */
object ProgressionEngine {
    /** PO-06: a step bigger than this share of the current load needs 2 extra reps first. */
    const val BIG_JUMP_RATIO = 0.10
    const val BIG_JUMP_EXTRA_REPS = 2
    /** PO-08: the double step is only for steps up to this share of the load, so dumbbells don't leap. */
    const val DOUBLE_STEP_MAX_RATIO = 0.05
    /** PO-08: every top set needs at least this many reps in reserve (the 3+ chip). */
    const val DOUBLE_STEP_MIN_RIR = 3
    const val DELOAD_FACTOR = 0.9
    /** Estimated 1RM is only shown and used for PRs up to this many reps (Epley). */
    const val E1RM_MAX_REPS = 12

    /** @param history completed exposures, newest first. */
    fun recommend(cfg: ExerciseConfig, history: List<Exposure>, ctx: ProgressionContext): Recommendation {
        val history = history.filter { it.working.isNotEmpty() }
        val base = if (cfg.loadType == LoadType.BODYWEIGHT) repsOnly(cfg, history) else doubleProgression(cfg, history, ctx)
        if (base.action == Action.BASELINE) return base
        val window = ctx.experience.stallWindow + if (ctx.inDeficit) 1 else 0
        return base.copy(stalled = stalled(history, window, ctx.stallReset, cfg.loadType, ctx.bodyWeightG))
    }

    private fun doubleProgression(cfg: ExerciseConfig, history: List<Exposure>, ctx: ProgressionContext): Recommendation {
        val assisted = cfg.loadType == LoadType.ASSISTED
        val last = history.firstOrNull()
            ?: return Recommendation(Action.BASELINE, null, List(cfg.targetSets) { cfg.repMax }, assisted = assisted)
        val top = last.topLoadG(cfg.loadType)
        val topSets = last.topSets(cfg.loadType)
        val stepBase = stepBaseG(cfg.loadType, top, ctx.bodyWeightG)
        val effRepMax = if (stepBase == null) cfg.repMax else effectiveRepMax(cfg, stepBase)
        val allAtTop = topSets.size >= cfg.targetSets && topSets.all { it.reps >= effRepMax }
        val missedBottom = last.missedBottom(cfg.repMin, cfg.loadType)
        val previous = history.getOrNull(1)
        val bottom = List(cfg.targetSets) { cfg.repMin }
        val rec = when {
            allAtTop -> increase(cfg, top, topSets, stepBase, ctx.experience)
            missedBottom && previous != null && previous.topLoadG(cfg.loadType) == top && previous.missedBottom(cfg.repMin, cfg.loadType) ->
                Recommendation(Action.DELOAD, deloadLoad(cfg.loadType, top, cfg.incrementG, ctx.bodyWeightG), bottom)
            missedBottom -> Recommendation(Action.REPEAT, top, bottom)
            else -> Recommendation(Action.ADD_REPS, top, addOneRep(topSets.map { it.reps }, cfg, effRepMax))
        }
        return rec.copy(assisted = assisted)
    }

    /**
     * One step up, or two (PO-08) when the experience policy allows it, every top set had 3+ reps in
     * reserve and one step is at most 5% of the load. Assisted lifts take the steps off the assistance;
     * with none left, the next step is added load or a harder variation.
     */
    private fun increase(cfg: ExerciseConfig, top: Int, topSets: List<LoggedSet>, stepBase: Int?, policy: ExperiencePolicy): Recommendation {
        val bottom = List(cfg.targetSets) { cfg.repMin }
        val double = policy.allowsDoubleJump && stepBase != null && stepBase > 0 &&
            cfg.incrementG <= stepBase * DOUBLE_STEP_MAX_RATIO && topSets.all { (it.rir ?: 0) >= DOUBLE_STEP_MIN_RIR }
        val steps = if (double) 2 else 1
        if (cfg.loadType != LoadType.ASSISTED) return Recommendation(Action.INCREASE, top + steps * cfg.incrementG, bottom, steps = steps)
        if (top <= 0) return Recommendation(Action.INCREASE, 0, List(cfg.targetSets) { cfg.repMax }, harderVariation = true)
        val load = (top - steps * cfg.incrementG).coerceAtLeast(0)
        return Recommendation(Action.INCREASE, load, bottom, steps = if (load == top - steps * cfg.incrementG) steps else 1)
    }

    /** Bodyweight (`REPS_ONLY`): reps climb to the top of the range, then add load or a harder variation. */
    private fun repsOnly(cfg: ExerciseConfig, history: List<Exposure>): Recommendation {
        val last = history.firstOrNull() ?: return Recommendation(Action.BASELINE, null, List(cfg.targetSets) { cfg.repMax })
        val reps = last.working.map { it.reps }
        return when {
            reps.size >= cfg.targetSets && reps.all { it >= cfg.repMax } ->
                Recommendation(Action.INCREASE, null, List(cfg.targetSets) { cfg.repMax }, harderVariation = true)
            reps.any { it < cfg.repMin } -> Recommendation(Action.REPEAT, null, List(cfg.targetSets) { cfg.repMin })
            else -> Recommendation(Action.ADD_REPS, null, addOneRep(reps, cfg, cfg.repMax))
        }
    }

    /**
     * The load a step is measured against (PO-06, PO-08): the top load, or for an assisted lift your
     * body weight minus the assistance. Null when that's unknown (no weigh-in yet).
     */
    fun stepBaseG(loadType: LoadType, topLoadG: Int, bodyWeightG: Int?): Int? =
        if (loadType == LoadType.ASSISTED) bodyWeightG?.let { it - topLoadG } else topLoadG

    /** PO-06: with no load yet (weighted bodyweight), the first plate is always a big jump. */
    fun effectiveRepMax(cfg: ExerciseConfig, topLoadG: Int): Int {
        val bigJump = topLoadG <= 0 || cfg.incrementG.toDouble() / topLoadG > BIG_JUMP_RATIO
        return cfg.repMax + if (bigJump) BIG_JUMP_EXTRA_REPS else 0
    }

    /** Last time's reps, padded or trimmed to the target set count, with +1 on the first set below the top. */
    private fun addOneRep(lastReps: List<Int>, cfg: ExerciseConfig, effRepMax: Int): List<Int> {
        val padded = List(cfg.targetSets) { i -> lastReps.getOrNull(i) ?: lastReps.lastOrNull() ?: cfg.repMin }.toMutableList()
        val i = padded.indexOfFirst { it < effRepMax }
        if (i >= 0) padded[i] = padded[i] + 1
        return padded
    }

    /** 90% of the top load, rounded down to a whole number of increments. */
    fun deloadLoad(topLoadG: Int, incrementG: Int): Int {
        if (incrementG <= 0) return (topLoadG * DELOAD_FACTOR).toInt()
        return (floor(topLoadG * DELOAD_FACTOR / incrementG) * incrementG).toInt()
    }

    /**
     * A deload for any load type (PO-05). An assisted lift adds the assistance that takes about 10% off
     * the effective load, rounded up to the step so it's never harder than that. Before the first
     * weigh-in, or with assistance at or above body weight, it adds one step.
     */
    fun deloadLoad(loadType: LoadType, topLoadG: Int, incrementG: Int, bodyWeightG: Int?): Int {
        if (loadType != LoadType.ASSISTED) return deloadLoad(topLoadG, incrementG)
        val effective = bodyWeightG?.let { it - topLoadG }?.takeIf { it > 0 } ?: return topLoadG + incrementG
        val assistance = topLoadG + effective * (1 - DELOAD_FACTOR)
        if (incrementG <= 0) return assistance.roundToInt()
        return (ceil(assistance / incrementG) * incrementG).toInt()
    }

    /** Epley, in grams: load × (1 + reps / 30). */
    fun e1rmG(loadG: Int, reps: Int): Double = loadG * (1 + reps / 30.0)

    /**
     * The stall metric: best Epley score over every working set, with no rep cap. Above 12 reps it's a
     * poor absolute 1RM, but as a progress measure it's consistent between sessions and rises with
     * reps, which high-rep work needs: excluding or capping sets above 12 makes a session that
     * straddles 12 score on its weakest set, or makes 14 → 16 reps look flat. Bodyweight uses reps.
     * Assisted lifts score body weight minus the assistance; before the first weigh-in, less assistance
     * always scores higher and reps break ties.
     */
    fun score(exposure: Exposure, loadType: LoadType, bodyWeightG: Int? = null): Double {
        val sets = exposure.working
        if (sets.isEmpty()) return 0.0
        if (loadType == LoadType.BODYWEIGHT) return sets.maxOf { it.reps }.toDouble()
        if (loadType == LoadType.ASSISTED) {
            if (bodyWeightG == null) return sets.maxOf { -(it.loadG ?: 0) + it.reps / 1000.0 }
            return sets.maxOf { s -> (bodyWeightG - (s.loadG ?: 0)).let { if (it > 0) e1rmG(it, s.reps) else s.reps.toDouble() } }
        }
        // Weighted bodyweight with no load yet scores by reps so it still shows progress.
        return sets.maxOf { s -> (s.loadG ?: 0).let { if (it > 0) e1rmG(it, s.reps) else s.reps.toDouble() } }
    }

    /**
     * PO-05: no gain in estimated 1RM across the last [window] exposures compared with the ones before.
     * After a Deload only exposures since the reset count; after a Hold the lift must also fail to beat
     * the best from before the hold for a whole window.
     */
    fun stalled(history: List<Exposure>, window: Int, reset: StallReset?, loadType: LoadType, bodyWeightG: Int? = null): Boolean {
        fun List<Exposure>.best() = maxOf { score(it, loadType, bodyWeightG) }
        val recent = if (reset == null) history else history.filter { it.startedAt > reset.at }
        if (reset?.keepsBaseline == true) {
            val before = history.filter { it.startedAt <= reset.at }
            if (before.isNotEmpty()) return recent.size >= window && recent.best() <= before.best()
        }
        return recent.size > window && recent.take(window).best() <= recent.drop(window).best()
    }
}
