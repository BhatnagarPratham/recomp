package app.recomp.domain.training

import app.recomp.domain.model.Equipment
import app.recomp.domain.model.LoadType
import app.recomp.domain.model.SetType
import java.math.BigDecimal
import java.math.RoundingMode

/** A completed set. Loads are integer grams (TRD §4); bodyweight sets have no load. */
data class LoggedSet(val type: SetType, val loadG: Int?, val reps: Int)

/** Load maths and formatting. Storage is always grams; display is kg (lb display is an open question, PRD §11). */
object Loads {
    /** Smallest steps offered by the equipment walkthrough (PR-04), in grams. */
    val incrementChoicesG = listOf(1000, 1250, 2000, 2500, 4000, 5000)

    /**
     * Starting increment per equipment, confirmed or changed in the walkthrough. Barbell and plate-loaded
     * steps assume 1.25 kg plates on each side; dumbbells are per dumbbell, never the pair (TRD §5.1).
     */
    fun defaultIncrementG(equipment: Equipment): Int = when (equipment) {
        Equipment.BARBELL, Equipment.PLATE_LOADED, Equipment.DUMBBELL, Equipment.BODYWEIGHT, Equipment.BAND -> 2500
        Equipment.MACHINE, Equipment.CABLE -> 5000
        Equipment.KETTLEBELL -> 4000
    }

    /** 42500 → "42.5", 40000 → "40", 1250 → "1.25". */
    fun formatKg(grams: Int): String =
        BigDecimal(grams).divide(BigDecimal(1000)).stripTrailingZeros().toPlainString()

    /** No lift or plate step is heavier; anything above is a typo that would poison targets and PRs. */
    const val MAX_LOAD_G = 1_000_000

    /** "42.5" → 42500. Accepts a comma as the decimal mark. Null for blanks, negatives, junk and over 1,000 kg. */
    fun parseKg(text: String): Int? {
        val kg = text.trim().replace(',', '.').toBigDecimalOrNull() ?: return null
        if (kg.signum() < 0) return null
        val grams = kg.multiply(BigDecimal(1000)).setScale(0, RoundingMode.HALF_UP)
        return grams.takeIf { it <= BigDecimal(MAX_LOAD_G) }?.intValueExact()
    }

    /** Weight stepper (WL-03): one increment up or down, never below zero. */
    fun step(loadG: Int, incrementG: Int, up: Boolean): Int =
        if (up) loadG + incrementG else (loadG - incrementG).coerceAtLeast(0)

    /** Whether sets of this load type log a weight at all (WL-09). */
    fun logsLoad(loadType: LoadType): Boolean = loadType != LoadType.BODYWEIGHT

    /**
     * Compact history line, e.g. "45 × 12/11/10" or "40 × 12, 42.5 × 10/9". Warm-ups are left out.
     * Bodyweight sets print as "BW × 15/12".
     */
    fun describe(sets: List<LoggedSet>): String {
        val counted = sets.filter { it.type != SetType.WARMUP }
        if (counted.isEmpty()) return ""
        val runs = mutableListOf<Pair<Int?, MutableList<Int>>>()
        for (set in counted) {
            if (runs.isNotEmpty() && runs.last().first == set.loadG) runs.last().second += set.reps
            else runs += set.loadG to mutableListOf(set.reps)
        }
        return runs.joinToString(", ") { (load, reps) ->
            val weight = when {
                load == null -> "BW"
                else -> formatKg(load)
            }
            "$weight × ${reps.joinToString("/")}"
        }
    }
}
