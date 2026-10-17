package app.recomp.domain.food

/**
 * A meal slot (NU-10). Log rows store [key], so renaming keeps history. A [removed] slot isn't offered
 * for new entries but still shows on days that have entries in it.
 */
data class MealSlot(val key: String, val name: String, val removed: Boolean = false)

object MealSlots {
    val DEFAULT: List<MealSlot> = listOf(
        MealSlot("breakfast", "Breakfast"),
        MealSlot("lunch", "Lunch"),
        MealSlot("snacks", "Snacks"),
        MealSlot("dinner", "Dinner"),
    )

    /**
     * The slots a day shows, in order: every active slot, plus removed ones that still have entries
     * that day. Keys with entries but no slot at all (e.g. after restoring an old backup) show by key.
     */
    fun forDay(slots: List<MealSlot>, keysWithEntries: Set<String>): List<MealSlot> {
        val known = slots.filter { !it.removed || it.key in keysWithEntries }
        val unknown = (keysWithEntries - slots.map { it.key }.toSet()).sorted().map { MealSlot(it, it.replaceFirstChar(Char::uppercase), removed = true) }
        return known + unknown
    }

    /** A key for a new slot that no existing slot uses. */
    fun newKey(slots: List<MealSlot>): String {
        val taken = slots.map { it.key }.toSet()
        return generateSequence(1) { it + 1 }.map { "meal-$it" }.first { it !in taken }
    }
}
