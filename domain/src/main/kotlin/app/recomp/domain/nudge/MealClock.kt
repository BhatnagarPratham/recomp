package app.recomp.domain.nudge

import app.recomp.domain.food.MealSlot
import app.recomp.domain.food.MealTimes
import java.time.LocalTime

/**
 * "The meal for the time of day" (PRD §12.5), as M12 uses it: where a tapped suggestion is logged, and which
 * meal the lunch reminder watches by default. It's M9's rule, each slot's usual time range (`MealTimes.slotAt`),
 * which replaced M12's stand-in when the two met on `main`.
 */
object MealClock {
    fun slotAt(slots: List<MealSlot>, time: LocalTime): MealSlot? = MealTimes.slotAt(slots, time)

    /** The meal the lunch reminder watches: the one you picked while it still exists, else the meal for its time. */
    fun watched(slots: List<MealSlot>, picked: String?, time: LocalTime): MealSlot? =
        slots.firstOrNull { it.key == picked && !it.removed } ?: slotAt(slots, time)
}
