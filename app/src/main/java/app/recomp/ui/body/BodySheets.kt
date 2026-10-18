package app.recomp.ui.body

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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.recomp.domain.persona.WeightSource
import app.recomp.ui.components.ChoiceChips
import app.recomp.ui.components.NumberEntryDialog
import app.recomp.ui.components.Stepper
import app.recomp.ui.components.WhenRow
import kotlin.math.roundToInt

/** A weigh-in being logged or edited. [id] is null for a new one. */
data class WeighInDraft(val id: Long?, val weightG: Int, val source: WeightSource, val at: Long)

/** A waist measurement being logged or edited, in tenths of a cm. */
data class WaistDraft(val id: Long?, val tenthsCm: Int, val at: Long)

/**
 * The weigh-in sheet (BM-01, J5): weight with 0.1 kg steps, the source (your primary one by default) and
 * when. Saving is the second tap for a repeat weight.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeighInSheet(draft: WeighInDraft, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (WeighInDraft) -> Unit) {
    var grams by remember { mutableIntStateOf(draft.weightG) }
    var source by remember { mutableStateOf(draft.source) }
    var at by remember { mutableLongStateOf(draft.at) }
    var typing by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (draft.id == null) "Log weight" else "Edit weigh-in", style = MaterialTheme.typography.titleLarge)
            Stepper(
                "Weight",
                BodyText.kg(grams),
                onMinus = { grams = (grams - STEP_G).coerceAtLeast(MIN_G) },
                onPlus = { grams = (grams + STEP_G).coerceAtMost(MAX_G) },
                onValueClick = { typing = true },
                modifier = Modifier.fillMaxWidth(),
                minusLabel = "−0.1",
                plusLabel = "+0.1",
            )
            Text("Source", style = MaterialTheme.typography.labelLarge)
            ChoiceChips(WeightSource.entries, source, BodyText::source, { source = it })
            WhenRow(at, BodyText::dateTime, { at = it })
            Button(onClick = { onSave(WeighInDraft(draft.id, grams, source, at)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Save") }
            if (onDelete != null) OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Delete") }
        }
    }

    if (typing) {
        NumberEntryDialog("Weight", BodyText.oneDecimal(grams / 1000.0), decimal = true, onDismiss = { typing = false }, suffix = "kg") { text ->
            typing = false
            BodyText.parse(text)?.let { (it * 1000).roundToInt() }?.takeIf { it in MIN_G..MAX_G }?.let { grams = it }
        }
    }
}

/** The weekly waist sheet (BM-02): 0.5 cm steps, prefilled from last time, with how to measure. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaistSheet(draft: WaistDraft, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (WaistDraft) -> Unit) {
    var tenths by remember { mutableIntStateOf(draft.tenthsCm) }
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
                BodyText.cm(tenths / 10.0),
                onMinus = { tenths = (tenths - 5).coerceAtLeast(MIN_WAIST) },
                onPlus = { tenths = (tenths + 5).coerceAtMost(MAX_WAIST) },
                onValueClick = { typing = true },
                modifier = Modifier.fillMaxWidth(),
                minusLabel = "−½",
                plusLabel = "+½",
            )
            WhenRow(at, BodyText::dateTime, { at = it })
            Button(onClick = { onSave(WaistDraft(draft.id, tenths, at)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Save") }
            if (onDelete != null) OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Delete") }
        }
    }

    if (typing) {
        NumberEntryDialog("Waist", BodyText.oneDecimal(tenths / 10.0), decimal = true, onDismiss = { typing = false }, suffix = "cm") { text ->
            typing = false
            BodyText.parse(text)?.let { (it * 10).roundToInt() }?.takeIf { it in MIN_WAIST..MAX_WAIST }?.let { tenths = it }
        }
    }
}

private const val STEP_G = 100
private const val MIN_G = 20_000
private const val MAX_G = 300_000
private const val MIN_WAIST = 300
private const val MAX_WAIST = 2500
