package app.recomp.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.recomp.data.health.BackgroundRead
import app.recomp.data.health.ExternalWorkout
import app.recomp.data.health.HealthAvailability
import app.recomp.data.health.HealthChanges
import app.recomp.data.health.HealthFeature
import app.recomp.data.health.HealthSource
import app.recomp.domain.health.BodyMetric
import app.recomp.domain.health.BodyReading
import app.recomp.domain.health.SleepSession
import app.recomp.domain.health.Span
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

/**
 * Health Connect itself (TRD §6.3), read-only. It replaces M0's spike. Each feature has its own permissions, so
 * turning one on asks only for those. Records this app wrote are left out of every read.
 */
@Singleton
class HealthConnectSource @Inject constructor(@ApplicationContext private val context: Context) : HealthSource {
    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    override fun availability(): HealthAvailability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAvailability.NEEDS_UPDATE
        else -> HealthAvailability.UNAVAILABLE
    }

    override suspend fun granted(): Set<HealthFeature> {
        val granted = client.permissionController.getGrantedPermissions()
        return HealthFeature.entries.filter { granted.containsAll(permissions(it)) }.toSet()
    }

    override suspend fun backgroundRead(): BackgroundRead = when {
        client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) != HealthConnectFeatures.FEATURE_STATUS_AVAILABLE -> BackgroundRead.UNAVAILABLE
        BACKGROUND_PERMISSION in client.permissionController.getGrantedPermissions() -> BackgroundRead.GRANTED
        else -> BackgroundRead.NOT_GRANTED
    }

    /**
     * One aggregate, sliced by calendar day in the phone's time zone. The aggregate de-duplicates across apps by
     * your priority list and includes the phone's own count (Android 14 with SDK extension 20 or later), whose data
     * origin is never named here. Days with no data are simply absent.
     */
    override suspend fun stepsByDay(from: LocalDate, to: LocalDate, zone: ZoneId): Map<LocalDate, Long> =
        client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(from.atStartOfDay(), to.plusDays(1).atStartOfDay()),
                timeRangeSlicer = Period.ofDays(1),
            ),
        ).mapNotNull { bucket -> bucket.result[StepsRecord.COUNT_TOTAL]?.let { bucket.startTime.toLocalDate() to it } }.toMap()

    override suspend fun sleepSessions(span: Span): List<SleepSession> =
        // A night can start well before the span does; what counts is where it ends.
        readAll(SleepSessionRecord::class, Span(span.start - LONGEST_SLEEP_MS, span.end))
            .filter { notOurs(it) && it.endTime.toEpochMilli() in (span.start + 1)..span.end }
            .map { record ->
                SleepSession(
                    Span(record.startTime.toEpochMilli(), record.endTime.toEpochMilli()),
                    record.stages.filter { it.stage in AWAKE_STAGES && it.endTime >= it.startTime }.map { Span(it.startTime.toEpochMilli(), it.endTime.toEpochMilli()) },
                )
            }

    override suspend fun workouts(span: Span): List<ExternalWorkout> =
        readAll(ExerciseSessionRecord::class, span).filter(::notOurs).map(::workout)

    override suspend fun bodyReadings(span: Span): List<BodyReading> {
        val inBody = setOf(DataOrigin(INBODY_PACKAGE))
        return readAll(WeightRecord::class, span, inBody).map { BodyReading(it.metadata.id, BodyMetric.WEIGHT, it.time.toEpochMilli(), it.weight.inGrams) } +
            readAll(BodyFatRecord::class, span, inBody).map { BodyReading(it.metadata.id, BodyMetric.BODY_FAT, it.time.toEpochMilli(), it.percentage.value) } +
            readAll(BasalMetabolicRateRecord::class, span, inBody).map {
                BodyReading(it.metadata.id, BodyMetric.BMR, it.time.toEpochMilli(), it.basalMetabolicRate.inKilocaloriesPerDay)
            }
    }

    override suspend fun token(feature: HealthFeature): String = client.getChangesToken(
        ChangesTokenRequest(recordTypes(feature), if (feature == HealthFeature.INBODY) setOf(DataOrigin(INBODY_PACKAGE)) else emptySet()),
    )

    override suspend fun changes(feature: HealthFeature, token: String): HealthChanges {
        val workouts = mutableListOf<ExternalWorkout>()
        val deleted = mutableListOf<String>()
        var changed = false
        var next = token
        do {
            val page = client.getChanges(next)
            if (page.changesTokenExpired) return HealthChanges(nextToken = token, expired = true)
            page.changes.forEach { change ->
                when (change) {
                    // Changes include this app's own writes (M16's), which no feature reads back.
                    is UpsertionChange -> if (notOurs(change.record)) {
                        changed = true
                        (change.record as? ExerciseSessionRecord)?.let { workouts += workout(it) }
                    }
                    is DeletionChange -> {
                        changed = true
                        deleted += change.recordId
                    }
                }
            }
            next = page.nextChangesToken
        } while (page.hasMore)
        return HealthChanges(nextToken = next, changed = changed, workouts = workouts, deletedIds = deleted)
    }

    override fun appName(packageName: String): String = KNOWN_APPS[packageName]
        ?: runCatching { context.packageManager.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() } }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: packageName

    private fun notOurs(record: Record): Boolean = record.metadata.dataOrigin.packageName != context.packageName

    private fun workout(record: ExerciseSessionRecord) = ExternalWorkout(
        id = record.metadata.id,
        origin = record.metadata.dataOrigin.packageName,
        start = record.startTime.toEpochMilli(),
        end = record.endTime.toEpochMilli(),
        type = record.exerciseType,
    )

    private suspend fun <T : Record> readAll(type: KClass<T>, span: Span, origins: Set<DataOrigin> = emptySet()): List<T> {
        val filter = TimeRangeFilter.between(Instant.ofEpochMilli(span.start), Instant.ofEpochMilli(span.end))
        val out = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val page = client.readRecords(ReadRecordsRequest(type, filter, origins, pageToken = pageToken))
            out += page.records
            pageToken = page.pageToken
        } while (pageToken != null)
        return out
    }

    companion object {
        /** The InBody app (TRD §6.3). It writes weight, body fat % and BMR; the rest of a scan is typed. */
        const val INBODY_PACKAGE = "com.inbody2014.inbody"

        /** Lets the daily job read while the app is closed, where Health Connect offers it (TRD §10 M13). */
        const val BACKGROUND_PERMISSION: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

        private const val LONGEST_SLEEP_MS = 24 * 3_600_000L

        private val AWAKE_STAGES = setOf(SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED)

        /** Apps whose label needn't be looked up, or can't be when they aren't visible to this app. */
        private val KNOWN_APPS = mapOf(
            "com.google.android.apps.fitness" to "Google Fit",
            INBODY_PACKAGE to "InBody",
            "com.sec.android.app.shealth" to "Samsung Health",
            "com.fitbit.FitbitMobile" to "Fitbit",
            "com.strava" to "Strava",
        )

        private fun recordTypes(feature: HealthFeature): Set<KClass<out Record>> = when (feature) {
            HealthFeature.STEPS -> setOf(StepsRecord::class)
            HealthFeature.SLEEP -> setOf(SleepSessionRecord::class)
            HealthFeature.WORKOUTS -> setOf(ExerciseSessionRecord::class)
            HealthFeature.INBODY -> setOf(WeightRecord::class, BodyFatRecord::class, BasalMetabolicRateRecord::class)
        }

        /** What turning [feature] on asks for, and nothing more (PRD §12.9). */
        fun permissions(feature: HealthFeature): Set<String> = recordTypes(feature).map { HealthPermission.getReadPermission(it) }.toSet()
    }
}
