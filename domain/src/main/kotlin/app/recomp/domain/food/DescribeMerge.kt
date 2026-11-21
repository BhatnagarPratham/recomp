package app.recomp.domain.food

import app.recomp.domain.ai.EstimatedItem

/**
 * Joins what the local parser matched with what the AI estimated for the rest (PRD §12.5). The AI is told which
 * items are already counted; this checks it anyway, so nothing is counted twice.
 */
object DescribeMerge {
    /** The AI's items that are new, and those set aside because the parser already counted them (with *Add anyway*). */
    data class Merged(val items: List<EstimatedItem>, val alreadyCounted: List<EstimatedItem>)

    /**
     * An AI item is already counted when its name has every word of a counted item's typed name ("Roti (2 pcs)",
     * "Whole wheat roti" and "Butter roti" for "2 roti"), is exactly one of the counted food's names, or has every
     * word of the food's whole name. Amounts, unit words and filler words in the AI's name are ignored.
     *
     * [unsure] are the chunks sent to the AI to estimate: an item that names one of them at least as fully as it
     * names a counted item is that chunk's answer, so it's kept ("Dal makhani" when "dal" was counted and
     * "dal makhani" was sent).
     */
    fun merge(counted: List<ParsedItem>, ai: List<EstimatedItem>, unsure: List<String> = emptyList()): Merged {
        val typedKeys = counted.mapNotNull { nameKey(it.typed) }
        val aliasKeys = counted.flatMap { item -> FoodWords.aliases(item.food.name).map { FoodWords.key(it).toSet() } }.toSet()
        val fullKeys = counted.map { FoodWords.key(FoodWords.words(it.food.name)).toSet() }
        val asked = unsure.mapNotNull(::nameKey)
        val (dropped, kept) = ai.partition { item ->
            val key = itemKey(item.name)
            val countedBy = (typedKeys.filter { key.containsAll(it) } + fullKeys.filter { key.containsAll(it) }).maxOfOrNull { it.size }
                ?: if (key in aliasKeys) key.size else null
            val askedBy = asked.filter { key.containsAll(it) }.maxOfOrNull { it.size }
            countedBy != null && (askedBy == null || countedBy >= askedBy)
        }
        return Merged(kept, dropped)
    }

    /** A typed chunk's name as a set of words, without its amount and unit; null when nothing is left. */
    private fun nameKey(typed: String): Set<String>? =
        DescribeParser.nameOf(typed).let(FoodWords::words).let(FoodWords::key).toSet().takeIf { it.isNotEmpty() }

    /** An AI item's name as a set of words, without amounts or unit words ("Roti (2 pcs)" → roti). */
    private fun itemKey(name: String): Set<String> = FoodWords.key(
        FoodWords.words(name).filter { w -> FoodWords.singular(w) in FoodWords.FOOD_UNITS || FoodWords.unit(w) == null },
    ).toSet()
}
