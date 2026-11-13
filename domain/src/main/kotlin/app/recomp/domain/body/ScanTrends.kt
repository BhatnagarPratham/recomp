package app.recomp.domain.body

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/** One body-composition scan as trends see it (IB-02, IB-04). Masses in integer grams, as stored (TRD §4). */
data class ScanPoint(
    val date: LocalDate,
    val measuredAt: Long,
    val muscleG: Int,
    val fatG: Int,
    val bodyFatPct: Double,
    val onProtocol: Boolean,
) {
    val muscleKg: Double get() = muscleG / 1000.0
    val fatKg: Double get() = fatG / 1000.0
}

/**
 * The change between two consecutive on-protocol scans, with §5.6's noise labels. The edges are compared exactly:
 * muscle in grams and body fat in tenths of a point, as typed, so 15.4% → 16.4% is a full point, never 0.999….
 */
data class ScanChange(val from: ScanPoint, val to: ScanPoint) {
    val muscleKg: Double get() = (to.muscleG - from.muscleG) / 1000.0
    val fatKg: Double get() = (to.fatG - from.fatG) / 1000.0
    val bodyFatPct: Double get() = to.bodyFatPct - from.bodyFatPct

    /** Under 1 kg of muscle: within the machine's noise. */
    val muscleWithinNoise: Boolean get() = abs(to.muscleG - from.muscleG) < ScanTrends.MUSCLE_NOISE_G

    /** Under 1 point of body fat: within noise. */
    val bodyFatWithinNoise: Boolean get() = (abs(bodyFatPct) * 10).roundToInt() < ScanTrends.BODY_FAT_NOISE_TENTHS
}

/** Least-squares slopes per 4 weeks, from three or more on-protocol scans. */
data class ScanSlopes(val muscleKg: Double, val fatKg: Double, val bodyFatPct: Double)

/** A week's scan against the previous on-protocol one (PRD §12.4); [change] is null when it's off protocol or the first. */
data class WeekScan(val scan: ScanPoint, val change: ScanChange?)

/**
 * InBody trends (IB-04, TRD §5.6). Only on-protocol scans count: they make the deltas and the slope, and off-protocol
 * ones are drawn hollow and left out of both.
 */
object ScanTrends {
    const val MUSCLE_NOISE_G = 1_000
    const val BODY_FAT_NOISE_TENTHS = 10
    const val MIN_SCANS_FOR_SLOPE = 3
    const val SLOPE_DAYS = 28.0

    /** On-protocol scans, oldest first. */
    fun onProtocol(scans: List<ScanPoint>): List<ScanPoint> = scans.filter { it.onProtocol }.sortedBy { it.measuredAt }

    /** Each change between consecutive on-protocol scans, oldest first. */
    fun changes(scans: List<ScanPoint>): List<ScanChange> = onProtocol(scans).zipWithNext { a, b -> ScanChange(a, b) }

    /** Slopes per 4 weeks, or null under three on-protocol scans or when they're all on one day. */
    fun slopes(scans: List<ScanPoint>): ScanSlopes? {
        val counted = onProtocol(scans)
        if (counted.size < MIN_SCANS_FOR_SLOPE) return null
        val first = counted.first().date
        val x = counted.map { ChronoUnit.DAYS.between(first, it.date).toDouble() }
        fun slope(y: List<Double>): Double? {
            val mx = x.average()
            val my = y.average()
            val sxx = x.sumOf { (it - mx) * (it - mx) }
            if (sxx == 0.0) return null
            return x.indices.sumOf { (x[it] - mx) * (y[it] - my) } / sxx * SLOPE_DAYS
        }
        return ScanSlopes(
            muscleKg = slope(counted.map { it.muscleKg }) ?: return null,
            fatKg = slope(counted.map { it.fatKg }) ?: return null,
            bodyFatPct = slope(counted.map { it.bodyFatPct }) ?: return null,
        )
    }

    /** Scans on or after [from], for a chart's window: its changes and slope come from these only. */
    fun since(scans: List<ScanPoint>, from: LocalDate): List<ScanPoint> = scans.filter { !it.date.isBefore(from) }

    /**
     * The week's latest on-protocol scan in [from]..[to] against the on-protocol scan before it. Only a week with no
     * on-protocol scan shows its off-protocol one, without a comparison, because it isn't one. Null without a scan.
     */
    fun weekScan(scans: List<ScanPoint>, from: LocalDate, to: LocalDate): WeekScan? {
        val inWeek = scans.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
        val latest = inWeek.filter { it.onProtocol }.maxByOrNull { it.measuredAt } ?: inWeek.maxByOrNull { it.measuredAt } ?: return null
        if (!latest.onProtocol) return WeekScan(latest, null)
        val previous = onProtocol(scans).lastOrNull { it.measuredAt < latest.measuredAt }
        return WeekScan(latest, previous?.let { ScanChange(it, latest) })
    }
}
