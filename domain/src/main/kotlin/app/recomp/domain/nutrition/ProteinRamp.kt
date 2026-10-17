package app.recomp.domain.nutrition

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The ramp's stored state (TRD §5.3). [countFrom] is the day it was turned on; the baseline is locked once found. */
data class RampState(val countFrom: LocalDate, val baselineEnd: LocalDate? = null, val baselineMeanG: Int? = null)

/** Where the baseline week stands. */
sealed interface Baseline {
    /** No food logged since [RampState.countFrom]. */
    data object NotStarted : Baseline

    /** A window that hasn't finished yet. */
    data class Measuring(val start: LocalDate, val daysLogged: Int) : Baseline

    data class Done(val end: LocalDate, val meanG: Int) : Baseline
}

/** Today's protein target (NU-03). */
sealed interface ProteinGoal {
    val fullG: Int

    /** Ramp off, finished, or the baseline already meets the full target. */
    data class Full(override val fullG: Int) : ProteinGoal

    /** Ramp on and nothing logged yet: the full target is shown with "the ramp starts when you log food". */
    data class NotStarted(override val fullG: Int) : ProteinGoal

    /** The baseline week: protein is shown but has no target. */
    data class Measuring(override val fullG: Int, val daysLogged: Int) : ProteinGoal

    data class Ramping(override val fullG: Int, val targetG: Int, val week: Int) : ProteinGoal

    /** The number to aim for today, or null while measuring. */
    val todayG: Int?
        get() = when (this) {
            is Full -> fullG
            is NotStarted -> fullG
            is Measuring -> null
            is Ramping -> targetG
        }
}

/**
 * Protein ramp (NU-03, TRD §5.3): a baseline week measures what you eat now, then the target rises
 * 15 g a week until it reaches the full target.
 */
object ProteinRamp {
    const val STEP_G = 15
    const val WINDOW_DAYS = 7L
    const val MIN_LOGGED_DAYS = 5

    /**
     * Finds the baseline week. The window starts on the first logged day on or after [countFrom] and
     * runs 7 days; a finished window with fewer than 5 logged days slides to the next logged day.
     * @param proteinByDay protein per day that has food logged; days without logs are absent.
     */
    fun baseline(countFrom: LocalDate, today: LocalDate, proteinByDay: Map<LocalDate, Int>): Baseline {
        val logged = proteinByDay.filterKeys { !it.isBefore(countFrom) && !it.isAfter(today) }.toSortedMap()
        for (start in logged.keys) {
            val end = start.plusDays(WINDOW_DAYS - 1)
            val inWindow = logged.subMap(start, end.plusDays(1))
            // Today is still being logged, so a window that includes it hasn't finished.
            if (!end.isBefore(today)) return Baseline.Measuring(start, inWindow.size)
            if (inWindow.size >= MIN_LOGGED_DAYS) return Baseline.Done(end, inWindow.values.average().roundToInt())
        }
        return Baseline.NotStarted
    }

    /** Today's goal from the stored state, with the baseline found from [proteinByDay] if it isn't locked yet. */
    fun goal(fullG: Int, enabled: Boolean, state: RampState?, today: LocalDate, proteinByDay: Map<LocalDate, Int>): ProteinGoal {
        if (!enabled || state == null) return ProteinGoal.Full(fullG)
        val locked = if (state.baselineEnd != null && state.baselineMeanG != null) Baseline.Done(state.baselineEnd, state.baselineMeanG) else null
        return when (val b = locked ?: baseline(state.countFrom, today, proteinByDay)) {
            Baseline.NotStarted -> ProteinGoal.NotStarted(fullG)
            is Baseline.Measuring -> ProteinGoal.Measuring(fullG, b.daysLogged)
            is Baseline.Done -> ramped(fullG, b, today)
        }
    }

    private fun ramped(fullG: Int, baseline: Baseline.Done, today: LocalDate): ProteinGoal {
        if (baseline.meanG >= fullG) return ProteinGoal.Full(fullG)
        // Week 1 starts the day after the baseline week.
        val daysAfter = max(1L, ChronoUnit.DAYS.between(baseline.end, today))
        val week = ((daysAfter - 1) / WINDOW_DAYS + 1).toInt()
        val target = min(fullG, baseline.meanG + STEP_G * week)
        return if (target >= fullG) ProteinGoal.Full(fullG) else ProteinGoal.Ramping(fullG, target, week)
    }
}
