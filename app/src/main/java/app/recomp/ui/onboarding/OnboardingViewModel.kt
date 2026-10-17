package app.recomp.ui.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.recomp.data.db.NutritionTargetEntity
import app.recomp.data.prefs.FinishKind
import app.recomp.data.repo.ExtraDraft
import app.recomp.data.repo.NutritionRepository
import app.recomp.data.repo.OnboardingResult
import app.recomp.data.repo.PersonaRepository
import app.recomp.data.repo.TrainingRepository
import app.recomp.data.repo.WalkthroughItem
import app.recomp.data.repo.toDraft
import app.recomp.domain.model.SessionKind
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.GoalPolicy
import app.recomp.domain.nutrition.NutritionTargets
import app.recomp.domain.nutrition.Pace
import app.recomp.domain.nutrition.Sex
import app.recomp.domain.nutrition.TargetOverrides
import app.recomp.domain.persona.ActivityLevel
import app.recomp.domain.persona.Bmi
import app.recomp.domain.persona.Experience
import app.recomp.domain.persona.FinishLine
import app.recomp.domain.persona.GoalAdvice
import app.recomp.domain.persona.GoalPlan
import app.recomp.domain.persona.GoalRecommender
import app.recomp.domain.persona.GoalWeights
import app.recomp.domain.persona.Guarded
import app.recomp.domain.persona.Guardrails
import app.recomp.domain.persona.Intent
import app.recomp.domain.persona.PersonaDraft
import app.recomp.domain.persona.Presets
import app.recomp.domain.persona.Profile
import app.recomp.domain.persona.Projection
import app.recomp.domain.persona.SplitTemplate
import app.recomp.domain.training.LogDates
import app.recomp.ui.components.Labels
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Onboarding (PS-01, J0), or the same goal flow from Settings → Goal (PRD §4.6). */
enum class FlowMode { ONBOARDING, GOAL }

enum class Step { PRESET, ABOUT, GOAL, QUESTIONS, SUGGESTION, PACE, FINISH, REVIEW, TARGETS, EQUIPMENT }

/** "About you" (PR-01) as typed. */
data class AboutForm(
    val sex: Sex? = null,
    val birthDate: LocalDate? = null,
    val height: String = "",
    val weight: String = "",
    val activity: ActivityLevel = ActivityLevel.MODERATE,
) {
    val heightCm: Double? get() = height.toDecimal()?.takeIf { it in 100.0..250.0 }
    val weightKg: Double? get() = weight.toDecimal()?.takeIf { it in 30.0..300.0 }

    fun profile(today: LocalDate): Profile? {
        val birth = birthDate?.takeIf { it.isBefore(today) && it.isAfter(today.minusYears(120)) } ?: return null
        return Profile(sex ?: return null, birth, heightCm ?: return null, weightKg ?: return null, activity)
    }

    companion object {
        fun of(p: Profile) = AboutForm(p.sex, p.birthDate, GoalText.d(p.heightCm), GoalText.d(p.weightKg), p.activity)
    }
}

/** The goal flow's answers (PRD §4.6, §4.7). */
data class GoalForm(
    val goal: Goal? = null,
    /** "Not sure? Answer 3 quick questions" took the questions route. */
    val notSure: Boolean = false,
    val intent: Intent? = null,
    val liftingAge: Experience? = null,
    val knowsBodyFat: Boolean = false,
    val bodyFat: String = "",
    val advice: GoalAdvice? = null,
    val pace: Pace = Pace.STANDARD,
    /** Custom values under Advanced; null uses the pace preset. */
    val custom: GoalPolicy? = null,
    val goalWeight: String = "",
    val goalWeightTouched: Boolean = false,
    val finishKind: FinishKind? = null,
    val finishDate: LocalDate? = null,
    /** You picked a finish line (including none), so the goal's default no longer applies. */
    val finishChosen: Boolean = false,
    val acknowledged: Boolean = false,
) {
    val bodyFatPct: Double? get() = if (knowsBodyFat) bodyFat.toDecimal()?.takeIf { it in 3.0..70.0 } else null
    val goalWeightKg: Double? get() = goalWeight.toDecimal()?.takeIf { it in 30.0..300.0 }
}

data class PaceOption(val pace: Pace, val text: String, val allowed: Boolean)

data class OnboardingUi(
    val mode: FlowMode = FlowMode.ONBOARDING,
    val loaded: Boolean = false,
    val today: LocalDate = LocalDate.now(),
    val step: Step = Step.PRESET,
    val presetKey: String? = null,
    val about: AboutForm = AboutForm(),
    val goal: GoalForm = GoalForm(),
    /** Review values. Its goal stays null; [plan] is the goal. */
    val draft: PersonaDraft = PersonaDraft(),
    val extras: List<ExtraDraft> = emptyList(),
    val dayStart: LocalTime = LogDates.DEFAULT_DAY_START,
    val overrides: TargetOverrides = TargetOverrides(),
    /** The split already set up (an upgraded install); its routines are kept unless you pick another. */
    val currentSplit: SplitTemplate? = null,
    val walkthrough: List<WalkthroughItem> = emptyList(),
    val choices: Map<String, Long> = emptyMap(),
    val increments: Map<Long, Int> = emptyMap(),
    val askAggressive: Boolean = false,
    /** The warning came from Next (custom values), so acknowledging it moves on. */
    val continueAfterAck: Boolean = false,
    /** Settings → Goal: the targets in force before this change. */
    val before: NutritionTargetEntity? = null,
    val busy: Boolean = false,
    /** Saving failed: the form stays as it is so you can try again. */
    val error: String? = null,
    val done: Boolean = false,
) {
    val profile: Profile? get() = about.profile(today)

    /** The goal plan as chosen, before guardrails. */
    val rawPlan: GoalPlan?
        get() {
            val goal = goal.goal ?: return null
            val custom = this.goal.custom?.takeIf { it.goal == goal }
            val policy = custom ?: GoalPolicy.defaultFor(goal, this.goal.pace)
            val pace = if (goal == Goal.MAINTAIN || custom != null) null else this.goal.pace
            val finish = when (this.goal.finishKind) {
                FinishKind.GOAL_WEIGHT -> FinishLine.GoalWeight
                FinishKind.DATE -> this.goal.finishDate?.let(FinishLine::ByDate)
                FinishKind.BODY_FAT, null -> null
            }
            return GoalPlan(policy, pace, this.goal.goalWeightKg, finish, startedOn = today, aggressiveAcknowledged = this.goal.acknowledged)
        }

    val guarded: Guarded? get() = profile?.let { p -> rawPlan?.let { Guardrails.apply(p, it, today) } }

    val plan: GoalPlan? get() = guarded?.plan

    val needsEquipment: Boolean
        get() = mode == FlowMode.ONBOARDING && draft.split != null && draft.split != SplitTemplate.CUSTOM && draft.split != currentSplit

    val steps: List<Step>
        get() = buildList {
            if (mode == FlowMode.ONBOARDING) addAll(listOf(Step.PRESET, Step.ABOUT))
            add(Step.GOAL)
            if (goal.notSure) addAll(listOf(Step.QUESTIONS, Step.SUGGESTION))
            if (goal.goal != Goal.MAINTAIN) add(Step.PACE)
            add(Step.FINISH)
            if (mode == FlowMode.ONBOARDING) addAll(listOf(Step.REVIEW, Step.TARGETS)) else add(Step.TARGETS)
            if (needsEquipment) add(Step.EQUIPMENT)
        }

    val isLast: Boolean get() = steps.last() == step

    val canContinue: Boolean
        get() = when (step) {
            Step.PRESET -> presetKey != null
            Step.ABOUT -> profile != null
            Step.GOAL -> goal.goal != null && profile?.let { Guardrails.goalAvailable(goal.goal, it) } == true
            Step.QUESTIONS -> goal.intent != null && goal.liftingAge != null && (!goal.knowsBodyFat || goal.bodyFatPct != null)
            Step.SUGGESTION -> goal.advice != null
            Step.PACE -> true
            Step.FINISH -> when (goal.finishKind) {
                FinishKind.GOAL_WEIGHT -> goal.goalWeightKg != null
                FinishKind.DATE -> goal.finishDate?.isAfter(today) == true
                else -> goal.goalWeight.isBlank() || goal.goalWeightKg != null
            }
            Step.REVIEW -> draft.missing.none { it != "goal" }
            Step.TARGETS, Step.EQUIPMENT -> !busy
        }
}

private fun String.toDecimal(): Double? = trim().replace(',', '.').toDoubleOrNull()

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val personas: PersonaRepository,
    private val training: TrainingRepository,
    private val nutrition: NutritionRepository,
) : ViewModel() {
    private val mode = if (savedState.get<Boolean>("goalOnly") == true) FlowMode.GOAL else FlowMode.ONBOARDING
    private val _ui = MutableStateFlow(OnboardingUi(mode = mode))
    val ui: StateFlow<OnboardingUi> = _ui

    init {
        viewModelScope.launch {
            val today = personas.today()
            val stored = personas.stored.first()
            val profile = personas.currentProfile()
            val routines = training.observeExtras().first().associateBy { it.id }
            val extras = stored.extras.mapNotNull { s -> routines[s.routineId]?.let { ExtraDraft(it.id, it.name, it.kind, s.days) } }
            val plan = stored.goal?.toPlan()
            _ui.update {
                it.copy(
                    loaded = true,
                    today = today,
                    about = profile?.let(AboutForm::of) ?: it.about,
                    goal = plan?.let(::formOf) ?: it.goal,
                    draft = stored.toDraft(),
                    extras = extras,
                    dayStart = personas.dayStart.first(),
                    overrides = personas.overrides.first(),
                    currentSplit = stored.split,
                    step = if (mode == FlowMode.GOAL) Step.GOAL else Step.PRESET,
                    before = if (mode == FlowMode.GOAL) nutrition.targetOn(today) else null,
                )
            }
        }
    }

    private fun formOf(plan: GoalPlan) = GoalForm(
        goal = plan.goal,
        pace = plan.pace ?: Pace.STANDARD,
        custom = if (plan.pace == null && plan.goal != Goal.MAINTAIN) plan.policy else null,
        goalWeight = plan.goalWeightKg?.let(GoalText::d).orEmpty(),
        goalWeightTouched = plan.goalWeightKg != null,
        finishKind = when (plan.finishLine) {
            null -> null
            FinishLine.GoalWeight -> FinishKind.GOAL_WEIGHT
            is FinishLine.BodyFat -> FinishKind.BODY_FAT
            is FinishLine.ByDate -> FinishKind.DATE
        },
        finishDate = (plan.finishLine as? FinishLine.ByDate)?.date,
        finishChosen = true,
        acknowledged = plan.aggressiveAcknowledged,
    )

    // Steps

    fun next() {
        val ui = _ui.value
        if (!ui.canContinue) return
        if (ui.step == Step.PACE && ui.guarded?.needsAcknowledgement == true) {
            _ui.update { it.copy(askAggressive = true, continueAfterAck = true) }
            return
        }
        if (ui.step == Step.QUESTIONS) return suggest()
        if (ui.step == Step.SUGGESTION) return acceptSuggestion()
        if (ui.isLast) return save()
        val nextStep = ui.steps[ui.steps.indexOf(ui.step) + 1]
        if (nextStep == Step.EQUIPMENT) return openWalkthrough()
        _ui.update { it.copy(step = nextStep) }
        if (nextStep == Step.FINISH) prepareFinishLine()
    }

    /** False when there's nowhere to go back to inside the flow. */
    fun back(): Boolean {
        val ui = _ui.value
        val index = ui.steps.indexOf(ui.step)
        if (index <= 0) return false
        _ui.update { it.copy(step = ui.steps[index - 1]) }
        return true
    }

    private fun openWalkthrough() {
        val split = _ui.value.draft.split ?: return
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val items = training.walkthrough(split)
            _ui.update { state ->
                state.copy(
                    step = Step.EQUIPMENT,
                    walkthrough = items,
                    choices = items.associate { it.slotKey to it.selectedId },
                    increments = items.flatMap { it.options }.associate { it.id to it.incrementG },
                    busy = false,
                )
            }
        }
    }

    // Preset (PRD §4.4)

    fun pickPreset(key: String) {
        val preset = Presets.byKey(key) ?: return
        _ui.update { ui ->
            // An upgraded install keeps the training setup it already has; the preset fills the rest.
            val kept = ui.currentSplit != null
            val stored = ui.draft
            ui.copy(
                presetKey = key,
                draft = preset.draft.copy(
                    experience = if (kept) stored.experience else preset.draft.experience,
                    split = if (kept) stored.split else preset.draft.split,
                    strengthDaysPerWeek = if (kept) stored.strengthDaysPerWeek else null,
                ),
                extras = if (kept) ui.extras else preset.draft.extras.map { ExtraDraft(null, it.name, it.kind, it.days.map { d -> d.value }.toSet()) },
                goal = GoalForm(goal = preset.goal),
            )
        }
    }

    // About you (PR-01)

    fun updateAbout(transform: (AboutForm) -> AboutForm) = _ui.update { it.copy(about = transform(it.about)) }

    // Goal (PRD §4.6)

    fun pickGoal(goal: Goal) = _ui.update { ui ->
        if (ui.goal.goal == goal && !ui.goal.notSure) ui
        else if (ui.goal.goal == goal) ui.copy(goal = ui.goal.copy(notSure = false))
        else ui.copy(goal = ui.goal.copy(goal = goal, notSure = false, custom = null, acknowledged = false, goalWeightTouched = false, finishChosen = false))
    }

    fun notSure() = _ui.update { it.copy(goal = it.goal.copy(notSure = true), step = Step.QUESTIONS) }

    fun answer(transform: (GoalForm) -> GoalForm) = _ui.update { it.copy(goal = transform(it.goal)) }

    /** Ends the three questions with a reasoned recommendation (GL-02). The lifting answer pre-fills experience. */
    fun suggest() {
        val ui = _ui.value
        val profile = ui.profile ?: return
        val form = ui.goal
        val advice = GoalRecommender.recommend(form.intent ?: return, form.liftingAge ?: return, profile.sex, profile.bmi, form.bodyFatPct)
        _ui.update {
            it.copy(
                goal = it.goal.copy(advice = advice),
                draft = if (it.mode == FlowMode.ONBOARDING) it.draft.copy(experience = form.liftingAge) else it.draft,
                step = Step.SUGGESTION,
            )
        }
    }

    fun acceptSuggestion() {
        val advice = _ui.value.goal.advice ?: return
        if (_ui.value.goal.goal != advice.goal) {
            _ui.update { it.copy(goal = it.goal.copy(goal = advice.goal, custom = null, acknowledged = false, goalWeightTouched = false, finishChosen = false)) }
        }
        // Keep the questions in the back stack, then move past the suggestion.
        val ui = _ui.value
        _ui.update { it.copy(step = ui.steps[ui.steps.indexOf(Step.SUGGESTION) + 1]) }
        if (_ui.value.step == Step.FINISH) prepareFinishLine()
    }

    fun pickAnotherGoal() = _ui.update { it.copy(goal = it.goal.copy(notSure = false), step = Step.GOAL) }

    // Pace (GL-04)

    fun paceOptions(): List<PaceOption> {
        val ui = _ui.value
        val goal = ui.goal.goal ?: return emptyList()
        val profile = ui.profile ?: return emptyList()
        val allowed = Guardrails.allowedPaces(goal, profile, ui.today)
        return Pace.entries.map { pace ->
            val plan = ui.rawPlan!!.copy(policy = GoalPolicy.defaultFor(goal, pace), pace = pace)
            PaceOption(pace, projectionFor(ui, profile, plan), pace in allowed)
        }
    }

    fun projectionText(): String? {
        val ui = _ui.value
        val profile = ui.profile ?: return null
        val plan = ui.plan ?: return null
        return projectionFor(ui, profile, plan)
    }

    private fun projectionFor(ui: OnboardingUi, profile: Profile, plan: GoalPlan): String {
        val targets = personas.targetsFor(profile, plan, TargetOverrides(), ui.today)
        val perWeek = Projection.kgPerWeek(targets.energyAdjustmentKcal)
        val goalKg = plan.goalWeightKg ?: suggestedGoalWeight(ui)
        val weeks = goalKg?.let { Projection.weeksTo(profile.weightKg, it, perWeek) }
        val byDate = (plan.finishLine as? FinishLine.ByDate)?.date
        return GoalText.projection(perWeek, weeks, weeks?.let { Projection.finishDate(ui.today, it) }, ui.today, byDate)
    }

    private fun suggestedGoalWeight(ui: OnboardingUi): Double? {
        val p = ui.profile ?: return null
        val goal = ui.goal.goal ?: return null
        return if (GoalWeights.suggestsGoalWeight(goal)) GoalWeights.suggest(p.heightCm, p.weightKg) else null
    }

    fun pickPace(pace: Pace) {
        val ui = _ui.value
        val goal = ui.goal.goal ?: return
        val aggressive = Guardrails.isAggressive(GoalPolicy.defaultFor(goal, pace))
        _ui.update { it.copy(goal = it.goal.copy(pace = pace, custom = null), askAggressive = aggressive && !it.goal.acknowledged, continueAfterAck = false) }
    }

    fun acknowledgeAggressive() {
        val proceed = _ui.value.continueAfterAck
        _ui.update { it.copy(goal = it.goal.copy(acknowledged = true), askAggressive = false, continueAfterAck = false) }
        if (proceed) next()
    }

    /** Declining a picked Aggressive pace goes back to Standard; declining at Next leaves your custom values to adjust. */
    fun declineAggressive() = _ui.update {
        val pace = if (it.continueAfterAck) it.goal.pace else Pace.STANDARD
        it.copy(goal = it.goal.copy(pace = pace), askAggressive = false, continueAfterAck = false)
    }

    /** Advanced (GL-04): exact values, still held to the caps, the floor and the guardrails. */
    fun setCustom(ratePct: Double?, proteinGPerKg: Double?) {
        val ui = _ui.value
        val goal = ui.goal.goal ?: return
        val base = ui.goal.custom ?: GoalPolicy.defaultFor(goal, ui.goal.pace)
        val protein = (proteinGPerKg ?: base.proteinGPerKg).coerceIn(GoalPolicy.proteinRange(goal))
        val policy = when (goal) {
            Goal.FAT_LOSS, Goal.RECOMP -> base.copy(lossRatePctPerWeek = (ratePct ?: base.lossRatePctPerWeek).coerceIn(GoalPolicy.LOSS_RATE_RANGE), proteinGPerKg = protein)
            Goal.LEAN_BULK -> base.copy(surplusFraction = ((ratePct?.div(100)) ?: base.surplusFraction).coerceIn(GoalPolicy.SURPLUS_RANGE), proteinGPerKg = protein)
            Goal.MAINTAIN -> base.copy(proteinGPerKg = protein)
        }
        _ui.update { it.copy(goal = it.goal.copy(custom = policy)) }
    }

    fun clearCustom() = _ui.update { it.copy(goal = it.goal.copy(custom = null)) }

    // Finish line (PRD §4.7)

    /** Fills the suggested goal weight and the goal's default finish line, unless you already chose. */
    private fun prepareFinishLine() = _ui.update { ui ->
        val goal = ui.goal.goal ?: return@update ui
        val form = ui.goal
        val suggestion = suggestedGoalWeight(ui)
        val default = GoalWeights.defaultFinishLine(goal, ui.today)
        ui.copy(
            goal = form.copy(
                goalWeight = if (form.goalWeightTouched) form.goalWeight else suggestion?.let(GoalText::d).orEmpty(),
                finishKind = if (form.finishChosen) form.finishKind else when (default) {
                    null -> null
                    FinishLine.GoalWeight -> FinishKind.GOAL_WEIGHT
                    is FinishLine.ByDate -> FinishKind.DATE
                    is FinishLine.BodyFat -> FinishKind.BODY_FAT
                },
                finishDate = if (form.finishChosen) form.finishDate else (default as? FinishLine.ByDate)?.date ?: form.finishDate,
            ),
        )
    }

    fun setGoalWeight(text: String) = _ui.update { it.copy(goal = it.goal.copy(goalWeight = text, goalWeightTouched = true)) }

    fun setFinish(kind: FinishKind?) = _ui.update {
        it.copy(goal = it.goal.copy(finishKind = kind, finishChosen = true, finishDate = it.goal.finishDate ?: it.today.plusWeeks(GoalWeights.LEAN_BULK_WEEKS)))
    }

    fun setFinishDate(date: LocalDate) = _ui.update { it.copy(goal = it.goal.copy(finishDate = date, finishKind = FinishKind.DATE, finishChosen = true)) }

    // Review (PS-01)

    fun updateDraft(transform: (PersonaDraft) -> PersonaDraft) = _ui.update { it.copy(draft = transform(it.draft)) }

    fun pickSplit(split: SplitTemplate) = _ui.update {
        it.copy(draft = it.draft.copy(split = split, strengthDaysPerWeek = if (it.currentSplit == null) split.defaultStrengthDaysPerWeek else it.draft.strengthDaysPerWeek))
    }

    fun setStrengthDays(days: Int) = _ui.update { it.copy(draft = it.draft.copy(strengthDaysPerWeek = days.coerceIn(1, 7))) }
    fun addExtra() = _ui.update { it.copy(extras = it.extras + ExtraDraft(null, "Yoga class", SessionKind.YOGA, emptySet())) }
    fun updateExtra(index: Int, draft: ExtraDraft) = _ui.update { it.copy(extras = it.extras.toMutableList().also { l -> l[index] = draft }) }
    fun removeExtra(index: Int) = _ui.update { it.copy(extras = it.extras.toMutableList().also { l -> l.removeAt(index) }) }
    fun setDayStart(time: LocalTime) = _ui.update { it.copy(dayStart = time) }

    // Targets (PR-02)

    fun targets(): NutritionTargets? {
        val ui = _ui.value
        val profile = ui.profile ?: return null
        val plan = ui.plan ?: return null
        return personas.targetsFor(profile, plan, ui.overrides, ui.today)
    }

    fun setOverrides(overrides: TargetOverrides) = _ui.update { it.copy(overrides = overrides) }

    // Equipment (PR-04)

    fun choose(slot: String, exerciseId: Long) = _ui.update { it.copy(choices = it.choices + (slot to exerciseId)) }
    fun setIncrement(exerciseId: Long, grams: Int) = _ui.update { it.copy(increments = it.increments + (exerciseId to grams)) }

    // Saving

    private fun save() {
        val ui = _ui.value
        val profile = ui.profile ?: return
        val plan = ui.plan ?: return
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                write(ui, profile, plan)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Safe to retry: the routines step re-applies in place, and the settings are written last.
                _ui.update { it.copy(busy = false, error = "Couldn't save: ${e.message ?: e.javaClass.simpleName}. Nothing was lost; try again.") }
                return@launch
            }
            _ui.update { it.copy(busy = false, done = true) }
        }
    }

    private suspend fun write(ui: OnboardingUi, profile: Profile, plan: GoalPlan) {
        if (ui.mode == FlowMode.GOAL) {
            personas.save(plan = plan)
        } else {
            personas.completeOnboarding(
                OnboardingResult(
                    draft = ui.draft.copy(goal = plan),
                    presetKey = ui.presetKey,
                    extras = ui.extras.map { it.copy(name = it.name.trim().ifEmpty { Labels.kind(it.kind) }) },
                    profile = profile,
                    dayStart = ui.dayStart,
                    overrides = ui.overrides,
                    choices = if (ui.needsEquipment) ui.choices else emptyMap(),
                    increments = if (ui.needsEquipment) ui.increments.filterKeys { id -> id in ui.choices.values } else emptyMap(),
                ),
            )
        }
    }

    /** BMI for the third question's default answer. */
    fun bmi(): Double? = _ui.value.profile?.let { Bmi.of(it.weightKg, it.heightCm) }
}
