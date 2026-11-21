package app.recomp.domain.coach

import kotlin.math.roundToInt

/** The kinds a suggestion can be (TRD §10 M11), as the schema's enum names them. */
enum class SuggestionKind { CALORIES, PROTEIN, SWAP, ADD, REMOVE, SETS, REP_RANGE, STRENGTH_DAYS, DELOAD_EXERCISE, DELOAD_ALL, ADVICE }

/** One suggestion as the reply gives it: refs are the report's, unused fields "" or 0 (TRD §6.2's convention). */
data class RawSuggestion(
    val kind: String,
    val routineRef: String = "",
    val exerciseRef: String = "",
    val replacementRef: String = "",
    val min: Double = 0.0,
    val max: Double = 0.0,
    val reason: String = "",
)

/** The reply: a short summary and the suggestions, before any check. */
data class CoachReply(val summary: String, val suggestions: List<RawSuggestion>)

/** What applying a checked suggestion changes, with every ref mapped back to an id. */
sealed interface Change {
    data class Calories(val kcal: Int) : Change
    data class Protein(val grams: Int) : Change
    data class Swap(val routineId: Long, val exerciseId: Long, val replacementId: Long) : Change
    data class Add(val routineId: Long, val exerciseId: Long) : Change
    data class Remove(val routineId: Long, val exerciseId: Long) : Change
    data class Sets(val routineId: Long, val exerciseId: Long, val sets: Int) : Change
    data class RepRange(val routineId: Long, val exerciseId: Long, val min: Int, val max: Int) : Change
    data class StrengthDays(val days: Int) : Change
    data class DeloadExercise(val exerciseId: Long) : Change
    data object DeloadAll : Change
}

/** Why a suggestion the app could otherwise apply is shown as advice only. */
enum class AdviceOnly {
    /** It names an exercise or routine that isn't in the report, or isn't in the library any more. */
    UNKNOWN_EXERCISE,

    /** Its value is outside the guardrails (PRD §4.9) or the app's ranges. */
    OUTSIDE_GUARDRAILS,

    /** It doesn't fit its kind: a swap to the same exercise, adding one that's already there. */
    DOESNT_FIT,
}

/**
 * A checked suggestion. [change] is null for advice: what the reply called advice, or what [adviceOnly] says
 * couldn't be applied. [kind] is what the reply asked for.
 */
data class Suggestion(val kind: SuggestionKind, val change: Change?, val reason: String, val adviceOnly: AdviceOnly? = null)

/** What a suggestion is checked against: the report's refs, the library, the routines and the guardrails. */
data class CheckContext(
    val terms: TargetTerms,
    /** The report's exercise refs (`e1…`) and routine refs (`r1…`). */
    val exerciseRefs: Map<String, Long>,
    val routineRefs: Map<String, Long>,
    /** Each routine's exercises, by id. */
    val routineExercises: Map<Long, List<Long>>,
    /** Exercises that can be used: the library, custom ones included, without archived ones. */
    val library: Set<Long>,
    val maintenanceKcal: Double,
    val referenceKg: Double,
    /**
     * The calories a suggestion may set (PRD §4.9): never under the floor or a 25% deficit, never over a 15% surplus,
     * and no deficit at all when the guardrails rule one out.
     */
    val kcalRange: IntRange,
    /** PRD §4.2's protein range for the goal, in g per kg. */
    val proteinGPerKg: ClosedFloatingPointRange<Double>,
    val strengthDays: IntRange = 1..7,
)

/**
 * Validates a reply's suggestions (HM-06, TRD §10 M11): at most [MAX_SUGGESTIONS], each mapped back from its refs
 * and held to the library, the routines and the guardrails. Anything that fails is advice only, so nothing outside
 * them can be applied. The schema can't say any of this, since structured outputs don't take such constraints.
 */
object SuggestionCheck {
    const val MAX_SUGGESTIONS = 5
    val SETS = 1..10
    val REPS = 1..50

    fun check(reply: CoachReply, ctx: CheckContext): List<Suggestion> = reply.suggestions.take(MAX_SUGGESTIONS).map { check(it, ctx) }

    fun check(raw: RawSuggestion, ctx: CheckContext): Suggestion {
        val kind = SuggestionKind.entries.firstOrNull { it.name.equals(raw.kind.trim(), ignoreCase = true) } ?: SuggestionKind.ADVICE
        val reason = raw.reason.trim()
        fun advice(why: AdviceOnly?) = Suggestion(kind, null, reason, why)
        fun ok(change: Change) = Suggestion(kind, change, reason)
        val single = if (raw.min == raw.max) raw.min else (raw.min + raw.max) / 2
        val routine = ctx.routineRefs[raw.routineRef.trim()]
        val exercise = ctx.exerciseRefs[raw.exerciseRef.trim()]?.takeIf { it in ctx.library }
        val replacement = ctx.exerciseRefs[raw.replacementRef.trim()]?.takeIf { it in ctx.library }
        val inRoutine = routine?.let { ctx.routineExercises[it] }.orEmpty()
        /** The exercise named, in the routine named. */
        fun routineExercise(): Pair<Long, Long>? {
            if (routine == null || exercise == null || exercise !in inRoutine) return null
            return routine to exercise
        }
        return when (kind) {
            SuggestionKind.ADVICE -> advice(null)
            SuggestionKind.CALORIES -> {
                if (!single.isFinite()) return advice(AdviceOnly.OUTSIDE_GUARDRAILS)
                val kcal = when (ctx.terms) {
                    TargetTerms.RELATIVE -> ctx.maintenanceKcal * (1 + single / 100)
                    TargetTerms.ABSOLUTE -> single
                }
                val rounded = (kcal / 10).roundToInt() * 10
                if (rounded in ctx.kcalRange) ok(Change.Calories(rounded)) else advice(AdviceOnly.OUTSIDE_GUARDRAILS)
            }
            SuggestionKind.PROTEIN -> {
                if (!single.isFinite() || ctx.referenceKg <= 0) return advice(AdviceOnly.OUTSIDE_GUARDRAILS)
                val grams = when (ctx.terms) {
                    TargetTerms.RELATIVE -> single * ctx.referenceKg
                    TargetTerms.ABSOLUTE -> single
                }.roundToInt()
                if (grams / ctx.referenceKg in ctx.proteinGPerKg) ok(Change.Protein(grams)) else advice(AdviceOnly.OUTSIDE_GUARDRAILS)
            }
            SuggestionKind.SWAP -> {
                val (r, e) = routineExercise() ?: return advice(AdviceOnly.UNKNOWN_EXERCISE)
                when {
                    replacement == null -> advice(AdviceOnly.UNKNOWN_EXERCISE)
                    replacement == e || replacement in inRoutine -> advice(AdviceOnly.DOESNT_FIT)
                    else -> ok(Change.Swap(r, e, replacement))
                }
            }
            SuggestionKind.ADD -> when {
                routine == null || exercise == null -> advice(AdviceOnly.UNKNOWN_EXERCISE)
                exercise in inRoutine -> advice(AdviceOnly.DOESNT_FIT)
                else -> ok(Change.Add(routine, exercise))
            }
            SuggestionKind.REMOVE -> {
                val (r, e) = routineExercise() ?: return advice(AdviceOnly.UNKNOWN_EXERCISE)
                if (inRoutine.size <= 1) advice(AdviceOnly.DOESNT_FIT) else ok(Change.Remove(r, e))
            }
            SuggestionKind.SETS -> {
                val (r, e) = routineExercise() ?: return advice(AdviceOnly.UNKNOWN_EXERCISE)
                val sets = single.roundToInt()
                if (single.isFinite() && sets in SETS) ok(Change.Sets(r, e, sets)) else advice(AdviceOnly.OUTSIDE_GUARDRAILS)
            }
            SuggestionKind.REP_RANGE -> {
                val (r, e) = routineExercise() ?: return advice(AdviceOnly.UNKNOWN_EXERCISE)
                if (!raw.min.isFinite() || !raw.max.isFinite()) return advice(AdviceOnly.OUTSIDE_GUARDRAILS)
                val min = raw.min.roundToInt()
                val max = raw.max.roundToInt()
                if (min in REPS && max in REPS && min <= max) ok(Change.RepRange(r, e, min, max)) else advice(AdviceOnly.OUTSIDE_GUARDRAILS)
            }
            SuggestionKind.STRENGTH_DAYS -> {
                val days = single.roundToInt()
                if (single.isFinite() && days in ctx.strengthDays) ok(Change.StrengthDays(days)) else advice(AdviceOnly.OUTSIDE_GUARDRAILS)
            }
            SuggestionKind.DELOAD_EXERCISE -> exercise?.let { ok(Change.DeloadExercise(it)) } ?: advice(AdviceOnly.UNKNOWN_EXERCISE)
            SuggestionKind.DELOAD_ALL -> ok(Change.DeloadAll)
        }
    }
}
