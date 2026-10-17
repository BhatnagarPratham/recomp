package app.recomp.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.recomp.data.db.NutritionTargetEntity
import app.recomp.data.prefs.FinishKind
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.GoalPolicy
import app.recomp.domain.nutrition.NutritionTargets
import app.recomp.domain.nutrition.Sex
import app.recomp.domain.nutrition.TargetOverrides
import app.recomp.domain.persona.ActivityLevel
import app.recomp.domain.persona.Diet
import app.recomp.domain.persona.Experience
import app.recomp.domain.persona.FoodRegion
import app.recomp.domain.persona.Guardrails
import app.recomp.domain.persona.GuardrailNote
import app.recomp.domain.persona.Intent
import app.recomp.domain.persona.Presets
import app.recomp.domain.persona.SplitTemplate
import app.recomp.domain.persona.Units
import app.recomp.domain.persona.WeightSource
import app.recomp.domain.training.LogDates
import app.recomp.ui.components.ChoiceChips
import app.recomp.ui.components.ConfirmDialog
import app.recomp.ui.components.DateDialog
import app.recomp.ui.components.Labels
import app.recomp.ui.components.NumberEntryDialog
import app.recomp.ui.components.OptionCard
import app.recomp.ui.components.SectionTitle
import app.recomp.ui.components.Stepper
import app.recomp.ui.components.formatDayStart
import app.recomp.ui.setup.ExtraEditor
import app.recomp.ui.setup.WalkthroughCard
import app.recomp.ui.setup.description
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Onboarding (PS-01, PR-01, PR-02, J0): preset, about you, the goal flow, your settings, targets and
 * the equipment walkthrough. With [goalOnly] it's Settings → Goal: the same goal flow, ending in a
 * before-and-after of the targets.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit, onExit: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.done) { if (ui.done) onDone() }
    if (!ui.loaded) return

    val first = ui.steps.indexOf(ui.step) <= 0
    BackHandler(enabled = !first || ui.mode == FlowMode.GOAL) { if (!viewModel.back()) onExit() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Step ${ui.steps.indexOf(ui.step) + 1} of ${ui.steps.size}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            when (ui.step) {
                Step.PRESET -> presetStep(ui, viewModel)
                Step.ABOUT -> aboutStep(ui, viewModel)
                Step.GOAL -> goalStep(ui, viewModel)
                Step.QUESTIONS -> questionsStep(ui, viewModel)
                Step.SUGGESTION -> suggestionStep(ui, viewModel)
                Step.PACE -> paceStep(ui, viewModel)
                Step.FINISH -> finishStep(ui, viewModel)
                Step.REVIEW -> reviewStep(ui, viewModel)
                Step.TARGETS -> targetsStep(ui, viewModel)
                Step.EQUIPMENT -> equipmentStep(ui, viewModel)
            }
            item { Text("", Modifier.padding(bottom = 8.dp)) }
        }

        Row(
            Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!first) {
                OutlinedButton(onClick = { viewModel.back() }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Back") }
            } else if (ui.mode == FlowMode.GOAL) {
                TextButton(onClick = onExit, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Cancel") }
            }
            val label = when {
                !ui.isLast -> "Next"
                ui.mode == FlowMode.GOAL -> "Save"
                else -> "Start"
            }
            Button(onClick = viewModel::next, enabled = ui.canContinue && !ui.busy, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text(label) }
        }
    }

    if (ui.askAggressive) {
        ConfirmDialog(
            title = "An aggressive pace",
            text = GoalText.AGGRESSIVE_WARNING,
            confirm = "I understand",
            dismiss = "Choose another",
            onDismiss = viewModel::declineAggressive,
            onConfirm = viewModel::acknowledgeAggressive,
        )
    }
}

private fun LazyListScope.heading(title: String, body: String? = null) {
    item {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (body != null) Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// 1. Preset (PRD §4.4)

private fun LazyListScope.presetStep(ui: OnboardingUi, vm: OnboardingViewModel) {
    heading(
        "Start from a preset",
        if (ui.currentSplit != null) "Your training setup stays as it is. The preset fills in the rest, and you can change everything."
        else "Every value is pre-filled for you to review, and you can change any of them later in Settings.",
    )
    items(Presets.all, key = { it.key }) { preset ->
        OptionCard(preset.name, preset.summary, selected = preset.key == ui.presetKey) { vm.pickPreset(preset.key) }
    }
}

// 2. About you (PR-01)

private fun LazyListScope.aboutStep(ui: OnboardingUi, vm: OnboardingViewModel) {
    heading("About you", "For your calorie and protein targets. It stays on this phone.")
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sex", style = MaterialTheme.typography.titleMedium)
            ChoiceChips(Sex.entries, ui.about.sex, Labels::sex, { s -> vm.updateAbout { it.copy(sex = s) } })
            Text("The calorie formulas differ for men and women.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    item {
        var picking by remember { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Date of birth", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { picking = true }, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(ui.about.birthDate?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)) ?: "Choose a date")
            }
        }
        if (picking) {
            DateDialog(ui.about.birthDate ?: ui.today.minusYears(30), onDismiss = { picking = false }) { date ->
                picking = false
                vm.updateAbout { it.copy(birthDate = date) }
            }
        }
    }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField("Height", ui.about.height, "cm", Modifier.weight(1f), error = ui.about.height.isNotBlank() && ui.about.heightCm == null) { t -> vm.updateAbout { it.copy(height = t) } }
            NumberField("Current weight", ui.about.weight, "kg", Modifier.weight(1f), error = ui.about.weight.isNotBlank() && ui.about.weightKg == null) { t -> vm.updateAbout { it.copy(weight = t) } }
        }
    }
    item { Text("Activity", style = MaterialTheme.typography.titleMedium) }
    items(ActivityLevel.entries) { level ->
        OptionCard(Labels.activity(level), "× ${level.factor} on your resting calories", selected = level == ui.about.activity) { vm.updateAbout { it.copy(activity = level) } }
    }
}

// 3. Goal (PRD §4.6, GL-01)

private fun LazyListScope.goalStep(ui: OnboardingUi, vm: OnboardingViewModel) {
    heading("What's your goal?")
    val profile = ui.profile
    items(Goal.entries) { goal ->
        val available = profile == null || Guardrails.goalAvailable(goal, profile)
        OptionCard(
            GoalText.cardTitle(goal),
            GoalText.cardDescription(goal),
            selected = goal == ui.goal.goal && !ui.goal.notSure,
            enabled = available,
            note = if (available) null else GoalText.LOW_BMI_CARD,
        ) { vm.pickGoal(goal) }
    }
    item {
        // With AI off (all of M3a) nothing would read free text, so there's no text box (PRD §4.6).
        // TODO(GL-03): with AI on, "Describe your goal" adds a text box that Claude maps to an intent.
        OptionCard("Not sure? Answer 3 quick questions", "Get a suggestion with the reason for it.", selected = ui.goal.notSure, onClick = vm::notSure)
    }
}

private fun LazyListScope.questionsStep(ui: OnboardingUi, vm: OnboardingViewModel) {
    heading("Three quick questions")
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("What matters most to you?", style = MaterialTheme.typography.titleMedium)
            ChoiceChips(Intent.entries, ui.goal.intent, ::intentLabel, { i -> vm.answer { it.copy(intent = i) } })
        }
    }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("How long have you been lifting?", style = MaterialTheme.typography.titleMedium)
            ChoiceChips(Experience.entries, ui.goal.liftingAge, Labels::liftingAge, { e -> vm.answer { it.copy(liftingAge = e) } })
        }
    }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("What's your body fat?", style = MaterialTheme.typography.titleMedium)
            val bmi = vm.bmi()
            OptionCard(
                "Estimate it from my height and weight",
                if (bmi != null) "Your BMI is ${GoalText.d(Math.round(bmi * 10) / 10.0)}." else "Uses your BMI.",
                selected = !ui.goal.knowsBodyFat,
            ) { vm.answer { it.copy(knowsBodyFat = false) } }
            OptionCard("I know my body fat %", "Used for this suggestion only, not saved.", selected = ui.goal.knowsBodyFat) { vm.answer { it.copy(knowsBodyFat = true) } }
            if (ui.goal.knowsBodyFat) {
                NumberField("Body fat", ui.goal.bodyFat, "%", Modifier.fillMaxWidth(), error = ui.goal.bodyFat.isNotBlank() && ui.goal.bodyFatPct == null) { t -> vm.answer { it.copy(bodyFat = t) } }
            }
        }
    }
}

private fun intentLabel(i: Intent) = when (i) {
    Intent.LOSE_FAT -> "Lose fat"
    Intent.BUILD_MUSCLE -> "Build muscle"
    Intent.BOTH -> "Both"
    Intent.MAINTAIN -> "Stay as I am"
}

private fun LazyListScope.suggestionStep(ui: OnboardingUi, vm: OnboardingViewModel) {
    val advice = ui.goal.advice ?: return
    heading("Our suggestion")
    item {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(GoalText.cardTitle(advice.goal), style = MaterialTheme.typography.titleLarge)
                Text(GoalText.advice(advice))
                Text(GoalText.cardDescription(advice.goal), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    item { Text("Next uses this goal.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    item { TextButton(onClick = vm::pickAnotherGoal, modifier = Modifier.heightIn(min = 48.dp)) { Text("Pick another goal") } }
}

// 4. Pace (GL-04)

private fun LazyListScope.paceStep(ui: OnboardingUi, vm: OnboardingViewModel) {
    val goal = ui.goal.goal ?: return
    heading("Pace", "Faster isn't better: a gentler pace keeps more muscle and is easier to stick with.")
    val custom = ui.goal.custom
    items(vm.paceOptions(), key = { it.pace }) { option ->
        OptionCard(
            "${Labels.pace(option.pace)} · ${GoalText.rateLabel(GoalPolicy.defaultFor(goal, option.pace))}",
            option.text,
            selected = custom == null && option.pace == ui.goal.pace,
            enabled = option.allowed,
        ) { vm.pickPace(option.pace) }
    }
    guardrailNotes(ui.guarded?.notes.orEmpty().filter { it == GuardrailNote.UNDER_18_GENTLE_ONLY })
    item {
        var open by remember { mutableStateOf(custom != null) }
        var editing by remember { mutableStateOf<String?>(null) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { open = !open }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (open) "Hide Advanced" else "Advanced: exact values") }
            if (open) {
                val policy = ui.plan?.policy ?: GoalPolicy.defaultFor(goal, ui.goal.pace)
                val rate = if (goal == Goal.LEAN_BULK) GoalText.d(policy.surplusFraction * 100) else GoalText.d(policy.lossRatePctPerWeek)
                if (goal != Goal.MAINTAIN) {
                    OutlinedButton(onClick = { editing = "rate" }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(if (goal == Goal.LEAN_BULK) "Surplus: $rate% of maintenance" else "Loss: $rate% of body weight a week")
                    }
                }
                OutlinedButton(onClick = { editing = "protein" }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text("Protein: ${GoalText.d(policy.proteinGPerKg)} g per kg")
                }
                val range = GoalPolicy.proteinRange(goal)
                Text(
                    "Protein ${GoalText.d(range.start)}–${GoalText.d(range.endInclusive)} g per kg for this goal. Deficits stay capped at 25% of maintenance and surpluses at 15%.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (custom != null) TextButton(onClick = vm::clearCustom) { Text("Back to the ${Labels.pace(ui.goal.pace)} preset") }
            }
        }
        editing?.let { which ->
            val policy = ui.plan?.policy ?: GoalPolicy.defaultFor(goal, ui.goal.pace)
            val initial = when (which) {
                "rate" -> if (goal == Goal.LEAN_BULK) GoalText.d(policy.surplusFraction * 100) else GoalText.d(policy.lossRatePctPerWeek)
                else -> GoalText.d(policy.proteinGPerKg)
            }
            NumberEntryDialog(
                if (which == "rate") "Rate" else "Protein per kg",
                initial,
                decimal = true,
                suffix = if (which == "rate") "%" else "g/kg",
                onDismiss = { editing = null },
            ) { text ->
                editing = null
                val value = text.trim().replace(',', '.').toDoubleOrNull() ?: return@NumberEntryDialog
                if (which == "rate") vm.setCustom(ratePct = value, proteinGPerKg = null) else vm.setCustom(ratePct = null, proteinGPerKg = value)
            }
        }
    }
}

// 5. Finish line (PRD §4.7, GL-05)

private fun LazyListScope.finishStep(ui: OnboardingUi, vm: OnboardingViewModel) {
    val goal = ui.goal.goal ?: return
    heading("Finish line", "Reached on your weight trend, not a single weigh-in.")
    item {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val required = ui.goal.finishKind == FinishKind.GOAL_WEIGHT
            NumberField(
                if (required) "Goal weight" else "Goal weight (optional)",
                ui.goal.goalWeight,
                "kg",
                Modifier.fillMaxWidth(),
                error = ui.goal.goalWeight.isNotBlank() && ui.goal.goalWeightKg == null,
                onChange = vm::setGoalWeight,
            )
            Text(
                "Protein is worked out from your goal weight whatever finish line you pick, or from your current weight if you leave it blank.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    item {
        OptionCard("When I reach my goal weight", "The finish line is your goal weight above.", selected = ui.goal.finishKind == FinishKind.GOAL_WEIGHT) { vm.setFinish(FinishKind.GOAL_WEIGHT) }
    }
    item {
        var picking by remember { mutableStateOf(false) }
        val date = ui.goal.finishDate
        OptionCard(
            "By a date",
            if (ui.goal.finishKind == FinishKind.DATE && date != null) date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)) + " · tap again to change" else "Pick a date to finish on.",
            selected = ui.goal.finishKind == FinishKind.DATE,
        ) { if (ui.goal.finishKind == FinishKind.DATE) picking = true else vm.setFinish(FinishKind.DATE) }
        if (picking) {
            DateDialog(date ?: ui.today.plusWeeks(16), onDismiss = { picking = false }) { picked ->
                picking = false
                vm.setFinishDate(picked)
            }
        }
    }
    item {
        // TODO(M4): available once body-composition scans exist (IB-05).
        OptionCard("A target body fat %", "Needs a body-composition scan. Arrives with scans.", selected = false, enabled = false) {}
    }
    item { OptionCard("No finish line", if (goal == Goal.MAINTAIN) "The usual for Maintain." else "Keep going until you change goal.", selected = ui.goal.finishKind == null) { vm.setFinish(null) } }
    vm.projectionText()?.takeIf { goal != Goal.MAINTAIN }?.let { text -> item { Text(text, color = MaterialTheme.colorScheme.primary) } }
}

// 6. Your settings (PS-01)

private fun LazyListScope.reviewStep(ui: OnboardingUi, vm: OnboardingViewModel) {
    val d = ui.draft
    heading("Your settings", "Pre-filled from the preset. Values marked \"Choose\" are needed before you go on.")
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("Experience", d.experience == null)
            ChoiceChips(Experience.entries, d.experience, { "${Labels.experience(it)} (${Labels.liftingAge(it).lowercase()})" }, { e -> vm.updateDraft { it.copy(experience = e) } })
        }
    }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("Diet", d.diet == null)
            ChoiceChips(Diet.entries, d.diet, Labels::diet, { diet -> vm.updateDraft { it.copy(diet = diet) } })
        }
    }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("Food region", d.region == null)
            ChoiceChips(FoodRegion.entries, d.region, Labels::region, { r -> vm.updateDraft { it.copy(region = r) } })
            Text("Sets the food database and portion units (katori and roti, or cup and slice).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    item { Label("Training split", d.split == null) }
    items(SplitTemplate.entries) { split ->
        OptionCard(split.label, split.description(), selected = split == d.split) { vm.pickSplit(split) }
    }
    val current = ui.currentSplit
    if (current != null && d.split != null && current != d.split) {
        item { Text("Your current ${current.label} routines will be archived. Their history stays.", color = MaterialTheme.colorScheme.secondary) }
    }
    item {
        val days = d.strengthDaysPerWeek ?: d.split?.defaultStrengthDaysPerWeek ?: 3
        Stepper("Strength days a week", "$days", onMinus = { vm.setStrengthDays(days - 1) }, onPlus = { vm.setStrengthDays(days + 1) }, onValueClick = {})
    }
    item { SectionTitle("Extra sessions") }
    ui.extras.forEachIndexed { index, extra ->
        item(key = "extra-$index") { ExtraEditor(extra, onChange = { vm.updateExtra(index, it) }, onRemove = { vm.removeExtra(index) }) }
    }
    item { OutlinedButton(onClick = vm::addExtra, modifier = Modifier.heightIn(min = 48.dp)) { Text("+ Add yoga, mobility, cardio or sport") } }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Weight trend from", style = MaterialTheme.typography.titleMedium)
            ChoiceChips(WeightSource.entries, d.weightSource, Labels::weightSource, { w -> vm.updateDraft { it.copy(weightSource = w) } })
        }
    }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Units", style = MaterialTheme.typography.titleMedium)
            ChoiceChips(listOf(Units.METRIC), d.units, Labels::units, { u -> vm.updateDraft { it.copy(units = u) } })
            Text("lb and in arrive in a later version.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Day starts at", style = MaterialTheme.typography.titleMedium)
            ChoiceChips(LogDates.DAY_START_CHOICES, ui.dayStart, ::formatDayStart, vm::setDayStart)
            Text("A late-night snack before this time counts toward the day before.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    item {
        val on = d.proteinRamp ?: (d.diet?.rampByDefault ?: false)
        SwitchRow(
            "Protein ramp",
            "Your first logged week measures what you eat now, then protein rises about 15 g a week to the full target.",
            on,
        ) { checked -> vm.updateDraft { it.copy(proteinRamp = checked) } }
    }
}

@Composable
private fun Label(text: String, missing: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium)
        if (missing) Text("Choose", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
    }
}

// 7. Targets (PR-02)

private fun LazyListScope.targetsStep(ui: OnboardingUi, vm: OnboardingViewModel) {
    val targets = vm.targets() ?: return
    val policy = ui.plan?.policy ?: return
    heading("Your targets", "The same every day, judged on your 7-day average.")
    guardrailNotes(ui.guarded?.notes.orEmpty().filter { it != GuardrailNote.AGGRESSIVE_NEEDS_ACK })
    item {
        TargetList(
            targets = targets,
            policy = policy,
            before = ui.before.takeIf { ui.mode == FlowMode.GOAL },
            onOverride = vm::setOverrides,
        )
    }
    item { Text(GoalText.NOT_MEDICAL_ADVICE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

private fun LazyListScope.guardrailNotes(notes: List<GuardrailNote>) {
    notes.forEach { note -> item { Text(GoalText.guardrail(note), color = MaterialTheme.colorScheme.secondary) } }
}

/**
 * Calories, protein, fat and carbs, each with where it came from (PR-02). Tapping one of the first three
 * sets your own value; carbs always fill the rest. [before] shows a Settings change's old numbers.
 */
@Composable
fun TargetList(targets: NutritionTargets, policy: GoalPolicy, before: NutritionTargetEntity?, onOverride: (TargetOverrides) -> Unit) {
    var editing by remember { mutableStateOf<String?>(null) }
    val o = targets.overrides
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Protein first: the hero metric (NU-02).
        TargetRow("Protein", "${targets.proteinG} g", before?.proteinG?.let { "$it g" }, GoalText.protein(targets), o.proteinG != null, { editing = "protein" }) {
            onOverride(o.copy(proteinG = null))
        }
        TargetRow("Calories", GoalText.kcal(targets.kcal), before?.kcal?.let(GoalText::kcal), GoalText.calories(targets, policy), o.kcal != null, { editing = "kcal" }) {
            onOverride(o.copy(kcal = null))
        }
        TargetRow("Fat", "${targets.fatG} g", before?.fatG?.let { "$it g" }, GoalText.fat(targets), o.fatG != null, { editing = "fat" }) {
            onOverride(o.copy(fatG = null))
        }
        TargetRow("Carbs", "${targets.carbsG} g", before?.carbsG?.let { "$it g" }, GoalText.CARBS, overridden = false, onEdit = null, onReset = {})
    }
    editing?.let { which ->
        val (title, initial, suffix) = when (which) {
            "kcal" -> Triple("Your calorie target", targets.kcal, "kcal")
            "protein" -> Triple("Your protein target", targets.proteinG, "g")
            else -> Triple("Your fat target", targets.fatG, "g")
        }
        NumberEntryDialog(title, "$initial", decimal = false, suffix = suffix, onDismiss = { editing = null }) { text ->
            editing = null
            val value = text.trim().toIntOrNull()?.takeIf { it > 0 } ?: return@NumberEntryDialog
            onOverride(
                when (which) {
                    "kcal" -> o.copy(kcal = value)
                    "protein" -> o.copy(proteinG = value)
                    else -> o.copy(fatG = value)
                },
            )
        }
    }
}

@Composable
private fun TargetRow(label: String, value: String, was: String?, why: String, overridden: Boolean, onEdit: (() -> Unit)?, onReset: () -> Unit) {
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                if (was != null && was != value) Text("$was → ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            }
            Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (overridden) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Set by you", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f))
                    TextButton(onClick = onReset) { Text("Reset") }
                }
            } else if (onEdit != null) {
                Text("Tap to set your own", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    if (onEdit != null) Card(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { content() } else Card(Modifier.fillMaxWidth()) { content() }
}

// 8. Gym equipment (PR-04)

private fun LazyListScope.equipmentStep(ui: OnboardingUi, vm: OnboardingViewModel) {
    heading(
        "Your gym's equipment",
        if (ui.walkthrough.isEmpty()) "No exercises to set up for this split." else "Pick the variant your gym has, and the smallest weight step for each. The step sets how weights go up.",
    )
    var group: String? = null
    ui.walkthrough.forEach { entry ->
        val routine = entry.routines.first()
        if (routine != group) {
            group = routine
            item(key = "group-$routine") { Text(routine, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        }
        item(key = entry.slotKey) {
            WalkthroughCard(entry, ui.choices[entry.slotKey] ?: entry.selectedId, ui.increments, { vm.choose(entry.slotKey, it) }, vm::setIncrement)
        }
    }
}

// Shared fields

@Composable
fun NumberField(label: String, value: String, suffix: String, modifier: Modifier = Modifier, error: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        isError = error,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
fun SwitchRow(title: String, body: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (body != null) Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
