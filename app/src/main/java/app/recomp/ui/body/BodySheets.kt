package app.recomp.ui.body

import app.recomp.domain.body.TapeSites
import app.recomp.domain.body.TapeSite
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.recomp.domain.persona.WeightSource
import app.recomp.domain.units.UnitMath
import app.recomp.domain.units.UnitStyle
import app.recomp.ui.components.ChoiceChips
import app.recomp.ui.components.LocalUnitStyle
import app.recomp.ui.components.NumberEntryDialog
import app.recomp.ui.components.Stepper
import app.recomp.ui.components.WhenRow

/** A weigh-in being logged or edited. [id] is null for a new one. */
data class WeighInDraft(
    val id: Long?,
    val weightG: Int,
    val source: WeightSource,
    val at: Long,
    /** *Track sleep* is on, so a new weigh-in also asks about last night (AR-02). */
    val askSleep: Boolean = false,
    /** Prefilled from what's stored for that morning, typed or from a tracker. */
    val sleepHours: Double? = null,
    /** Only a value you changed is saved, as typed; an untouched prefill stays what it was. */
    val sleepTouched: Boolean = false,
)

/** A waist measurement being logged or edited, in exact cm (PR-05: never via tenths, so ¼ in readings don't drift). */
data class WaistDraft(val id: Long?, val cm: Double, val at: Long)

/**
 * *Log measurements* (BM-03): the sites due today, waist first, each in exact cm, prefilled from last time
 * (PR-05: never via tenths, so ¼ in readings don't drift).
 */
data class TapeDraft(val sites: List<Pair<TapeSite, Double>>, val at: Long)

/**
 * The weigh-in and waist sheets' maths in the persona's units (PR-05), kept out of composition so it's tested.
 * State stays in grams and exact cm; what you type is converted once, and text left as shown keeps the stored value.
 */
object BodyEntry {
    const val MIN_G = 20_000
    const val MAX_G = 300_000
    const val MIN_WAIST_CM = 30.0
    const val MAX_WAIST_CM = 250.0

    /** One press of the weight stepper: 0.1 kg, or 0.2 lb on the lb grid. */
    fun weightStep(grams: Int, up: Boolean, style: UnitStyle): Int = BodyText.weightStep(grams, up, style).coerceIn(MIN_G, MAX_G)

    /** The stepper's button labels: "−0.1" and "+0.1", or "−0.2" and "+0.2" in lb. */
    fun weightStepLabels(style: UnitStyle): Pair<String, String> = style.mark(if (style.imperial) "0.2" else "0.1").let { "−$it" to "+$it" }

    /** The typed-weight dialog's prefill. */
    fun weightText(grams: Int, style: UnitStyle): String = BodyText.weightNumber(grams / 1000.0, style)

    /** A typed weight in grams; the prefill left unchanged keeps [grams]. Null when it's not a weight in range. */
    fun typedWeight(text: String, grams: Int, style: UnitStyle): Int? =
        if (text.trim() == weightText(grams, style)) grams else BodyText.parseWeight(text, style)?.takeIf { it in MIN_G..MAX_G }

    /** One press of the waist stepper: 0.5 cm, or ¼ in. */
    fun waistStep(cm: Double, up: Boolean, style: UnitStyle): Double = lengthStep(cm, up, style, MIN_WAIST_CM..MAX_WAIST_CM)

    fun waistText(cm: Double, style: UnitStyle): String = lengthText(cm, style)

    /** A typed waist in exact cm; see [typedLength]. Null when it's not a waist in range. */
    fun typedWaist(text: String, cm: Double, style: UnitStyle): Double? = typedLength(text, cm, style, MIN_WAIST_CM..MAX_WAIST_CM)

    // Any length entry: the other tape sites (BM-03) and sit-and-reach (YG-04), which may be negative.

    /** An arm can be far smaller than any waist; the range `validReading` takes for a tape site. */
    const val MIN_TAPE_CM = 10.0
    val TAPE_CM = MIN_TAPE_CM..MAX_WAIST_CM

    /** One press of a length stepper: 0.5 cm, or ¼ in, kept in [range]. */
    fun lengthStep(cm: Double, up: Boolean, style: UnitStyle, range: ClosedFloatingPointRange<Double>): Double =
        BodyText.lengthStep(cm, up, style).coerceIn(range)

    /** The stepper's button labels: "−½" and "+½", or "−¼" and "+¼" in inches. */
    fun lengthStepLabels(style: UnitStyle): Pair<String, String> = (if (style.imperial) "¼" else "½").let { "−$it" to "+$it" }

    /** A typed-length dialog's prefill: the number alone, in the style's unit. */
    fun lengthText(cm: Double, style: UnitStyle): String = BodyText.lengthNumber(cm, style)

    /**
     * A typed length in exact cm: cm to 0.1, or inches to 0.01 then × 2.54; a minus sign or hyphen makes it negative.
     * The prefill left unchanged keeps [cm]. Null when it's not a number in [range].
     */
    fun typedLength(text: String, cm: Double, style: UnitStyle, range: ClosedFloatingPointRange<Double>): Double? {
        if (text.trim() == lengthText(cm, style)) return cm
        val value = BodyText.parseSigned(text) ?: return null
        val typed = if (style.imperial) UnitMath.inToCm(Math.round(value * 100) / 100.0) else Math.round(value * 10) / 10.0
        return typed.takeIf { it in range }
    }
}

/**
 * The weigh-in sheet (BM-01, J5): weight with 0.1 kg or 0.2 lb steps, the source (your primary one by default) and
 * when. Saving is the second tap for a repeat weight.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeighInSheet(draft: WeighInDraft, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (WeighInDraft) -> Unit) {
    val style = LocalUnitStyle.current
    val (minus, plus) = BodyEntry.weightStepLabels(style)
    var grams by remember { mutableIntStateOf(draft.weightG) }
    var source by remember { mutableStateOf(draft.source) }
    var at by remember { mutableLongStateOf(draft.at) }
    var typing by remember { mutableStateOf(false) }
    var sleep by remember { mutableStateOf(draft.sleepHours) }
    var sleepTouched by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (draft.id == null) "Log weight" else "Edit weigh-in", style = MaterialTheme.typography.titleLarge)
            Stepper(
                "Weight",
                BodyText.weight(grams, style),
                onMinus = { grams = BodyEntry.weightStep(grams, up = false, style) },
                onPlus = { grams = BodyEntry.weightStep(grams, up = true, style) },
                onValueClick = { typing = true },
                modifier = Modifier.fillMaxWidth(),
                minusLabel = minus,
                plusLabel = plus,
            )
            Text("Source", style = MaterialTheme.typography.labelLarge)
            ChoiceChips(WeightSource.entries, source, BodyText::source, { source = it })
            WhenRow(at, BodyText::dateTime, { at = it })
            if (draft.askSleep) {
                app.recomp.ui.health.SleepChips(sleep, { sleep = it; sleepTouched = true })
            }
            Button(onClick = { onSave(draft.copy(weightG = grams, source = source, at = at, sleepHours = sleep, sleepTouched = sleepTouched)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Save") }
            if (onDelete != null) OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Delete") }
        }
    }

    if (typing) {
        NumberEntryDialog("Weight", BodyEntry.weightText(grams, style), decimal = true, onDismiss = { typing = false }, suffix = style.weight) { text ->
            typing = false
            BodyEntry.typedWeight(text, grams, style)?.let { grams = it }
        }
    }
}

/** The weekly waist sheet (BM-02): 0.5 cm or ¼ in steps, prefilled from last time, with how to measure. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaistSheet(draft: WaistDraft, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (WaistDraft) -> Unit) {
    val style = LocalUnitStyle.current
    var cm by remember { mutableDoubleStateOf(draft.cm) }
    var at by remember { mutableLongStateOf(draft.at) }
    var typing by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (draft.id == null) "Log waist" else "Edit waist", style = MaterialTheme.typography.titleLarge)
            Text(BodyText.WAIST_HOW, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Stepper(
                "Waist",
                BodyText.length(cm, style),
                onMinus = { cm = BodyEntry.waistStep(cm, up = false, style) },
                onPlus = { cm = BodyEntry.waistStep(cm, up = true, style) },
                onValueClick = { typing = true },
                modifier = Modifier.fillMaxWidth(),
                minusLabel = BodyEntry.lengthStepLabels(style).first,
                plusLabel = BodyEntry.lengthStepLabels(style).second,
            )
            WhenRow(at, BodyText::dateTime, { at = it })
            Button(onClick = { onSave(WaistDraft(draft.id, cm, at)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Save") }
            if (onDelete != null) OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Delete") }
        }
    }

    if (typing) {
        NumberEntryDialog("Waist", BodyEntry.waistText(cm, style), decimal = true, onDismiss = { typing = false }, suffix = style.length) { text ->
            typing = false
            BodyEntry.typedWaist(text, cm, style)?.let { cm = it }
        }
    }
}

/**
 * *Log measurements* (PRD §12.13): waist first, then the other chosen sites once they're 4 weeks old. Each has
 * its how-to and a 0.5 cm or ¼ in stepper, prefilled from last time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasurementsSheet(draft: TapeDraft, onDismiss: () -> Unit, onSave: (TapeDraft) -> Unit) {
    val style = LocalUnitStyle.current
    val (minus, plus) = BodyEntry.lengthStepLabels(style)
    var values by remember { mutableStateOf(draft.sites.toMap()) }
    var at by remember { mutableLongStateOf(draft.at) }
    var typing by remember { mutableStateOf<TapeSite?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Log measurements", style = MaterialTheme.typography.titleLarge)
            draft.sites.forEach { (site, _) ->
                val cm = values.getValue(site)
                Text(TapeSites.how(site), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Stepper(
                    BodyText.site(site),
                    BodyText.length(cm, style),
                    onMinus = { values = values + (site to BodyEntry.lengthStep(cm, up = false, style, BodyEntry.TAPE_CM)) },
                    onPlus = { values = values + (site to BodyEntry.lengthStep(cm, up = true, style, BodyEntry.TAPE_CM)) },
                    onValueClick = { typing = site },
                    modifier = Modifier.fillMaxWidth(),
                    minusLabel = minus,
                    plusLabel = plus,
                )
            }
            WhenRow(at, BodyText::dateTime, { at = it })
            Button(onClick = { onSave(TapeDraft(draft.sites.map { (site, _) -> site to values.getValue(site) }, at)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Save") }
        }
    }

    typing?.let { site ->
        val cm = values.getValue(site)
        NumberEntryDialog(BodyText.site(site), BodyEntry.lengthText(cm, style), decimal = true, onDismiss = { typing = null }, suffix = style.length) { text ->
            typing = null
            BodyEntry.typedLength(text, cm, style, BodyEntry.TAPE_CM)?.let { values = values + (site to it) }
        }
    }
}
