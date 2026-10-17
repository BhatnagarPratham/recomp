package app.recomp.ui.body

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.recomp.data.db.BodyCompositionEntity
import app.recomp.data.repo.BodyRepository
import app.recomp.data.repo.ScanInput
import app.recomp.data.repo.ScanSaved
import app.recomp.data.repo.SegmentalLean
import app.recomp.domain.body.ProtocolCheck
import app.recomp.domain.body.ScanProtocol
import app.recomp.domain.body.ScanSlot
import app.recomp.domain.body.SinceMeal
import app.recomp.ui.components.ChoiceChips
import app.recomp.ui.components.ConfirmDialog
import app.recomp.ui.components.WhenRow
import app.recomp.ui.navigation.ScanRoute
import app.recomp.ui.onboarding.SwitchRow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The scan form as typed (IB-01). Masses are kg strings; [fatPctTyped] stops body fat % following fat mass ÷ weight. */
data class ScanForm(
    val loaded: Boolean = false,
    val id: Long? = null,
    val at: Long = System.currentTimeMillis(),
    val weight: String = "",
    val muscle: String = "",
    val fatMass: String = "",
    val fatPct: String = "",
    val fatPctTyped: Boolean = false,
    val visceral: String = "",
    val sinceMeal: SinceMeal? = null,
    val workout: Boolean = false,
    val workoutTouched: Boolean = false,
    val more: Boolean = false,
    val bmr: String = "",
    val ecw: String = "",
    val score: String = "",
    val segments: List<String> = List(5) { "" },
    val notes: String = "",
) {
    private fun kgToG(text: String, range: ClosedFloatingPointRange<Double>): Int? = BodyText.parse(text)?.takeIf { it in range }?.let { (it * 1000).roundToInt() }

    val weightG: Int? get() = kgToG(weight, 20.0..300.0)
    val muscleG: Int? get() = kgToG(muscle, 5.0..150.0)?.takeIf { weightG == null || it < weightG!! }
    val fatG: Int? get() = kgToG(fatMass, 0.5..250.0)?.takeIf { weightG == null || it < weightG!! }
    val fatPctValue: Double? get() = BodyText.parse(fatPct)?.takeIf { it in 1.0..75.0 }
    val visceralValue: Double? get() = BodyText.parse(visceral)?.takeIf { it in 1.0..60.0 }

    private fun optional(text: String, range: ClosedFloatingPointRange<Double>): Result<Double?> =
        if (text.isBlank()) Result.success(null) else BodyText.parse(text)?.takeIf { it in range }?.let { Result.success(it) } ?: Result.failure(IllegalArgumentException())

    val bmrValue get() = optional(bmr, 500.0..5000.0)
    val ecwValue get() = optional(ecw, 0.2..0.6)
    val scoreValue get() = optional(score, 0.0..200.0)
    val segmentValues get() = segments.map { optional(it, 0.1..80.0) }

    /** Null until every required field is valid and *Last ate* is answered. */
    fun input(): ScanInput? {
        val segs = segmentValues
        if (listOf(bmrValue, ecwValue, scoreValue).any { it.isFailure } || segs.any { it.isFailure }) return null
        val parts = segs.map { it.getOrNull() }
        val segmental = if (parts.all { it != null }) parts.map { (it!! * 1000).roundToInt() }.let { SegmentalLean(it[0], it[1], it[2], it[3], it[4]) } else null
        if (parts.any { it != null } && segmental == null) return null
        return ScanInput(
            measuredAt = at,
            weightG = weightG ?: return null,
            skeletalMuscleG = muscleG ?: return null,
            bodyFatG = fatG ?: return null,
            bodyFatPct = fatPctValue ?: return null,
            visceralFatLevel = visceralValue ?: return null,
            minsSinceMeal = (sinceMeal ?: return null).minutes,
            workoutBefore = workout,
            bmrKcal = bmrValue.getOrNull()?.roundToInt(),
            ecwTbwRatio = ecwValue.getOrNull(),
            score = scoreValue.getOrNull()?.roundToInt(),
            segmental = segmental,
            notes = notes,
        )
    }

    companion object {
        private fun kg(g: Int) = BodyText.oneDecimal(g / 1000.0)
        private fun num(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()

        fun of(s: BodyCompositionEntity): ScanForm {
            val seg = BodyRepository.segmental(s.segmentalLeanJson)
            return ScanForm(
                loaded = true,
                id = s.id,
                at = s.measuredAt,
                weight = kg(s.weightG),
                muscle = kg(s.skeletalMuscleG),
                fatMass = kg(s.bodyFatG),
                fatPct = num(s.bodyFatPct),
                fatPctTyped = true,
                visceral = num(s.visceralFatLevel),
                sinceMeal = SinceMeal.of(s.minsSinceMeal),
                workout = s.workoutBeforeScan,
                workoutTouched = true,
                more = s.bmrKcal != null || s.ecwTbwRatio != null || s.inbodyScore != null || seg != null || s.notes != null,
                bmr = s.bmrKcal?.toString().orEmpty(),
                ecw = s.ecwTbwRatio?.let(::num).orEmpty(),
                score = s.inbodyScore?.toString().orEmpty(),
                segments = seg?.let { listOf(it.rightArmG, it.leftArmG, it.trunkG, it.rightLegG, it.leftLegG).map(::kg) } ?: List(5) { "" },
                notes = s.notes.orEmpty(),
            )
        }
    }
}

@HiltViewModel
class ScanViewModel @Inject constructor(savedState: SavedStateHandle, private val body: BodyRepository) : ViewModel() {
    private val scanId = savedState.toRoute<ScanRoute>().scanId.takeIf { it > 0 }

    private val _form = MutableStateFlow(ScanForm())
    val form: StateFlow<ScanForm> = _form

    val slot: StateFlow<ScanSlot?> = body.scanSlot.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            val existing = scanId?.let { body.scan(it) }
            _form.value = existing?.let(ScanForm::of) ?: ScanForm(loaded = true).let { it.copy(workout = body.trainedBefore(it.at)) }
        }
    }

    fun update(transform: (ScanForm) -> ScanForm) = _form.update { old ->
        val new = transform(old)
        // Body fat % follows fat mass ÷ weight until you type it yourself.
        if (new.fatPctTyped) new
        else new.copy(fatPct = new.weightG?.let { w -> new.fatG?.let { f -> ScanProtocol.bodyFatPct(w, f) } }?.toString().orEmpty())
    }

    /** A new time re-checks "trained before", unless you've set it yourself. */
    fun setAt(at: Long) {
        update { it.copy(at = at) }
        if (!_form.value.workoutTouched) viewModelScope.launch {
            val trained = body.trainedBefore(at)
            _form.update { it.copy(workout = trained) }
        }
    }

    fun check(form: ScanForm, slot: ScanSlot?): ProtocolCheck? {
        val since = form.sinceMeal ?: return null
        return body.check(form.at, since.minutes, form.workout, slot)
    }

    /** True while a save runs, so a second tap can't save the scan twice. */
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving

    suspend fun save(): ScanSaved? {
        if (_saving.value) return null
        val input = _form.value.input() ?: return null
        _saving.value = true
        return try {
            body.saveScan(input, _form.value.id).also { saved -> _form.update { it.copy(id = saved.id) } }
        } finally {
            _saving.value = false
        }
    }

    suspend fun delete() = _form.value.id?.let { body.deleteScan(it) }
}

private val SEGMENTS = listOf("Right arm", "Left arm", "Trunk", "Right leg", "Left leg")

/**
 * The scan form (IB-01, IB-02): the protocol first, with a live verdict, then the key numbers, the
 * conditions, and optional extras. Saving also records the scan's weight as a *Scan* weigh-in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(onDone: () -> Unit, viewModel: ScanViewModel = hiltViewModel()) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val slot by viewModel.slot.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var slotSet by remember { mutableStateOf<ScanSlot?>(null) }
    var deleting by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (form.id == null) "Log scan" else "Edit scan") },
                navigationIcon = { TextButton(onClick = onDone) { Text("Back") } },
            )
        },
    ) { padding ->
        if (!form.loaded) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val check = viewModel.check(form, slot)
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Protocol", style = MaterialTheme.typography.titleMedium)
                    Text(BodyText.protocol(slot))
                    if (check != null) {
                        Text(
                            (if (check.ok) "✓ " else "◌ ") + BodyText.verdict(check.issues, check.minutesFromSlot),
                            color = if (check.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                }
            }

            WhenRow(form.at, BodyText::dateTime, viewModel::setAt)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Weight", form.weight, "kg", form.weight.isNotBlank() && form.weightG == null, Modifier.weight(1f)) { v -> viewModel.update { it.copy(weight = v) } }
                Field("Skeletal muscle", form.muscle, "kg", form.muscle.isNotBlank() && form.muscleG == null, Modifier.weight(1f)) { v -> viewModel.update { it.copy(muscle = v) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Body fat mass", form.fatMass, "kg", form.fatMass.isNotBlank() && form.fatG == null, Modifier.weight(1f)) { v -> viewModel.update { it.copy(fatMass = v) } }
                Field("Body fat", form.fatPct, "%", form.fatPct.isNotBlank() && form.fatPctValue == null, Modifier.weight(1f)) { v ->
                    viewModel.update { it.copy(fatPct = v, fatPctTyped = v.isNotBlank()) }
                }
            }
            Field("Visceral fat level", form.visceral, null, form.visceral.isNotBlank() && form.visceralValue == null, Modifier.fillMaxWidth()) { v -> viewModel.update { it.copy(visceral = v) } }

            Text("Last ate", style = MaterialTheme.typography.labelLarge)
            ChoiceChips(SinceMeal.entries, form.sinceMeal, BodyText::sinceMeal, { s -> viewModel.update { it.copy(sinceMeal = s) } })
            SwitchRow(
                "Trained before the scan today",
                "Set from your workout log; change it if that's wrong.",
                form.workout,
                onChange = { on -> viewModel.update { it.copy(workout = on, workoutTouched = true) } },
            )

            if (!form.more) {
                TextButton(onClick = { viewModel.update { it.copy(more = true) } }) { Text("More: BMR, ECW/TBW, score, segmental lean, notes") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("BMR", form.bmr, "kcal", form.bmrValue.isFailure, Modifier.weight(1f)) { v -> viewModel.update { it.copy(bmr = v) } }
                    Field("ECW/TBW", form.ecw, null, form.ecwValue.isFailure, Modifier.weight(1f)) { v -> viewModel.update { it.copy(ecw = v) } }
                    Field("Score", form.score, null, form.scoreValue.isFailure, Modifier.weight(1f)) { v -> viewModel.update { it.copy(score = v) } }
                }
                Text("Segmental lean mass (all five, or none)", style = MaterialTheme.typography.labelLarge)
                SEGMENTS.chunked(2).forEachIndexed { row, names ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        names.forEachIndexed { col, name ->
                            val i = row * 2 + col
                            Field(name, form.segments[i], "kg", form.segmentValues[i].isFailure, Modifier.weight(1f)) { v ->
                                viewModel.update { it.copy(segments = it.segments.toMutableList().also { s -> s[i] = v }) }
                            }
                        }
                        if (names.size == 1) Column(Modifier.weight(1f)) {}
                    }
                }
                OutlinedTextField(
                    value = form.notes,
                    onValueChange = { v -> viewModel.update { it.copy(notes = v) } },
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val input = form.input()
            if (input == null) {
                Text(
                    "Fill in weight, muscle, fat mass, body fat %, visceral fat and when you last ate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = {
                    scope.launch {
                        val saved = viewModel.save() ?: return@launch
                        if (saved.slotSet != null) slotSet = saved.slotSet else onDone()
                    }
                },
                enabled = input != null && !saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text("Save scan") }
            if (form.id != null) {
                OutlinedButton(onClick = { deleting = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Delete scan") }
            }
            Text(
                "Saving also records this weight as a Scan weigh-in.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }

    slotSet?.let { s ->
        AlertDialog(
            onDismissRequest = { slotSet = null; onDone() },
            title = { Text("Usual scan slot set") },
            text = { Text("Your usual scan slot is now ${BodyText.slot(s)}, from this scan. Later scans are checked against it. You can change it in Settings → Body data.") },
            confirmButton = { TextButton(onClick = { slotSet = null; onDone() }) { Text("OK") } },
        )
    }
    if (deleting) {
        ConfirmDialog("Delete this scan?", "Its Scan weigh-in is deleted with it.", confirm = "Delete", onDismiss = { deleting = false }) {
            deleting = false
            scope.launch {
                viewModel.delete()
                onDone()
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, suffix: String?, error: Boolean, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        suffix = suffix?.let { { Text(it) } },
        singleLine = true,
        isError = error,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}
