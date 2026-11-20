package app.recomp.data.repo

import app.recomp.domain.ai.AiKind
import app.recomp.domain.ai.AiState
import app.recomp.domain.ai.AiStatus
import app.recomp.domain.ai.Clarification
import app.recomp.domain.ai.EstimatedItem
import app.recomp.domain.food.DescribeMerge
import app.recomp.domain.food.Origin
import app.recomp.domain.food.ParsedItem
import app.recomp.domain.food.ParsedMeal
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Where a described item's numbers came from (PRD §12.5). */
enum class ItemFrom { YOUR_FOOD, DATABASE, AI, QUICK_ADD }

/** One item of a described meal. [foodId] is set for a food the local parser matched. */
data class DescribedItem(val item: EstimatedItem, val from: ItemFrom, val foodId: Long? = null)

/**
 * A described meal, ready to review: what the local parser matched, what the AI estimated for the rest, and the
 * chunks neither found ([unmatched], as typed).
 *
 * - [rejected]: AI items left out for having no usable weight or amount.
 * - [alreadyCounted]: AI items left out because the parser had counted them.
 * - [requestId]: the AI request, when one was sent. Null means the parser answered alone, at no cost.
 * - [aiFailure]: why the AI's part is missing, when a request was sent and failed.
 * - [aiState]: the gate when the meal was described; anything but READY means the parser answered alone.
 */
data class DescribedMeal(
    val items: List<DescribedItem>,
    val unmatched: List<String> = emptyList(),
    val rejected: List<String> = emptyList(),
    val alreadyCounted: List<String> = emptyList(),
    val question: String? = null,
    val requestId: Long? = null,
    val aiFailure: AiStatus? = null,
    val aiState: AiState = AiState.READY,
)

sealed interface DescribeResult {
    data class Described(val meal: DescribedMeal) : DescribeResult

    /** Nothing to review: the AI was needed for all of it and failed. [draftId] is the draft kept, if one was asked for. */
    data class Failed(val status: AiStatus, val requestId: Long?, val draftId: Long? = null) : DescribeResult
}

/**
 * Describe it and *Will it fit?* (AI-04, NU-17, NU-18; PRD §12.5): the local parser and the AI, in the order the
 * PRD settles.
 *
 * - AI off or not ready: the parser answers alone.
 * - AI ready, *Ordered*: straight to the AI, because restaurant oil is what the database misses.
 * - AI ready otherwise: the parser first. Everything matched means no request and no spend. Otherwise one request
 *   with the whole description and the matched items marked as already counted, and the two are merged.
 */
@Singleton
class MealDescriber @Inject constructor(
    private val ai: AiRepository,
    private val foods: FoodRepository,
) {
    /**
     * [draft] keeps the description when the AI was needed for all of it and failed (AI-02). With
     * [parserOnFailure] (a fit check, which never keeps a draft) a failed request falls back to whatever the
     * parser found instead.
     *
     * Like the request itself, this runs to completion even if the caller goes away: a screen left the moment
     * *Estimate* is tapped still sends its request once and keeps its draft, never neither.
     */
    suspend fun describe(
        text: String,
        origin: Origin,
        kind: AiKind = AiKind.MEAL_TEXT,
        clarification: Clarification? = null,
        draft: DraftPlan? = null,
        parserOnFailure: Boolean = false,
    ): DescribeResult = withContext(NonCancellable) {
        val state = ai.state()
        if (state != AiState.READY) return@withContext DescribeResult.Described(local(foods.parseMeal(text), state))
        val parsed = if (origin == Origin.ORDERED) null else foods.parseMeal(text)
        if (parsed != null && parsed.items.isNotEmpty() && parsed.unmatched.isEmpty()) return@withContext DescribeResult.Described(local(parsed, state))

        val counted = parsed?.items.orEmpty()
        // With something matched there's a review either way, so no draft: saving both would count the meal twice.
        val plan = draft.takeIf { counted.isEmpty() && !parserOnFailure }
        when (val result = ai.estimate(text, origin, clarification, plan, kind, counted.map { it.counted })) {
            is EstimateResult.Estimated -> {
                val merged = DescribeMerge.merge(counted, result.meal.items)
                DescribeResult.Described(
                    DescribedMeal(
                        items = counted.map(::described) + merged.items.map { DescribedItem(it, ItemFrom.AI) },
                        rejected = result.meal.rejected,
                        alreadyCounted = merged.dropped,
                        question = result.meal.clarifyingQuestion,
                        requestId = result.requestId,
                    ),
                )
            }
            // The gate changed between the check and the send: the parser answers, as it would have.
            is EstimateResult.NotReady -> DescribeResult.Described(local(parsed ?: foods.parseMeal(text), result.state))
            is EstimateResult.Failed -> when {
                counted.isNotEmpty() -> DescribeResult.Described(local(parsed!!, state).copy(requestId = result.requestId, aiFailure = result.status))
                parserOnFailure -> DescribeResult.Described(local(parsed ?: foods.parseMeal(text), state).copy(requestId = result.requestId, aiFailure = result.status))
                else -> DescribeResult.Failed(result.status, result.requestId, result.draftId)
            }
        }
    }

    private fun local(parsed: ParsedMeal, state: AiState) = DescribedMeal(parsed.items.map(::described), parsed.unmatched, aiState = state)

    private fun described(item: ParsedItem) = DescribedItem(item.estimated(), if (item.food.own) ItemFrom.YOUR_FOOD else ItemFrom.DATABASE, item.food.id)
}
