package app.recomp.data.health

import app.recomp.domain.health.BodyReading
import app.recomp.domain.health.SleepSession
import app.recomp.domain.health.Span
import java.time.LocalDate
import java.time.ZoneId

/** What Recomp can read from Health Connect (PRD §12.9). Each is off until you turn it on, and asks only for its own permission. */
enum class HealthFeature { STEPS, SLEEP, WORKOUTS, INBODY }

enum class HealthAvailability {
    AVAILABLE,

    /** Health Connect is there but too old (or, before Android 14, not installed from Play). */
    NEEDS_UPDATE,
    UNAVAILABLE,
}

/** Whether the daily background sync can read (TRD §10 M13). Without it, Recomp syncs whenever you open it. */
enum class BackgroundRead { GRANTED, NOT_GRANTED, UNAVAILABLE }

/** A session another app wrote. [type] is Health Connect's exercise type; [origin] is the writing app's package. */
data class ExternalWorkout(val id: String, val origin: String, val start: Long, val end: Long, val type: Int)

/**
 * What changed for a feature since its token was issued. [expired] means the token is too old (30 days without a
 * sync) and everything has to be read again. [workouts] carries the new and edited sessions when the feature is
 * [HealthFeature.WORKOUTS]; the other features only need to know that something [changed].
 */
data class HealthChanges(
    val nextToken: String,
    val expired: Boolean = false,
    val changed: Boolean = false,
    val workouts: List<ExternalWorkout> = emptyList(),
    val deletedIds: List<String> = emptyList(),
)

/**
 * Health Connect as the sync sees it (TRD §6.3), so tests can stand in for it. Reads only: Recomp writes nothing
 * here until M16. Every read leaves out what Recomp itself wrote.
 */
interface HealthSource {
    fun availability(): HealthAvailability

    /** The features whose permissions are all granted. */
    suspend fun granted(): Set<HealthFeature>

    suspend fun backgroundRead(): BackgroundRead

    /** The steps of each calendar day in [from]..[to], de-duplicated across apps by Health Connect. Days with no data are absent. */
    suspend fun stepsByDay(from: LocalDate, to: LocalDate, zone: ZoneId): Map<LocalDate, Long>

    /** Sleep sessions that end inside [span]. */
    suspend fun sleepSessions(span: Span): List<SleepSession>

    /** Exercise sessions that start inside [span], from every app but this one. */
    suspend fun workouts(span: Span): List<ExternalWorkout>

    /** The InBody app's weight, body fat and BMR records inside [span]. */
    suspend fun bodyReadings(span: Span): List<BodyReading>

    /** A fresh change token for [feature]'s record types. */
    suspend fun token(feature: HealthFeature): String

    suspend fun changes(feature: HealthFeature, token: String): HealthChanges

    /** The name of the app behind [packageName], e.g. "Google Fit", for "from Google Fit". */
    fun appName(packageName: String): String
}
