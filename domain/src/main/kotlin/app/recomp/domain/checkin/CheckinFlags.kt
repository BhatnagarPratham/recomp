package app.recomp.domain.checkin

import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.GoalPolicy
import app.recomp.domain.trend.TrendCalculator
import app.recomp.domain.trend.TrendPoint
import java.time.LocalDate
import kotlin.math.abs

/** The goal's check-in flag (GL-08, PRD §12.1), stored on the week's row. */
enum class CheckinFlag {
    /** Fat loss: losing under half the pace two weeks running. */
    LOSS_SLOW,

    /** Recomp: trend and waist both flat over 4 weeks. */
    FLAT,

    /** Maintain: the trend moved more than 1% over 4 weeks. */
    DRIFT,

    /** Lean bulk: gaining under 0.1% a week two weeks running. */
    GAIN_SLOW,

    /** Lean bulk: gaining over 0.5% a week two weeks running. */
    GAIN_FAST,
}

/** The verdict under the headline: on track, the flag that fired, or what's missing. */
enum class CheckinStatus { ON_TRACK, FLAGGED, NOT_ENOUGH_DATA, NEEDS_WAIST }

/** A waist reading (BM-02), in cm. */
data class WaistReading(val date: LocalDate, val cm: Double)

/** What the week's flag was judged on. Rates and changes are in % of body weight; waist in cm. */
data class CheckinEvaluation(
    val status: CheckinStatus,
    val flag: CheckinFlag?,
    /** The weekly rate at the week's end, and a week before (TRD §5.2). */
    val ratePct: Double?,
    val previousRatePct: Double?,
    /** The trend's change over the 4 weeks to the week's end. */
    val fourWeekPct: Double?,
    val fourWeekKg: Double?,
    /** Waist change between readings near each end of the 4 weeks. */
    val waistChangeCm: Double?,
    /** The trend weight at the week's last weigh-in. */
    val trendKg: Double?,
)

/**
 * Per-goal check-in flags (GL-08, TRD §5.4), judged on the trend and never on single weigh-ins:
 * - fat loss: the weekly rate is under half the pace for 2 weeks in a row;
 * - recomp: over 4 weeks the trend changed by less than 0.5% and waist by less than 1 cm, with a waist
 *   reading near each end (without them it asks for one);
 * - maintain: the trend moved more than 1% over 4 weeks;
 * - lean bulk: the weekly rate is under 0.1% or over 0.5% for 2 weeks in a row.
 */
object CheckinFlags {
    const val SLOW_LOSS_SHARE = 0.5
    const val FLAT_TREND_PCT = 0.5
    const val FLAT_WAIST_CM = 1.0
    const val DRIFT_PCT = 1.0
    const val GAIN_SLOW_PCT = 0.1
    const val GAIN_FAST_PCT = 0.5
    const val FOUR_WEEKS = 28L

    /** A waist reading counts as "near" an end within this many days of it. */
    const val WAIST_NEAR_DAYS = 7L

    fun evaluate(policy: GoalPolicy, points: List<TrendPoint>, waist: List<WaistReading>, end: LocalDate): CheckinEvaluation {
        val trend = points.filter { !it.date.isAfter(end) }
        val rate = rateAsOf(trend, end)
        val previous = rateAsOf(trend, end.minusDays(TrendCalculator.RATE_DAYS))
        val anchor = trend.lastOrNull()?.takeIf { it.date.isAfter(end.minusDays(TrendCalculator.RATE_DAYS)) }
        // Like the weekly rate: from the week's last weigh-in back 4 weeks, unknown until the trend reaches that far.
        val fourWeekKg = anchor?.let { a -> TrendCalculator.trendAt(trend, a.date.minusDays(FOUR_WEEKS))?.let { a.trendKg - it } }
        val fourWeekPct = if (anchor != null && fourWeekKg != null) fourWeekKg / anchor.trendKg * 100 else null
        val waistChange = waistChange(waist, end)
        val (status, flag) = judge(policy, rate, previous, fourWeekPct, waistChange)
        return CheckinEvaluation(status, flag, rate, previous, fourWeekPct, fourWeekKg, waistChange, anchor?.trendKg ?: trend.lastOrNull()?.trendKg)
    }

    /**
     * The flag from the numbers, so each edge can be tested exactly. A null input means it couldn't be measured.
     * @param ratePct this week's rate; [previousRatePct] last week's
     */
    fun judge(policy: GoalPolicy, ratePct: Double?, previousRatePct: Double?, fourWeekPct: Double?, waistChangeCm: Double?): Pair<CheckinStatus, CheckinFlag?> {
        val twoWeeks = if (ratePct != null && previousRatePct != null) listOf(ratePct, previousRatePct) else null
        val flag: CheckinFlag? = when (policy.goal) {
            // "Under half the pace": losing less than half the planned rate, gaining included.
            Goal.FAT_LOSS -> when {
                twoWeeks == null -> return CheckinStatus.NOT_ENOUGH_DATA to null
                twoWeeks.all { it > -policy.lossRatePctPerWeek * SLOW_LOSS_SHARE } -> CheckinFlag.LOSS_SLOW
                else -> null
            }
            Goal.RECOMP -> when {
                fourWeekPct == null -> return CheckinStatus.NOT_ENOUGH_DATA to null
                abs(fourWeekPct) >= FLAT_TREND_PCT -> null
                waistChangeCm == null -> return CheckinStatus.NEEDS_WAIST to null
                abs(waistChangeCm) < FLAT_WAIST_CM -> CheckinFlag.FLAT
                else -> null
            }
            Goal.MAINTAIN -> when {
                fourWeekPct == null -> return CheckinStatus.NOT_ENOUGH_DATA to null
                abs(fourWeekPct) > DRIFT_PCT -> CheckinFlag.DRIFT
                else -> null
            }
            Goal.LEAN_BULK -> when {
                twoWeeks == null -> return CheckinStatus.NOT_ENOUGH_DATA to null
                twoWeeks.all { it < GAIN_SLOW_PCT } -> CheckinFlag.GAIN_SLOW
                twoWeeks.all { it > GAIN_FAST_PCT } -> CheckinFlag.GAIN_FAST
                else -> null
            }
        }
        return (if (flag == null) CheckinStatus.ON_TRACK else CheckinStatus.FLAGGED) to flag
    }

    /** The weekly rate as of [date], in % a week: anchored at the last weigh-in in the 7 days to it, else unknown. */
    fun rateAsOf(points: List<TrendPoint>, date: LocalDate): Double? {
        val upTo = points.filter { !it.date.isAfter(date) }
        val rate = TrendCalculator.weeklyRate(upTo) ?: return null
        return rate.pctPerWeek.takeIf { rate.asOf.isAfter(date.minusDays(TrendCalculator.RATE_DAYS)) }
    }

    /** The change between the waist readings nearest each end of the 4 weeks to [end], each within a week of its end. */
    fun waistChange(waist: List<WaistReading>, end: LocalDate): Double? {
        val start = end.minusDays(FOUR_WEEKS)
        fun nearest(day: LocalDate, from: LocalDate, to: LocalDate) = waist
            .filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
            .minByOrNull { abs(it.date.toEpochDay() - day.toEpochDay()) }
        val last = nearest(end, end.minusDays(WAIST_NEAR_DAYS - 1), end) ?: return null
        val first = nearest(start, start.minusDays(WAIST_NEAR_DAYS), start.plusDays(WAIST_NEAR_DAYS)) ?: return null
        return last.cm - first.cm
    }
}
