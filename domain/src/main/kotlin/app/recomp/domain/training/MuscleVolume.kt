package app.recomp.domain.training

import java.time.LocalDate

/** One exercise in one completed session: its muscles and how many working sets it had. */
data class VolumeRow(val logDate: LocalDate, val primaryMuscle: String, val secondaryMuscles: List<String>, val workingSets: Int)

/** A muscle's working sets this week, counted in half sets so secondaries stay exact. */
data class MuscleSets(val muscle: String, val halfSets: Int) {
    val sets: Double get() = halfSets / 2.0
}

/**
 * Weekly sets per muscle (PO-10, TRD §5.5): each working set counts 1 for the exercise's primary muscle
 * and ½ for each secondary one. Warm-ups, drop and failure sets aren't counted. Weeks start on Monday,
 * by log date. It's information only: the band is a reference, never a warning.
 */
object MuscleVolume {
    const val DEFAULT_BAND_MIN = 10
    const val DEFAULT_BAND_MAX = 20
    /** No band goes higher; nobody recovers from more. */
    const val BAND_LIMIT = 60

    /** Custom exercises have no muscle yet, so they're left out rather than shown as a muscle. */
    const val UNKNOWN = "other"

    /**
     * The week of [today], most sets first. [alsoShow] lists muscles to include at zero, e.g. the
     * primary muscles of the routines in rotation, so an untrained one is visible.
     */
    fun week(rows: List<VolumeRow>, today: LocalDate, alsoShow: Collection<String> = emptyList()): List<MuscleSets> {
        val start = WeeklyPlan.weekStart(today)
        val end = start.plusDays(7)
        val halves = LinkedHashMap<String, Int>()
        alsoShow.forEach { halves[it] = 0 }
        rows.filter { !it.logDate.isBefore(start) && it.logDate.isBefore(end) && it.workingSets > 0 }.forEach { row ->
            halves.merge(row.primaryMuscle, 2 * row.workingSets, Int::plus)
            // A muscle listed as both primary and secondary counts once, as the primary.
            row.secondaryMuscles.distinct().filter { it != row.primaryMuscle }.forEach { halves.merge(it, row.workingSets, Int::plus) }
        }
        return halves.filterKeys { it != UNKNOWN }.map { MuscleSets(it.key, it.value) }
            .sortedWith(compareByDescending<MuscleSets> { it.halfSets }.thenBy { it.muscle })
    }

    /** Whether a band is one the setting accepts: whole sets, low below high. */
    fun validBand(min: Int, max: Int): Boolean = min >= 0 && max > min && max <= BAND_LIMIT

    /** "7.5" or "12". */
    fun format(sets: MuscleSets): String = if (sets.halfSets % 2 == 0) "${sets.halfSets / 2}" else "${sets.halfSets / 2}.5"
}
