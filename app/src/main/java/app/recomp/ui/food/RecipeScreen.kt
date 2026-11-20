package app.recomp.ui.food

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.recomp.data.db.FoodEntity
import app.recomp.data.repo.FoodHit
import app.recomp.data.repo.FoodRepository
import app.recomp.data.repo.RecipeDraft
import app.recomp.data.repo.RecipeLine
import app.recomp.data.repo.RecipeRepository
import app.recomp.data.repo.RecipeSaved
import app.recomp.domain.food.FoodSource
import app.recomp.domain.food.PortionUnit
import app.recomp.domain.food.Portions
import app.recomp.domain.food.RecipeProblem
import app.recomp.domain.food.RecipeValues
import app.recomp.domain.food.Recipes
import app.recomp.ui.components.ChoiceChips
import app.recomp.ui.components.ConfirmDialog
import app.recomp.ui.navigation.RecipeRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the recipe editor holds: the name, the ingredients and how much it makes, as typed. */
data class RecipeForm(
    val name: String = "",
    val lines: List<RecipeLine> = emptyList(),
    val cooked: String = "",
    val servings: String = "",
) {
    /** The cooked weight: null when left blank, NaN when it isn't a number, so the check names it. */
    val cookedWeightG: Double? get() = amount(cooked)
    val servingCount: Double? get() = amount(servings)

    private fun amount(text: String): Double? = if (text.isBlank()) null else parseNumber(text) ?: Double.NaN

    /** What stops a save, or null. */
    val problem: RecipeProblem?
        get() = Recipes.problem(lines.map { it.ingredient }, cookedWeightG, servingCount) ?: RecipeProblem.NO_NAME.takeIf { name.isBlank() }

    /** The numbers so far. They show as soon as there's an ingredient, before the servings are typed. */
    val values: RecipeValues?
        get() {
            val cooked = cookedWeightG
            val count = servingCount
            if (lines.isEmpty() || cooked?.isNaN() == true || count?.isNaN() == true) return null
            // With neither given yet, show the totals over the ingredients' own weight.
            return Recipes.values(lines.map { it.ingredient }, cooked, count ?: if (cooked == null) 1.0 else null)
                ?.let { if (count == null) it.copy(servingG = null) else it }
        }

    val draft: RecipeDraft get() = RecipeDraft(name, lines.map { it.food.id to it.grams }, cookedWeightG, servingCount)
}

/** What the editor hands back to the screen that opened it: the recipe's food, and whether it's new. */
data class RecipeOutcome(val foodId: Long, val created: Boolean)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class RecipeViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val foods: FoodRepository,
    private val recipes: RecipeRepository,
) : ViewModel() {
    private val route = savedState.toRoute<RecipeRoute>()

    /** The recipe being edited, or null for a new one. */
    val recipeId: Long? = route.id.takeIf { it > 0 }

    val form = MutableStateFlow(RecipeForm(name = route.name))

    /** False until an edited recipe has loaded. */
    val loaded = MutableStateFlow(recipeId == null)

    /** Set when the recipe to edit is gone. */
    val missing = MutableStateFlow(false)

    private var saved = RecipeForm(name = route.name)
    val dirty: Boolean get() = form.value != saved

    val query = MutableStateFlow("")

    /** Itself and every recipe that holds it: never offered as an ingredient. */
    private val blocked = MutableStateFlow<Set<Long>>(emptySet())

    val results: StateFlow<List<FoodHit>> = combine(query.debounce(120), blocked) { q, b -> q to b }
        .mapLatest { (q, b) -> if (q.isBlank()) emptyList() else foods.search(q, limit = 30).filter { it.food.id !in b }.take(RESULTS) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        if (recipeId != null) viewModelScope.launch {
            blocked.value = recipes.blockedIngredients(recipeId)
            val r = recipes.recipe(recipeId)
            if (r == null) {
                missing.value = true
            } else {
                saved = RecipeForm(r.food.name, r.lines, RecipeText.number(r.cookedWeightG), RecipeText.number(r.servings))
                form.value = saved
            }
            loaded.value = true
        }
    }

    fun edit(change: (RecipeForm) -> RecipeForm) = form.update(change)

    suspend fun units(foodId: Long): List<PortionUnit> = foods.units(foodId)

    fun add(food: FoodEntity, grams: Double) {
        edit { it.copy(lines = it.lines + RecipeLine(food, grams)) }
        query.value = ""
    }

    fun setGrams(index: Int, grams: Double) = edit { f -> f.copy(lines = f.lines.mapIndexed { i, l -> if (i == index) l.copy(grams = grams) else l }) }

    fun remove(index: Int) = edit { f -> f.copy(lines = f.lines.filterIndexed { i, _ -> i != index }) }

    suspend fun save(): RecipeSaved = recipes.save(recipeId, form.value.draft)

    private companion object {
        const val RESULTS = 8
    }
}

/** User-facing text for the recipe editor (PRD §12.10). */
object RecipeText {
    /** A typed amount back as text: "920", "2.5", or nothing. */
    fun number(value: Double?): String = when {
        value == null -> ""
        value % 1.0 == 0.0 -> value.toLong().toString()
        else -> "%.1f".format(value)
    }

    /** A food weight. One place, so M20's units change it once. */
    fun grams(value: Double): String = FoodText.g(value)

    /** "300 g · 1,080 kcal · 21 g protein" */
    fun line(l: RecipeLine): String = "${grams(l.grams)} · ${FoodText.kcalProtein(l.food.per100g.forGrams(l.grams))}"

    /**
     * Which weight the numbers assume (TRD §10 M14's risk: raw and cooked are easy to mix up). Ingredients
     * are always as weighed going in; per 100 g is over the cooked pot when you weighed it, else over the
     * ingredients' own weight.
     */
    fun weightNote(v: RecipeValues): String =
        if (v.cooked) {
            "Ingredient amounts are raw, as you weighed them (${grams(v.rawWeightG)} in all). Per 100 g is for the cooked dish, " +
                "using the ${grams(v.weightG)} you weighed."
        } else {
            "No cooked weight, so per 100 g assumes the dish weighs what its ingredients do raw (${grams(v.rawWeightG)}). " +
                "Cooking adds or loses water, so weigh the pot if you'll log this by weight. A serving's numbers are right either way."
        }

    fun problem(p: RecipeProblem): String = when (p) {
        RecipeProblem.NO_NAME -> "Give the recipe a name."
        RecipeProblem.NO_INGREDIENTS -> "Add at least one ingredient."
        RecipeProblem.BAD_AMOUNT -> "Every ingredient needs an amount above 0 g."
        RecipeProblem.BAD_COOKED_WEIGHT -> "The cooked weight should be a number of grams, up to ${grams(Recipes.MAX_GRAMS)}."
        RecipeProblem.BAD_SERVINGS -> "Servings should be a number above 0."
        RecipeProblem.NO_YIELD -> "Enter the number of servings, the cooked weight, or both."
        RecipeProblem.CONTAINS_ITSELF -> "A recipe can't contain itself, even through another recipe. Remove that ingredient."
    }

    fun diet(v: RecipeValues): String = "${FoodText.diet(v.diet)}, from its strictest ingredient."
}

/** An ingredient whose amount is being typed: a new one, or line [index] being changed. */
private data class Amount(val food: FoodEntity, val units: List<PortionUnit>, val index: Int?, val grams: Double?)

/**
 * The recipe editor (NU-22): a name, ingredients from search with their amounts, and how much it makes.
 * It shows the totals, per 100 g and per serving as you go, and says which weight they assume.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeScreen(onBack: () -> Unit, onSaved: (RecipeOutcome) -> Unit, viewModel: RecipeViewModel = hiltViewModel()) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val missing by viewModel.missing.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var amount by remember { mutableStateOf<Amount?>(null) }
    var leaving by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val editing = viewModel.recipeId != null

    fun leave() = if (viewModel.dirty) leaving = true else onBack()
    BackHandler(enabled = viewModel.dirty) { leaving = true }
    LaunchedEffect(missing) { if (missing) onBack() }

    fun pick(food: FoodEntity, index: Int?, grams: Double?) = scope.launch { amount = Amount(food, viewModel.units(food.id), index, grams) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (editing) "Edit recipe" else "New recipe") },
                navigationIcon = { TextButton(onClick = ::leave) { Text("Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!loaded) return@Scaffold
        val values = form.values
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { text -> viewModel.edit { it.copy(name = text) } },
                    label = { Text("Recipe name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item { Label("Ingredients") }
            if (form.lines.isEmpty()) {
                item { Text("Search below and add each ingredient with its raw weight.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            itemsIndexed(form.lines) { index, line ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier.weight(1f).heightIn(min = 56.dp)
                            .clickable(onClickLabel = "Change the amount of ${line.food.name}") { pick(line.food, index, line.grams) }
                            .padding(vertical = 8.dp),
                    ) {
                        Text(line.food.name + if (line.food.source == FoodSource.RECIPE) " · recipe" else "", style = MaterialTheme.typography.bodyLarge)
                        Text(RecipeText.line(line), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { viewModel.remove(index) }, modifier = Modifier.semantics { contentDescription = "Remove ${line.food.name}" }) { Text("✕") }
                }
                HorizontalDivider()
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { viewModel.query.value = it },
                    label = { Text("Add an ingredient") },
                    placeholder = { Text("Search foods") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            items(results, key = { "hit-${it.food.id}" }) { hit ->
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(onClickLabel = "Add ${hit.food.name}") { pick(hit.food, null, null) }
                        .padding(vertical = 6.dp),
                ) {
                    Text(hit.food.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "100 g · ${FoodText.kcalProtein(hit.food.per100g.forGrams(100.0))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (query.isNotBlank() && results.isEmpty()) {
                item { Text("No food matches “${query.trim()}”. Save it as a new food first, then add it here.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            item { Label("How much it makes") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    YieldField("Servings", form.servings, null, Modifier.weight(1f)) { text -> viewModel.edit { it.copy(servings = text) } }
                    YieldField("Cooked weight (optional)", form.cooked, "g", Modifier.weight(1f)) { text -> viewModel.edit { it.copy(cooked = text) } }
                }
            }
            item {
                Text(
                    "Weigh the finished dish without the pot for the cooked weight. One serving is the dish's weight ÷ servings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (values != null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Whole recipe · ${FoodText.macros(values.total)}", style = MaterialTheme.typography.bodyMedium)
                            Text("Per 100 g · ${FoodText.macros(values.per100g.forGrams(100.0))}", style = MaterialTheme.typography.bodyMedium)
                            val serving = values.servingG
                            if (serving != null) {
                                Text(
                                    "Per serving (${RecipeText.grams(serving)}) · ${FoodText.macros(values.per100g.forGrams(serving))}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Text(RecipeText.weightNote(values), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(RecipeText.diet(values), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item {
                val problem = form.problem
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (problem != null && form.lines.isNotEmpty()) {
                        Text(RecipeText.problem(problem), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(
                        onClick = {
                            saving = true
                            scope.launch {
                                when (val result = viewModel.save()) {
                                    is RecipeSaved.Ok -> onSaved(RecipeOutcome(result.foodId, created = !editing))
                                    is RecipeSaved.Refused -> {
                                        saving = false
                                        snackbar.showSnackbar(RecipeText.problem(result.problem))
                                    }
                                }
                            }
                        },
                        enabled = problem == null && !saving,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) { Text("Save recipe") }
                    if (editing) {
                        Text(
                            "Saving changes what you log from now on. Entries already logged keep their numbers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    amount?.let { a ->
        AmountDialog(
            a,
            onDismiss = { amount = null },
            onRemove = if (a.index != null) {
                { amount = null; viewModel.remove(a.index) }
            } else {
                null
            },
        ) { grams ->
            amount = null
            if (a.index != null) viewModel.setGrams(a.index, grams) else viewModel.add(a.food, grams)
        }
    }

    if (leaving) {
        ConfirmDialog(
            if (editing) "Discard your changes?" else "Discard this recipe?",
            "It hasn't been saved.",
            confirm = "Discard",
            onDismiss = { leaving = false },
            dismiss = "Keep editing",
        ) {
            leaving = false
            onBack()
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun YieldField(label: String, value: String, suffix: String?, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        suffix = suffix?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = value.isNotBlank() && parseNumber(value)?.let { it <= 0 } != false,
        modifier = modifier,
    )
}

/** How much of an ingredient goes in: a number in grams or one of the food's units, shown as grams. */
@Composable
private fun AmountDialog(a: Amount, onDismiss: () -> Unit, onRemove: (() -> Unit)?, onConfirm: (Double) -> Unit) {
    val gramsUnit = a.units.last()
    var unit by remember { mutableStateOf(gramsUnit) }
    var text by remember { mutableStateOf(RecipeText.number(a.grams)) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val grams = parseNumber(text)?.let { it * unit.grams }?.takeIf { it > 0 && it <= Recipes.MAX_GRAMS }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(a.food.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Amount, raw") },
                    suffix = { Text(unit.label) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (a.units.size > 1) ChoiceChips(a.units, unit, { it.label }, { unit = it })
                if (grams != null) {
                    val weight = if (unit.label == Portions.GRAMS) "" else "${RecipeText.grams(grams)} · "
                    Text(weight + FoodText.kcalProtein(a.food.per100g.forGrams(grams)), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { grams?.let(onConfirm) }, enabled = grams != null) { Text(if (a.index == null) "Add" else "OK") } },
        dismissButton = {
            Row {
                if (onRemove != null) TextButton(onClick = onRemove) { Text("Remove") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
