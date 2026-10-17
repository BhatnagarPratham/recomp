package app.recomp.domain.training

import app.recomp.domain.model.SessionKind
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** A completed session, as the weekly plan sees it. */
data class PlanSession(val logDate: LocalDate, val kind: SessionKind, val routineId: Long?)

/** A configured extra session (PRD §7.6) and the weekdays it's scheduled on. No days means "whenever". */
data class ExtraPlan(val routineId: Long, val days: Set<DayOfWeek>)

data class ExtraProgress(val routineId: Long, val done: Int, val planned: Int)

data class WeekProgress(val strengthDays: Int, val strengthTarget: Int, val extras: List<ExtraProgress>)

/**
 * The weekly plan (RT-02, YG-02): a target number of strength days plus the configured extra sessions.
 * Weeks start on Monday (ISO 8601).
 */
object WeeklyPlan {
    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun progress(today: LocalDate, sessions: List<PlanSession>, strengthTarget: Int, extras: List<ExtraPlan>): WeekProgress {
        val start = weekStart(today)
        val week = sessions.filter { !it.logDate.isBefore(start) && it.logDate.isBefore(start.plusDays(7)) }
        // A strength *day* counts once, however many strength sessions it had.
        val strengthDays = week.filter { it.kind == SessionKind.STRENGTH }.map { it.logDate }.distinct().size
        return WeekProgress(
            strengthDays = strengthDays,
            strengthTarget = strengthTarget,
            extras = extras.map { plan ->
                ExtraProgress(plan.routineId, done = week.count { it.routineId == plan.routineId }, planned = plan.days.size)
            },
        )
    }

    /** Extra sessions scheduled for [today] that haven't been logged yet today. */
    fun dueToday(today: LocalDate, sessions: List<PlanSession>, extras: List<ExtraPlan>): List<Long> =
        extras.filter { plan ->
            today.dayOfWeek in plan.days && sessions.none { it.logDate == today && it.routineId == plan.routineId }
        }.map { it.routineId }
}
