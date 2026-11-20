package app.recomp.domain.nudge

import app.recomp.domain.food.MealSlot
import java.time.LocalTime

/**
 * "The meal for the time of day" (PRD §12.5), as M12 uses it: where a tapped suggestion is logged, and which
 * meal the lunch reminder watches by default.
 *
 * M9 owns the real rule (each slot's usual time range, `MealTimes.slotAt`). Until it merges, this is a simple
 * stand-in, and this function is the only place to switch: before the evening it's the slot with key "lunch"
 * (the first slot if that one is gone), and from the evening on it's the last slot.
 */
object MealClock {
    private val EVENING: LocalTime = LocalTime.of(17, 0)
    private const val LUNCH_KEY = "lunch"

    fun slotAt(slots: List<MealSlot>, time: LocalTime): MealSlot? {
        val active = slots.filter { !it.removed }
        return if (time.isBefore(EVENING)) active.firstOrNull { it.key == LUNCH_KEY } ?: active.firstOrNull() else active.lastOrNull()
    }

    /** The meal the lunch reminder watches: the one you picked while it still exists, else the meal for its time. */
    fun watched(slots: List<MealSlot>, picked: String?, time: LocalTime): MealSlot? =
        slots.firstOrNull { it.key == picked && !it.removed } ?: slotAt(slots, time)
}
