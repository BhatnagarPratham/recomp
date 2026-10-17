package app.recomp.ui.food

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.recomp.data.repo.CustomFood
import app.recomp.domain.food.FoodCaveat
import app.recomp.domain.food.FoodDiet
import app.recomp.domain.food.Macros
import app.recomp.domain.food.MealSlot
import app.recomp.domain.food.Origin
import app.recomp.domain.food.Per100g
import app.recomp.domain.food.PortionUnit
import app.recomp.domain.food.Portions
import app.recomp.ui.components.ChoiceChips
import app.recomp.ui.components.NumberEntryDialog
import app.recomp.ui.components.Stepper
import kotlin.math.roundToInt

/** What the portion sheet opens with. [slot] is set when editing an entry, which shows the meal chips. */
data class PortionSheetState(
    val title: String,
    val caveat: FoodCaveat?,
    val units: List<PortionUnit>,
    val quantity: Double,
    val unit: PortionUnit,
    val origin: Origin,
    val slot: String? = null,
)

/**
 * The portion sheet (PRD §7.7): amount, unit, origin, a live preview, and Save. Units can be
 * recalibrated for this food. When editing, it also moves the entry to another meal or deletes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortionSheet(
    state: PortionSheetState,
    slots: List<MealSlot>,
    macrosFor: (quantity: Double, unit: PortionUnit) -> Macros,
    onCalibrate: ((label: String, grams: Double) -> Unit)?,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onSave: (quantity: Double, unit: PortionUnit, origin: Origin, slot: String?) -> Unit,
) {
    var quantity by remember { mutableDoubleStateOf(state.quantity) }
    var unitLabel by remember { mutableStateOf(state.unit.label) }
    var origin by remember { mutableStateOf(state.origin) }
    var slot by remember { mutableStateOf(state.slot) }
    var typing by remember { mutableStateOf(false) }
    var calibrating by remember { mutableStateOf(false) }
    // Units can change underneath (a calibration), so the selection is by label.
    val unit = state.units.firstOrNull { it.label == unitLabel } ?: state.unit

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(state.title, style = MaterialTheme.typography.titleLarge)
            state.caveat?.let { Text("⚠ " + FoodText.caveat(it), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodyMedium) }
            Stepper(
                "Amount",
                if (unit.label == Portions.GRAMS) "${quantity.roundToInt()} g" else "${Portions.format(quantity)} ${unit.label}",
                onMinus = { quantity = Portions.down(quantity, unit.label) },
                onPlus = { quantity = Portions.up(quantity, unit.label) },
                onValueClick = { typing = true },
                modifier = Modifier.fillMaxWidth(),
                minusLabel = if (unit.label == Portions.GRAMS) "−10" else "−½",
                plusLabel = if (unit.label == Portions.GRAMS) "+10" else "+½",
            )
            ChoiceChips(state.units, unit, { if (it.label == Portions.GRAMS) "g" else "${it.label} · ${it.grams.roundToInt()} g" }, { picked ->
                if (picked.label != unit.label) {
                    // Grams carry the amount over; any other unit starts at 1.
                    quantity = if (picked.label == Portions.GRAMS) (quantity * unit.grams).roundToInt().toDouble().coerceAtLeast(1.0) else 1.0
                    unitLabel = picked.label
                }
            })
            if (onCalibrate != null && unit.label != Portions.GRAMS) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("1 ${unit.label} = ${unit.grams.roundToInt()} g", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { calibrating = true }) { Text("Change") }
                }
            }
            Text("Origin", style = MaterialTheme.typography.labelLarge)
            ChoiceChips(Origin.entries, origin, FoodText::origin, { origin = it })
            if (origin == Origin.ORDERED) Text(FoodText.ORDERED_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (slot != null) {
                Text("Meal", style = MaterialTheme.typography.labelLarge)
                val shown = slots.filter { !it.removed || it.key == slot }
                ChoiceChips(shown, shown.firstOrNull { it.key == slot }, { it.name }, { slot = it.key })
            }
            Text(FoodText.macros(macrosFor(quantity, unit)), style = MaterialTheme.typography.titleMedium)
            Button(onClick = { onSave(quantity, unit, origin, slot) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Save") }
            if (onDelete != null) {
                OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Delete") }
            }
        }
    }

    if (typing) {
        val grams = unit.label == Portions.GRAMS
        val shown = if (quantity % 1.0 == 0.0) "${quantity.toLong()}" else "$quantity"
        NumberEntryDialog("Amount", shown, decimal = true, onDismiss = { typing = false }, suffix = unit.label) { text ->
            typing = false
            parseNumber(text)?.takeIf { it > 0 && (grams && it <= 5000 || !grams && it <= 50) }?.let { quantity = it }
        }
    }
    if (calibrating && onCalibrate != null) {
        NumberEntryDialog("Grams in 1 ${unit.label}", "${unit.grams.roundToInt()}", decimal = true, onDismiss = { calibrating = false }, suffix = "g") { text ->
            calibrating = false
            parseNumber(text)?.takeIf { it in 1.0..3000.0 }?.let { onCalibrate(unit.label, it) }
        }
    }
}

/** Accepts "1.5", "1,5" and "½". */
internal fun parseNumber(text: String): Double? {
    val t = text.trim().replace(',', '.')
    return when {
        t == "½" -> 0.5
        t.endsWith("½") -> t.dropLast(1).toDoubleOrNull()?.plus(0.5)
        else -> t.toDoubleOrNull()
    }?.takeIf { it.isFinite() }
}

/** A quick add's values (NU-14). Null carbs or fat are unknown. [slot] is set when editing. */
data class QuickAddValues(
    val name: String?,
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double?,
    val fatG: Double?,
    val origin: Origin,
    val slot: String? = null,
)

/** Quick add (NU-14): calories and protein, with optional carbs, fat and a name. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddSheet(
    initial: QuickAddValues?,
    slots: List<MealSlot>,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onSave: (QuickAddValues) -> Unit,
) {
    fun num(d: Double?) = d?.let { if (it % 1.0 == 0.0) "${it.toLong()}" else "%.1f".format(it) }.orEmpty()
    var name by remember { mutableStateOf(initial?.name?.takeIf { it != "Quick add" }.orEmpty()) }
    var kcal by remember { mutableStateOf(num(initial?.kcal)) }
    var protein by remember { mutableStateOf(num(initial?.proteinG)) }
    var carbs by remember { mutableStateOf(num(initial?.carbsG)) }
    var fat by remember { mutableStateOf(num(initial?.fatG)) }
    var more by remember { mutableStateOf(initial?.carbsG != null || initial?.fatG != null) }
    var origin by remember { mutableStateOf(initial?.origin ?: Origin.HOME) }
    var slot by remember { mutableStateOf(initial?.slot) }
    val kcalValue = parseNumber(kcal)?.takeIf { it in 0.0..10_000.0 }
    val proteinValue = if (protein.isBlank()) 0.0 else parseNumber(protein)?.takeIf { it in 0.0..1_000.0 }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Quick add", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Calories", kcal, "kcal", Modifier.weight(1f)) { kcal = it }
                NumberField("Protein", protein, "g", Modifier.weight(1f)) { protein = it }
            }
            if (more) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Carbs", carbs, "g", Modifier.weight(1f)) { carbs = it }
                    NumberField("Fat", fat, "g", Modifier.weight(1f)) { fat = it }
                }
            } else {
                TextButton(onClick = { more = true }) { Text("Add carbs and fat") }
            }
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ChoiceChips(Origin.entries, origin, FoodText::origin, { origin = it })
            if (slot != null) {
                val shown = slots.filter { !it.removed || it.key == slot }
                ChoiceChips(shown, shown.firstOrNull { it.key == slot }, { it.name }, { slot = it.key })
            }
            Button(
                onClick = {
                    onSave(QuickAddValues(name, kcalValue!!, proteinValue!!, parseNumber(carbs)?.takeIf { it >= 0 }, parseNumber(fat)?.takeIf { it >= 0 }, origin, slot))
                },
                enabled = kcalValue != null && proteinValue != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text("Save") }
            if (onDelete != null) OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Delete") }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, suffix: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = value.isNotBlank() && parseNumber(value)?.let { it < 0 } != false,
        modifier = modifier,
    )
}

/**
 * A custom food (NU-12): per 100 g or per serving. A serving is a unit (the region's, or "serving") and its
 * weight, prefilled for household measures. Switching between the two converts the numbers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomFoodSheet(
    initial: CustomFood?,
    initialName: String,
    servingUnits: List<String>,
    household: Map<String, Double>,
    defaultDiet: FoodDiet,
    onDismiss: () -> Unit,
    onSave: (CustomFood) -> Unit,
) {
    fun num(d: Double?) = d?.let { if (it % 1.0 == 0.0) "${it.toLong()}" else "%.1f".format(it) }.orEmpty()
    val start = initial?.per100g
    var name by remember { mutableStateOf(initial?.name ?: initialName) }
    var brand by remember { mutableStateOf(initial?.brand.orEmpty()) }
    val units = (listOf(SERVING) + servingUnits + listOfNotNull(initial?.serving?.first)).distinct()
    var perServing by remember { mutableStateOf(false) }
    var servingUnit by remember { mutableStateOf(initial?.serving?.first ?: SERVING) }
    var servingGrams by remember { mutableStateOf(num(initial?.serving?.second)) }
    var kcal by remember { mutableStateOf(num(start?.kcal)) }
    var protein by remember { mutableStateOf(num(start?.proteinG)) }
    var carbs by remember { mutableStateOf(num(start?.carbsG)) }
    var fat by remember { mutableStateOf(num(start?.fatG)) }
    var fiber by remember { mutableStateOf(num(start?.fiberG?.takeIf { it > 0 })) }
    var diet by remember { mutableStateOf(initial?.diet ?: defaultDiet) }

    val grams = parseNumber(servingGrams)?.takeIf { it in 1.0..3000.0 }
    fun values() = listOf(kcal, protein, carbs, fat, fiber).map { if (it.isBlank()) 0.0 else parseNumber(it)?.takeIf { v -> v >= 0 } }
    val parsed = values()
    val valid = name.isNotBlank() && parseNumber(kcal) != null && parsed.all { it != null } && (!perServing || grams != null) &&
        (servingGrams.isBlank() || grams != null)

    /** Converts the numbers you've typed between per 100 g and per serving; blank fields stay blank. */
    fun convert(toServing: Boolean) {
        val g = grams ?: return
        val factor = if (toServing) g / 100.0 else 100.0 / g
        fun scale(text: String) = if (text.isBlank()) text else parseNumber(text)?.let { num(it * factor) } ?: text
        kcal = scale(kcal); protein = scale(protein); carbs = scale(carbs); fat = scale(fat); fiber = scale(fiber)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (initial == null) "New food" else "Edit food", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = brand, onValueChange = { brand = it }, label = { Text("Brand (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Serving", style = MaterialTheme.typography.labelLarge)
            ChoiceChips(units, servingUnit, { it }, { unit ->
                servingUnit = unit
                servingGrams = num(household[unit])
            })
            NumberField(if (perServing) "Weight of 1 $servingUnit" else "Weight of 1 $servingUnit (optional)", servingGrams, "g", Modifier.fillMaxWidth()) { servingGrams = it }
            Text("Values", style = MaterialTheme.typography.labelLarge)
            ChoiceChips(listOf(false, true), perServing, { if (it) "Per $servingUnit" else "Per 100 g" }, { picked ->
                if (picked != perServing) {
                    convert(toServing = picked)
                    perServing = picked
                }
            })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Calories", kcal, "kcal", Modifier.weight(1f)) { kcal = it }
                NumberField("Protein", protein, "g", Modifier.weight(1f)) { protein = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Carbs", carbs, "g", Modifier.weight(1f)) { carbs = it }
                NumberField("Fat", fat, "g", Modifier.weight(1f)) { fat = it }
            }
            NumberField("Fibre (optional)", fiber, "g", Modifier.fillMaxWidth()) { fiber = it }
            Text("Diet", style = MaterialTheme.typography.labelLarge)
            ChoiceChips(FoodDiet.entries, diet, FoodText::diet, { diet = it })
            Button(
                onClick = {
                    val v = values().map { it!! }
                    val m = Macros(v[0], v[1], v[2], v[3], v[4])
                    val per100 = if (perServing) Per100g.fromServing(grams!!, m) else Per100g(v[0], v[1], v[2], v[3], v[4])
                    onSave(CustomFood(name.trim(), brand.trim().ifEmpty { null }, per100, diet, grams?.let { servingUnit to it }))
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text("Save") }
        }
    }
}

private const val SERVING = "serving"
