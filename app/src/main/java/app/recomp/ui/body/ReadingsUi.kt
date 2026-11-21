package app.recomp.ui.body

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.recomp.data.db.MeasurementEntity
import app.recomp.data.db.MeasurementType
import app.recomp.data.repo.mobilityMarker
import app.recomp.data.repo.tapeSite
import app.recomp.data.repo.validReading
import app.recomp.domain.units.UnitStyle
import app.recomp.ui.components.ChoiceChips
import app.recomp.ui.components.LocalUnitStyle
import app.recomp.ui.components.NumberEntryDialog
import app.recomp.ui.components.Stepper
import app.recomp.ui.components.WhenRow

/** Editing a tape or mobility reading (M17): its id, type, value and when it was taken. */
data class ReadingDraft(val id: Long, val type: MeasurementType, val value: Double, val at: Long) {
    constructor(row: MeasurementEntity) : this(row.id, row.type, row.value, row.measuredAt)
}

/** Words for a tape or mobility reading. */
object ReadingText {
    fun name(type: MeasurementType): String =
        type.tapeSite()?.let(BodyText::site) ?: type.mobilityMarker()?.let(MobilityText::name) ?: type.name.lowercase()

    fun value(type: MeasurementType, v: Double, style: UnitStyle): String =
        type.mobilityMarker()?.let { MobilityText.value(it, v, style) } ?: BodyText.length(v, style)

    /** Seconds and yes/no don't convert; tape sites and sit-and-reach are lengths. */
    fun isLength(type: MeasurementType): Boolean = type != MeasurementType.SQUAT_HOLD && type != MeasurementType.TOE_TOUCH

    /** The value one ½ cm, ¼ in or 5 s step away (lengths in exact cm), kept in range; a yes/no has no steps. */
    fun step(type: MeasurementType, v: Double, up: Boolean, style: UnitStyle): Double {
        val next = if (isLength(type)) BodyText.lengthStep(v, up, style) else v + if (up) 5 else -5
        return if (validReading(type, next)) next else v
    }

    /** The typed-value dialog's prefill: whole seconds, or the length in the style's unit. */
    fun text(type: MeasurementType, v: Double, style: UnitStyle): String =
        if (isLength(type)) BodyEntry.lengthText(v, style) else "${v.toInt()}"

    /** A typed value; a length's prefill left unchanged keeps [v]. Null when it's out of the reading's range. */
    fun typed(type: MeasurementType, text: String, v: Double, style: UnitStyle): Double? {
        val parsed = if (isLength(type)) BodyEntry.typedLength(text, v, style, Double.NEGATIVE_INFINITY..Double.POSITIVE_INFINITY) else text.trim().toIntOrNull()?.toDouble()
        return parsed?.takeIf { validReading(type, it) }
    }
}

/** A tape site's or marker's readings, newest first; tap one to change or delete it. */
@Composable
fun ReadingsDialog(type: MeasurementType, rows: List<MeasurementEntity>, onPick: (MeasurementEntity) -> Unit, onDismiss: () -> Unit) {
    val style = LocalUnitStyle.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ReadingText.name(type)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (rows.isEmpty()) Text("No readings.")
                rows.forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Edit ${ReadingText.name(type).lowercase()}") { onPick(row) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(BodyText.date(row.logDate), Modifier.weight(1f))
                        Text(ReadingText.value(type, row.value, style))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Changes or deletes one tape or mobility reading, with the same steps as when it was logged. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingSheet(draft: ReadingDraft, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (ReadingDraft) -> Unit) {
    val style = LocalUnitStyle.current
    var value by remember { mutableDoubleStateOf(draft.value) }
    var at by remember { mutableLongStateOf(draft.at) }
    var typing by remember { mutableStateOf(false) }
    val type = draft.type

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Edit ${ReadingText.name(type).lowercase()}", style = MaterialTheme.typography.titleLarge)
            if (type == MeasurementType.TOE_TOUCH) {
                ChoiceChips(listOf(true, false), value >= 1.0, { if (it) "Yes" else "Not yet" }, { value = if (it) 1.0 else 0.0 })
            } else {
                val (minus, plus) = if (ReadingText.isLength(type)) BodyEntry.lengthStepLabels(style) else "−5" to "+5"
                Stepper(
                    ReadingText.name(type),
                    if (type == MeasurementType.SIT_AND_REACH) BodyText.signedLengthValue(value, style) else ReadingText.value(type, value, style),
                    onMinus = { value = ReadingText.step(type, value, up = false, style) },
                    onPlus = { value = ReadingText.step(type, value, up = true, style) },
                    onValueClick = { typing = true },
                    modifier = Modifier.fillMaxWidth(),
                    minusLabel = minus,
                    plusLabel = plus,
                )
            }
            WhenRow(at, BodyText::dateTime, { at = it })
            Button(onClick = { onSave(draft.copy(value = value, at = at)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Save") }
            OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Delete") }
        }
    }

    if (typing) {
        val length = ReadingText.isLength(type)
        NumberEntryDialog(ReadingText.name(type), ReadingText.text(type, value, style), decimal = length, onDismiss = { typing = false }, suffix = if (length) style.length else "s") { text ->
            typing = false
            ReadingText.typed(type, text, value, style)?.let { value = it }
        }
    }
}
