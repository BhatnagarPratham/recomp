package app.recomp.domain.health

import app.recomp.domain.model.SessionKind
import java.time.DayOfWeek
import java.time.LocalDate

/** A configured extra session (PRD §7.6), in the persona's order. */
data class ExtraSlot(val routineId: Long, val kind: SessionKind, val days: Set<DayOfWeek>)

/** Other apps' workouts read from Health Connect (AR-04, PRD §12.9). */
object ImportedWorkouts {
    /**
     * The extra an imported session of [kind] counts toward: the one scheduled on [day] first, else the first of
     * that kind in the persona. Null when you have no extra of that kind, so it counts toward nothing.
     */
    fun routineFor(kind: SessionKind, day: DayOfWeek, extras: List<ExtraSlot>): Long? {
        val ofKind = extras.filter { it.kind == kind }
        return (ofKind.firstOrNull { day in it.days } ?: ofKind.firstOrNull())?.routineId
    }

    /** Whether [session] overlaps a workout you logged in Recomp, so importing it would count the time twice. */
    fun overlapsOwn(session: Span, own: List<Span>): Boolean = own.any { it.overlaps(session) }

    /**
     * Whether two sessions of one kind from different apps are copies of the same one: they share more than half of
     * the shorter one's time. Two walks an hour apart, or a short one inside a long hike only in part, stay two.
     */
    fun sameSession(a: Span, b: Span): Boolean {
        val shared = minOf(a.end, b.end) - maxOf(a.start, b.start)
        return shared > 0 && shared * 2 > minOf(a.length, b.length)
    }
}

/** Daily steps (AR-01), keyed by calendar day so they match Google Fit. */
object Steps {
    const val DEFAULT_GOAL = 8_000
    const val MIN_GOAL = 2_000
    const val MAX_GOAL = 20_000
    const val GOAL_STEP = 500

    fun clampGoal(goal: Int): Int = goal.coerceIn(MIN_GOAL, MAX_GOAL)

    /**
     * Body's 7-day average: the mean over the days that have a count among the seven before [today]. Today is
     * left out because it isn't over. Null when none of them has a count.
     */
    fun weekAverage(byDay: Map<LocalDate, Int>, today: LocalDate): Int? {
        val counts = (1L..7L).mapNotNull { byDay[today.minusDays(it)] }
        return if (counts.isEmpty()) null else (counts.sum().toDouble() / counts.size).toInt()
    }
}
