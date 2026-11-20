package app.recomp.domain.training

import app.recomp.domain.model.LoadType
import app.recomp.domain.model.SetType
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** A set as the edit screen holds it. [id] is null for a set added in the edit. */
data class EditedSet(val id: Long?, val type: SetType, val loadG: Int?, val reps: Int, val rir: Int? = null)

/** An exercise kept in the edited workout, with its sets in order. One left out of the edit is removed. */
data class EditedExercise(val sessionExerciseId: Long, val sets: List<EditedSet>)

data class EditedSession(val startedAt: Long, val endedAt: Long, val notes: String?, val exercises: List<EditedExercise>)

/** The rules for editing a past workout (WL-10, PRD §12.6), kept apart from the database so they're easy to test. */
object SessionEdit {
    const val MAX_REPS = 999

    /**
     * The start and end instants for a workout logged on [logDate] between two clock times. A start
     * before the "day starts at" time belongs to the next calendar day (it's still [logDate]'s late
     * night), and an end earlier than the start means the workout ran past midnight.
     */
    fun times(logDate: LocalDate, start: LocalTime, end: LocalTime, zone: ZoneId, dayStart: LocalTime): Pair<Long, Long> {
        val day = if (start < dayStart) logDate.plusDays(1) else logDate
        val startedAt = day.atTime(start).atZone(zone).toInstant().toEpochMilli()
        val endDay = if (end < start) day.plusDays(1) else day
        return startedAt to endDay.atTime(end).atZone(zone).toInstant().toEpochMilli()
    }

    /** Why these times can't be saved, or null. */
    fun timeProblem(startedAt: Long, endedAt: Long, now: Long): String? = when {
        startedAt > now -> "A workout can't start in the future."
        endedAt > now -> "A workout can't end in the future."
        endedAt < startedAt -> "The end is before the start."
        else -> null
    }

    /** Why this set can't be saved, or null. Bodyweight sets carry no load; the others need one. */
    fun setProblem(set: EditedSet, loadType: LoadType): String? = when {
        set.reps !in 1..MAX_REPS -> "Every set needs at least 1 rep."
        !Loads.logsLoad(loadType) -> null
        set.loadG == null -> "Every set needs a weight."
        set.loadG !in 0..Loads.MAX_LOAD_G -> "That weight is out of range."
        set.rir != null && set.rir !in 0..3 -> "Reps in reserve is 0 to 3+."
        else -> null
    }

    /** A bodyweight exercise's sets never store a load, whatever was typed. */
    fun normalized(set: EditedSet, loadType: LoadType): EditedSet = if (Loads.logsLoad(loadType)) set else set.copy(loadG = null)
}
