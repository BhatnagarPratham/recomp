package app.recomp.ui.checkin

import app.recomp.data.db.WeekRecordRow
import app.recomp.data.repo.ActivityWeek
import app.recomp.data.repo.BodyWeek
import app.recomp.data.repo.FoodWeek
import app.recomp.data.repo.TrainingWeek
import app.recomp.data.repo.WeekPlan
import app.recomp.domain.body.WeekScan
import app.recomp.domain.model.LoadType
import app.recomp.domain.review.FinishOutlook
import app.recomp.domain.review.OriginShares
import app.recomp.domain.review.WeeklyReview
import app.recomp.ui.body.BodyText
import app.recomp.ui.components.Labels
import app.recomp.ui.onboarding.GoalText
import java.time.LocalDate
import kotlin.math.roundToInt

/** The weekly review's sentences (HM-03, PRD §12.4). Information about the week, never a verdict on a single day. */
object ReviewText {
    fun title(weekStart: LocalDate, inProgress: Boolean): String = if (inProgress) "This week" else CheckinText.title(weekStart)

    const val SO_FAR = "So far this week. The check-in for it comes on Monday."

    // Food

    fun proteinDays(food: FoodWeek): String? = when {
        food.protein.counted > 0 -> "Protein target hit on ${food.protein.hit} of ${food.protein.counted} ${days(food.protein.counted)} (${WeeklyReview.PROTEIN_HIT_PERCENT}% or more)"
        food.average != null -> "No protein target this week: the protein ramp is measuring what you usually eat"
        else -> null
    }

    /** "Ordered 18% · packaged 6% of calories". */
    fun origins(o: OriginShares): String = "Ordered ${pct(o.orderedPct)} · packaged ${pct(o.packagedPct)} of calories"

    private fun pct(v: Double) = "${v.roundToInt()}%"

    private fun days(n: Int) = if (n == 1) "day" else "days"

    // Training

    /**
     * "Strength 2 of 3 days · Yoga class 1 of 1". An extra session without set days counts without a plan, and so does
     * a past week, whose plan isn't kept: "Strength 2 days · Yoga class 1".
     */
    fun plan(t: TrainingWeek): String {
        val target = t.strengthTarget
        val strength = if (target == null) "Strength ${t.strengthDays} ${days(t.strengthDays)}" else "Strength ${t.strengthDays} of $target ${days(target)}"
        return (listOf(strength) + t.extras.map { e -> e.planned?.takeIf { it > 0 }?.let { "${e.name} ${e.done} of $it" } ?: "${e.name} ${e.done}" }).joinToString(" · ")
    }

    const val PAST_PLAN = "What you did. Your weekly plan isn't kept for past weeks, so there's nothing to count it against."

    /** How many lifts' PRs the Training section lists before "and N more". */
    const val RECORDS_SHOWN = 5

    /**
     * The week's PRs, one line per lift in the order they were set: each kind of record with its latest value that week
     * (a later workout beats an earlier one), worded as the workout summary words them.
     */
    fun records(rows: List<WeekRecordRow>): List<String> = rows.groupBy { it.exercise }.map { (exercise, prs) ->
        val assisted = prs.first().loadType == LoadType.ASSISTED
        exercise + ": " + prs.associateBy { it.type }.values.sortedBy { it.type.ordinal }
            .joinToString(" · ") { "${Labels.record(it.type, assisted)} ${Labels.recordValue(it.type, it.value, it.loadG, assisted)}" }
    }

    fun moreRecords(n: Int): String = "and $n more ${if (n == 1) "lift" else "lifts"} with PRs"

    const val NO_RECORDS = "No PRs this week"

    fun stalled(names: List<String>): String = "Stall card: ${names.joinToString(", ")}"

    // Body

    fun trend(b: BodyWeek): String {
        val kg = b.trendKg ?: return BodyText.NO_TREND
        val change = b.changeKg
        return "Trend ${BodyText.kg(kg)} · " +
            when {
                change == null -> "no weigh-in this week"
                BodyText.roundsToZero(change) -> "no change this week"
                else -> "${BodyText.signed(change, "kg")} this week (${CheckinText.pct(b.changePct ?: 0.0)})"
            } +
            (b.pace?.let { ", ${BodyText.pace(it)}" } ?: "")
    }

    /** The week's waist and its change against a reading at most 4 weeks older, named by that reading's date. */
    fun waist(b: BodyWeek): String? {
        val cm = b.waistCm ?: return null
        val since = b.waistSince
        return "Waist ${BodyText.cm(cm)}" + (b.waistChangeCm?.takeIf { since != null }?.let { " (${BodyText.signed(it, "cm")} since ${BodyText.shortDate(since!!)})" } ?: "")
    }

    /** Why a week before the current plan has no pace or finish line (PRD §12.4). Null for the current plan's weeks. */
    fun earlierPlan(p: WeekPlan?): String? = when (p) {
        null, WeekPlan.Current -> null
        is WeekPlan.EarlierGoal ->
            "This week's goal was ${Labels.goal(p.goal)}, before your current one from ${BodyText.shortDate(p.since)}, so it isn't judged against today's pace or finish line."
        is WeekPlan.EarlierPlan ->
            "This week was before your current plan from ${BodyText.shortDate(p.since)}. The plan then isn't kept, so there's no pace or finish line for it."
    }

    fun scan(s: WeekScan): String {
        val change = s.change
        return "Scan ${BodyText.shortDate(s.scan.date)}: " + when {
            !s.scan.onProtocol -> "off protocol, so it isn't compared"
            change == null -> "the first on-protocol scan, nothing to compare yet"
            else -> BodyText.scanChange(change)
        }
    }

    fun steps(a: ActivityWeek): String? =
        a.stepsAvg?.takeIf { a.stepsDays > 0 }?.let { "Steps: ${GoalText.n(it)} a day over ${a.stepsDays} ${days(a.stepsDays)}" }

    fun sleep(a: ActivityWeek): String? =
        a.sleepAvgHours?.takeIf { a.sleepDays > 0 }?.let { "Sleep: ${BodyText.oneDecimal(it)} h a night over ${a.sleepDays} ${if (a.sleepDays == 1) "night" else "nights"}" }

    // The finish line (TRD §5.8)

    fun outlook(o: FinishOutlook): String = when (o) {
        is FinishOutlook.Around ->
            "At this rate: around ${GoalText.around(o.date)} (goal " + (if (o.bodyFat) "${GoalText.d(o.goal)}% body fat" else "${GoalText.d(o.goal)} kg") + ")"
        is FinishOutlook.Reached -> if (o.bodyFat) "You've reached your body-fat goal (${GoalText.d(o.goal)}%)" else "You've reached your goal weight (${GoalText.d(o.goal)} kg)"
        FinishOutlook.MovingAway -> "Moving away from your goal"
        FinishOutlook.Steady -> "Holding steady, so there's no date at this rate"
        FinishOutlook.FarOff -> "More than two years away at this rate"
        is FinishOutlook.ByDate -> "At this rate: ${BodyText.signed(o.changeKg, "kg")} by ${BodyText.shortDate(o.date)}"
        is FinishOutlook.NeedsData ->
            if (o.bodyFat) "The date appears from three on-protocol scans" else "The date appears with a weigh-in this week, once your trend spans a week"
    }

    // The calendar (HM-04)

    const val CALENDAR_KEY = "● strength · ○ extra session · tinted: food logged"
}
