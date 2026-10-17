package app.recomp.ui.components

import app.recomp.domain.model.Equipment
import app.recomp.domain.model.LoadType
import app.recomp.domain.model.Mechanic
import app.recomp.domain.model.SessionKind
import app.recomp.domain.model.SetType
import app.recomp.domain.persona.Experience
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

    fun setType(t: SetType) = when (t) {
        SetType.WARMUP -> "Warm-up"
        SetType.WORKING -> "Working"
        SetType.DROP -> "Drop"
        SetType.FAILURE -> "Failure"
    }

    /** "2.5 kg". */
    fun kg(grams: Int) = "${Loads.formatKg(grams)} kg"

    fun muscle(key: String) = key.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
