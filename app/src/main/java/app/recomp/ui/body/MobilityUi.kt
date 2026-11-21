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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.recomp.data.db.MeasurementEntity
import app.recomp.domain.body.MobilityMarker
import app.recomp.domain.body.MobilityMarkers
import app.recomp.domain.units.UnitStyle
import app.recomp.ui.components.ChoiceChips
import app.recomp.ui.components.LocalUnitStyle
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

    /**
     * Sit-and-reach is a length in the style's unit, past or short of your toes; the squat hold's seconds and the
     * toe touch don't convert (PRD §12.16).
     */
    fun value(m: MobilityMarker, v: Double, style: UnitStyle): String = when (m) {
        MobilityMarker.SIT_AND_REACH -> when {
            BodyText.lengthShowsZero(v, style) -> "At your toes"
            v > 0 -> "${BodyText.length(v, style)} past your toes"
            else -> "${BodyText.length(-v, style)} short"
        }
        MobilityMarker.SQUAT_HOLD -> BodyText.minutesSeconds(v.roundToInt())
        MobilityMarker.TOE_TOUCH -> if (v >= 1.0) "Yes" else "Not yet"
    }
}

/** YG-04: the monthly card. It shows each marker's latest and asks again every 4 weeks; hidden while the setting is off. */
@Composable
fun MobilityCard(history: Map<MobilityMarker, List<MeasurementEntity>>, today: LocalDate, onLog: () -> Unit, onOpen: (MobilityMarker) -> Unit = {}) {
    val style = LocalUnitStyle.current
    val last = history.values.flatten().maxOfOrNull { it.logDate }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Mobility", style = MaterialTheme.typography.titleMedium)
            if (last == null) Text("Three quick tests once a month show whether your mobility work is paying off.")
            MobilityMarker.entries.forEach { m ->
                val rows = history[m].orEmpty()
                rows.firstOrNull()?.let { latest ->
                    // Tapping a marker lists its readings to change or delete one.
                    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "${MobilityText.name(m)} readings") { onOpen(m) }) {
                        Text("${MobilityText.name(m)}: ${MobilityText.value(m, latest.value, style)}", style = MaterialTheme.typography.bodyLarge)
                        rows.getOrNull(1)?.let { before ->
                            Text("Before: ${MobilityText.value(m, before.value, style)} on ${BodyText.date(before.logDate)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
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

/** The markers you took; one left at its start (nothing entered) isn't saved. Sit-and-reach is in exact cm. */
data class MobilityDraft(val reachCm: Double?, val holdSeconds: Int?, val toeTouch: Boolean?, val at: Long) {
    fun values(): Map<MobilityMarker, Double> = buildMap {
        reachCm?.let { put(MobilityMarker.SIT_AND_REACH, it) }
        holdSeconds?.let { put(MobilityMarker.SQUAT_HOLD, clampHold(it).toDouble()) }
        toeTouch?.let { put(MobilityMarker.TOE_TOUCH, if (it) 1.0 else 0.0) }
    }

    companion object {
        /** 30 minutes, the longest hold `MobilityMarkers.validHold` takes. */
        const val MAX_HOLD = 1_800

        fun clampHold(seconds: Int): Int = seconds.coerceIn(0, MAX_HOLD)

        /** What `MobilityMarkers.validReach` takes, in cm. */
        val REACH_CM = -60.0..60.0

        /** One press of the sit-and-reach stepper: 0.5 cm, or ¼ in, in exact cm. */
        fun reachStep(cm: Double, up: Boolean, style: UnitStyle): Double = BodyEntry.lengthStep(cm, up, style, REACH_CM)

        /** A typed sit-and-reach in exact cm, negative when short; the prefill left unchanged keeps [cm]. */
        fun typedReach(text: String, cm: Double, style: UnitStyle): Double? = BodyEntry.typedLength(text, cm, style, REACH_CM)
    }
}

/** YG-04's sheet: each test with its how-to; the squat hold has a stopwatch. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobilitySheet(lastReachCm: Double?, onDismiss: () -> Unit, onSave: (MobilityDraft) -> Unit) {
    val style = LocalUnitStyle.current
    val (minus, plus) = BodyEntry.lengthStepLabels(style)
    // Saveable, so a rotation keeps what's entered and a running stopwatch keeps counting from when it started.
    var reach by rememberSaveable { mutableStateOf<Double?>(null) }
    var hold by rememberSaveable { mutableStateOf<Int?>(null) }
    var toe by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var at by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
    var startedAt by rememberSaveable { mutableStateOf<Long?>(null) }
    val running = startedAt != null
    var elapsed by rememberSaveable { mutableIntStateOf(0) }
    var typing by rememberSaveable { mutableStateOf<MobilityMarker?>(null) }

    LaunchedEffect(startedAt) {
        val start = startedAt ?: return@LaunchedEffect
        while (startedAt != null) {
            elapsed = MobilityDraft.clampHold(((System.currentTimeMillis() - start) / 1000).toInt())
            // A stopwatch left running stops at the longest hold a reading can have.
            if (elapsed >= MobilityDraft.MAX_HOLD) {
                startedAt = null
                hold = elapsed
            }
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
            val r = reach ?: lastReachCm ?: 0.0
            Stepper(
                "Past your toes",
                if (reach == null) "—" else BodyText.signedLengthValue(r, style),
                onMinus = { reach = MobilityDraft.reachStep(r, up = false, style) },
                onPlus = { reach = MobilityDraft.reachStep(r, up = true, style) },
                onValueClick = { typing = MobilityMarker.SIT_AND_REACH },
                modifier = Modifier.fillMaxWidth(),
                minusLabel = minus,
                plusLabel = plus,
                ghost = reach == null,
            )

            Text(MobilityText.name(MobilityMarker.SQUAT_HOLD), style = MaterialTheme.typography.titleMedium)
            Text(MobilityMarkers.how(MobilityMarker.SQUAT_HOLD), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(BodyText.minutesSeconds(if (running) elapsed else hold ?: elapsed), style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (running) {
                            startedAt = null
                            hold = MobilityDraft.clampHold(elapsed)
                        } else {
                            elapsed = 0
                            startedAt = System.currentTimeMillis()
                        }
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                ) { Text(if (running) "Stop" else "Start") }
                OutlinedButton(onClick = { startedAt = null; typing = MobilityMarker.SQUAT_HOLD }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Type it") }
            }

            Text(MobilityText.name(MobilityMarker.TOE_TOUCH), style = MaterialTheme.typography.titleMedium)
            Text(MobilityMarkers.how(MobilityMarker.TOE_TOUCH), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChoiceChips(listOf(true, false), toe, { if (it) "Yes" else "Not yet" }, { toe = if (toe == it) null else it })

            WhenRow(at, BodyText::dateTime, { at = it })
            val draft = MobilityDraft(reach, hold, toe, at)
            Button(onClick = { startedAt = null; onSave(draft) }, enabled = draft.values().isNotEmpty() && !running, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Save") }
        }
    }

    when (typing) {
        MobilityMarker.SIT_AND_REACH -> {
            val r = reach ?: lastReachCm ?: 0.0
            NumberEntryDialog("Sit-and-reach", BodyEntry.lengthText(r, style), decimal = true, onDismiss = { typing = null }, suffix = style.length) { text ->
                typing = null
                MobilityDraft.typedReach(text, r, style)?.takeIf(MobilityMarkers::validReach)?.let { reach = it }
            }
        }
        MobilityMarker.SQUAT_HOLD -> NumberEntryDialog("Deep-squat hold", "${hold ?: elapsed}", decimal = false, onDismiss = { typing = null }, suffix = "s") { text ->
            typing = null
            text.trim().toIntOrNull()?.takeIf(MobilityMarkers::validHold)?.let { hold = it; elapsed = it }
        }
        else -> Unit
    }
}
