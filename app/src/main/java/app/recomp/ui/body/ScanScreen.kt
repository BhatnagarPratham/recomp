package app.recomp.ui.body

import app.recomp.data.body.ScanImages
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import app.recomp.data.db.ScanDraftEntity
import app.recomp.data.db.ScanSource
import app.recomp.data.repo.BodyRepository
import app.recomp.data.repo.HealthRepository
import app.recomp.data.repo.ScanInput
import app.recomp.data.repo.SegmentalLean
import app.recomp.domain.body.ProtocolCheck
import app.recomp.domain.body.ScanProtocol
import app.recomp.domain.body.ScanSlot
import app.recomp.domain.body.SinceMeal
import app.recomp.data.repo.PersonaRepository
import app.recomp.domain.persona.Units
import app.recomp.domain.units.UnitStyle
import app.recomp.ui.components.ChoiceChips
import app.recomp.ui.components.unitStyle
import app.recomp.ui.components.ConfirmDialog
import app.recomp.ui.components.WhenRow
import app.recomp.ui.navigation.ScanRoute
import app.recomp.ui.onboarding.SwitchRow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The scan form as typed (IB-01). Masses are strings in [style]'s unit, the form's own kg/lb switch (PR-05), since
 * the InBody sheet's unit is the machine's. [shown] keeps the grams behind each prefilled mass, so a field left as
 * it was shown saves its stored grams exactly. [fatPctTyped] stops body fat % following fat mass ÷ weight.
 */
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
    val style: UnitStyle = UnitStyle.METRIC,
    val shown: Map<MassField, Shown> = emptyMap(),
) {
    /** A prefilled mass: the text it was shown as, and its stored grams. */
    data class Shown(val text: String, val grams: Int)

    /** The mass fields, for [shown]. */
    enum class MassField { WEIGHT, MUSCLE, FAT, SEG0, SEG1, SEG2, SEG3, SEG4 }

    private fun text(f: MassField): String = when (f) {
        MassField.WEIGHT -> weight
        MassField.MUSCLE -> muscle
        MassField.FAT -> fatMass
        else -> segments[f.ordinal - MassField.SEG0.ordinal]
    }

    private fun withText(f: MassField, t: String): ScanForm = when (f) {
        MassField.WEIGHT -> copy(weight = t)
        MassField.MUSCLE -> copy(muscle = t)
        MassField.FAT -> copy(fatMass = t)
        else -> copy(segments = segments.toMutableList().also { it[f.ordinal - MassField.SEG0.ordinal] = t })
    }

    /** A mass field's grams: the stored grams when its text is as shown, else what's typed in [style]'s unit, in [kgRange]. */
    private fun grams(f: MassField, kgRange: ClosedFloatingPointRange<Double>): Int? {
        val text = text(f)
        shown[f]?.takeIf { it.text == text.trim() }?.let { return it.grams }
        return BodyText.parseWeight(text, style)?.takeIf { it / 1000.0 in kgRange }
    }

    /**
     * The form in the other unit: each mass is shown again in [units], and one you haven't changed still saves its
     * stored grams. A field that isn't a number is left as typed.
     */
    fun inUnits(units: Units): ScanForm {
        if (units == style.units) return this
        val next = style.copy(units = units)
        var form = copy(style = next)
        val kept = mutableMapOf<MassField, Shown>()
        MassField.entries.forEach { f ->
            val g = shown[f]?.takeIf { it.text == text(f).trim() }?.grams ?: BodyText.parseWeight(text(f), style) ?: return@forEach
            val t = massText(g, next)
            form = form.withText(f, t)
            kept[f] = Shown(t, g)
        }
        return form.copy(shown = kept)
    }

    val weightG: Int? get() = grams(MassField.WEIGHT, 20.0..300.0)
    val muscleG: Int? get() = grams(MassField.MUSCLE, 5.0..150.0)?.takeIf { weightG == null || it < weightG!! }
    val fatG: Int? get() = grams(MassField.FAT, 0.5..250.0)?.takeIf { weightG == null || it < weightG!! }
    val fatPctValue: Double? get() = BodyText.parse(fatPct)?.takeIf { it in 1.0..75.0 }
    val visceralValue: Double? get() = BodyText.parse(visceral)?.takeIf { it in 1.0..60.0 }

    private fun optional(text: String, range: ClosedFloatingPointRange<Double>): Result<Double?> =
        if (text.isBlank()) Result.success(null) else BodyText.parse(text)?.takeIf { it in range }?.let { Result.success(it) } ?: Result.failure(IllegalArgumentException())

    val bmrValue get() = optional(bmr, 500.0..5000.0)
    val ecwValue get() = optional(ecw, 0.2..0.6)
    val scoreValue get() = optional(score, 0.0..200.0)
    /** Segmental lean masses in grams. */
    val segmentValues: List<Result<Int?>>
        get() = segments.indices.map { i ->
            if (segments[i].isBlank()) Result.success(null)
            else grams(MassField.entries[MassField.SEG0.ordinal + i], 0.1..80.0)?.let { Result.success(it) } ?: Result.failure(IllegalArgumentException())
        }

    /** Null until every required field is valid and *Last ate* is answered. */
    fun input(): ScanInput? {
        val segs = segmentValues
        if (listOf(bmrValue, ecwValue, scoreValue).any { it.isFailure } || segs.any { it.isFailure }) return null
        val parts = segs.map { it.getOrNull() }
        val segmental = if (parts.all { it != null }) parts.map { it!! }.let { SegmentalLean(it[0], it[1], it[2], it[3], it[4]) } else null
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
        /** A mass as the form shows it: one decimal in [style]'s unit. */
        fun massText(grams: Int, style: UnitStyle): String = BodyText.weightNumber(grams / 1000.0, style)

        private fun num(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()

        /** Prefilled masses, each remembered with its grams. */
        private fun ScanForm.prefilled(masses: Map<MassField, Int>): ScanForm =
            masses.entries.fold(copy(shown = emptyMap())) { form, (f, g) ->
                val t = massText(g, style)
                form.withText(f, t).copy(shown = form.shown + (f to Shown(t, g)))
            }

        /**
         * An InBody draft (IB-07): its time, weight, body fat % and BMR are filled in, with fat mass worked out
         * from the first two. Muscle and visceral fat are yours to type, and the protocol questions are asked as usual.
         */
        fun of(d: ScanDraftEntity, style: UnitStyle = UnitStyle.METRIC): ScanForm = ScanForm(
            loaded = true,
            at = d.measuredAt,
            fatPct = d.bodyFatPct?.let(::num).orEmpty(),
            fatPctTyped = d.bodyFatPct != null,
            more = d.bmrKcal != null,
            bmr = d.bmrKcal?.toString().orEmpty(),
            style = style,
        ).prefilled(listOfNotNull(MassField.WEIGHT to d.weightG, d.bodyFatPct?.let { MassField.FAT to (d.weightG * it / 100).roundToInt() }).toMap())

        fun of(s: BodyCompositionEntity, style: UnitStyle = UnitStyle.METRIC): ScanForm {
            val seg = BodyRepository.segmental(s.segmentalLeanJson)
            val segs = seg?.let { listOf(it.rightArmG, it.leftArmG, it.trunkG, it.rightLegG, it.leftLegG) }.orEmpty()
            return ScanForm(
                loaded = true,
                id = s.id,
                at = s.measuredAt,
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
                notes = s.notes.orEmpty(),
                style = style,
            ).prefilled(
                mapOf(MassField.WEIGHT to s.weightG, MassField.MUSCLE to s.skeletalMuscleG, MassField.FAT to s.bodyFatG) +
                    segs.mapIndexed { i, g -> MassField.entries[MassField.SEG0.ordinal + i] to g },
            )
        }
    }
}

@HiltViewModel
class ScanViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val body: BodyRepository,
    private val health: HealthRepository,
    private val images: ScanImages,
    private val personas: PersonaRepository,
) : ViewModel() {
    private val scanId = savedState.toRoute<ScanRoute>().scanId.takeIf { it > 0 }

    /** An InBody measurement from Health Connect that this form completes (IB-07). */
    private var draftId = savedState.toRoute<ScanRoute>().draftId.takeIf { it > 0 }

    /** A shared InBody screenshot (IB-03), kept with the scan when it's saved. */
    private val sharedImage = savedState.toRoute<ScanRoute>().image.takeIf { it.isNotEmpty() }

    /** The image pinned above the form: the shared one, or the one a saved scan keeps. */
    private val _image = MutableStateFlow<ImageBitmap?>(null)
    val image: StateFlow<ImageBitmap?> = _image

    private val _form = MutableStateFlow(ScanForm())
    val form: StateFlow<ScanForm> = _form

    val slot: StateFlow<ScanSlot?> = body.scanSlot.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            // The form's kg/lb switch starts at the app's unit and is remembered for this form only (PR-05).
            val style = unitStyle(personas.stored.first().units)
            val existing = scanId?.let { body.scan(it) }
            // A shared screenshot picks up the InBody draft from the same time, if Health Connect has one (PRD §12.11).
            if (existing == null && draftId == null && sharedImage != null) draftId = health.draftForShare(System.currentTimeMillis())?.id
            val draft = if (existing == null) draftId?.let { health.draft(it) } else null
            _form.value = existing?.let { ScanForm.of(it, style) }
                ?: draft?.let { ScanForm.of(it, style).copy(workout = body.trainedBefore(it.measuredAt)) }
                ?: ScanForm(loaded = true, style = style).let { it.copy(workout = body.trainedBefore(it.at)) }
            (sharedImage ?: existing?.imagePath)?.let { _image.value = loadScanImage(images, it) }
        }
    }

    fun update(transform: (ScanForm) -> ScanForm) = _form.update { old ->
        val new = transform(old)
        // Body fat % follows fat mass ÷ weight until you type it yourself.
        if (new.fatPctTyped) new
        else new.copy(fatPct = new.weightG?.let { w -> new.fatG?.let { f -> ScanProtocol.bodyFatPct(w, f) } }?.toString().orEmpty())
    }

    /** The form's kg/lb switch. */
    fun setUnits(units: Units) = update { it.inUnits(units) }

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

    /** True while a save or delete runs, so a second tap can't repeat it. */
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving

    /** What finished; the screen closes on it. The work runs in the view model, so leaving early can't cut it short. */
    private val _done = MutableStateFlow<ScanDone?>(null)
    val done: StateFlow<ScanDone?> = _done

    fun save() {
        if (_saving.value) return
        val input = _form.value.input() ?: return
        _saving.value = true
        viewModelScope.launch {
            try {
                val fromDraft = draftId?.takeIf { _form.value.id == null }
                val withImage = input.copy(imagePath = sharedImage)
                val saved = body.saveScan(if (fromDraft != null) withImage.copy(source = ScanSource.HEALTH_CONNECT) else withImage, _form.value.id)
                // Saved as a scan, so the draft is done and never comes back.
                fromDraft?.let { health.closeDraft(it) }
                _form.update { it.copy(id = saved.id) }
                _done.value = ScanDone.Saved(saved.slotSet)
            } finally {
                _saving.value = false
            }
        }
    }

    fun delete() {
        val id = _form.value.id ?: return
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                body.deleteScan(id)
                _done.value = ScanDone.Deleted
            } finally {
                _saving.value = false
            }
        }
    }
}

/** A finished save (with the usual slot when this first scan set it) or delete. */
sealed interface ScanDone {
    data class Saved(val slotSet: ScanSlot?) : ScanDone
    data object Deleted : ScanDone
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
    val done by viewModel.done.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf(false) }
    // A save that set the usual slot says so first; anything else closes the screen.
    val slotSet = (done as? ScanDone.Saved)?.slotSet
    LaunchedEffect(done) {
        if (done != null && slotSet == null) onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (form.id == null) "Log scan" else "Edit scan") },
                navigationIcon = { TextButton(onClick = onDone) { Text("Back") } },
            )
        },
    ) { padding ->
        if (!form.loaded) return@Scaffold
        val image by viewModel.image.collectAsStateWithLifecycle()
        Column(Modifier.fillMaxSize().padding(padding)) {
        // A shared screenshot stays pinned above the form while you type its numbers (IB-03); tap to see more of it.
        image?.let { bitmap ->
            var tall by remember { mutableStateOf(false) }
            Image(
                bitmap,
                "The InBody result you shared. Tap to " + if (tall) "shrink it" else "see more of it",
                Modifier.fillMaxWidth().height(if (tall) 420.dp else 200.dp).clickable { tall = !tall }.padding(horizontal = 16.dp, vertical = 4.dp),
                contentScale = ContentScale.Fit,
            )
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).imePadding(),
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

            Text("Masses on the sheet are in", style = MaterialTheme.typography.labelLarge)
            ChoiceChips(Units.entries, form.style.units, { UnitStyle(it).weight }, viewModel::setUnits)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Weight", form.weight, form.style.weight, form.weight.isNotBlank() && form.weightG == null, Modifier.weight(1f)) { v -> viewModel.update { it.copy(weight = v) } }
                Field("Skeletal muscle", form.muscle, form.style.weight, form.muscle.isNotBlank() && form.muscleG == null, Modifier.weight(1f)) { v -> viewModel.update { it.copy(muscle = v) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Body fat mass", form.fatMass, form.style.weight, form.fatMass.isNotBlank() && form.fatG == null, Modifier.weight(1f)) { v -> viewModel.update { it.copy(fatMass = v) } }
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
                            Field(name, form.segments[i], form.style.weight, form.segmentValues[i].isFailure, Modifier.weight(1f)) { v ->
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
                onClick = viewModel::save,
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
    }

    slotSet?.let { s ->
        AlertDialog(
            onDismissRequest = onDone,
            title = { Text("Usual scan slot set") },
            text = { Text("Your usual scan slot is now ${BodyText.slot(s)}, from this scan. Later scans are checked against it. You can change it in Settings → Body data.") },
            confirmButton = { TextButton(onClick = onDone) { Text("OK") } },
        )
    }
    if (deleting) {
        ConfirmDialog("Delete this scan?", "Its Scan weigh-in, and any image kept with it, are deleted with it.", confirm = "Delete", onDismiss = { deleting = false }) {
            deleting = false
            viewModel.delete()
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
