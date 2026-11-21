package app.recomp.domain.persona

import app.recomp.domain.checkin.CheckinFlags
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.trend.TrendCalculator
import java.time.LocalDate

/** Whether a goal's finish line is reached (PRD §4.7, TRD §5.8). */
object FinishLines {
    /**
     * On the trend, never on one weigh-in: [trendKg] is the smoothed weight (TRD §5.2). A goal weight is reached
     * when the trend crosses it in the goal's direction, a body-fat target when a scan reads at or under it, and
     * a date on the day itself. Maintain has no direction, so only a date can finish it.
     */
    fun reached(plan: GoalPlan, trendKg: Double?, bodyFatPct: Double?, today: LocalDate): Boolean = when (val line = plan.finishLine) {
        null -> false
        FinishLine.GoalWeight -> {
            val target = plan.goalWeightKg
            if (target == null || trendKg == null) false
            else when (plan.goal) {
                Goal.FAT_LOSS, Goal.RECOMP -> trendKg <= target
                Goal.LEAN_BULK -> trendKg >= target
                Goal.MAINTAIN -> false
            }
        }
        is FinishLine.BodyFat -> bodyFatPct != null && Guardrails.isDeficitGoal(plan.goal) && bodyFatPct <= line.pct
        is FinishLine.ByDate -> !today.isBefore(line.date)
    }
}

/**
 * The maintenance break (GL-06, PRD §12.15, TRD §5.8): after 12 weeks in a deficit without reaching the finish
 * line, 1 or 2 weeks at maintenance. The same mechanism runs the 2 weeks at maintenance after a reached goal.
 */
object MaintenanceBreak {
    const val DEFICIT_WEEKS = 12L
    const val SNOOZE_WEEKS = 4L

    /** The lengths offered after 12 weeks in a deficit. */
    val WEEKS = listOf(1, 2)

    /** After a reached goal (PRD §4.7). */
    const val REACHED_WEEKS = 2

    /** The 12 weeks count from the goal's start, or from the end of the last break. */
    fun countingFrom(plan: GoalPlan): LocalDate = plan.lastBreakEndedOn?.takeIf { it.isAfter(plan.startedOn) } ?: plan.startedOn

    /** `maintenance_break_due`, before *Not now* is considered. */
    fun due(plan: GoalPlan, today: LocalDate): Boolean =
        Guardrails.isDeficitGoal(plan.goal) && !plan.onBreak && plan.reachedOn == null && !today.isBefore(countingFrom(plan).plusWeeks(DEFICIT_WEEKS))

    /** Due, and not put off with *Not now* in the last 4 weeks. */
    fun suggested(plan: GoalPlan, today: LocalDate): Boolean = due(plan, today) && plan.breakSnoozedUntil?.let { today.isBefore(it) } != true

    fun start(plan: GoalPlan, today: LocalDate, weeks: Int): GoalPlan {
        require(weeks in 1..REACHED_WEEKS) { "A break is 1 or 2 weeks" }
        return plan.copy(breakUntil = today.plusWeeks(weeks.toLong()), breakSnoozedUntil = null)
    }

    /** *Not now*: asked again in 4 weeks. */
    fun snooze(plan: GoalPlan, today: LocalDate): GoalPlan = plan.copy(breakSnoozedUntil = today.plusWeeks(SNOOZE_WEEKS))

    /**
     * The goal resumes on its own on the first launch on or after the end date. Not after a reached goal: its
     * 2 weeks end with a choice, never with the deficit coming back by itself.
     */
    fun shouldResume(plan: GoalPlan, today: LocalDate): Boolean = plan.breakUntil?.let { !today.isBefore(it) } == true && plan.reachedOn == null

    /** The break ended on [on]: the end date for an automatic resume, the day itself for *End early*. */
    fun end(plan: GoalPlan, on: LocalDate): GoalPlan = plan.copy(breakUntil = null, lastBreakEndedOn = on)

    /**
     * No check-in flag or proposal for the week ending [weekEnd]: during a break, and afterwards until the flag's
     * whole window is back on the goal (2 weeks of rates for fat loss and a lean bulk, 4 weeks of trend for a
     * recomp and Maintain). Otherwise the first check-ins after a break would read its flat weeks as a stall.
     */
    fun flagsQuiet(plan: GoalPlan, weekEnd: LocalDate): Boolean {
        if (plan.onBreak) return true
        val ended = plan.lastBreakEndedOn ?: return false
        val window = when (plan.goal) {
            Goal.FAT_LOSS, Goal.LEAN_BULK -> 2 * TrendCalculator.RATE_DAYS
            Goal.RECOMP, Goal.MAINTAIN -> CheckinFlags.FOUR_WEEKS
        }
        return weekEnd.isBefore(ended.plusDays(window))
    }
}

/** What Today and the check-in show about the goal itself (GL-06). */
sealed interface GoalStatus {
    data object None : GoalStatus

    /**
     * The finish line is reached. [maintenanceUntil] is set while the 2 weeks at maintenance run, and
     * [maintenanceDone] once they're over; [offerMaintenance] is false for Maintain and once they've been taken.
     */
    data class Reached(val plan: GoalPlan, val maintenanceUntil: LocalDate?, val maintenanceDone: Boolean, val offerMaintenance: Boolean) : GoalStatus

    /** A maintenance break is running; the goal resumes on [until]. */
    data class OnBreak(val plan: GoalPlan, val until: LocalDate) : GoalStatus

    /** 12 weeks in a deficit: the check-in suggests a break. */
    data class BreakDue(val plan: GoalPlan, val weeksIn: Long) : GoalStatus
}

object GoalProgress {
    /** [plan] is the goal after the guardrails. Reached wins over a break, and a running break over a due one. */
    fun status(plan: GoalPlan, today: LocalDate): GoalStatus {
        val until = plan.breakUntil
        return when {
            plan.reachedOn != null -> {
                val running = until != null && today.isBefore(until)
                GoalStatus.Reached(
                    plan = plan,
                    maintenanceUntil = until.takeIf { running },
                    maintenanceDone = until != null && !running,
                    // Once taken, `breakUntil` stays set until you choose, so it's never offered twice.
                    offerMaintenance = until == null && plan.goal != Goal.MAINTAIN,
                )
            }
            until != null -> GoalStatus.OnBreak(plan, until)
            MaintenanceBreak.suggested(plan, today) ->
                GoalStatus.BreakDue(plan, java.time.temporal.ChronoUnit.WEEKS.between(MaintenanceBreak.countingFrom(plan), today))
            else -> GoalStatus.None
        }
    }

    /** The plan with the finish line marked reached today, or null when nothing changes. Not during a break. */
    fun markReached(plan: GoalPlan, trendKg: Double?, bodyFatPct: Double?, today: LocalDate): GoalPlan? =
        if (plan.reachedOn == null && !plan.onBreak && FinishLines.reached(plan, trendKg, bodyFatPct, today)) plan.copy(reachedOn = today) else null

    /** *Keep going*: the finish line is cleared, and any maintenance after it ends today. You can set a new one. */
    fun keepGoing(plan: GoalPlan, today: LocalDate): GoalPlan =
        plan.copy(finishLine = null, reachedOn = null, breakUntil = null, lastBreakEndedOn = if (plan.onBreak) today else plan.lastBreakEndedOn)
}

/** What a goal's energy balance does to the scale. */
enum class EnergySide { DEFICIT, MAINTENANCE, SURPLUS }

/** *What to expect* after a change of goal (PS-04). The UI turns each into one line. */
enum class Expectation {
    /** A cut to a bulk: water and glycogen come back. */
    DEFICIT_TO_SURPLUS,
    DEFICIT_TO_MAINTENANCE,
    MAINTENANCE_TO_SURPLUS,
    SURPLUS_TO_DEFICIT,
    MAINTENANCE_TO_DEFICIT,
    SURPLUS_TO_MAINTENANCE,
    /** Fat loss to a recomp: a gentler deficit. */
    GENTLER_DEFICIT,
    /** A recomp to fat loss: a larger deficit. */
    LARGER_DEFICIT,
}

/** A goal saved from the goal flow, against the one it replaces (PS-04). */
object GoalChange {
    fun side(goal: Goal): EnergySide = when (goal) {
        Goal.FAT_LOSS, Goal.RECOMP -> EnergySide.DEFICIT
        Goal.MAINTAIN -> EnergySide.MAINTENANCE
        Goal.LEAN_BULK -> EnergySide.SURPLUS
    }

    /** Null when the goal doesn't change. */
    fun expect(from: Goal, to: Goal): Expectation? = when {
        from == to -> null
        from == Goal.FAT_LOSS && to == Goal.RECOMP -> Expectation.GENTLER_DEFICIT
        from == Goal.RECOMP && to == Goal.FAT_LOSS -> Expectation.LARGER_DEFICIT
        else -> when (side(from) to side(to)) {
            EnergySide.DEFICIT to EnergySide.SURPLUS -> Expectation.DEFICIT_TO_SURPLUS
            EnergySide.DEFICIT to EnergySide.MAINTENANCE -> Expectation.DEFICIT_TO_MAINTENANCE
            EnergySide.MAINTENANCE to EnergySide.SURPLUS -> Expectation.MAINTENANCE_TO_SURPLUS
            EnergySide.SURPLUS to EnergySide.DEFICIT -> Expectation.SURPLUS_TO_DEFICIT
            EnergySide.MAINTENANCE to EnergySide.DEFICIT -> Expectation.MAINTENANCE_TO_DEFICIT
            EnergySide.SURPLUS to EnergySide.MAINTENANCE -> Expectation.SURPLUS_TO_MAINTENANCE
            else -> null
        }
    }

    /**
     * The plan to store when [chosen] replaces [stored]. A change of goal is a fresh start: it begins today, and
     * the reached mark, any break and its snooze are gone. A pace, goal-weight or finish-line edit keeps the
     * goal's start (the 12 weeks and "since you started" carry on) and its break history; it ends a running break,
     * because the edit's targets are the goal's own, and clears the reached mark so the new finish line is judged
     * afresh.
     */
    fun settle(stored: GoalPlan?, chosen: GoalPlan, today: LocalDate): GoalPlan =
        if (stored == null || stored.goal != chosen.goal) {
            chosen.copy(startedOn = today, reachedOn = null, breakUntil = null, lastBreakEndedOn = null, breakSnoozedUntil = null)
        } else {
            chosen.copy(
                startedOn = stored.startedOn,
                reachedOn = null,
                breakUntil = null,
                lastBreakEndedOn = if (stored.onBreak) today else stored.lastBreakEndedOn,
                breakSnoozedUntil = stored.breakSnoozedUntil,
            )
        }

    /**
     * The plan to store when the guardrails are re-applied without a goal-flow save (a body-data change, a
     * check-in answer): unchanged unless they moved the goal, which is a change of goal like any other.
     */
    fun afterGuardrails(stored: GoalPlan, guarded: GoalPlan, today: LocalDate): GoalPlan =
        if (stored.goal == guarded.goal) guarded else settle(stored, guarded, today)
}
