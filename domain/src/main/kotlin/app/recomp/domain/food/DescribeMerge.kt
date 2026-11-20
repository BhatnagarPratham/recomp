package app.recomp.domain.food

import app.recomp.domain.ai.EstimatedItem

/**
 * Joins what the local parser matched with what the AI estimated for the rest (PRD §12.5). The AI is told which
 * items are already counted; this checks it anyway, so nothing is ever counted twice.
 */
object DescribeMerge {
    /** The AI's items that are new, and the names of those left out because the parser already counted them. */
    data class Merged(val items: List<EstimatedItem>, val dropped: List<String>)

    fun merge(counted: List<ParsedItem>, ai: List<EstimatedItem>): Merged {
        val taken = counted.flatMap { item ->
            val typedName = DescribeParser.chunk(Quantities.tokens(item.typed), emptySet())?.name
            listOfNotNull(typedName).map(FoodWords::key) + FoodWords.aliases(item.food.name).map(FoodWords::key)
        }.toSet()
        val (dropped, kept) = ai.partition { FoodWords.key(FoodWords.words(it.name)) in taken }
        return Merged(kept, dropped.map { it.name })
    }
}
