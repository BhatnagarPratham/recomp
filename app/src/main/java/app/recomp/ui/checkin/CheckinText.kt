package app.recomp.ui.checkin

import app.recomp.data.db.CheckinDecision
import app.recomp.data.db.WeeklyCheckinEntity
import app.recomp.data.repo.CheckinView
import app.recomp.domain.checkin.AdaptiveTdee
import app.recomp.domain.checkin.CheckinFlag
import app.recomp.domain.checkin.CheckinStatus
import app.recomp.domain.checkin.EstimateGap
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.WeekAverage
import app.recomp.domain.trend.WeightPace
import app.recomp.ui.body.BodyText
import app.recomp.ui.onboarding.GoalText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/** The check-in's sentences (PRD §12.1). A flag is information about the trend, never a failure. */
object CheckinText {
    fun title(weekStart: LocalDate): String = "Week of ${BodyText.shortDate(weekStart)}"

    /** "−0.4%", "+0.05%": a rate or change in % of body weight. */
    fun pct(value: Double): String = (if (value > 0) "+" else if (value < 0) "−" else "") + GoalText.d(abs(round2(value))) + "%"

    private fun round2(v: Double) = Math.round(v * 100) / 100.0

    private fun aWeek(value: Double) = "${pct(value)} a week"

    /** The goal's headline (PRD §4.8): what success means for it, from the trend. */
    fun headline(v: CheckinView): String {
        val e = v.evaluation
        val trend = e.trendKg?.let { "Trend ${BodyText.kg(it)}" }
        return when (v.plan.goal) {
            Goal.FAT_LOSS -> e.ratePct?.let { (if (it <= 0) "Losing " else "Gaining ") + aWeek(abs(it)).removePrefix("+") } ?: trend
            Goal.LEAN_BULK -> e.ratePct?.let { (if (it >= 0) "Gaining " else "Losing ") + aWeek(abs(it)).removePrefix("+") } ?: trend
            Goal.RECOMP -> listOfNotNull(e.waistChangeCm?.let { "Waist ${BodyText.signed(it, "cm")}" }, trend).joinToString(" · ").ifEmpty { null }
            Goal.MAINTAIN -> trend?.let { t -> e.fourWeekPct?.let { "$t, ${pct(it)} over 4 weeks" } ?: t }
        } ?: "No weigh-ins yet"
    }

    /** One verdict under the headline: on track, the flag that fired, or what's missing. */
    fun verdict(v: CheckinView): String {
        val e = v.evaluation
        val pace = WeightPace.of(v.plan.policy)
        return when (v.status) {
            CheckinStatus.ON_TRACK -> "On track"
            CheckinStatus.NOT_ENOUGH_DATA -> "Not enough data yet"
            CheckinStatus.NEEDS_WAIST -> "Trend weight is flat. Log your waist to see whether it's a recomp working or a stall."
            CheckinStatus.FLAGGED -> when (v.row.flag) {
                CheckinFlag.LOSS_SLOW -> "Losing slower than planned" + (e.ratePct?.let { ": ${aWeek(it)} against ${pct(pace.minPctPerWeek)}" } ?: "")
                CheckinFlag.FLAT -> "Trend weight and waist both flat for 4 weeks" +
                    listOfNotNull(e.fourWeekPct?.let(::pct), e.waistChangeCm?.let { BodyText.signed(it, "cm") }).joinToString(" and ").let { if (it.isEmpty()) "" else ": $it" }
                CheckinFlag.DRIFT -> "Drifting from your weight" + (e.fourWeekPct?.let { ": ${pct(it)} over 4 weeks, beyond ±1%" } ?: "")
                CheckinFlag.GAIN_SLOW -> "Gaining slower than planned" + (e.ratePct?.let { ": ${aWeek(it)} against ${range(pace)}" } ?: "")
                CheckinFlag.GAIN_FAST -> "Gaining faster than planned" + (e.ratePct?.let { ": ${aWeek(it)} against ${range(pace)}" } ?: "")
                null -> "On track"
            }
        }
    }

    private fun range(p: WeightPace) = "${pct(p.minPctPerWeek)} to ${pct(p.maxPctPerWeek)}"

    /** "Calories 2,050 of 2,090 · protein 118 of 125 g · 6 days logged". */
    fun averages(w: WeekAverage?): String {
        if (w == null) return "No food logged this week"
        val protein = w.proteinTargetG?.let { "protein ${w.proteinG} of $it g" } ?: "protein ${w.proteinG} g"
        return "Calories ${GoalText.n(w.kcal)} of ${GoalText.kcal(w.kcalTarget)} · $protein · ${days(w.daysLogged)} logged"
    }

    private fun days(n: Int) = if (n == 1) "1 day" else "$n days"

    /** The week's trend line: its rate against the pace. */
    fun trend(v: CheckinView): String {
        val e = v.evaluation
        val kg = e.trendKg ?: return BodyText.NO_TREND
        val rate = e.ratePct?.let { "${aWeek(it)}, ${BodyText.pace(WeightPace.of(v.plan.policy))}" } ?: BodyText.RATE_PENDING
        return "${BodyText.kg(kg)} · $rate"
    }

    fun waist(v: CheckinView): String =
        v.evaluation.waistChangeCm?.let { "Waist ${BodyText.signed(it, "cm")} over 4 weeks" } ?: "Waist: needs a reading near each end of the last 4 weeks"

    /** "About 2,480 kcal a day, from 21 days of food and weigh-ins (the formula said 2,610)". */
    fun estimate(v: CheckinView): String = when (v.gap) {
        null -> "About ${GoalText.kcal(round10(v.row.tdeeNew))} a day, from ${v.row.windowDays} days of food and weigh-ins (the formula said ${GoalText.n(round10(v.formulaTdee))})"
        EstimateGap.WEIGH_INS -> "Needs more weigh-ins: ${v.row.weighIns} in the last ${AdaptiveTdee.WEEKLY_WINDOW_DAYS} days, at least ${AdaptiveTdee.WEEKLY_MIN_WEIGH_INS} " +
            "(or ${AdaptiveTdee.DAILY_MIN_WEIGH_INS} in ${AdaptiveTdee.DAILY_WINDOW_DAYS} days)"
        EstimateGap.FOOD_DAYS, EstimateGap.HISTORY -> "Needs ${v.row.windowDays} days with food and weigh-ins: ${v.row.loggedDays} so far"
    }

    /** What the estimate moved from, or what the missing data means. */
    fun estimateDetail(v: CheckinView): String = when (v.gap) {
        null -> "This window measured ${GoalText.kcal(v.row.tdeeObs ?: v.row.tdeeNew)}. The estimate moves halfway there each week, at most ${AdaptiveTdee.MAX_STEP_KCAL.toInt()} kcal."
        EstimateGap.FOOD_DAYS -> "At least ${AdaptiveTdee.minLoggedDays(v.row.windowDays)} of the ${v.row.windowDays} days need food logged. Days marked incomplete are left out."
        EstimateGap.HISTORY -> "Your weigh-ins need to cover all ${v.row.windowDays} days."
        EstimateGap.WEIGH_INS -> "Weigh in daily, or at least weekly."
    }

    /** "Lower your target to 1,960 kcal (now 2,090)". */
    fun proposal(proposedKcal: Int, currentKcal: Int): String =
        "${if (proposedKcal < currentKcal) "Lower" else "Raise"} your target to ${GoalText.kcal(proposedKcal)} (now ${GoalText.n(currentKcal)})"

    fun override(kcal: Int): String = "Your own target of ${GoalText.kcal(kcal)} stays if you accept. Use the new target replaces it."

    /** A flag fired but the target barely moves. */
    const val WITHIN_50 = "The new estimate keeps your target within 50 kcal, so there's nothing to change."

    /** What became of a decided week. */
    fun decision(row: WeeklyCheckinEntity, writtenKcal: Int?): String? {
        val on = row.decidedAt?.let { " on ${BodyText.shortDate(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate())}" } ?: ""
        return when (row.decision) {
            CheckinDecision.PENDING -> null
            CheckinDecision.ACCEPTED -> "You accepted the new target$on" + (writtenKcal?.let { ": ${GoalText.kcal(it)}." } ?: ".")
            CheckinDecision.ADJUSTED -> "You set the new target$on" + (writtenKcal?.let { ": ${GoalText.kcal(it)}." } ?: ".")
            CheckinDecision.KEPT -> "You kept your target$on."
            CheckinDecision.LAPSED -> if (row.proposedKcal != null) "Not answered before the next check-in, so your target stayed." else null
        }
    }

    /** Today's card, one line under its title. */
    fun cardLine(row: WeeklyCheckinEntity): String = title(row.weekStart) + " · " + when {
        row.pending && row.proposedKcal != null -> "a new target to look at"
        row.tdeeObs != null -> "maintenance about ${GoalText.kcal(round10(row.tdeeNew))}"
        else -> "not enough data for an estimate yet"
    }

    private fun round10(kcal: Int): Int = Math.round(kcal / 10.0).toInt() * 10
}
