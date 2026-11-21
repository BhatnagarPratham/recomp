package app.recomp.ui.checkin

import app.recomp.domain.ai.AiProviderId
import app.recomp.domain.ai.AiStatus
import app.recomp.domain.coach.AdviceOnly
import app.recomp.domain.coach.Change
import app.recomp.domain.coach.Suggestion
import app.recomp.domain.coach.SuggestionKind
import app.recomp.ui.food.AiText
import app.recomp.ui.onboarding.GoalText

/** The weekly programme review's sentences (HM-06, PRD §12.7). */
object ProgrammeReviewText {
    const val ASK = "Ask AI to review your programme"
    const val CARD_LINE = "You can also ask AI to review your programme from the check-in."

    const val GOAL = "Goal and targets"
    const val GOAL_DETAIL = "Goal, pace and finish line. Targets as % of maintenance and g/kg, not calories and grams."
    const val FOOD = "Food"
    const val FOOD_DETAIL = "The week's averages against target, protein days hit, ordered and packaged share."
    const val TRAINING = "Training"
    const val TRAINING_DETAIL = "Your routines with sets, rep ranges and loads, sessions done, PRs, stalls and sets per muscle."
    const val BODY = "Body trends"
    const val BODY_DETAIL = "The trend's change, waist and scan changes. With this ticked, targets go as calories and grams, which reveal your weight."
    const val NOTE_LABEL = "A note (optional)"
    const val NEVER_SENT = "Never sent: your name, sex, age, height, or your food log."
    const val PREVIEW = "Preview what's sent"
    const val SEND = "Send"
    const val SENDING = "Asking AI to review your week. This can take up to a minute."
    const val NOTHING_TICKED = "Tick at least one section."
    const val NO_SUGGESTIONS = "No changes suggested this week."
    const val HISTORY = "Past reviews"
    const val ADVICE_ONLY_TITLE = "Advice"
    const val APPLIED = "Applied"
    const val UNDONE = "Undone"
    const val INVALID = "The reply didn't fit what the app can read, so nothing came of it. What was sent is kept below."

    fun sentTo(provider: AiProviderId): String = "This text goes to ${provider.service} with your API key, and nothing else. Nothing changes until you tap Apply."

    fun failed(status: AiStatus, provider: AiProviderId): String =
        if (status == AiStatus.INVALID) INVALID else AiText.reason(status, provider)

    /** What a suggestion would do, with names for its exercises and routines. */
    fun title(s: Suggestion, exercise: (Long) -> String, routine: (Long) -> String): String = when (val c = s.change) {
        null -> ADVICE_ONLY_TITLE
        is Change.Calories -> "Calories: ${GoalText.kcal(c.kcal)} a day"
        is Change.Protein -> "Protein: ${c.grams} g a day"
        is Change.Swap -> "${routine(c.routineId)}: swap ${exercise(c.exerciseId)} for ${exercise(c.replacementId)}"
        is Change.Add -> "${routine(c.routineId)}: add ${exercise(c.exerciseId)}"
        is Change.Remove -> "${routine(c.routineId)}: remove ${exercise(c.exerciseId)}"
        is Change.Sets -> "${routine(c.routineId)}: ${exercise(c.exerciseId)} for ${c.sets} sets"
        is Change.RepRange -> "${routine(c.routineId)}: ${exercise(c.exerciseId)} at ${c.min}–${c.max} reps"
        is Change.StrengthDays -> "${c.days} strength days a week"
        is Change.DeloadExercise -> "Deload ${exercise(c.exerciseId)} next workout"
        Change.DeloadAll -> "Deload the whole week"
    }

    /** Why a suggestion the app could otherwise apply is advice only, or null. */
    fun adviceOnly(s: Suggestion): String? = when (s.adviceOnly) {
        null -> null
        AdviceOnly.UNKNOWN_EXERCISE -> "Shown as advice: it names an exercise or routine that isn't in your library or routines."
        AdviceOnly.OUTSIDE_GUARDRAILS -> "Shown as advice: it's outside the safe ranges for your targets or training."
        AdviceOnly.DOESNT_FIT -> "Shown as advice: it doesn't fit your routine as it is."
    } ?: s.kind.takeIf { it != SuggestionKind.ADVICE && s.change == null }?.let { "Shown as advice." }

    /** What *Apply* says it did. */
    fun applied(c: Change): String = when (c) {
        is Change.Calories, is Change.Protein -> "Saved as a new target version from today."
        is Change.DeloadExercise, Change.DeloadAll -> "Your next workout starts with the deload."
        else -> "Routine changed."
    }

    // The scheduled deload week (PRD §4.3)

    const val DELOAD_TITLE = "Deload week"
    fun deloadLine(weeks: Long): String = "It's been $weeks weeks of training. A lighter week now helps you keep progressing: each lift at 90% for a week."
    const val DELOAD_ACCEPT = "Deload next workouts"
    const val DELOAD_LATER = "Not now"
}
