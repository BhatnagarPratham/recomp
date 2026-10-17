package app.recomp.data.repo

import app.recomp.data.db.NutritionDao
import app.recomp.data.db.NutritionTargetEntity
import app.recomp.data.db.TargetReason
import app.recomp.data.prefs.DayBoundary
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.data.prefs.StoredGoal
import app.recomp.data.prefs.StoredPersona
import app.recomp.domain.nutrition.NutritionCalculator
import app.recomp.domain.nutrition.NutritionTargets
import app.recomp.domain.nutrition.RampState
import app.recomp.domain.nutrition.TargetInputs
import app.recomp.domain.nutrition.TargetOverrides
import app.recomp.domain.persona.Diet
import app.recomp.domain.persona.ExtraSession
import app.recomp.domain.persona.FoodRegion
import app.recomp.domain.persona.GoalPlan
import app.recomp.domain.persona.Guardrails
import app.recomp.domain.persona.GuardrailNote
import app.recomp.domain.persona.Persona
import app.recomp.domain.persona.PersonaDraft
import app.recomp.domain.persona.Profile
import app.recomp.domain.persona.Units
import app.recomp.domain.persona.WeightSource
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Everything onboarding collected (PS-01, PR-01 to PR-04). */
data class OnboardingResult(
    val draft: PersonaDraft,
    val presetKey: String?,
    val extras: List<ExtraDraft>,
    val profile: Profile,
    val dayStart: LocalTime,
    val overrides: TargetOverrides = TargetOverrides(),
    /** The equipment walkthrough's picks; empty when it didn't run (same split on an upgraded install). */
    val choices: Map<String, Long> = emptyMap(),
    val increments: Map<Long, Int> = emptyMap(),
)

/** What a Settings change would do to the targets (PRD §7.1: shown before it saves). */
data class TargetPreview(
    val before: NutritionTargetEntity?,
    val after: NutritionTargets,
    val plan: GoalPlan,
    val notes: List<GuardrailNote>,
    /** The body data the new targets use, with the trend weight once there is one (TRD §5.0). */
    val profile: Profile,
) {
    val changes: Boolean
        get() = before == null || before.kcal != after.kcal || before.proteinG != after.proteinG ||
            before.fatG != after.fatG || before.carbsG != after.carbsG || before.goal != plan.goal
}

/**
 * The persona, body data and their settings (PS-01 to PS-03, PR-01 to PR-03). Any change that moves the
 * targets writes a `nutrition_target` version effective today; a second change the same day replaces it.
 */
@Singleton
class PersonaRepository @Inject constructor(
    private val prefs: SettingsPrefs,
    private val nutritionDao: NutritionDao,
    private val training: TrainingRepository,
    private val days: DayBoundary,
    private val body: BodyRepository,
) {
    val stored: Flow<StoredPersona> = prefs.persona
    val profile: Flow<Profile?> = prefs.profile
    val overrides: Flow<TargetOverrides> = prefs.overrides
    val dayStart: Flow<LocalTime> = prefs.dayStart
    val onboarded: Flow<Boolean> = combine(prefs.persona, prefs.profile) { p, profile -> p.onboarded && profile != null }

    /** Settings stored but no longer readable; they're kept aside, never overwritten (TRD §4). */
    val unreadable: Flow<List<String>> = prefs.unreadable

    /** The domain persona, with extra sessions named from their routines. Null until onboarding finishes. */
    val persona: Flow<Persona?> = combine(prefs.persona, training.observeExtras()) { p, routines ->
        if (!p.onboarded) return@combine null
        val byId = routines.associateBy { it.id }
        p.toDraft(p.extras.mapNotNull { s -> byId[s.routineId]?.let { ExtraSession(it.name, it.kind, s.days.map(DayOfWeek::of).toSet()) } }).complete()
    }

    fun targetsFor(profile: Profile, plan: GoalPlan, overrides: TargetOverrides, today: LocalDate): NutritionTargets =
        NutritionCalculator.startingTargets(TargetInputs.from(profile, plan, today), overrides)

    suspend fun today(): LocalDate = days.today()

    /** The trend weight, once the primary source has a weigh-in (TRD §5.0). */
    suspend fun trendKg(): Double? = body.trendKg()

    /** Body data as targets see it: the trend weight stands in for the typed one once there is one (TRD §5.0). */
    suspend fun currentProfile(): Profile? = prefs.profile.first()?.let { withTrend(it) }

    private suspend fun withTrend(profile: Profile): Profile = body.trendKg()?.let { profile.copy(weightKg = it) } ?: profile

    // Onboarding (PS-01)

    /**
     * Saves onboarding: builds or keeps the routines, then writes the first target version, then the
     * settings. `onboarded_on` is written last, so an interrupted save simply shows onboarding again.
     */
    suspend fun completeOnboarding(result: OnboardingResult, now: Long = System.currentTimeMillis()) {
        val today = days.of(now)
        val chosen = requireNotNull(result.draft.complete()) { "Onboarding finished with ${result.draft.missing} unset" }
        val plan = Guardrails.apply(result.profile, chosen.goal, today).plan
        training.applySetup(
            TrainingSetup(
                split = chosen.split,
                experience = chosen.experience,
                strengthDaysPerWeek = chosen.strengthDaysPerWeek,
                extras = result.extras,
                choices = result.choices,
                increments = result.increments,
            ),
        )
        writeTarget(targetsFor(result.profile, plan, result.overrides, today), plan, today, TargetReason.INITIAL, now)
        prefs.edit {
            persona = this.persona.copy(
                goal = StoredGoal.of(plan),
                diet = chosen.diet,
                region = chosen.region,
                weightSource = chosen.weightSource,
                units = chosen.units,
                proteinRamp = chosen.proteinRamp,
                presetKey = result.presetKey,
                onboardedOn = today,
            )
            setProfile(result.profile)
            setDayStart(result.dayStart)
            setOverrides(result.overrides)
            setRamp(if (chosen.proteinRamp) RampState(countFrom = today) else null)
        }
    }

    // Settings (PS-02)

    /** What saving [plan], [profile] and [overrides] (null = unchanged) would do to today's targets. */
    suspend fun preview(plan: GoalPlan? = null, profile: Profile? = null, overrides: TargetOverrides? = null): TargetPreview {
        val today = days.today()
        val stored = prefs.currentPersona()
        val newProfile = withTrend(profile ?: requireNotNull(prefs.profile.first()) { "No profile before onboarding" })
        val guarded = Guardrails.apply(newProfile, plan ?: requireNotNull(stored.goal).toPlan(), today)
        val newOverrides = overrides ?: prefs.overrides.first()
        return TargetPreview(
            before = nutritionDao.targetOn(today),
            after = targetsFor(newProfile, guarded.plan, newOverrides, today),
            plan = guarded.plan,
            notes = guarded.notes,
            profile = newProfile,
        )
    }

    /**
     * Saves a goal, body-data or override change and, if the targets moved, a new version from today. The
     * stored body data then holds the weight those targets used, the trend weight once there is one.
     */
    suspend fun save(plan: GoalPlan? = null, profile: Profile? = null, overrides: TargetOverrides? = null, now: Long = System.currentTimeMillis()) {
        val p = preview(plan, profile, overrides)
        val today = days.of(now)
        if (p.changes) writeTarget(p.after, p.plan, today, TargetReason.MANUAL, now)
        prefs.edit {
            persona = this.persona.copy(goal = StoredGoal.of(p.plan))
            if (profile != null || p.changes) setProfile(p.profile)
            if (overrides != null) setOverrides(overrides)
        }
    }

    suspend fun setDiet(diet: Diet) = prefs.updatePersona { it.copy(diet = diet) }
    suspend fun setRegion(region: FoodRegion) = prefs.updatePersona { it.copy(region = region) }
    suspend fun setWeightSource(source: WeightSource) = prefs.updatePersona { it.copy(weightSource = source) }
    suspend fun setUnits(units: Units) = prefs.updatePersona { it.copy(units = units) }

    /** PR-03. Days already logged keep their date. */
    suspend fun setDayStart(time: LocalTime) = prefs.edit { setDayStart(time) }

    /** NU-03: off jumps to the full target; on starts a fresh baseline from today. */
    suspend fun setProteinRamp(on: Boolean) {
        val today = days.today()
        prefs.edit {
            persona = this.persona.copy(proteinRamp = on)
            setRamp(if (on) RampState(countFrom = today) else null)
        }
    }

    private suspend fun writeTarget(t: NutritionTargets, plan: GoalPlan, today: LocalDate, reason: TargetReason, now: Long) {
        nutritionDao.upsert(
            NutritionTargetEntity(
                effectiveFrom = today,
                goal = plan.goal,
                kcal = t.kcal,
                proteinG = t.proteinG,
                fatG = t.fatG,
                carbsG = t.carbsG,
                tdeeEstimate = t.tdeeKcal.roundToInt(),
                bmrKcal = t.bmrKcal.roundToInt(),
                reason = reason,
                createdAt = now,
            ),
        )
    }
}

/** The stored persona as an onboarding draft, with [extras] resolved from the routines. */
fun StoredPersona.toDraft(extras: List<ExtraSession> = emptyList()) = PersonaDraft(
    goal = goal?.toPlan(),
    experience = experience,
    diet = diet,
    region = region,
    split = split,
    strengthDaysPerWeek = strengthDaysPerWeek,
    extras = extras,
    weightSource = weightSource,
    units = units,
    proteinRamp = proteinRamp,
)

/** Exposed for the 7-day average and Today. */
fun StoredPersona.rampEnabled(): Boolean = proteinRamp ?: (diet?.rampByDefault ?: false)
