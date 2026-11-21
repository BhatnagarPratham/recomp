package app.recomp.ui.health

import app.recomp.data.db.ImportedSessionRow
import app.recomp.data.db.ScanDraftEntity
import app.recomp.data.db.SleepSource
import app.recomp.data.health.BackgroundRead
import app.recomp.data.health.HealthFeature
import app.recomp.data.repo.BodyActivity
import app.recomp.data.repo.SleepToday
import app.recomp.data.repo.StepsToday
import app.recomp.domain.health.SleepNight
import app.recomp.domain.units.UnitStyle
import app.recomp.ui.body.BodyText
import app.recomp.ui.onboarding.GoalText

/** Health Connect's sentences (PRD §12.9). Counts are plain numbers, never failure styling. */
object HealthText {
    fun title(feature: HealthFeature) = when (feature) {
        HealthFeature.STEPS -> "Steps"
        HealthFeature.SLEEP -> "Sleep"
        HealthFeature.WORKOUTS -> "Other apps' workouts"
        HealthFeature.INBODY -> "InBody body data"
    }

    /** What each feature shows, and what it needs before anything arrives. */
    fun needs(feature: HealthFeature) = when (feature) {
        HealthFeature.STEPS -> "Daily steps against your goal, on Today and Body. Health Connect counts this phone's own steps on an up-to-date phone. " +
            "For Google Fit's count, turn on Health Connect access in Google Fit (Profile → Settings → Health Connect)."
        HealthFeature.SLEEP -> "Last night's sleep, filled in for the morning weigh-in. Needs a watch or app that writes sleep to Health Connect; " +
            "until one does, you type it. What you type always wins."
        HealthFeature.WORKOUTS -> "Walks, runs, cycling, yoga and other sessions from Google Fit or a watch app, counted toward a matching extra session. " +
            "Turn on Health Connect access in the app that records them. Strength workouts are never imported."
        HealthFeature.INBODY -> "Each InBody measurement (weight, body fat % and BMR) becomes a draft scan on Body; you add muscle and visceral fat. " +
            "Turn on Health Connect in the InBody app (Settings → Health Connect) and let it write those three."
    }

    const val NOT_ALLOWED = "Turned on, but Health Connect doesn't allow it at the moment. Switch it on to ask again."
    const val DENIED = "Health Connect didn't grant that. If it no longer asks, allow it in Health Connect → App permissions → Recomp."
    const val UNAVAILABLE = "Health Connect isn't available on this phone. Everything in Recomp works without it."
    const val NEEDS_UPDATE = "Health Connect needs an update before Recomp can read from it. Everything in Recomp works without it."

    fun background(state: BackgroundRead) = when (state) {
        BackgroundRead.GRANTED -> "Recomp reads Health Connect whenever you open it, and once a day in the background."
        BackgroundRead.NOT_GRANTED -> "Recomp reads Health Connect whenever you open it. Allow background reads to also sync once a day while it's closed."
        BackgroundRead.UNAVAILABLE -> "Recomp reads Health Connect whenever you open it. This phone's Health Connect doesn't offer background reads, so there's no daily sync while it's closed."
    }

    fun synced(at: Long?): String = if (at == null) "Not synced yet." else "Last synced ${BodyText.dateTime(at)}."

    /** "6,240 of 8,000 steps". */
    fun steps(s: StepsToday): String = if (s.steps == null) "Steps appear after the next sync · goal ${GoalText.n(s.goal)}" else "${GoalText.n(s.steps)} of ${GoalText.n(s.goal)} steps"

    fun hours(h: Double): String = if (h % 1.0 == 0.0) "${h.toInt()} h" else "${BodyText.oneDecimal(h)} h"

    fun chip(h: Double): String = when (h) {
        SleepNight.CHIPS.first() -> "5 or less"
        SleepNight.CHIPS.last() -> "9+"
        else -> "${h.toInt()}"
    }

    /** "Slept 7.5 h · from Health Connect". */
    fun slept(s: SleepToday): String? = s.hours?.let { "Slept ${hours(it)}" + if (s.source == SleepSource.HEALTH_CONNECT) " · from Health Connect" else "" }

    const val SLEEP_ASK = "How long did you sleep?"

    /** "Walk · 35 min · from Google Fit". */
    fun imported(row: ImportedSessionRow): String = listOfNotNull(row.style, BodyText.minutes(row.durationMin), row.notes).joinToString(" · ")

    /** "New InBody scan, Mon 16 Nov, 10:05: add muscle and visceral fat". */
    fun draft(d: ScanDraftEntity): String = "New InBody scan, ${BodyText.dateTime(d.measuredAt)}: add muscle and visceral fat"

    fun draftDetail(d: ScanDraftEntity, style: UnitStyle): String = listOfNotNull(
        BodyText.weight(d.weightG, style),
        d.bodyFatPct?.let { "${BodyText.oneDecimal(it)}% body fat" },
        d.bmrKcal?.let { "BMR ${GoalText.kcal(it)}" },
    ).joinToString(" · ")

    fun stepsWeek(w: BodyActivity): String =
        (w.stepsAverage?.let { "7-day average ${GoalText.n(it)} steps" } ?: "The 7-day average appears after a full day of steps") + " · goal ${GoalText.n(w.stepsGoal)}"

    fun sleepWeek(w: BodyActivity): String = w.sleepAverage?.let {
        "7-day average ${BodyText.oneDecimal(it)} h of sleep (${w.sleepNights} ${if (w.sleepNights == 1) "night" else "nights"})"
    } ?: "Sleep appears once a night is entered"

    // Writing to Health Connect (M16, DA-04)

    const val SHARE_TITLE = "Share with other apps"

    const val SHARE_NOTE =
        "Writes your workouts, one nutrition record per meal and the weigh-ins you log to Health Connect, from the last 30 days on, " +
            "and keeps them up to date as you edit. Scan weights and other apps' workouts aren't written. Apps you allow to read " +
            "them, such as Google Fit, may upload them to their own cloud."

    const val RESYNC_NOTE =
        "Edits and deletes older than 30 days aren't followed on their own. Resync everything replaces all of Recomp's records in Health Connect with what's here now."

    fun shared(at: Long?): String = if (at == null) "Nothing written yet." else "Last written ${app.recomp.ui.settings.DataViewModel.dateTime(at)}."
}
