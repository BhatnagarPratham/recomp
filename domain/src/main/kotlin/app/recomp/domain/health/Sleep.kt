package app.recomp.domain.health

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

/** A stretch of time in epoch milliseconds. Two spans that only touch don't overlap. */
data class Span(val start: Long, val end: Long) {
    init {
        require(end >= start) { "A span can't end before it starts" }
    }

    val length: Long get() = end - start

    fun overlaps(other: Span): Boolean = start < other.end && other.start < end
}

/** One sleep session as a tracker recorded it, with the stretches it marked awake or out of bed. */
data class SleepSession(val span: Span, val awake: List<Span> = emptyList())

/**
 * Sleep for "the night that ended that morning" (AR-02, TRD §10 M13): the sessions that ended between 18:00
 * the day before and noon. Several apps often record the same night, so overlapping sessions are merged,
 * never added up, and awake stretches are left out.
 */
object SleepNight {
    val WINDOW_START: LocalTime = LocalTime.of(18, 0)
    val WINDOW_END: LocalTime = LocalTime.NOON

    /** The typed chips (PRD §12.9): "5 or less" stores 5 and "9+" stores 9. */
    val CHIPS: List<Double> = listOf(5.0, 6.0, 7.0, 8.0, 9.0)

    /** What typing accepts, to the half hour. */
    val TYPED_RANGE: ClosedFloatingPointRange<Double> = 0.0..16.0

    /** The window whose sessions count for the morning of [date]. [until] cuts it short, e.g. at the weigh-in. */
    fun window(date: LocalDate, zone: ZoneId, until: Long? = null): Span {
        val start = date.minusDays(1).atTime(WINDOW_START).atZone(zone).toInstant().toEpochMilli()
        val noon = date.atTime(WINDOW_END).atZone(zone).toInstant().toEpochMilli()
        return Span(start, (until ?: noon).coerceIn(start, noon))
    }

    /** Milliseconds asleep in the sessions that ended inside [window]; null when none did. */
    fun asleepMs(sessions: List<SleepSession>, window: Span): Long? {
        val night = sessions.filter { it.span.end > window.start && it.span.end <= window.end && it.span.length > 0 }
        if (night.isEmpty()) return null
        val asleep = night.flatMap { session -> subtract(session.span, session.awake) }.sortedBy { it.start }
        var total = 0L
        var current: Span? = null
        asleep.forEach { span ->
            val open = current
            current = when {
                open == null -> span
                span.start <= open.end -> Span(open.start, maxOf(open.end, span.end))
                else -> {
                    total += open.length
                    span
                }
            }
        }
        return total + (current?.length ?: 0)
    }

    /** Hours asleep that night, to the half hour like a typed value; null when no session ended in [window]. */
    fun hours(sessions: List<SleepSession>, window: Span): Double? = asleepMs(sessions, window)?.let { toHalfHours(it / 3_600_000.0) }

    fun toHalfHours(hours: Double): Double = (hours * 2).roundToInt() / 2.0

    /** [span] with every stretch in [holes] cut out. */
    private fun subtract(span: Span, holes: List<Span>): List<Span> {
        var pieces = listOf(span)
        holes.forEach { hole ->
            pieces = pieces.flatMap { piece ->
                if (!piece.overlaps(hole)) listOf(piece)
                else listOfNotNull(
                    if (hole.start > piece.start) Span(piece.start, hole.start) else null,
                    if (hole.end < piece.end) Span(hole.end, piece.end) else null,
                )
            }
        }
        return pieces
    }
}
