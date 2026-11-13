package app.recomp.ui.checkin

import app.recomp.data.db.WeekRecordRow
import app.recomp.data.repo.ActivityWeek
import app.recomp.data.repo.BodyWeek
import app.recomp.data.repo.FoodWeek
import app.recomp.data.repo.TrainingWeek
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

    /** "Strength 2 of 3 days · Yoga class 1 of 1". An extra session without set days counts without a plan. */
    fun plan(t: TrainingWeek): String =
        (listOf("Strength ${t.strengthDays} of ${t.strengthTarget} ${days(t.strengthTarget)}") +
            t.extras.map { if (it.planned > 0) "${it.name} ${it.done} of ${it.planned}" else "${it.name} ${it.done}" }).joinToString(" · ")

    /** A PR as the workout summary says it: the exercise, the kind of record, and its value. */
    fun record(r: WeekRecordRow): String {
        val assisted = r.loadType == LoadType.ASSISTED
        return "${r.exercise}: ${Labels.record(r.type, assisted)}, ${Labels.recordValue(r.type, r.value, r.loadG, assisted)}"
    }

    const val NO_RECORDS = "No PRs this week"

    fun stalled(names: List<String>): String = "Stall card: ${names.joinToString(", ")}"

    // Body

    fun trend(b: BodyWeek): String {
        val kg = b.trendKg ?: return BodyText.NO_TREND
        val change = b.changeKg
        return "Trend ${BodyText.kg(kg)} · " +
            (if (change == null) "no weigh-in this week" else "${BodyText.signed(change, "kg")} this week (${CheckinText.pct(b.changePct ?: 0.0)})") +
            (b.pace?.let { ", ${BodyText.pace(it)}" } ?: "")
    }

    fun waist(b: BodyWeek): String? {
        val cm = b.waistCm ?: return null
        return "Waist ${BodyText.cm(cm)}" + (b.waistChangeCm?.let { " (${BodyText.signed(it, "cm")} since the last)" } ?: "")
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
            "At this rate: around ${GoalText.around(o.date)} (goal " + (if (o.bodyFat) "${BodyText.oneDecimal(o.goal)}% body fat" else BodyText.kg(o.goal)) + ")"
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
