package app.recomp.domain.progression

import app.recomp.domain.model.LoadType
import app.recomp.domain.model.SetType
import app.recomp.domain.persona.ExperiencePolicy
import app.recomp.domain.training.LoggedSet
import kotlin.math.floor

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

    fun missedBottom(repMin: Int) = topSets.any { it.reps < repMin }
}

enum class Action { BASELINE, INCREASE, ADD_REPS, REPEAT, DELOAD }

/**
 * Today's target for one exercise (PO-02). [loadG] is null for a baseline and for pure bodyweight;
 * [reps] has one entry per target set. [harderVariation] means a bodyweight exercise is at the top of
 * its range, so the next step is added load or a harder variation.
 */
data class Recommendation(
    val action: Action,
    val loadG: Int?,
    val reps: List<Int>,
    val stalled: Boolean = false,
    val harderVariation: Boolean = false,
)

/** A Hold or Deload choice (PO-05): stall detection restarts from [at]. */
data class StallReset(val at: Long, val keepsBaseline: Boolean)

data class ProgressionContext(
    val experience: ExperiencePolicy,
    /** TODO(M3): true when the calorie target is below estimated TDEE. False until nutrition targets exist. */
    val inDeficit: Boolean = false,
    val stallReset: StallReset? = null,
)

/**
 * Double progression with the big-jump guard, deloads and stall detection (TRD §5.1, PO-01 to PO-06).
 * Pure rules, no AI, always offline (PRD §7.12).
 */
object ProgressionEngine {
    /** PO-06: a step bigger than this share of the current load needs 2 extra reps first. */
    const val BIG_JUMP_RATIO = 0.10
    const val BIG_JUMP_EXTRA_REPS = 2
    const val DELOAD_FACTOR = 0.9
    /** Estimated 1RM is only trusted up to this many reps (Epley). */
    const val E1RM_MAX_REPS = 12

    /** @param history completed exposures, newest first. */
    fun recommend(cfg: ExerciseConfig, history: List<Exposure>, ctx: ProgressionContext): Recommendation {
        val history = history.filter { it.working.isNotEmpty() }
        val base = if (cfg.loadType == LoadType.BODYWEIGHT) repsOnly(cfg, history) else doubleProgression(cfg, history)
        if (base.action == Action.BASELINE) return base
        val window = ctx.experience.stallWindow + if (ctx.inDeficit) 1 else 0
        return base.copy(stalled = stalled(history, window, ctx.stallReset, cfg.loadType))
    }

    private fun doubleProgression(cfg: ExerciseConfig, history: List<Exposure>): Recommendation {
        val last = history.firstOrNull() ?: return Recommendation(Action.BASELINE, null, List(cfg.targetSets) { cfg.repMax })
        val top = last.topLoadG
        val effRepMax = effectiveRepMax(cfg, top)
        val allAtTop = last.topSets.size >= cfg.targetSets && last.topSets.all { it.reps >= effRepMax }
        val missedBottom = last.missedBottom(cfg.repMin)
        val previous = history.getOrNull(1)
        val bottom = List(cfg.targetSets) { cfg.repMin }
        return when {
            allAtTop -> Recommendation(Action.INCREASE, top + cfg.incrementG, bottom)
            missedBottom && previous != null && previous.topLoadG == top && previous.missedBottom(cfg.repMin) ->
                Recommendation(Action.DELOAD, deloadLoad(top, cfg.incrementG), bottom)
            missedBottom -> Recommendation(Action.REPEAT, top, bottom)
            else -> Recommendation(Action.ADD_REPS, top, addOneRep(last.topSets.map { it.reps }, cfg, effRepMax))
        }
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

    /** Epley, in grams: load × (1 + reps / 30). */
    fun e1rmG(loadG: Int, reps: Int): Double = loadG * (1 + reps / 30.0)

    /**
     * The stall metric: best estimated 1RM over working sets of up to 12 reps, falling back to every
     * working set for high-rep work. Bodyweight uses reps.
     */
    fun score(exposure: Exposure, loadType: LoadType): Double {
        val sets = exposure.working
        if (sets.isEmpty()) return 0.0
        if (loadType == LoadType.BODYWEIGHT) return sets.maxOf { it.reps }.toDouble()
        val trusted = sets.filter { it.reps <= E1RM_MAX_REPS }.ifEmpty { sets }
        // Weighted bodyweight with no load yet scores by reps so it still shows progress.
        return trusted.maxOf { s -> (s.loadG ?: 0).let { if (it > 0) e1rmG(it, s.reps) else s.reps.toDouble() } }
    }

    /**
     * PO-05: no gain in estimated 1RM across the last [window] exposures compared with the ones before.
     * After a Deload only exposures since the reset count; after a Hold the lift must also fail to beat
     * the best from before the hold for a whole window.
     */
    fun stalled(history: List<Exposure>, window: Int, reset: StallReset?, loadType: LoadType): Boolean {
        fun List<Exposure>.best() = maxOf { score(it, loadType) }
        val recent = if (reset == null) history else history.filter { it.startedAt > reset.at }
        if (reset?.keepsBaseline == true) {
            val before = history.filter { it.startedAt <= reset.at }
            if (before.isNotEmpty()) return recent.size >= window && recent.best() <= before.best()
        }
        return recent.size > window && recent.take(window).best() <= recent.drop(window).best()
    }
}
