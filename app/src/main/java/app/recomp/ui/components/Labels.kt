package app.recomp.ui.components

import app.recomp.domain.model.Equipment
import app.recomp.domain.model.LoadType
import app.recomp.domain.model.Mechanic
import app.recomp.domain.model.SessionKind
import app.recomp.domain.model.SetType
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.Pace
import app.recomp.domain.nutrition.Sex
import app.recomp.domain.persona.ActivityLevel
import app.recomp.domain.persona.Diet
import app.recomp.domain.persona.Experience
import app.recomp.domain.persona.FoodRegion
import app.recomp.domain.persona.Units
import app.recomp.domain.persona.WeightSource
import app.recomp.domain.progression.PrType
import app.recomp.domain.training.Loads

/** User-facing names for the domain vocabulary. */
object Labels {
    fun equipment(e: Equipment) = when (e) {
        Equipment.BARBELL -> "Barbell"
        Equipment.DUMBBELL -> "Dumbbell"
        Equipment.MACHINE -> "Machine"
        Equipment.PLATE_LOADED -> "Plate-loaded"
        Equipment.CABLE -> "Cable"
        Equipment.BODYWEIGHT -> "Bodyweight"
        Equipment.KETTLEBELL -> "Kettlebell"
        Equipment.BAND -> "Band"
    }

    fun loadType(t: LoadType) = when (t) {
        LoadType.WEIGHTED -> "Weighted"
        LoadType.BODYWEIGHT -> "Bodyweight"
        LoadType.BODYWEIGHT_PLUS -> "Bodyweight + load"
        LoadType.ASSISTED -> "Assisted"
    }

    fun mechanic(m: Mechanic) = when (m) {
        Mechanic.COMPOUND -> "Compound"
        Mechanic.ISOLATION -> "Isolation"
    }

    fun kind(k: SessionKind) = when (k) {
        SessionKind.STRENGTH -> "Strength"
        SessionKind.YOGA -> "Yoga"
        SessionKind.MOBILITY -> "Mobility"
        SessionKind.CARDIO -> "Cardio"
        SessionKind.SPORT -> "Sport"
    }

    fun experience(e: Experience) = when (e) {
        Experience.BEGINNER -> "Beginner"
        Experience.INTERMEDIATE -> "Intermediate"
        Experience.ADVANCED -> "Advanced"
    }

    /** "Under a year", for the lifting question and the experience chips. */
    fun liftingAge(e: Experience) = when (e) {
        Experience.BEGINNER -> "Under a year"
        Experience.INTERMEDIATE -> "1 to 3 years"
        Experience.ADVANCED -> "3 years or more"
    }

    fun goal(g: Goal) = when (g) {
        Goal.FAT_LOSS -> "Lose fat"
        Goal.RECOMP -> "Recomp"
        Goal.MAINTAIN -> "Maintain"
        Goal.LEAN_BULK -> "Lean bulk"
    }

    fun pace(p: Pace?) = when (p) {
        Pace.GENTLE -> "Gentle"
        Pace.STANDARD -> "Standard"
        Pace.AGGRESSIVE -> "Aggressive"
        null -> "Custom"
    }

    fun diet(d: Diet) = when (d) {
        Diet.VEGAN -> "Vegan"
        Diet.LACTO_VEGETARIAN -> "Lacto-vegetarian"
        Diet.EGGETARIAN -> "Eggetarian"
        Diet.PESCATARIAN -> "Pescatarian"
        Diet.NON_VEGETARIAN -> "Non-vegetarian"
    }

    fun region(r: FoodRegion) = when (r) {
        FoodRegion.INDIAN -> "Indian"
        FoodRegion.INTERNATIONAL -> "International"
    }

    fun weightSource(w: WeightSource) = when (w) {
        WeightSource.HOME_SCALE -> "Home scale"
        WeightSource.SCAN -> "Body-composition scans"
        WeightSource.GYM_SCALE -> "Gym scale"
    }

    fun units(u: Units) = when (u) {
        Units.METRIC -> "kg and cm"
        Units.IMPERIAL -> "lb and in"
    }

    fun activity(a: ActivityLevel) = when (a) {
        ActivityLevel.SEDENTARY -> "Mostly sitting, little training"
        ActivityLevel.MODERATE -> "Desk job plus 3–4 sessions a week"
        ActivityLevel.ACTIVE -> "Active job or daily training"
    }

    fun sex(s: Sex) = when (s) {
        Sex.MALE -> "Male"
        Sex.FEMALE -> "Female"
    }

    fun setType(t: SetType) = when (t) {
        SetType.WARMUP -> "Warm-up"
        SetType.WORKING -> "Working"
        SetType.DROP -> "Drop"
        SetType.FAILURE -> "Failure"
    }

    /** For an assisted lift (WL-09) the heaviest-weight record is the least assistance. */
    fun record(t: PrType, assisted: Boolean = false) = when (t) {
        PrType.MAX_LOAD -> if (assisted) "least assistance" else "heaviest weight"
        PrType.REPS_AT_LOAD -> if (assisted) "most reps at an assistance level" else "most reps at a weight"
        PrType.E1RM -> "best estimated 1RM"
        PrType.SESSION_VOLUME -> "best session volume"
    }

    /** "2.5 kg". */
    fun kg(grams: Int) = "${Loads.formatKg(grams)} kg"

    /** "assist 25 kg" (WL-09), or "no assistance". */
    fun assist(grams: Int) = if (grams == 0) "no assistance" else "assist ${kg(grams)}"

    fun muscle(key: String) = key.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
