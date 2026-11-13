package app.recomp.domain.model

/** Exercise and workout vocabulary shared by the engines and the Room schema (TRD §4). */

enum class Equipment { BARBELL, DUMBBELL, MACHINE, PLATE_LOADED, CABLE, BODYWEIGHT, KETTLEBELL, BAND }

enum class Mechanic { COMPOUND, ISOLATION }

/**
 * How load is counted (WL-09). [BODYWEIGHT] is reps only; [BODYWEIGHT_PLUS] logs added load such as a
 * plate held during back extensions; [ASSISTED] logs the assistance taken off, so less is harder.
 */
enum class LoadType { WEIGHTED, BODYWEIGHT, BODYWEIGHT_PLUS, ASSISTED }

/**
 * Which experience-policy rep range an exercise defaults to (PRD §4.3). [HIGH_REP] covers small muscles
 * that respond to higher reps: lateral raises, rear delts, calves, hip abduction and adduction.
 */
enum class RepClass { COMPOUND, ISOLATION, HIGH_REP }

enum class ProgressionScheme { DOUBLE_PROGRESSION, REPS_ONLY, MANUAL }

/** Strength sessions follow the rotation; the rest are extra sessions (PRD §7.6). */
enum class SessionKind {
    STRENGTH, YOGA, MOBILITY, CARDIO, SPORT;

    val isExtra: Boolean get() = this != STRENGTH
}

enum class SessionStatus { ACTIVE, COMPLETED, DISCARDED }

/** Only working sets drive progression (WL-01). */
enum class SetType { WARMUP, WORKING, DROP, FAILURE }
