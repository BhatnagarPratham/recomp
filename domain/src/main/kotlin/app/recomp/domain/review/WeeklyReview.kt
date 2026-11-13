package app.recomp.domain.review

import app.recomp.domain.food.Origin
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.LoggedDay
import app.recomp.domain.persona.FinishLine
import app.recomp.domain.persona.GoalPlan
import app.recomp.domain.persona.Projection
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs

/** Protein days hit (PRD §12.4): days at 95% or more of that day's target, out of the days that had one. */
data class ProteinDays(val hit: Int, val counted: Int)

/** Each origin's share of the week's calories (NU-15, HM-03), in %. */
data class OriginShares(val totalKcal: Double, val orderedPct: Double, val packagedPct: Double)

/** Where the finish line is at the trend's current rate (PRD §12.4, TRD §5.8). */
sealed interface FinishOutlook {
    /** "At this rate: around mid-March (goal 78 kg)". [goal] is kg, or body fat % for a body-fat finish line. */
    data class Around(val date: LocalDate, val goal: Double, val bodyFat: Boolean = false) : FinishOutlook

    /** The trend is at the goal or past it, the way the goal is going. M19 adds the Goal reached card. */
    data class Reached(val goal: Double, val bodyFat: Boolean = false) : FinishOutlook

    /** The trend is going the other way. */
    data object MovingAway : FinishOutlook

    /** Barely moving: a date would be meaningless. */
    data object Steady : FinishOutlook

    /** More than two years out at this rate. */
    data object FarOff : FinishOutlook

    /** A date finish line: the change expected by then. */
    data class ByDate(val date: LocalDate, val changeKg: Double) : FinishOutlook

    /** Not enough trend yet for a rate, or ([bodyFat]) under three on-protocol scans for a body-fat finish line. */
    data class NeedsData(val bodyFat: Boolean = false) : FinishOutlook
}

/** A day of the consistency calendar (HM-04): what happened, never what didn't. */
data class CalendarDay(val date: LocalDate, val strength: Boolean, val extra: Boolean, val food: Boolean)

/** The weekly review's arithmetic (HM-03, HM-04). The repository supplies one week's or one month's rows. */
object WeeklyReview {
    const val PROTEIN_HIT_PERCENT = 95

    /** Under this many kg a week either way, the trend is holding steady. */
    const val STEADY_KG_PER_WEEK = 0.05

    /** The same for a body-fat finish line, in points a week. */
    const val STEADY_BODY_FAT_PCT_PER_WEEK = 0.1

    /** Beyond this many weeks, the date isn't worth naming. */
    const val FAR_OFF_WEEKS = 104.0

    /**
     * Days whose protein reached 95% of that day's target. A day without a target (the protein ramp's baseline week,
     * which only measures) isn't counted at all, so it neither hits nor misses.
     */
    fun proteinDays(days: List<LoggedDay>): ProteinDays {
        val counted = days.filter { it.proteinTargetG != null }
        return ProteinDays(hit = counted.count { it.intake.proteinG * 100 >= it.proteinTargetG!! * PROTEIN_HIT_PERCENT }, counted = counted.size)
    }

    /** Shares of [kcalByOrigin]'s total. Null when nothing was eaten. */
    fun originShares(kcalByOrigin: Map<Origin, Double>): OriginShares? {
        val total = kcalByOrigin.values.sum()
        if (total <= 0) return null
        fun pct(o: Origin) = (kcalByOrigin[o] ?: 0.0) / total * 100
        return OriginShares(total, pct(Origin.ORDERED), pct(Origin.PACKAGED))
    }

    /**
     * The finish line at the trend's rate, as of [asOf] (TRD §5.8): the goal weight from [trendKg] at [kgPerWeek], a
     * date finish line's expected change, or a body-fat finish line from the scans' slope ([bodyFatPct], and
     * [bodyFatPctPerWeek] from three or more on-protocol scans). Null for a plan without a finish line.
     */
    fun outlook(
        plan: GoalPlan,
        asOf: LocalDate,
        trendKg: Double?,
        kgPerWeek: Double?,
        bodyFatPct: Double? = null,
        bodyFatPctPerWeek: Double? = null,
    ): FinishOutlook? = when (val line = plan.finishLine) {
        null -> null
        FinishLine.GoalWeight -> {
            val goal = plan.goalWeightKg
            when {
                goal == null -> null
                trendKg != null && reached(trendKg, goal, plan.goal) -> FinishOutlook.Reached(goal)
                trendKg == null || kgPerWeek == null -> FinishOutlook.NeedsData()
                else -> towards(trendKg, goal, kgPerWeek, STEADY_KG_PER_WEEK, asOf, bodyFat = false)
            }
        }
        is FinishLine.ByDate -> when {
            line.date.isBefore(asOf) -> null
            kgPerWeek == null -> FinishOutlook.NeedsData()
            else -> FinishOutlook.ByDate(line.date, Projection.changeBy(asOf, line.date, kgPerWeek))
        }
        is FinishLine.BodyFat -> if (bodyFatPct != null && bodyFatPct <= line.pct) {
            // A body-fat finish line is always a way down.
            FinishOutlook.Reached(line.pct, bodyFat = true)
        } else if (bodyFatPct == null || bodyFatPctPerWeek == null) {
            FinishOutlook.NeedsData(bodyFat = true)
        } else {
            towards(bodyFatPct, line.pct, bodyFatPctPerWeek, STEADY_BODY_FAT_PCT_PER_WEEK, asOf, bodyFat = true)
        }
    }

    /**
     * At the goal weight or past it, the way [kind] goes: down for fat loss and recomp, up for a lean bulk. Maintain has
     * no way, so only the goal itself counts.
     */
    private fun reached(trendKg: Double, goalKg: Double, kind: Goal): Boolean = when (kind) {
        Goal.LEAN_BULK -> trendKg >= goalKg
        Goal.FAT_LOSS, Goal.RECOMP -> trendKg <= goalKg
        Goal.MAINTAIN -> trendKg == goalKg
    }

    private fun towards(current: Double, goal: Double, perWeek: Double, steady: Double, asOf: LocalDate, bodyFat: Boolean): FinishOutlook {
        if (abs(perWeek) < steady) return FinishOutlook.Steady
        val weeks = Projection.weeksTo(current, goal, perWeek) ?: return FinishOutlook.MovingAway
        if (weeks > FAR_OFF_WEEKS) return FinishOutlook.FarOff
        return FinishOutlook.Around(Projection.finishDate(asOf, weeks), goal, bodyFat)
    }

    /**
     * [month] as rows of seven days, Monday first; days of the neighbouring months are null. A day is marked only for
     * what happened: a strength workout, an extra session, food logged. There's no streak and no missed-day mark.
     */
    fun calendar(month: YearMonth, strength: Set<LocalDate>, extra: Set<LocalDate>, food: Set<LocalDate>): List<List<CalendarDay?>> {
        val first = month.atDay(1)
        val start = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val end = month.atEndOfMonth().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        val days = (0..ChronoUnit.DAYS.between(start, end)).map { start.plusDays(it) }
        return days.map { d -> if (YearMonth.from(d) != month) null else CalendarDay(d, d in strength, d in extra, d in food) }.chunked(7)
    }

    /** The month a week's calendar shows: the month of its Thursday, as ISO weeks do. */
    fun monthOf(weekStart: LocalDate): YearMonth = YearMonth.from(weekStart.plusDays(3))
}
