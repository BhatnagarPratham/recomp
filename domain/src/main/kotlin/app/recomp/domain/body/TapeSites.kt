package app.recomp.domain.body

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A tape site (BM-02, BM-03). Waist is always measured; the others are chosen in Settings → Body data. */
enum class TapeSite { WAIST, CHEST, ARM, THIGH, HIP }

/** A site's latest reading and the one before it, for Body's "latest and change" line. */
data class TapeReading(val site: TapeSite, val date: LocalDate, val cm: Double, val previousCm: Double?) {
    val changeCm: Double? get() = previousCm?.let { cm - it }
}

/**
 * Which sites the *Log measurements* sheet asks for (PRD §12.13): waist first, then each chosen site whose
 * last reading is [OTHERS_EVERY_DAYS] days old or more, or that has none yet.
 */
object TapeSites {
    const val OTHERS_EVERY_DAYS = 28L

    /** The sites you can choose; waist is always on. */
    val OPTIONAL = listOf(TapeSite.CHEST, TapeSite.ARM, TapeSite.THIGH, TapeSite.HIP)

    fun due(chosen: Set<TapeSite>, lastDates: Map<TapeSite, LocalDate>, today: LocalDate): List<TapeSite> =
        listOf(TapeSite.WAIST) + OPTIONAL.filter { it in chosen }.filter { site ->
            val last = lastDates[site] ?: return@filter true
            ChronoUnit.DAYS.between(last, today) >= OTHERS_EVERY_DAYS
        }

    /** Each site's one-line how-to. */
    fun how(site: TapeSite): String = when (site) {
        TapeSite.WAIST -> "Measure at your navel, standing relaxed, after breathing out."
        TapeSite.CHEST -> "Across the nipples, arms down, after a normal breath out."
        TapeSite.ARM -> "Right arm, relaxed, at the widest point."
        TapeSite.THIGH -> "Right thigh, standing, at the widest point below the glutes."
        TapeSite.HIP -> "Around the widest part of the buttocks, feet together."
    }

    /** The latest reading per site with the one before it, from readings in any order. */
    fun latest(readings: List<Triple<TapeSite, LocalDate, Double>>): List<TapeReading> =
        readings.groupBy { it.first }.mapNotNull { (site, rows) ->
            val sorted = rows.sortedBy { it.second }
            val last = sorted.lastOrNull() ?: return@mapNotNull null
            TapeReading(site, last.second, last.third, sorted.getOrNull(sorted.size - 2)?.third)
        }.sortedBy { it.site.ordinal }
}
