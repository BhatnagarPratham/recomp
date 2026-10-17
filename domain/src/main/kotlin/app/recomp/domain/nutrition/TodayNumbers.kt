package app.recomp.domain.nutrition

import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt

/** Food eaten on one day. Zero until food logging arrives in M3b. */
data class Intake(val kcal: Int, val proteinG: Int) {
    companion object {
        val NONE = Intake(0, 0)
    }
}

/** Today's main number (GL-07, PRD §4.8). Being over a target is a plain number, never a failure state. */
sealed interface MainNumber {
    /** Fat loss: calories left; negative once over. */
    data class CaloriesLeft(val left: Int, val target: Int) : MainNumber

    /** Recomp: protein left against today's (ramped) target, which is null during the baseline week. */
    data class ProteinLeft(val left: Int?, val target: Int?, val eaten: Int) : MainNumber

    /** Maintain: calories against the ±5% band. */
    data class CaloriesInRange(val band: IntRange, val eaten: Int) : MainNumber {
        val belowBy: Int get() = max(0, band.first - eaten)
        val aboveBy: Int get() = max(0, eaten - band.last)
        val inRange: Boolean get() = eaten in band
    }

    /** Lean bulk: calories still to eat, because under-eating is the usual failure. */
    data class CaloriesToEat(val left: Int, val target: Int) : MainNumber
}

/** One logged day for the 7-day average, with the targets that were in force that day. */
data class LoggedDay(val date: LocalDate, val intake: Intake, val kcalTarget: Int, val proteinTargetG: Int?)

data class WeekAverage(val kcal: Int, val proteinG: Int, val kcalTarget: Int, val proteinTargetG: Int?, val daysLogged: Int)

/** TRD §5.9: the main number by goal, and the daily target judged weekly (GL-10). */
object TodayNumbers {
    const val AVERAGE_DAYS = 7L

    fun main(goal: Goal, kcalTarget: Int, protein: ProteinGoal, eaten: Intake): MainNumber = when (goal) {
        Goal.FAT_LOSS -> MainNumber.CaloriesLeft(kcalTarget - eaten.kcal, kcalTarget)
        Goal.RECOMP -> {
            val target = protein.todayG
            MainNumber.ProteinLeft(target?.let { max(0, it - eaten.proteinG) }, target, eaten.proteinG)
        }
        Goal.MAINTAIN -> MainNumber.CaloriesInRange(EnergyBalance.band(kcalTarget), eaten.kcal)
        Goal.LEAN_BULK -> MainNumber.CaloriesToEat(max(0, kcalTarget - eaten.kcal), kcalTarget)
    }

    /**
     * The last 7 complete days (today is left out), over days with food logged only, against the mean of
     * those days' targets. Null when none were logged.
     */
    fun weekAverage(today: LocalDate, days: List<LoggedDay>): WeekAverage? {
        val from = today.minusDays(AVERAGE_DAYS)
        val window = days.filter { !it.date.isBefore(from) && it.date.isBefore(today) }.distinctBy { it.date }
        if (window.isEmpty()) return null
        val proteinTargets = window.mapNotNull { it.proteinTargetG }
        return WeekAverage(
            kcal = window.map { it.intake.kcal }.average().roundToInt(),
            proteinG = window.map { it.intake.proteinG }.average().roundToInt(),
            kcalTarget = window.map { it.kcalTarget }.average().roundToInt(),
            proteinTargetG = proteinTargets.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            daysLogged = window.size,
        )
    }
}
