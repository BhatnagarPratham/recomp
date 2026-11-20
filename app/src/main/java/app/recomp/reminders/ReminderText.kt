package app.recomp.reminders

import app.recomp.data.repo.EveningNudge
import app.recomp.data.repo.TodaySnapshot
import app.recomp.domain.body.ScanProtocol
import app.recomp.domain.body.ScanSlot
import app.recomp.domain.food.MealSlot
import app.recomp.domain.food.Portions
import app.recomp.domain.nudge.MealClock
import app.recomp.domain.nudge.NudgeLine
import app.recomp.domain.nudge.ReminderKind
import app.recomp.domain.nudge.Reminders
import app.recomp.domain.nudge.Suggestion
import app.recomp.ui.body.BodyText
import app.recomp.ui.checkin.CheckinText
import app.recomp.ui.onboarding.GoalText

/** What one reminder says. [suggestions] are the protein nudge's foods, which the notification can log in one tap. */
data class ReminderContent(
    val kind: ReminderKind,
    val title: String,
    val text: String,
    val suggestions: List<Suggestion> = emptyList(),
)

/** The words of reminders and the evening nudge (PRD §12.8), shared by notifications and Today's card. */
object ReminderText {
    fun label(kind: ReminderKind): String = when (kind) {
        ReminderKind.WEIGH_IN -> "Morning weigh-in"
        ReminderKind.LUNCH -> "Lunch not logged"
        ReminderKind.EVENING -> "Evening nudge"
        ReminderKind.WORKOUT -> "Workout day"
        ReminderKind.CHECKIN -> "Weekly check-in"
        ReminderKind.SCAN -> "Scan slot"
    }

    /** "Skipped when…", for Settings → Reminders. */
    fun skippedWhen(kind: ReminderKind, watched: String?): String = when (kind) {
        ReminderKind.WEIGH_IN -> "Skipped once you've weighed in today."
        ReminderKind.LUNCH -> "Skipped once ${watched ?: "the meal"} has an entry."
        ReminderKind.EVENING -> "Follows your goal. Skipped when there's nothing to nudge about."
        ReminderKind.WORKOUT -> "On the weekdays you pick. Skipped once a strength workout is done today."
        ReminderKind.CHECKIN -> "Mondays. Skipped once you've opened the check-in."
        ReminderKind.SCAN -> "3 hours before your usual scan slot. Skipped once you've logged a scan today."
    }

    fun line(line: NudgeLine): String = when (line) {
        is NudgeLine.ProteinLeft -> "${line.grams} g of protein left today"
        is NudgeLine.StepsLeft -> "${GoalText.n(line.steps)} steps left today"
        is NudgeLine.CaloriesShort -> "You're ${GoalText.kcal(line.kcal)} short tonight"
    }

    /** "200 g", "3 piece", "1½ katori". */
    fun amount(s: Suggestion): String =
        if (s.unit.label == Portions.GRAMS) "${GoalText.n(s.grams)} g" else "${Portions.format(s.quantity)} ${s.unit.label}"

    /** "Hung curd · 200 g · 22 g protein · 210 kcal". */
    fun suggestion(s: Suggestion): String = "${s.name} · ${amount(s)} · ${s.proteinG} g protein · ${GoalText.kcal(s.kcal)}"

    /** A notification button: "Hung curd 200 g". */
    fun suggestionAction(s: Suggestion): String = "${s.name} ${amount(s)}"

    fun logged(s: Suggestion, slot: MealSlot): String = "Logged ${s.name} · ${amount(s)} in ${slot.name}"

    /** "Pull is next · 3 lifts are due for a weight increase" (PO-11). */
    fun liftsDue(count: Int): String = when (count) {
        0 -> "Your targets are ready."
        1 -> "1 lift is due for a weight increase."
        else -> "$count lifts are due for a weight increase."
    }

    /** "Scan at 10:00 · eat by 08:00 · before your workout" (IB-06): the protocol the scan is checked against. */
    fun scanProtocol(slot: ScanSlot): String =
        "Scan at ${BodyText.time(slot.time)} · eat by ${BodyText.time(slot.time.minusMinutes(ScanProtocol.MIN_SINCE_MEAL.toLong()))} · before your workout"

    /**
     * What a reminder says right now, or null when its skip rule says it isn't needed (PRD §12.8's table). The
     * skip rule and the text read the same snapshot, so a reminder never contradicts the app.
     */
    fun content(
        kind: ReminderKind,
        snapshot: TodaySnapshot,
        nudge: EveningNudge,
        reminders: Reminders,
        slots: List<MealSlot>,
        scanSlot: ScanSlot?,
    ): ReminderContent? = when (kind) {
        ReminderKind.WEIGH_IN ->
            if (snapshot.weighedIn) null else ReminderContent(kind, "Morning weigh-in", "Weigh in before you eat or drink, then log it.")
        ReminderKind.LUNCH -> MealClock.watched(slots, reminders.lunchSlot, reminders.lunch.time)?.takeIf { it.key !in snapshot.mealsLogged }?.let {
            ReminderContent(kind, "${it.name} isn't logged yet", "Log it while you remember what you had.")
        }
        ReminderKind.EVENING -> nudge.lines.takeIf { it.isNotEmpty() }?.let { lines ->
            val rest = lines.drop(1).map(::line) + nudge.suggestions.map(::suggestion)
            ReminderContent(kind, line(lines.first()), rest.joinToString("\n").ifEmpty { "Open Today to see what's left." }, nudge.suggestions)
        }
        ReminderKind.WORKOUT -> snapshot.nextRoutine?.takeIf { !snapshot.strengthDone && !snapshot.workoutActive }?.let {
            ReminderContent(kind, "$it is next", liftsDue(snapshot.liftsDue))
        }
        ReminderKind.CHECKIN -> snapshot.checkin?.let { ReminderContent(kind, "Your weekly check-in is ready", CheckinText.cardLine(it)) }
        ReminderKind.SCAN -> scanSlot?.takeIf { !snapshot.scanned }?.let { ReminderContent(kind, "Scan day", scanProtocol(it)) }
    }
}
