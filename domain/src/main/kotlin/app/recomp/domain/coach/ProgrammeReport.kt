package app.recomp.domain.coach

import app.recomp.domain.nutrition.Goal
import kotlin.math.roundToInt
import app.recomp.domain.units.UnitStyle
import app.recomp.domain.units.UnitMath

/** The checklist (PRD §12.7): what the weekly programme review sends. Body trends are off by default. */
data class ReportSections(
    val goal: Boolean = true,
    val food: Boolean = true,
    val training: Boolean = true,
    val body: Boolean = false,
    /** Your note, sent as written when it isn't blank. */
    val note: String = "",
) {
    /** Calories and grams go as numbers only with body trends, since with them they reveal your weight. */
    val terms: TargetTerms get() = if (body) TargetTerms.ABSOLUTE else TargetTerms.RELATIVE

    /** As stored in `programme_review.sections`. */
    val key: String get() = listOfNotNull("goal".takeIf { goal }, "food".takeIf { food }, "training".takeIf { training }, "body".takeIf { body }, "note".takeIf { note.isNotBlank() })
        .joinToString(",")

    val any: Boolean get() = goal || food || training || body || note.isNotBlank()
}

/**
 * How the report states the targets, and so what a `CALORIES` or `PROTEIN` suggestion's value means: % of
 * maintenance (negative for a deficit) and g per kg, or kcal and grams.
 */
enum class TargetTerms { RELATIVE, ABSOLUTE }

/** The goal and targets as the review sends them. [maintenanceKcal] and [referenceKg] are only ever sent in [TargetTerms.ABSOLUTE]. */
data class GoalFacts(
    val goal: Goal,
    /** "Standard", or null for a custom pace or Maintain. */
    val pace: String?,
    /** "goal weight", "a date", "body fat %", or null without a finish line. */
    val finishLine: String?,
    val kcal: Int,
    val proteinG: Int,
    val maintenanceKcal: Double,
    /** The weight protein is set against: the goal weight if there is one, else the trend. */
    val referenceKg: Double,
)

/** The week's food against its targets (HM-03). Absolute averages go only in [TargetTerms.ABSOLUTE]. */
data class FoodFacts(
    val daysLogged: Int,
    val kcal: Int,
    val kcalTarget: Int,
    val proteinG: Int,
    /** Null in the protein ramp's baseline week, which has no target. */
    val proteinTargetG: Int?,
    val proteinHit: Int,
    val proteinCounted: Int,
    val orderedPct: Double?,
    val packagedPct: Double?,
)

/** One exercise of a routine: its sets, rep range and the load the next workout would start from. */
data class RoutineLine(val exerciseId: Long, val name: String, val sets: Int, val repMin: Int, val repMax: Int, val load: String?)

data class RoutineFacts(val routineId: Long, val name: String, val exercises: List<RoutineLine>)

/** A library exercise the review may suggest. */
data class LibraryLine(val exerciseId: Long, val name: String, val muscle: String)

data class TrainingFacts(
    val experience: String,
    val split: String,
    val strengthDaysPerWeek: Int,
    val routines: List<RoutineFacts>,
    /** "Strength 2 of 3 days · Yoga class 1 of 1", as the review words it. */
    val sessions: String,
    val records: List<String>,
    val stalled: List<String>,
    /** Working sets per muscle in the week, a secondary muscle counting half (TRD §5.5). */
    val setsPerMuscle: Map<String, Double>,
    val library: List<LibraryLine>,
)

/** Trends only: never a single weigh-in or a scan's values (PRD §12.7). */
data class BodyFacts(
    val trendChangeKg: Double?,
    val trendChangePct: Double?,
    val waistChangeCm: Double?,
    val scanChanges: List<String>,
)

data class ProgrammeFacts(val goal: GoalFacts?, val food: FoodFacts?, val training: TrainingFacts?, val body: BodyFacts?)

/**
 * The text that's sent and the refs it used. [exercises] maps `e1…eN` back to exercise ids, and [routines] `r1…rN`
 * to routine ids: the reply names them by ref, so custom exercises work as well as library ones.
 */
data class ProgrammeReport(
    val text: String,
    val sections: ReportSections,
    val exercises: Map<String, Long>,
    val routines: Map<String, Long>,
    /** The per-exercise detail was trimmed to keep the report inside [ProgrammeReports.MAX_CHARS]. */
    val trimmed: Boolean = false,
    /** What relative targets were stated against, so a reply's % and g/kg convert with the report's own numbers. */
    val maintenanceKcal: Double? = null,
    val referenceKg: Double? = null,
) {
    val terms: TargetTerms get() = sections.terms
}

/**
 * Builds the weekly programme review's text (HM-06, PRD §12.7, TRD §10 M11) from the ticked sections. It's built
 * only from [ProgrammeFacts], which has no name, sex, age, height or food log, so those are never sent.
 */
object ProgrammeReports {
    /** About 4,000 tokens at about four characters a token. */
    const val MAX_CHARS = 16_000

    /**
     * [style] is the person's units (PR-05): body weights and lengths are sent in them, said once at the top, so the
     * reply's reasons echo the numbers they see. Protein stays in g per kg.
     */
    fun build(facts: ProgrammeFacts, sections: ReportSections, style: UnitStyle): ProgrammeReport {
        val full = build(facts, sections, style, Detail.FULL)
        if (full.text.length <= MAX_CHARS) return full
        // Per-exercise detail goes first: the loads, then the library past the muscles the routines train, then as
        // much of the rest of the library as it takes to fit.
        val lean = build(facts, sections, style, Detail.NO_LOADS)
        if (lean.text.length <= MAX_CHARS) return lean.copy(trimmed = true)
        var limit = Int.MAX_VALUE
        while (true) {
            val short = build(facts, sections, style, Detail.SHORT_LIBRARY, limit).copy(trimmed = true)
            val listed = short.exercises.size - routineExercises(facts)
            if (short.text.length <= MAX_CHARS || listed <= 0) return short
            limit = listed / 2
        }
    }

    private fun routineExercises(facts: ProgrammeFacts): Int = facts.training?.routines?.flatMap { r -> r.exercises.map { it.exerciseId } }?.distinct()?.size ?: 0

    private enum class Detail { FULL, NO_LOADS, SHORT_LIBRARY }

    private fun build(facts: ProgrammeFacts, sections: ReportSections, style: UnitStyle, detail: Detail, libraryLimit: Int = Int.MAX_VALUE): ProgrammeReport {
        val exerciseRefs = linkedMapOf<Long, String>()
        val routineRefs = linkedMapOf<Long, String>()
        fun ref(id: Long) = exerciseRefs.getOrPut(id) { "e${exerciseRefs.size + 1}" }
        val terms = sections.terms
        val text = buildString {
            appendLine("Weekly programme review. Only what the person ticked is below.")
            appendLine(unitsLine(style))
            val goal = facts.goal
            if (sections.goal && goal != null) {
                appendLine()
                appendLine("## Goal and targets")
                appendLine("Goal: ${goalName(goal.goal)}" + (goal.pace?.let { ", pace $it" } ?: "") + (goal.finishLine?.let { ", finish line: $it" } ?: ""))
                if (terms == TargetTerms.ABSOLUTE) {
                    appendLine("Calories: ${goal.kcal} kcal a day; maintenance about ${goal.maintenanceKcal.roundToInt()} kcal")
                    appendLine("Protein: ${goal.proteinG} g a day")
                } else {
                    appendLine("Calories: ${signedPct(relativeKcal(goal.kcal, goal.maintenanceKcal))} of maintenance")
                    appendLine("Protein: ${oneDecimal(goal.proteinG / goal.referenceKg)} g per kg")
                }
            }
            val food = facts.food
            if (sections.food) {
                appendLine()
                appendLine("## Food this week")
                if (food == null || food.daysLogged == 0) {
                    appendLine("No food logged.")
                } else {
                    if (terms == TargetTerms.ABSOLUTE) {
                        appendLine("Averages over ${food.daysLogged} days logged: ${food.kcal} kcal (target ${food.kcalTarget}), protein ${food.proteinG} g" + (food.proteinTargetG?.let { " (target $it)" } ?: " (no target yet)"))
                    } else {
                        appendLine("Averages over ${food.daysLogged} days logged: calories ${pct(food.kcal, food.kcalTarget)} of target, protein " + (food.proteinTargetG?.let { "${pct(food.proteinG, it)} of target" } ?: "with no target yet"))
                    }
                    if (food.proteinCounted > 0) appendLine("Protein target hit on ${food.proteinHit} of ${food.proteinCounted} days")
                    if (food.orderedPct != null && food.packagedPct != null) {
                        appendLine("Share of calories: ordered ${food.orderedPct.roundToInt()}%, packaged ${food.packagedPct.roundToInt()}%")
                    }
                }
            }
            val training = facts.training
            if (sections.training && training != null) {
                appendLine()
                appendLine("## Training")
                appendLine("Experience: ${training.experience}. Split: ${training.split}. Strength days a week: ${training.strengthDaysPerWeek}")
                appendLine("This week: ${training.sessions}")
                if (training.records.isNotEmpty()) appendLine("PRs: " + training.records.joinToString("; "))
                if (training.stalled.isNotEmpty()) appendLine("Stalled: " + training.stalled.joinToString(", "))
                if (training.setsPerMuscle.isNotEmpty()) {
                    appendLine("Working sets per muscle: " + training.setsPerMuscle.entries.sortedByDescending { it.value }
                        .joinToString(", ") { "${it.key} ${oneDecimal(it.value).removeSuffix(".0")}" })
                }
                appendLine("Routines (exercises by ref):")
                training.routines.forEach { r ->
                    val rRef = routineRefs.getOrPut(r.routineId) { "r${routineRefs.size + 1}" }
                    appendLine("- $rRef ${r.name}:")
                    r.exercises.forEach { e ->
                        val load = e.load?.takeIf { detail == Detail.FULL }?.let { ", $it" } ?: ""
                        appendLine("  - ${ref(e.exerciseId)} ${e.name}: ${e.sets} × ${e.repMin}–${e.repMax}$load")
                    }
                }
                val routineMuscles = training.library.filter { it.exerciseId in exerciseRefs }.map { it.muscle }.toSet()
                val library = training.library.filter { it.exerciseId !in exerciseRefs }
                    .filter { detail != Detail.SHORT_LIBRARY || it.muscle in routineMuscles }
                    .take(libraryLimit)
                appendLine("Library exercises that can be added or swapped in (ref name: muscle):")
                library.forEach { appendLine("  ${ref(it.exerciseId)} ${it.name}: ${it.muscle}") }
            }
            val body = facts.body
            if (sections.body && body != null) {
                appendLine()
                appendLine("## Body trends")
                appendLine(
                    "Trend weight: " + when {
                        body.trendChangeKg == null -> "no rate this week"
                        else -> "${signed(UnitMath.weightOf(body.trendChangeKg, style.units))} ${style.weight} this week" + (body.trendChangePct?.let { " (${signedPct(it)})" } ?: "")
                    },
                )
                body.waistChangeCm?.let { appendLine("Waist: ${signed(UnitMath.lengthOf(it, style.units))} ${style.length}") }
                body.scanChanges.forEach { appendLine("Scan: $it") }
            }
            if (sections.note.isNotBlank()) {
                appendLine()
                appendLine("## A note from the person")
                appendLine(sections.note.trim())
            }
        }.trimEnd()
        return ProgrammeReport(
            text = text,
            sections = sections,
            exercises = exerciseRefs.entries.associate { (id, r) -> r to id },
            routines = routineRefs.entries.associate { (id, r) -> r to id },
            maintenanceKcal = facts.goal?.maintenanceKcal,
            referenceKg = facts.goal?.referenceKg,
        )
    }

    /** The report's units, stated once at the top: "Weights in lb, lengths in inches." */
    fun unitsLine(style: UnitStyle): String = if (style.imperial) "Weights in lb, lengths in inches." else "Weights in kg, lengths in cm."

    /** Calories against maintenance, in %: −20 is a 20% deficit. */
    fun relativeKcal(kcal: Int, maintenanceKcal: Double): Double = (kcal - maintenanceKcal) / maintenanceKcal * 100

    private fun goalName(goal: Goal) = when (goal) {
        Goal.FAT_LOSS -> "fat loss"
        Goal.RECOMP -> "recomposition"
        Goal.MAINTAIN -> "maintain"
        Goal.LEAN_BULK -> "lean bulk"
    }

    private fun pct(value: Int, target: Int) = if (target <= 0) "n/a" else "${(value * 100.0 / target).roundToInt()}%"

    private fun oneDecimal(v: Double) = (Math.round(v * 10) / 10.0).toString()

    private fun signed(v: Double): String {
        val r = Math.round(v * 10) / 10.0
        return (if (r > 0) "+" else "") + r
    }

    private fun signedPct(v: Double): String {
        val r = v.roundToInt()
        return (if (r > 0) "+" else "") + "$r%"
    }
}
