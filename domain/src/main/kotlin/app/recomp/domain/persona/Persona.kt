package app.recomp.domain.persona

import app.recomp.domain.model.SessionKind
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.GoalPolicy
import app.recomp.domain.nutrition.Pace
import app.recomp.domain.nutrition.Sex
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Period

/** Diet (PRD §4.1): filters food search, shapes AI prompts and sets the protein ramp's default. */
enum class Diet(val rampByDefault: Boolean) {
    VEGAN(true),
    LACTO_VEGETARIAN(true),
    EGGETARIAN(true),
    PESCATARIAN(false),
    NON_VEGETARIAN(false),
}

/** Food region (PRD §4.1): the bundled food database, portion units and AI portion conventions. */
enum class FoodRegion { INDIAN, INTERNATIONAL }

/** Where the weight trend comes from (BM-01). */
enum class WeightSource { HOME_SCALE, SCAN, GYM_SCALE }

/** Display units. Storage is always metric; the MVP shows metric only (PRD §11). */
enum class Units { METRIC, IMPERIAL }

/** Activity factor for TDEE (TRD §5.3). [MODERATE] is the default. */
enum class ActivityLevel(val factor: Double) {
    SEDENTARY(1.2),
    MODERATE(1.375),
    ACTIVE(1.55),
}

/** A configured extra session (PRD §7.6): yoga, mobility, cardio or sport on optional weekdays. */
data class ExtraSession(val name: String, val kind: SessionKind, val days: Set<DayOfWeek> = emptySet())

/** One finish line per goal (PRD §4.7). No finish line is a null [GoalPlan.finishLine]. */
sealed interface FinishLine {
    /** Reach [GoalPlan.goalWeightKg] on the trend. */
    data object GoalWeight : FinishLine

    /** Needs body-composition scans (IB-05). */
    data class BodyFat(val pct: Double) : FinishLine

    data class ByDate(val date: LocalDate) : FinishLine
}

/**
 * The active goal (TRD §5.0). [goalWeightKg] is the one goal weight: protein's reference weight
 * whatever the finish line. [pace] is null for Maintain and for custom values under Advanced.
 */
data class GoalPlan(
    val policy: GoalPolicy,
    val pace: Pace?,
    val goalWeightKg: Double?,
    val finishLine: FinishLine?,
    val startedOn: LocalDate,
    val aggressiveAcknowledged: Boolean = false,
) {
    val goal: Goal get() = policy.goal

    companion object {
        /** A preset pace for [goal]; Maintain has none. */
        fun of(goal: Goal, pace: Pace, goalWeightKg: Double?, finishLine: FinishLine?, startedOn: LocalDate) = GoalPlan(
            policy = GoalPolicy.defaultFor(goal, pace),
            pace = if (goal == Goal.MAINTAIN) null else pace,
            goalWeightKg = goalWeightKg,
            finishLine = finishLine,
            startedOn = startedOn,
        )
    }
}

/** Body data from onboarding (PR-01). Kept apart from the persona so sharing a persona never shares it (PS-06). */
data class Profile(
    val sex: Sex,
    val birthDate: LocalDate,
    val heightCm: Double,
    /** Current weight as typed. M4's weigh-ins replace it with the trend weight (TRD §5.2). */
    val weightKg: Double,
    val activity: ActivityLevel = ActivityLevel.MODERATE,
) {
    fun ageOn(date: LocalDate): Int = Period.between(birthDate, date).years

    val bmi: Double get() = Bmi.of(weightKg, heightCm)
}

object Bmi {
    fun of(weightKg: Double, heightCm: Double): Double = weightKg / (heightCm / 100 * heightCm / 100)

    /** The weight at [bmi] for a height. */
    fun weightAt(bmi: Double, heightCm: Double): Double = bmi * (heightCm / 100) * (heightCm / 100)
}

/** Everything that varies by person (PRD §4, TRD §5.0). Engines read it; nothing branches on preset names. */
data class Persona(
    val goal: GoalPlan,
    val experience: Experience,
    val diet: Diet,
    val region: FoodRegion,
    val split: SplitTemplate,
    val strengthDaysPerWeek: Int,
    val extras: List<ExtraSession>,
    val weightSource: WeightSource,
    val units: Units,
    val proteinRamp: Boolean,
) {
    val experiencePolicy: ExperiencePolicy get() = ExperiencePolicy.defaultFor(experience)
}

/**
 * A persona being built in onboarding (PS-01). Presets leave *Asked* values null, and the review step
 * can't finish until each is set.
 */
data class PersonaDraft(
    val goal: GoalPlan? = null,
    val experience: Experience? = null,
    val diet: Diet? = null,
    val region: FoodRegion? = null,
    val split: SplitTemplate? = null,
    val strengthDaysPerWeek: Int? = null,
    val extras: List<ExtraSession> = emptyList(),
    val weightSource: WeightSource = WeightSource.HOME_SCALE,
    val units: Units = Units.METRIC,
    /** Null follows the diet's default. */
    val proteinRamp: Boolean? = null,
) {
    /** The values still to choose, in review order. */
    val missing: List<String>
        get() = buildList {
            if (goal == null) add("goal")
            if (experience == null) add("experience")
            if (diet == null) add("diet")
            if (region == null) add("region")
            if (split == null) add("split")
        }

    fun complete(): Persona? {
        val split = split ?: return null
        return Persona(
            goal = goal ?: return null,
            experience = experience ?: return null,
            diet = diet ?: return null,
            region = region ?: return null,
            split = split,
            strengthDaysPerWeek = strengthDaysPerWeek ?: split.defaultStrengthDaysPerWeek,
            extras = extras,
            weightSource = weightSource,
            units = units,
            proteinRamp = proteinRamp ?: diet.rampByDefault,
        )
    }
}

fun Persona.toDraft() = PersonaDraft(goal, experience, diet, region, split, strengthDaysPerWeek, extras, weightSource, units, proteinRamp)
