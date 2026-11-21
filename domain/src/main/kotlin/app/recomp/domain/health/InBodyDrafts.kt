package app.recomp.domain.health

import kotlin.math.abs
import kotlin.math.roundToInt

/** What the InBody app writes to Health Connect (TRD §6.3): weight, body fat % and BMR, and nothing else of a scan. */
enum class BodyMetric { WEIGHT, BODY_FAT, BMR }

/** One body record: [value] is grams, percent or kcal a day, by [metric]. [id] is Health Connect's record id. */
data class BodyReading(val id: String, val metric: BodyMetric, val at: Long, val value: Double)

/** One InBody measurement, waiting for the numbers Health Connect has no type for (IB-07). */
data class DraftScan(val measuredAt: Long, val weightG: Int, val bodyFatPct: Double?, val bmrKcal: Int?, val ids: List<String>)

/**
 * InBody import (IB-07): the records of one measurement share a timestamp, so they're grouped into one draft.
 * A scan you already typed within ten minutes of it is the same scan.
 */
object InBodyDrafts {
    /** Records this close to a group's first one are the same measurement. */
    const val SAME_MEASUREMENT_MS = 60_000L

    const val MATCH_WINDOW_MS = 10 * 60_000L

    /**
     * Groups [readings] into measurements, oldest first. A second record of a metric starts a new group, so two
     * scans a minute apart stay two. A group with no weight makes no draft: the scan form needs one.
     */
    fun group(readings: List<BodyReading>): List<DraftScan> {
        val groups = mutableListOf<MutableList<BodyReading>>()
        readings.sortedWith(compareBy({ it.at }, { it.metric })).forEach { reading ->
            val open = groups.lastOrNull()
            if (open != null && reading.at - open.first().at <= SAME_MEASUREMENT_MS && open.none { it.metric == reading.metric }) open += reading
            else groups += mutableListOf(reading)
        }
        return groups.mapNotNull { group ->
            val weight = group.firstOrNull { it.metric == BodyMetric.WEIGHT } ?: return@mapNotNull null
            DraftScan(
                measuredAt = weight.at,
                weightG = weight.value.roundToInt(),
                bodyFatPct = group.firstOrNull { it.metric == BodyMetric.BODY_FAT }?.value?.let { (it * 10).roundToInt() / 10.0 },
                bmrKcal = group.firstOrNull { it.metric == BodyMetric.BMR }?.value?.roundToInt(),
                ids = group.map { it.id }.sorted(),
            )
        }
    }

    /** Whether a scan measured at one of [scanTimes] is this measurement. */
    fun matchesScan(measuredAt: Long, scanTimes: List<Long>): Boolean = scanTimes.any { abs(it - measuredAt) <= MATCH_WINDOW_MS }
}
