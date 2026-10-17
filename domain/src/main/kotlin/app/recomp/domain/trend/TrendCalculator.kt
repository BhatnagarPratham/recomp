package app.recomp.domain.trend

import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.GoalPolicy
import app.recomp.domain.persona.WeightSource
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.pow

/** One weigh-in (BM-01), in integer grams (TRD §4). */
data class WeighIn(val date: LocalDate, val measuredAt: Long, val weightG: Int, val source: WeightSource)

/** The trend on a day with a counted weigh-in: that day's reading and the smoothed value after it. */
data class TrendPoint(val date: LocalDate, val weightKg: Double, val trendKg: Double)

/** The change over the 7 days up to [asOf], the latest counted weigh-in (TRD §5.2). */
data class WeeklyRate(val kgPerWeek: Double, val pctPerWeek: Double, val asOf: LocalDate)

/**
 * The time-aware weight trend (BM-01, TRD §5.2). Only the primary source counts, and only its first
 * weigh-in of each log day; `α_eff = 1 − 0.9^Δdays`, so daily and weekly weigh-ins both give a
 * sensible trend.
 */
object TrendCalculator {
    /** The smoothing for one day's gap. */
    const val ALPHA = 0.1
    const val RATE_DAYS = 7L

    fun alpha(days: Long): Double = 1 - (1 - ALPHA).pow(days.toDouble())

    /** The weigh-ins the trend uses: [primary]'s earliest of each day, oldest first. */
    fun counted(weighIns: List<WeighIn>, primary: WeightSource): List<WeighIn> =
        weighIns.filter { it.source == primary }
            .groupBy { it.date }
            .map { (_, day) -> day.minBy { it.measuredAt } }
            .sortedBy { it.date }

    fun trend(weighIns: List<WeighIn>, primary: WeightSource): List<TrendPoint> {
        var previous: TrendPoint? = null
        return counted(weighIns, primary).map { w ->
            val kg = w.weightG / 1000.0
            val trend = previous?.let { p -> p.trendKg + alpha(ChronoUnit.DAYS.between(p.date, w.date)) * (kg - p.trendKg) } ?: kg
            TrendPoint(w.date, kg, trend).also { previous = it }
        }
    }

    /** The trend on [date], interpolated between points; null before the first, the last value after it. */
    fun trendAt(points: List<TrendPoint>, date: LocalDate): Double? {
        if (points.isEmpty() || date.isBefore(points.first().date)) return null
        val after = points.indexOfFirst { !it.date.isBefore(date) }
        if (after == -1) return points.last().trendKg
        val b = points[after]
        if (b.date == date || after == 0) return b.trendKg
        val a = points[after - 1]
        val span = ChronoUnit.DAYS.between(a.date, b.date).toDouble()
        return a.trendKg + (b.trendKg - a.trendKg) * ChronoUnit.DAYS.between(a.date, date) / span
    }

    /**
     * Anchored at the latest counted weigh-in, so a gap in weighing doesn't read as a flat week. Null until
     * the trend spans a week.
     */
    fun weeklyRate(points: List<TrendPoint>): WeeklyRate? {
        val last = points.lastOrNull() ?: return null
        val weekAgo = last.date.minusDays(RATE_DAYS)
        if (points.first().date.isAfter(weekAgo)) return null
        val kg = last.trendKg - (trendAt(points, weekAgo) ?: return null)
        return WeeklyRate(kg, kg / last.trendKg * 100, last.date)
    }
}

/** What the goal expects of the weekly rate, in % of body weight a week (TRD §5.2). Information, never a verdict. */
data class WeightPace(val minPctPerWeek: Double, val maxPctPerWeek: Double) {
    val steady: Boolean get() = minPctPerWeek == 0.0 && maxPctPerWeek == 0.0

    companion object {
        /** PRD §4.2: lean bulk's success range. */
        val LEAN_BULK_RANGE = WeightPace(0.25, 0.5)

        fun of(policy: GoalPolicy): WeightPace = when (policy.goal) {
            Goal.FAT_LOSS, Goal.RECOMP -> WeightPace(-policy.lossRatePctPerWeek, -policy.lossRatePctPerWeek)
            Goal.MAINTAIN -> WeightPace(0.0, 0.0)
            Goal.LEAN_BULK -> LEAN_BULK_RANGE
        }
    }
}
