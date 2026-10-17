package app.recomp.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.recomp.data.prefs.StoredPersona
import app.recomp.data.prefs.TrainingSettings
import app.recomp.data.repo.PersonaRepository
import app.recomp.data.repo.TargetPreview
import app.recomp.data.repo.TrainingRepository
import app.recomp.data.repo.rampEnabled
import app.recomp.domain.nutrition.NutritionTargets
import app.recomp.domain.nutrition.Sex
import app.recomp.domain.nutrition.TargetOverrides
import app.recomp.domain.persona.ActivityLevel
import app.recomp.domain.persona.Diet
import app.recomp.domain.persona.FinishLine
import app.recomp.domain.persona.FoodRegion
import app.recomp.domain.persona.GoalPlan
import app.recomp.domain.persona.Profile
import app.recomp.domain.persona.WeightSource
import app.recomp.domain.training.LogDates
import app.recomp.ui.components.ChoiceChips
import app.recomp.ui.components.DateDialog
import app.recomp.ui.components.Labels
import app.recomp.ui.components.NumberEntryDialog
import app.recomp.ui.components.SectionTitle
import app.recomp.ui.components.formatDayStart
import app.recomp.ui.onboarding.GoalText
import app.recomp.ui.onboarding.SwitchRow
import app.recomp.ui.onboarding.TargetList
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUi(
    val loaded: Boolean = false,
    val today: LocalDate = LocalDate.now(),
    val stored: StoredPersona = StoredPersona(),
    val plan: GoalPlan? = null,
    val profile: Profile? = null,
    val targets: NutritionTargets? = null,
    val dayStart: LocalTime = LogDates.DEFAULT_DAY_START,
    val training: TrainingSettings = TrainingSettings(),
    val extraNames: List<String> = emptyList(),
)

/** A change that moves the targets, waiting for you to confirm the before and after (PRD §7.1). */
data class PendingChange(val preview: TargetPreview, val profile: Profile?, val overrides: TargetOverrides?)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val personas: PersonaRepository,
    training: TrainingRepository,
) : ViewModel() {
    private val today = MutableStateFlow(LocalDate.now())

    init {
        viewModelScope.launch { today.value = personas.today() }
    }

    private val body = combine(personas.stored, personas.profile, personas.overrides, today) { stored, profile, overrides, day ->
        val plan = stored.goal?.toPlan()
        Triple(stored, profile, Triple(plan, overrides, day))
    }

    val ui: StateFlow<SettingsUi> = combine(body, personas.dayStart, training.settings, training.observeExtras()) { (stored, profile, rest), dayStart, settings, extras ->
        val (plan, overrides, day) = rest
        SettingsUi(
            loaded = true,
            today = day,
            stored = stored,
            plan = plan,
            profile = profile,
            targets = if (plan != null && profile != null) personas.targetsFor(profile, plan, overrides, day) else null,
            dayStart = dayStart,
            training = settings,
            extraNames = extras.map { it.name },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUi())

    private val _pending = MutableStateFlow<PendingChange?>(null)
    val pending: StateFlow<PendingChange?> = _pending

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun dismissError() {
        _error.value = null
    }

    /** A failed save leaves the old settings in place and says so, rather than crashing. */
    private fun guarded(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = "Couldn't save: ${e.message ?: e.javaClass.simpleName}. Your settings are unchanged."
        }
    }

    /** Body data and overrides move the targets, so they're previewed first; unchanged numbers save at once. */
    fun changeProfile(profile: Profile) = propose(profile = profile)

    fun changeOverrides(overrides: TargetOverrides) = propose(overrides = overrides)

    private fun propose(profile: Profile? = null, overrides: TargetOverrides? = null) = guarded {
        val preview = personas.preview(profile = profile, overrides = overrides)
        if (preview.changes) _pending.value = PendingChange(preview, profile, overrides)
        else personas.save(profile = profile, overrides = overrides)
    }

    fun confirm() {
        val change = _pending.value ?: return
        _pending.value = null
        guarded { personas.save(profile = change.profile, overrides = change.overrides) }
    }

    fun cancel() {
        _pending.value = null
    }

    fun setDiet(diet: Diet) = guarded { personas.setDiet(diet) }
    fun setRegion(region: FoodRegion) = guarded { personas.setRegion(region) }
    fun setWeightSource(source: WeightSource) = guarded { personas.setWeightSource(source) }
    fun setDayStart(time: LocalTime) = guarded { personas.setDayStart(time) }
    fun setRamp(on: Boolean) = guarded { personas.setProteinRamp(on) }
}

private sealed interface Editing {
    data object Sex : Editing
    data object Birth : Editing
    data object Height : Editing
    data object Weight : Editing
    data object Activity : Editing
    data object Diet : Editing
    data object Region : Editing
    data object Source : Editing
    data object DayStart : Editing
}

/** Settings (PS-02): every persona value, each change applying from today and never rewriting history. */
@Composable
fun SettingsScreen(onOpenGoal: () -> Unit, onOpenTraining: () -> Unit, onOpenDev: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Editing?>(null) }
    if (!ui.loaded) return
    val profile = ui.profile
    val plan = ui.plan

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        if (plan != null) {
            SectionTitle("Goal")
            Card(Modifier.fillMaxWidth().clickable(onClickLabel = "Change goal", onClick = onOpenGoal)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(GoalText.goalSummary(plan.goal, plan.policy, Labels.pace(plan.pace), plan.goalWeightKg), style = MaterialTheme.typography.titleMedium)
                    Text(finishLineText(plan), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Change goal, pace or finish line", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        val targets = ui.targets
        if (targets != null && plan != null) {
            SectionTitle("Targets")
            TargetList(targets, plan.policy, before = null, onOverride = viewModel::changeOverrides)
        }

        if (profile != null) {
            SectionTitle("About you")
            SettingRow("Sex", Labels.sex(profile.sex)) { editing = Editing.Sex }
            SettingRow("Date of birth", profile.birthDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))) { editing = Editing.Birth }
            SettingRow("Height", "${GoalText.d(profile.heightCm)} cm") { editing = Editing.Height }
            SettingRow("Current weight", "${GoalText.d(profile.weightKg)} kg") { editing = Editing.Weight }
            SettingRow("Activity", Labels.activity(profile.activity)) { editing = Editing.Activity }
        }

        SectionTitle("Training")
        Card(Modifier.fillMaxWidth().clickable(onClickLabel = "Change training setup", onClick = onOpenTraining)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val t = ui.training
                Text(
                    if (t.split == null) "Not set up yet" else "${t.split.label} · ${Labels.experience(t.experience)} · ${t.strengthDaysPerWeek} strength days a week",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(if (ui.extraNames.isEmpty()) "No extra sessions" else ui.extraNames.joinToString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Split, experience, weekly plan, equipment", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }

        SectionTitle("Food")
        SettingRow("Diet", ui.stored.diet?.let(Labels::diet) ?: "Not set") { editing = Editing.Diet }
        SettingRow("Food region", ui.stored.region?.let(Labels::region) ?: "Not set") { editing = Editing.Region }
        SwitchRow(
            "Protein ramp",
            "Measure a week of what you eat, then rise about 15 g a week to the full target. Turning it on again starts a fresh week.",
            ui.stored.rampEnabled(),
            onChange = viewModel::setRamp,
        )

        SectionTitle("Body data")
        SettingRow("Weight trend from", Labels.weightSource(ui.stored.weightSource)) { editing = Editing.Source }
        SettingRow("Units", Labels.units(ui.stored.units) + " (lb and in arrive later)", onClick = null)

        SectionTitle("Days")
        SettingRow("Day starts at", formatDayStart(ui.dayStart)) { editing = Editing.DayStart }

        SectionTitle("Developer")
        SettingRow("M0 spikes", "AI and Health Connect checks", onClick = onOpenDev)

        Text(GoalText.NOT_MEDICAL_ADVICE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }

    when (val e = editing) {
        null -> Unit
        Editing.Sex -> ChoiceDialog("Sex", Sex.entries, profile?.sex, Labels::sex, onDismiss = { editing = null }) { s ->
            editing = null
            profile?.let { viewModel.changeProfile(it.copy(sex = s)) }
        }
        Editing.Birth -> DateDialog(profile?.birthDate ?: ui.today.minusYears(30), onDismiss = { editing = null }) { date ->
            editing = null
            if (date.isBefore(ui.today)) profile?.let { viewModel.changeProfile(it.copy(birthDate = date)) }
        }
        Editing.Height, Editing.Weight -> {
            val height = e == Editing.Height
            NumberEntryDialog(
                if (height) "Height" else "Current weight",
                profile?.let { GoalText.d(if (height) it.heightCm else it.weightKg) }.orEmpty(),
                decimal = true,
                suffix = if (height) "cm" else "kg",
                onDismiss = { editing = null },
            ) { text ->
                editing = null
                val value = text.trim().replace(',', '.').toDoubleOrNull()
                if (profile != null && value != null) {
                    if (height && value in 100.0..250.0) viewModel.changeProfile(profile.copy(heightCm = value))
                    if (!height && value in 30.0..300.0) viewModel.changeProfile(profile.copy(weightKg = value))
                }
            }
        }
        Editing.Activity -> ChoiceDialog("Activity", ActivityLevel.entries, profile?.activity, Labels::activity, onDismiss = { editing = null }) { a ->
            editing = null
            profile?.let { viewModel.changeProfile(it.copy(activity = a)) }
        }
        Editing.Diet -> ChoiceDialog("Diet", Diet.entries, ui.stored.diet, Labels::diet, onDismiss = { editing = null }) { editing = null; viewModel.setDiet(it) }
        Editing.Region -> ChoiceDialog("Food region", FoodRegion.entries, ui.stored.region, Labels::region, onDismiss = { editing = null }) { editing = null; viewModel.setRegion(it) }
        Editing.Source -> ChoiceDialog("Weight trend from", WeightSource.entries, ui.stored.weightSource, Labels::weightSource, onDismiss = { editing = null }) {
            editing = null
            viewModel.setWeightSource(it)
        }
        Editing.DayStart -> ChoiceDialog(
            "Day starts at",
            LogDates.DAY_START_CHOICES,
            ui.dayStart,
            ::formatDayStart,
            note = "Days already logged keep their date.",
            onDismiss = { editing = null },
        ) { editing = null; viewModel.setDayStart(it) }
    }

    pending?.let { change -> PreviewDialog(change.preview, onConfirm = viewModel::confirm, onDismiss = viewModel::cancel) }

    val error by viewModel.error.collectAsStateWithLifecycle()
    error?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            title = { Text("Not saved") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::dismissError) { Text("OK") } },
        )
    }
}

private fun finishLineText(plan: GoalPlan): String = when (val f = plan.finishLine) {
    null -> "No finish line"
    FinishLine.GoalWeight -> "Finish line: reach ${plan.goalWeightKg?.let { "${GoalText.d(it)} kg" } ?: "your goal weight"}"
    is FinishLine.BodyFat -> "Finish line: ${GoalText.d(f.pct)}% body fat"
    is FinishLine.ByDate -> "Finish line: ${f.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))}"
}

@Composable
private fun SettingRow(title: String, value: String, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "Change $title", onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onDismiss: () -> Unit,
    note: String? = null,
    onPick: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChips(options, selected, label, onPick)
                if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The old and new targets side by side before a change saves (PRD §7.1). */
@Composable
private fun PreviewDialog(preview: TargetPreview, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val before = preview.before
    val after = preview.after
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your targets change") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                preview.notes.forEach { Text(GoalText.guardrail(it), color = MaterialTheme.colorScheme.secondary) }
                if (before != null && before.goal != preview.plan.goal) Text("Goal: ${Labels.goal(before.goal)} → ${Labels.goal(preview.plan.goal)}")
                ChangeLine("Protein", before?.proteinG, after.proteinG, "g")
                ChangeLine("Calories", before?.kcal, after.kcal, "kcal")
                ChangeLine("Fat", before?.fatG, after.fatG, "g")
                ChangeLine("Carbs", before?.carbsG, after.carbsG, "g")
                Text("From today. Earlier days keep their targets.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChangeLine(label: String, before: Int?, after: Int, unit: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f))
        Text(if (before == null || before == after) "${GoalText.n(after)} $unit" else "${GoalText.n(before)} → ${GoalText.n(after)} $unit")
    }
}
