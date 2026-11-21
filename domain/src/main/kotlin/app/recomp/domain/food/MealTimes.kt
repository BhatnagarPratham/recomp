package app.recomp.domain.food

import java.time.LocalTime

/**
 * "The meal for the time of day" (PRD §12.5): each meal slot has a usual start, and a time belongs to the last
 * slot that starts at or before it. *Will it fit?* logs into that slot, and M12's reminders use the same rule.
 */
object MealTimes {
    const val DAY_MINUTES = 24 * 60

    /** The starts of the four default meals, in minutes after midnight. A slot you add has none until you set one. */
    val DEFAULT_STARTS = mapOf("breakfast" to 5 * 60, "lunch" to 12 * 60, "snacks" to 16 * 60, "dinner" to 19 * 60)

    /** When a slot usually starts: the time you set, else the default for one of the four default meals. */
    fun start(slot: MealSlot): Int? = (slot.startMinutes ?: DEFAULT_STARTS[slot.key])?.takeIf { it in 0 until DAY_MINUTES }

    /**
     * The slot [time] falls in: among the meals you can log to, the one with the latest start at or before it.
     * Before the day's first start (01:30) it's the last meal of the day, a late dinner. Equal starts go to the
     * meal later in your list. With no starts at all it's your first meal; null only when there are no meals.
     */
    fun slotAt(slots: List<MealSlot>, time: LocalTime): MealSlot? {
        val active = slots.filter { !it.removed }
        val timed = active.mapNotNull { slot -> start(slot)?.let { slot to it } }
        if (timed.isEmpty()) return active.firstOrNull()
        val minute = time.hour * 60 + time.minute
        val started = timed.filter { it.second <= minute }
        return (if (started.isNotEmpty()) started.maxByOrNullLast { it.second } else timed.maxByOrNullLast { it.second })?.first
    }

    /** A slot's usual range: its start to the next meal's start, which may be tomorrow's first. Null without a start. */
    fun range(slots: List<MealSlot>, slot: MealSlot): Pair<Int, Int>? {
        val from = start(slot) ?: return null
        val starts = slots.filter { !it.removed && it.key != slot.key }.mapNotNull(::start).filter { it != from }
        val to = starts.filter { it > from }.minOrNull() ?: starts.minOrNull() ?: from
        return from to to
    }

    /** "05:00". */
    fun clock(minutes: Int): String = String.format(java.util.Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)

    /** The last of the largest, so equal starts go to the later slot. */
    private fun <T> List<T>.maxByOrNullLast(by: (T) -> Int): T? = if (isEmpty()) null else reduce { best, next -> if (by(next) >= by(best)) next else best }
}
