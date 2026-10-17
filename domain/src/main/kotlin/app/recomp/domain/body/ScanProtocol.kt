package app.recomp.domain.body

import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Your usual scan slot (IB-02). The weekday is for the reminder (IB-06); the check uses the time of day. */
data class ScanSlot(val day: DayOfWeek, val time: LocalTime)

/** The form's *Last ate* answers, stored as fixed minutes (TRD §5.6). Only the 120-minute threshold matters. */
enum class SinceMeal(val minutes: Int) {
    UNDER_2H(60),
    TWO_TO_3H(150),
    OVER_3H(180),
    FASTED(600),
    ;

    companion object {
        /** The answer a stored value came from, or the nearest one for a value typed some other way. */
        fun of(minutes: Int): SinceMeal = entries.minBy { abs(it.minutes - minutes) }
    }
}

/** Why a scan is off protocol. */
enum class ProtocolIssue { TIME, MEAL, WORKOUT }

/** [minutesFromSlot] is null without a usual slot. */
data class ProtocolCheck(val issues: Set<ProtocolIssue>, val minutesFromSlot: Int?) {
    val ok: Boolean get() = issues.isEmpty()
}

/**
 * Consistent conditions instead of a fasted morning (IB-02, TRD §5.6): within 90 minutes of your usual
 * time, at least 2 hours after eating, and before any workout that day.
 */
object ScanProtocol {
    const val TIME_WINDOW_MIN = 90
    const val MIN_SINCE_MEAL = 120

    /** Minutes between two times of day, the short way round midnight. */
    fun minutesApart(a: LocalTime, b: LocalTime): Int {
        val d = abs(a.toSecondOfDay() - b.toSecondOfDay()) / 60
        return min(d, 24 * 60 - d)
    }

    fun check(time: LocalTime, slot: ScanSlot?, minsSinceMeal: Int, workoutBefore: Boolean): ProtocolCheck {
        val apart = slot?.let { minutesApart(time, it.time) }
        val issues = buildSet {
            if (apart != null && apart > TIME_WINDOW_MIN) add(ProtocolIssue.TIME)
            if (minsSinceMeal < MIN_SINCE_MEAL) add(ProtocolIssue.MEAL)
            if (workoutBefore) add(ProtocolIssue.WORKOUT)
        }
        return ProtocolCheck(issues, apart)
    }

    /** Body fat % from fat mass and weight, to one decimal, for the form to pre-fill (IB-01). */
    fun bodyFatPct(weightG: Int, fatG: Int): Double? =
        if (weightG <= 0 || fatG < 0 || fatG > weightG) null else (fatG * 1000.0 / weightG).roundToInt() / 10.0
}
