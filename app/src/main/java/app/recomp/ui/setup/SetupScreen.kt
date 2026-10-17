package app.recomp.ui.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.recomp.data.repo.ExtraDraft
import app.recomp.data.repo.TrainingRepository
import app.recomp.data.repo.TrainingSetup
import app.recomp.data.repo.WalkthroughItem
import app.recomp.domain.model.Equipment
import app.recomp.domain.model.LoadType
import app.recomp.domain.model.SessionKind
import app.recomp.domain.persona.Experience
import app.recomp.domain.persona.ExperiencePolicy
import app.recomp.domain.persona.SplitTemplate
import app.recomp.domain.training.Loads
import app.recomp.ui.components.ChoiceChips
import app.recomp.ui.components.DayChips
import app.recomp.ui.components.Labels
import app.recomp.ui.components.NumberEntryDialog
import app.recomp.ui.components.Stepper
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SetupStep { SPLIT, EXPERIENCE, PLAN, EQUIPMENT }

data class SetupUi(
    val step: SetupStep = SetupStep.SPLIT,
    val split: SplitTemplate = SplitTemplate.PPL,
    val currentSplit: SplitTemplate? = null,
    val experience: Experience = Experience.BEGINNER,
    val strengthDays: Int = SplitTemplate.PPL.defaultStrengthDaysPerWeek,
    val extras: List<ExtraDraft> = emptyList(),
    val walkthrough: List<WalkthroughItem> = emptyList(),
    val choices: Map<String, Long> = emptyMap(),
    val increments: Map<Long, Int> = emptyMap(),
    val busy: Boolean = false,
    val done: Boolean = false,
)

@HiltViewModel
class SetupViewModel @Inject constructor(private val training: TrainingRepository) : ViewModel() {
    private val _ui = MutableStateFlow(SetupUi())
    val ui: StateFlow<SetupUi> = _ui

    init {
        viewModelScope.launch {
            val settings = training.settings.first()
            val days = settings.extras.associate { it.routineId to it.days }
            val extras = training.observeExtras().first().map { ExtraDraft(it.id, it.name, it.kind, days[it.id].orEmpty()) }
            _ui.update {
                it.copy(
                    split = settings.split ?: SplitTemplate.PPL,
                    currentSplit = settings.split,
                    experience = settings.experience,
                    strengthDays = if (settings.split != null) settings.strengthDaysPerWeek else SplitTemplate.PPL.defaultStrengthDaysPerWeek,
                    extras = extras,
                )
            }
        }
    }

    fun pickSplit(split: SplitTemplate) = _ui.update {
        it.copy(split = split, strengthDays = if (it.currentSplit == null) split.defaultStrengthDaysPerWeek else it.strengthDays)
    }

    fun pickExperience(experience: Experience) = _ui.update { it.copy(experience = experience) }
    fun setStrengthDays(days: Int) = _ui.update { it.copy(strengthDays = days.coerceIn(1, 7)) }
    fun addExtra() = _ui.update { it.copy(extras = it.extras + ExtraDraft(null, "Yoga class", SessionKind.YOGA, emptySet())) }
    fun updateExtra(index: Int, draft: ExtraDraft) = _ui.update { it.copy(extras = it.extras.toMutableList().also { list -> list[index] = draft }) }
    fun removeExtra(index: Int) = _ui.update { it.copy(extras = it.extras.toMutableList().also { list -> list.removeAt(index) }) }
    fun choose(slot: String, exerciseId: Long) = _ui.update { it.copy(choices = it.choices + (slot to exerciseId)) }
    fun setIncrement(exerciseId: Long, grams: Int) = _ui.update { it.copy(increments = it.increments + (exerciseId to grams)) }

    fun back() = _ui.update { it.copy(step = SetupStep.entries[(it.step.ordinal - 1).coerceAtLeast(0)]) }

    fun next() {
        val ui = _ui.value
        if (ui.step != SetupStep.PLAN) {
            _ui.update { it.copy(step = SetupStep.entries[it.step.ordinal + 1]) }
            return
        }
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val items = training.walkthrough(ui.split)
            _ui.update { state ->
                state.copy(
                    step = SetupStep.EQUIPMENT,
                    walkthrough = items,
                    choices = items.associate { it.slotKey to it.selectedId },
                    increments = items.flatMap { it.options }.associate { it.id to it.incrementG },
                    busy = false,
                )
            }
        }
    }

    fun save() {
        val ui = _ui.value
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            training.applySetup(
                TrainingSetup(
                    split = ui.split,
                    experience = ui.experience,
                    strengthDaysPerWeek = ui.strengthDays,
                    extras = ui.extras.map { it.copy(name = it.name.trim().ifEmpty { Labels.kind(it.kind) }) },
                    choices = ui.choices,
                    // Only the variants you kept: an increment for an unchosen alternative is never shown.
                    increments = ui.increments.filterKeys { id -> id in ui.choices.values },
                ),
            )
            _ui.update { it.copy(busy = false, done = true) }
        }
    }
}

private fun SplitTemplate.description() = when (this) {
    SplitTemplate.PPL -> "Push, Pull and Legs in rotation. Good for 3 to 6 days a week."
    SplitTemplate.FULL_BODY_AB -> "Two full-body days, A and B, alternating. Good for 2 to 4 days a week."
    SplitTemplate.UPPER_LOWER -> "Upper and Lower days, alternating. Good for 4 days a week."
    SplitTemplate.CUSTOM -> "Start empty and build your own routines."
}

/**
 * Training setup (PRD §7.1): split, experience and weekly plan, then the equipment walkthrough (PR-04).
 * M3's onboarding absorbs it; until then it's also reachable from the Train tab.
 */
@Composable
fun SetupScreen(onDone: () -> Unit, viewModel: SetupViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.done) { if (ui.done) onDone() }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Step ${ui.step.ordinal + 1} of ${SetupStep.entries.size}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp))
            }
            when (ui.step) {
                SetupStep.SPLIT -> {
                    item { Text("Training split", style = MaterialTheme.typography.headlineSmall) }
                    items(SplitTemplate.entries) { split ->
                        OptionCard(split.label, split.description(), selected = split == ui.split) { viewModel.pickSplit(split) }
                    }
                    val current = ui.currentSplit
                    if (current != null && current != ui.split) {
                        item {
                            Text(
                                "Your current ${current.label} routines will be archived. Their history stays.",
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
                SetupStep.EXPERIENCE -> {
                    item { Text("How long have you been lifting?", style = MaterialTheme.typography.headlineSmall) }
                    items(Experience.entries) { level ->
                        val policy = ExperiencePolicy.defaultFor(level)
                        val years = when (level) {
                            Experience.BEGINNER -> "Under a year"
                            Experience.INTERMEDIATE -> "1 to 3 years"
                            Experience.ADVANCED -> "3 years or more"
                        }
                        OptionCard(
                            "${Labels.experience(level)}: $years",
                            "Reps: compound ${policy.compoundReps.first}–${policy.compoundReps.last}, isolation ${policy.isolationReps.first}–${policy.isolationReps.last}, " +
                                "high-rep ${policy.highRepReps.first}–${policy.highRepReps.last}",
                            selected = level == ui.experience,
                        ) { viewModel.pickExperience(level) }
                    }
                }
                SetupStep.PLAN -> {
                    item { Text("Weekly plan", style = MaterialTheme.typography.headlineSmall) }
                    item {
                        Stepper(
                            "Strength days a week",
                            "${ui.strengthDays}",
                            onMinus = { viewModel.setStrengthDays(ui.strengthDays - 1) },
                            onPlus = { viewModel.setStrengthDays(ui.strengthDays + 1) },
                            onValueClick = {},
                        )
                    }
                    item {
                        Text(
                            "Routines follow a rotation, not the calendar: a missed day never breaks the plan.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item { Text("Extra sessions", style = MaterialTheme.typography.titleMedium) }
                    ui.extras.forEachIndexed { index, extra ->
                        item(key = "extra-$index") { ExtraEditor(extra, onChange = { viewModel.updateExtra(index, it) }, onRemove = { viewModel.removeExtra(index) }) }
                    }
                    item {
                        OutlinedButton(onClick = viewModel::addExtra, modifier = Modifier.heightIn(min = 48.dp)) { Text("+ Add yoga, mobility, cardio or sport") }
                    }
                }
                SetupStep.EQUIPMENT -> {
                    item { Text("Your gym's equipment", style = MaterialTheme.typography.headlineSmall) }
                    item {
                        Text(
                            if (ui.walkthrough.isEmpty()) "No exercises yet. Add routines on the Train tab, then come back to set their steps."
                            else "Pick the variant your gym has, and the smallest weight step for each. The step sets how weights go up.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    var group: String? = null
                    ui.walkthrough.forEach { item ->
                        val routine = item.routines.first()
                        if (routine != group) {
                            group = routine
                            item(key = "group-$routine") { Text(routine, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                        }
                        item(key = item.slotKey) {
                            WalkthroughCard(
                                item = item,
                                chosenId = ui.choices[item.slotKey] ?: item.selectedId,
                                increments = ui.increments,
                                onChoose = { viewModel.choose(item.slotKey, it) },
                                onIncrement = viewModel::setIncrement,
                            )
                        }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (ui.step == SetupStep.SPLIT) {
                TextButton(onClick = onDone, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Cancel") }
            } else {
                OutlinedButton(onClick = viewModel::back, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Back") }
            }
            val last = ui.step == SetupStep.EQUIPMENT
            Button(
                onClick = { if (last) viewModel.save() else viewModel.next() },
                enabled = !ui.busy,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            ) { Text(if (last) "Save" else "Next") }
        }
    }
}

@Composable
private fun OptionCard(title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ExtraEditor(extra: ExtraDraft, onChange: (ExtraDraft) -> Unit, onRemove: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = extra.name, onValueChange = { onChange(extra.copy(name = it)) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ChoiceChips(SessionKind.entries.filter { it.isExtra }, extra.kind, Labels::kind, { onChange(extra.copy(kind = it)) })
            Text("Days (optional)", style = MaterialTheme.typography.labelLarge)
            DayChips(extra.days, onToggle = { day -> onChange(extra.copy(days = if (day in extra.days) extra.days - day else extra.days + day)) })
            TextButton(onClick = onRemove) { Text("Remove") }
        }
    }
}

@Composable
private fun WalkthroughCard(
    item: WalkthroughItem,
    chosenId: Long,
    increments: Map<Long, Int>,
    onChoose: (Long) -> Unit,
    onIncrement: (Long, Int) -> Unit,
) {
    val chosen = item.options.first { it.id == chosenId }
    var typing by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(chosen.name, style = MaterialTheme.typography.titleMedium)
            if (item.routines.size > 1) Text("In ${item.routines.joinToString()}", style = MaterialTheme.typography.bodySmall)
            if (item.options.size > 1) {
                ChoiceChips(item.options.map { it.id }, chosenId, { id -> item.options.first { it.id == id }.name }, onChoose)
            }
            when (chosen.loadType) {
                LoadType.BODYWEIGHT -> Text("Bodyweight: reps only", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    val step = increments[chosen.id] ?: chosen.incrementG
                    val label = when {
                        chosen.loadType == LoadType.BODYWEIGHT_PLUS -> "Added weight step"
                        chosen.equipment == Equipment.DUMBBELL -> "Smallest step, per dumbbell"
                        else -> "Smallest step"
                    }
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    ChoiceChips((Loads.incrementChoicesG + step).distinct().sorted(), step, { Labels.kg(it) }, { onIncrement(chosen.id, it) })
                    TextButton(onClick = { typing = true }) { Text("Other step…") }
                }
            }
        }
    }
    if (typing) {
        NumberEntryDialog("Smallest step for ${chosen.name}", Loads.formatKg(increments[chosen.id] ?: chosen.incrementG), decimal = true, suffix = "kg", onDismiss = { typing = false }) { text ->
            typing = false
            Loads.parseKg(text)?.takeIf { it > 0 }?.let { onIncrement(chosen.id, it) }
        }
    }
}
