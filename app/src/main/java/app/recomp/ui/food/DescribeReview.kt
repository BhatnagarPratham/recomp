package app.recomp.ui.food

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.recomp.data.db.FoodEntity
import app.recomp.data.repo.DescribedMeal
import app.recomp.data.repo.FoodHit
import app.recomp.data.repo.ItemFrom
import app.recomp.data.repo.MealItemToLog
import app.recomp.data.repo.PortionChoice
import app.recomp.domain.ai.Confidence
import app.recomp.domain.ai.EstimatedItem
import app.recomp.domain.ai.MealCheck
import app.recomp.domain.food.DescribeParser
import app.recomp.domain.food.FoodSource
import app.recomp.domain.food.LogSource
import app.recomp.domain.food.Macros
import app.recomp.domain.food.MealSlot
import app.recomp.domain.food.Origin
import app.recomp.domain.food.PortionUnit
import app.recomp.domain.food.Portions
import kotlinx.coroutines.launch

/**
 * A reviewed item (PRD §12.5): what was estimated or matched, what you've made of it, and where its numbers came
 * from. Changing an AI item makes it [edited], which the learning loop keeps as one of your foods; a matched food
 * only changes its amount. [picked] marks a food you chose yourself for a part nothing found, and [quick] a quick
 * add for one.
 */
data class ReviewItem(
    val original: EstimatedItem,
    val current: EstimatedItem,
    val from: ItemFrom = ItemFrom.AI,
    val foodId: Long? = null,
    val picked: Boolean = false,
    val quick: MealItemToLog.Quick? = null,
) {
    val edited: Boolean get() = current != original

    /** What saving this item writes. */
    fun toLog(): MealItemToLog = when {
        quick != null -> quick
        foodId != null && from != ItemFrom.AI ->
            MealItemToLog.Local(foodId, current.quantity, current.unit, current.grams, if (picked) LogSource.MANUAL else LogSource.LOCAL_PARSE)
        else -> MealItemToLog.Ai(current, edited)
    }

    companion object {
        /** A food you picked for a part nothing found, at the amount from the portion sheet. */
        fun picked(food: FoodEntity, quantity: Double, unit: PortionUnit): ReviewItem {
            val grams = quantity * unit.grams
            val m = food.per100g.forGrams(grams)
            val item = EstimatedItem(
                food.name, if (unit.label == Portions.GRAMS) grams else quantity, unit.label, grams, m.kcal, m.proteinG, m.carbsG ?: 0.0, m.fatG ?: 0.0,
                m.fiberG ?: 0.0, Confidence.HIGH, "You picked this",
            )
            val own = food.source == FoodSource.CUSTOM || food.source == FoodSource.AI || food.source == FoodSource.LABEL_OCR
            return ReviewItem(item, item, if (own) ItemFrom.YOUR_FOOD else ItemFrom.DATABASE, food.id, picked = true)
        }

        /** A quick add for a part nothing found: calories and protein, with carbs and fat only if you know them. */
        fun quick(values: QuickAddValues, typed: String): ReviewItem {
            val name = values.name?.trim()?.ifEmpty { null } ?: typed
            val item = EstimatedItem(name, 1.0, "serving", 0.0, values.kcal, values.proteinG, values.carbsG ?: 0.0, values.fatG ?: 0.0, 0.0, Confidence.LOW, "")
            return ReviewItem(item, item, ItemFrom.QUICK_ADD, quick = MealItemToLog.Quick(name, values.kcal, values.proteinG, values.carbsG, values.fatG))
        }
    }
}

/** A described meal's items as the review starts with them. */
fun DescribedMeal.reviewItems(): List<ReviewItem> = items.map { ReviewItem(it.item, it.item, it.from, it.foodId) }

/** The items' totals, for the review's header and *Will it fit?*. */
fun List<ReviewItem>.totals(): Macros = MealCheck.totals(map { it.current })

/** What the review's sheets are open on. */
sealed interface ReviewTarget {
    data class Edit(val index: Int) : ReviewTarget

    /** *Search* on a "Couldn't find" chunk. */
    data class Search(val chunk: String) : ReviewTarget

    /** *Quick add* on a "Couldn't find" chunk. */
    data class Quick(val chunk: String) : ReviewTarget
}

/** What a review needs from its screen's view model (Describe it and *Will it fit?* share the review). */
interface ReviewActions {
    fun update(index: Int, item: ReviewItem)

    fun remove(index: Int)

    /** Replaces a "Couldn't find" chunk with [item]. */
    fun resolve(chunk: String, item: ReviewItem)

    /** Drops a "Couldn't find" chunk without adding anything. */
    fun skip(chunk: String)

    suspend fun searchFoods(query: String): List<FoodHit>

    suspend fun portion(foodId: Long): PortionChoice?
}

/** "Couldn't find: salad", with *Search* and *Quick add* (PRD §12.5). Nothing is logged until the meal is saved. */
@Composable
internal fun UnmatchedRow(chunk: String, onSearch: () -> Unit, onQuickAdd: () -> Unit, onSkip: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Couldn't find: $chunk", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSearch, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Search for $chunk" }) { Text("Search") }
                OutlinedButton(onClick = onQuickAdd, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Quick add $chunk" }) { Text("Quick add") }
                TextButton(onClick = onSkip, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Leave out $chunk" }) { Text("Leave out") }
            }
        }
    }
}

/** The sheets a review opens: change an item, search for a part nothing found, or quick add it. */
@Composable
internal fun ReviewSheets(target: ReviewTarget?, items: List<ReviewItem>, slots: List<MealSlot>, origin: Origin, actions: ReviewActions, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf<Pair<String, PortionChoice>?>(null) }

    when (target) {
        null -> Unit
        is ReviewTarget.Edit -> {
            val item = items.getOrNull(target.index)
            when {
                item == null -> LaunchedEffect(target) { onClose() }
                item.quick != null -> QuickAddSheet(
                    initial = QuickAddValues(item.quick.name, item.quick.kcal, item.quick.proteinG, item.quick.carbsG, item.quick.fatG, origin),
                    slots = slots,
                    onDismiss = onClose,
                    onDelete = { onClose(); actions.remove(target.index) },
                ) { values ->
                    onClose()
                    actions.update(target.index, ReviewItem.quick(values, item.current.name))
                }
                else -> AiItemSheet(
                    item = item.current,
                    amountOnly = item.from != ItemFrom.AI,
                    onDismiss = onClose,
                    onRemove = { onClose(); actions.remove(target.index) },
                ) { edited ->
                    onClose()
                    actions.update(target.index, item.copy(current = edited))
                }
            }
        }
        is ReviewTarget.Search -> if (choosing == null) {
            FoodPickSheet(target.chunk, actions::searchFoods, onDismiss = onClose) { hit ->
                scope.launch { actions.portion(hit.food.id)?.let { choosing = target.chunk to it } }
            }
        }
        is ReviewTarget.Quick -> QuickAddSheet(
            initial = null,
            slots = slots,
            onDismiss = onClose,
        ) { values ->
            onClose()
            actions.resolve(target.chunk, ReviewItem.quick(values, DescribeParser.nameOf(target.chunk)))
        }
    }

    choosing?.let { (chunk, choice) ->
        PortionSheet(
            state = PortionSheetState(choice.food.name, choice.food.caveat, choice.units, choice.quantity, choice.unit, origin),
            slots = slots,
            macrosFor = { q, unit -> choice.food.per100g.forGrams(q * unit.grams) },
            onCalibrate = null,
            onDismiss = { choosing = null },
        ) { q, unit, _, _ ->
            choosing = null
            onClose()
            actions.resolve(chunk, ReviewItem.picked(choice.food, q, unit))
        }
    }
}

/** Search your foods and the database for a part the parser couldn't find, starting from what you typed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FoodPickSheet(chunk: String, search: suspend (String) -> List<FoodHit>, onDismiss: () -> Unit, onPick: (FoodHit) -> Unit) {
    var query by remember { mutableStateOf(DescribeParser.nameOf(chunk)) }
    var hits by remember { mutableStateOf<List<FoodHit>?>(null) }
    LaunchedEffect(query) { hits = search(query) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding().imePadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Find “$chunk”", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search foods") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val found = hits
            if (found != null && found.isEmpty()) {
                Text(
                    if (query.isBlank()) "Type a food's name." else "Nothing matches. Try fewer words, or use Quick add.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(found.orEmpty(), key = { it.food.id }) { hit ->
                    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClickLabel = "Use ${hit.food.name}") { onPick(hit) }.padding(vertical = 8.dp)) {
                        Text(hit.food.name, style = MaterialTheme.typography.bodyLarge)
                        Text(FoodText.serving(hit.food, hit.serving), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** Pure edits to a review's items and its "Couldn't find" chunks, shared by both screens' view models. */
object ReviewEdits {
    fun update(items: List<ReviewItem>, index: Int, item: ReviewItem): List<ReviewItem> = items.mapIndexed { i, r -> if (i == index) item else r }

    fun remove(items: List<ReviewItem>, index: Int): List<ReviewItem> = items.filterIndexed { i, _ -> i != index }

    /** Only the first chunk of that text goes, so "salad, salad" resolves one at a time. */
    fun without(unmatched: List<String>, chunk: String): List<String> {
        val at = unmatched.indexOf(chunk)
        return if (at < 0) unmatched else unmatched.filterIndexed { i, _ -> i != at }
    }
}
