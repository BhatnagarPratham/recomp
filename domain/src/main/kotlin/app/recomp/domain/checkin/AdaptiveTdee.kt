package app.recomp.domain.checkin

import app.recomp.domain.nutrition.NutritionCalculator
import app.recomp.domain.trend.TrendCalculator
import app.recomp.domain.trend.TrendPoint
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Why a window gave no estimate, in the order the checks run (so a stored row tells which one it was). */
enum class EstimateGap {
    /** Under 10 weigh-ins in 21 days and under 4 in 28. */
    WEIGH_INS,

    /** Food logged on under 80% of the window's days. Days marked incomplete don't count. */
    FOOD_DAYS,

    /** The trend starts inside the window, so it doesn't cover the whole window yet. */
    HISTORY,
}

/**
 * One week's maintenance estimate (NU-05, TRD §5.4). Without enough data [observed] is null and the
 * estimate carries [previous] forward unchanged.
 */
data class TdeeEstimate(
    val windowDays: Int,
    /** Days in the window with food logged and not marked incomplete. */
    val loggedDays: Int,
    /** Counted weigh-ins (TRD §5.2) in the window. */
    val weighIns: Int,
    /** Mean kcal over [loggedDays]; null when none. */
    val kcalMean: Double?,
    val trendStartKg: Double?,
    val trendEndKg: Double?,
    val observed: Double?,
    val previous: Double,
    val new: Double,
    val gap: EstimateGap?,
) {
    val usable: Boolean get() = gap == null
}

/**
 * Adaptive maintenance (NU-05, TRD §5.4): intake on logged days against the change in the trend weight.
 *
 * The window ends on the check-in week's Sunday and is 21 days when the primary source has 10 or more
 * weigh-ins in it, else 28 days with at least 4. It's usable when the trend covers the whole window and at
 * least 80% of its days have food logged; a day with no food, or one marked incomplete, is left out of the mean.
 *
 * The trend change is anchored at the window's last weigh-in and measured over the window's length before it,
 * like the weekly rate (TRD §5.2), so a gap in weighing at the end doesn't read as a flat stretch. When the
 * trend starts less than a window before that weigh-in, the change since the first weigh-in is scaled to a day.
 */
object AdaptiveTdee {
    const val DAILY_WINDOW_DAYS = 21
    const val WEEKLY_WINDOW_DAYS = 28
    const val DAILY_MIN_WEIGH_INS = 10
    const val WEEKLY_MIN_WEIGH_INS = 4

    /** A window is usable from 80% of its days logged: 17 of 21, 23 of 28. */
    const val MIN_LOGGED_SHARE = 0.8

    /** Each week moves halfway to the observation, by at most 150 kcal. */
    const val STEP = 0.5
    const val MAX_STEP_KCAL = 150.0

    fun minLoggedDays(windowDays: Int): Int = kotlin.math.ceil(windowDays * MIN_LOGGED_SHARE - 1e-9).toInt()

    /**
     * @param end the last day of the window, the check-in week's Sunday
     * @param points the primary source's trend (TrendCalculator.trend); points after [end] are ignored
     * @param kcalByDay kcal per day with food logged; days without food are absent
     * @param incomplete days marked incomplete on the Food tab, left out wherever they fall
     * @param previous last week's estimate, or the target's TDEE for the first check-in
     */
    fun estimate(
        end: LocalDate,
        points: List<TrendPoint>,
        kcalByDay: Map<LocalDate, Number>,
        incomplete: Set<LocalDate>,
        previous: Double,
    ): TdeeEstimate {
        val trend = points.filter { !it.date.isAfter(end) }
        fun weighInsIn(days: Int) = trend.count { it.date.isAfter(end.minusDays(days.toLong())) }

        val daily = weighInsIn(DAILY_WINDOW_DAYS) >= DAILY_MIN_WEIGH_INS
        val windowDays = if (daily) DAILY_WINDOW_DAYS else WEEKLY_WINDOW_DAYS
        val start = end.minusDays(windowDays - 1L)
        val inWindow = trend.filter { !it.date.isBefore(start) }
        val logged = kcalByDay.filterKeys { !it.isBefore(start) && !it.isAfter(end) && it !in incomplete }
        val kcalMean = logged.values.takeIf { it.isNotEmpty() }?.map { it.toDouble() }?.average()

        val gap = when {
            !daily && inWindow.size < WEEKLY_MIN_WEIGH_INS -> EstimateGap.WEIGH_INS
            logged.size < minLoggedDays(windowDays) -> EstimateGap.FOOD_DAYS
            trend.first().date.isAfter(start) -> EstimateGap.HISTORY
            else -> null
        }

        // The trend at the window's last weigh-in, and a window's length before it (or its first point).
        val endPoint = inWindow.lastOrNull()
        val from = endPoint?.let { maxOf(it.date.minusDays(windowDays.toLong()), trend.first().date) }
        val trendStart = from?.let { TrendCalculator.trendAt(trend, it) }
        val span = if (endPoint != null && from != null) ChronoUnit.DAYS.between(from, endPoint.date) else 0L

        val observed = if (gap == null && kcalMean != null && trendStart != null && span > 0) {
            kcalMean - (endPoint!!.trendKg - trendStart) * NutritionCalculator.KCAL_PER_KG_FAT / span
        } else {
            null
        }
        return TdeeEstimate(
            windowDays = windowDays,
            loggedDays = logged.size,
            weighIns = inWindow.size,
            kcalMean = kcalMean,
            trendStartKg = trendStart,
            trendEndKg = endPoint?.trendKg,
            observed = observed,
            previous = previous,
            new = observed?.let { step(previous, it) } ?: previous,
            gap = gap,
        )
    }

    /** Which check a stored week failed, from its counts: the checks run in [EstimateGap]'s order. */
    fun gapOf(windowDays: Int, loggedDays: Int, weighIns: Int, observed: Boolean): EstimateGap? = when {
        observed -> null
        windowDays == WEEKLY_WINDOW_DAYS && weighIns < WEEKLY_MIN_WEIGH_INS -> EstimateGap.WEIGH_INS
        loggedDays < minLoggedDays(windowDays) -> EstimateGap.FOOD_DAYS
        else -> EstimateGap.HISTORY
    }

    /** `tdee_prev + clamp(0.5 × (tdee_obs − tdee_prev), −150, +150)`. */
    fun step(previous: Double, observed: Double): Double =
        previous + (STEP * (observed - previous)).coerceIn(-MAX_STEP_KCAL, MAX_STEP_KCAL)
}
