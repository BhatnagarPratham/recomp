package app.recomp.domain.food

/**
 * NU-06: a planned snack inside the calorie target, counted against one meal ([slotKey], Snacks by default).
 * It is part of the target, not extra: the day's target and what's left are the same with it on or off.
 */
data class SnackAllowance(val kcal: Int, val slotKey: String = DEFAULT_SLOT) {
    val valid: Boolean get() = kcal in OPTIONS && slotKey.isNotBlank()

    /**
     * The meal the allowance counts against today: its own, or, once that meal is removed, the meal a new allowance
     * would take, so the day never says "includes a snack allowance" with no meal showing it.
     */
    fun slotIn(slots: List<MealSlot>): String? = slots.firstOrNull { it.key == slotKey && !it.removed }?.key ?: defaultSlot(slots)

    companion object {
        const val DEFAULT_SLOT = "snacks"

        /** 100 to 300 kcal in steps of 50 (PRD §12.5). */
        val OPTIONS: List<Int> = (100..300 step 50).toList()

        /** The meal a new allowance counts against: Snacks, or your first meal when there's no Snacks. */
        fun defaultSlot(slots: List<MealSlot>): String? {
            val active = slots.filter { !it.removed }
            return (active.firstOrNull { it.key == DEFAULT_SLOT } ?: active.firstOrNull())?.key
        }
    }
}
