package app.recomp.ui.goal

import app.recomp.domain.nutrition.Goal
import app.recomp.domain.persona.FinishLine
import app.recomp.domain.persona.GoalPlan
import app.recomp.domain.persona.GoalStatus
import app.recomp.domain.persona.Guardrails
import app.recomp.domain.persona.MaintenanceBreak
import app.recomp.ui.onboarding.GoalText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The Goal reached card and the maintenance break's lines (GL-06, PRD §12.15). */
object GoalCardText {
    /** "Mon 3 Nov". */
    fun day(date: LocalDate, locale: Locale = Locale.getDefault()): String = date.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))

    /** "your recomp", "your fat loss": the goal as a noun, which the card titles aren't ("Lose fat"). */
    fun yourGoal(goal: Goal): String = "your " + when (goal) {
        Goal.FAT_LOSS -> "fat loss"
        Goal.RECOMP -> "recomp"
        Goal.MAINTAIN -> "maintenance"
        Goal.LEAN_BULK -> "lean bulk"
    }

    /** "You reached 78 kg on the trend". Weights go through the shared formatter, so M20 changes units in one place. */
    fun reached(plan: GoalPlan): String = when (val line = plan.finishLine) {
        FinishLine.GoalWeight -> plan.goalWeightKg?.let { "You reached ${GoalText.d(it)} kg on the trend" } ?: "You reached your goal weight on the trend"
        is FinishLine.BodyFat -> "You reached ${GoalText.d(line.pct)}% body fat"
        is FinishLine.ByDate -> "You reached your finish date, ${day(line.date)}"
        null -> "You reached your finish line"
    }

    /** The suggested next phase (PRD §4.7), under the title. */
    fun next(status: GoalStatus.Reached): String {
        val until = status.maintenanceUntil
        return when {
            until != null -> "At maintenance until ${day(until)}. Then choose a new goal."
            status.maintenanceDone -> "Your ${MaintenanceBreak.REACHED_WEEKS} weeks at maintenance are done, and your targets stay there until you choose. Pick a new goal, or keep going with ${yourGoal(status.plan.goal)}."
            Guardrails.isDeficitGoal(status.plan.goal) -> "Next: ${MaintenanceBreak.REACHED_WEEKS} weeks at maintenance, then choose a new goal."
            status.plan.goal == Goal.LEAN_BULK -> "Next: maintain for a while, or start a fat-loss phase."
            else -> "Choose what comes next."
        }
    }

    const val START_MAINTENANCE = "Start 2 weeks at maintenance"
    const val NEW_GOAL = "Choose a new goal"
    const val KEEP_GOING = "Keep going"
    const val KEEP_GOING_NOTE = "Keep going clears the finish line. You can set a new one in Settings → Goal."

    /** "Maintenance break · back to your recomp on Mon 3 Nov". */
    fun onBreak(status: GoalStatus.OnBreak): String = "Maintenance break · back to ${yourGoal(status.plan.goal)} on ${day(status.until)}"

    const val END_EARLY = "End early"

    fun breakTitle(status: GoalStatus.BreakDue): String = "${status.weeksIn} weeks in a deficit"

    fun breakBody(status: GoalStatus.BreakDue): String =
        "A week or two at maintenance makes a long deficit easier to keep up: hunger eases and training recovers. " +
            "Your targets move to maintenance from today, and ${yourGoal(status.plan.goal)} resumes on its own afterwards."

    fun weeks(n: Int): String = if (n == 1) "1 week" else "$n weeks"

    const val NOT_NOW = "Not now"
    const val NOT_NOW_NOTE = "Not now asks again in 4 weeks."

    /** Settings → Goal, under the goal's summary. */
    fun settingsLine(status: GoalStatus): String? = when (status) {
        is GoalStatus.OnBreak -> onBreak(status)
        is GoalStatus.Reached -> status.maintenanceUntil?.let { "Finish line reached · at maintenance until ${day(it)}" } ?: "Finish line reached"
        is GoalStatus.BreakDue, GoalStatus.None -> null
    }
}
