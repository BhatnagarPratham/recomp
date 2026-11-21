package app.recomp.ui.body

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.recomp.data.db.MeasurementEntity
import app.recomp.domain.body.MobilityMarker
import app.recomp.domain.body.MobilityMarkers
import app.recomp.ui.components.ChoiceChips
import app.recomp.ui.components.NumberEntryDialog
import app.recomp.ui.components.Stepper
import app.recomp.ui.components.WhenRow
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Words for YG-04's markers, kept apart so they're tested. */
object MobilityText {
    fun name(m: MobilityMarker) = when (m) {
        MobilityMarker.SIT_AND_REACH -> "Sit-and-reach"
        MobilityMarker.SQUAT_HOLD -> "Deep-squat hold"
        MobilityMarker.TOE_TOUCH -> "Toe touch"
    }

    /** "+3.5 cm past your toes", "4 cm short", "45 s", "Yes". */
    fun value(m: MobilityMarker, v: Double): String = when (m) {
        MobilityMarker.SIT_AND_REACH -> when {
            v > 0 -> "${BodyText.oneDecimal(v)} cm past your toes"
            v < 0 -> "${BodyText.oneDecimal(-v)} cm short"
            else -> "At your toes"
        }
        MobilityMarker.SQUAT_HOLD -> BodyText.minutesSeconds(v.roundToInt())
        MobilityMarker.TOE_TOUCH -> if (v >= 1.0) "Yes" else "Not yet"
    }
}

/** YG-04: the monthly card. It shows each marker's latest and asks again every 4 weeks; hidden while the setting is off. */
@Composable
fun MobilityCard(history: Map<MobilityMarker, List<MeasurementEntity>>, today: LocalDate, onLog: () -> Unit) {
    val last = history.values.flatten().maxOfOrNull { it.logDate }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Mobility", style = MaterialTheme.typography.titleMedium)
            if (last == null) Text("Three quick tests once a month show whether your mobility work is paying off.")
            MobilityMarker.entries.forEach { m ->
                val rows = history[m].orEmpty()
                rows.firstOrNull()?.let { latest ->
                    Text("${MobilityText.name(m)}: ${MobilityText.value(m, latest.value)}", style = MaterialTheme.typography.bodyLarge)
                    rows.getOrNull(1)?.let { before ->
                        Text("Before: ${MobilityText.value(m, before.value)} on ${BodyText.date(before.logDate)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (MobilityMarkers.due(last, today)) {
                Button(onClick = onLog, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (last == null) "Take the tests" else "Time for this month's tests") }
            } else {
                OutlinedButton(onClick = onLog, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Take them again") }
                last?.let { Text("Next due ${BodyText.date(it.plusDays(MobilityMarkers.EVERY_DAYS))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/** The markers you took; one left at its start (nothing entered) isn't saved. */
data class MobilityDraft(val reachTenths: Int?, val holdSeconds: Int?, val toeTouch: Boolean?, val at: Long) {
    fun values(): Map<MobilityMarker, Double> = buildMap {
        reachTenths?.let { put(MobilityMarker.SIT_AND_REACH, it / 10.0) }
        holdSeconds?.let { put(MobilityMarker.SQUAT_HOLD, it.toDouble()) }
        toeTouch?.let { put(MobilityMarker.TOE_TOUCH, if (it) 1.0 else 0.0) }
    }
}

/** YG-04's sheet: each test with its how-to; the squat hold has a stopwatch. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobilitySheet(lastReachCm: Double?, onDismiss: () -> Unit, onSave: (MobilityDraft) -> Unit) {
    var reach by remember { mutableStateOf<Int?>(null) }
    var hold by remember { mutableStateOf<Int?>(null) }
    var toe by remember { mutableStateOf<Boolean?>(null) }
    var at by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var running by remember { mutableStateOf(false) }
    var elapsed by remember { mutableIntStateOf(0) }
    var typing by remember { mutableStateOf<MobilityMarker?>(null) }

    LaunchedEffect(running) {
        val start = System.currentTimeMillis() - elapsed * 1000L
        while (running) {
            elapsed = ((System.currentTimeMillis() - start) / 1000).toInt()
            delay(200)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Mobility tests", style = MaterialTheme.typography.titleLarge)
            Text("Skip any you can't do today.", color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text(MobilityText.name(MobilityMarker.SIT_AND_REACH), style = MaterialTheme.typography.titleMedium)
            Text(MobilityMarkers.how(MobilityMarker.SIT_AND_REACH), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val r = reach ?: ((lastReachCm ?: 0.0) * 10).roundToInt()
            Stepper(
                "cm past your toes",
                if (reach == null) "—" else BodyText.signedCm(r / 10.0),
                onMinus = { reach = (r - 5).coerceAtLeast(-600) },
                onPlus = { reach = (r + 5).coerceAtMost(600) },
                onValueClick = { typing = MobilityMarker.SIT_AND_REACH },
                modifier = Modifier.fillMaxWidth(),
                minusLabel = "−½",
                plusLabel = "+½",
                ghost = reach == null,
            )

            Text(MobilityText.name(MobilityMarker.SQUAT_HOLD), style = MaterialTheme.typography.titleMedium)
            Text(MobilityMarkers.how(MobilityMarker.SQUAT_HOLD), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(BodyText.minutesSeconds(if (running) elapsed else hold ?: elapsed), style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (running) {
                            running = false
                            hold = elapsed
                        } else {
                            elapsed = 0
                            running = true
                        }
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                ) { Text(if (running) "Stop" else "Start") }
                OutlinedButton(onClick = { running = false; typing = MobilityMarker.SQUAT_HOLD }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Type it") }
            }

            Text(MobilityText.name(MobilityMarker.TOE_TOUCH), style = MaterialTheme.typography.titleMedium)
            Text(MobilityMarkers.how(MobilityMarker.TOE_TOUCH), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChoiceChips(listOf(true, false), toe, { if (it) "Yes" else "Not yet" }, { toe = if (toe == it) null else it })

            WhenRow(at, BodyText::dateTime, { at = it })
            val draft = MobilityDraft(reach, hold, toe, at)
            Button(onClick = { running = false; onSave(draft) }, enabled = draft.values().isNotEmpty() && !running, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Save") }
        }
    }

    when (typing) {
        MobilityMarker.SIT_AND_REACH -> NumberEntryDialog("Sit-and-reach", BodyText.oneDecimal((reach ?: ((lastReachCm ?: 0.0) * 10).roundToInt()) / 10.0), decimal = true, onDismiss = { typing = null }, suffix = "cm") { text ->
            typing = null
            BodyText.parseSigned(text)?.takeIf(MobilityMarkers::validReach)?.let { reach = (it * 10).roundToInt() }
        }
        MobilityMarker.SQUAT_HOLD -> NumberEntryDialog("Deep-squat hold", "${hold ?: elapsed}", decimal = false, onDismiss = { typing = null }, suffix = "s") { text ->
            typing = null
            text.trim().toIntOrNull()?.takeIf(MobilityMarkers::validHold)?.let { hold = it; elapsed = it }
        }
        else -> Unit
    }
}
