package app.recomp.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** One body-composition record as Health Connect reports it, with the app that wrote it. */
data class BodyRecordRow(val type: String, val time: Instant, val value: String, val origin: String)

data class HealthConnectReport(
    val stepsToday: Long,
    val stepsTodayOrigins: List<String>,
    /** Steps over the last 7 days, summed per writing app, to see whether Google Fit writes them. */
    val steps7dByOrigin: Map<String, Long>,
    val bodyRecords: List<BodyRecordRow>,
)

/**
 * M0 spike for TRD §6.3: answers (c) does Google Fit's step count reach Health Connect on this
 * phone, and (e) which body records the InBody app writes.
 */
class HealthConnectSpike(private val context: Context) {

    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(LeanBodyMassRecord::class),
        HealthPermission.getReadPermission(BasalMetabolicRateRecord::class),
        HealthPermission.getReadPermission(BodyWaterMassRecord::class),
    )

    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context)

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun grantedPermissions(): Set<String> = client.permissionController.getGrantedPermissions()

    suspend fun report(): HealthConnectReport {
        val now = Instant.now()
        val startOfToday = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()

        val today = client.aggregate(
            AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(startOfToday, now),
            ),
        )

        val weekOfSteps = client.readRecords(
            ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.after(now.minus(7, ChronoUnit.DAYS))),
        ).records
        val byOrigin = weekOfSteps.groupBy { it.metadata.dataOrigin.packageName }
            .mapValues { (_, records) -> records.sumOf { it.count } }

        val since = TimeRangeFilter.after(now.minus(180, ChronoUnit.DAYS))
        val body = buildList {
            client.readRecords(ReadRecordsRequest(WeightRecord::class, since)).records.forEach {
                add(BodyRecordRow("Weight", it.time, "%.1f kg".format(it.weight.inKilograms), it.metadata.dataOrigin.packageName))
            }
            client.readRecords(ReadRecordsRequest(BodyFatRecord::class, since)).records.forEach {
                add(BodyRecordRow("Body fat", it.time, "%.1f %%".format(it.percentage.value), it.metadata.dataOrigin.packageName))
            }
            client.readRecords(ReadRecordsRequest(LeanBodyMassRecord::class, since)).records.forEach {
                add(BodyRecordRow("Lean mass", it.time, "%.1f kg".format(it.mass.inKilograms), it.metadata.dataOrigin.packageName))
            }
            client.readRecords(ReadRecordsRequest(BasalMetabolicRateRecord::class, since)).records.forEach {
                add(BodyRecordRow("BMR", it.time, "%.0f kcal/day".format(it.basalMetabolicRate.inKilocaloriesPerDay), it.metadata.dataOrigin.packageName))
            }
            client.readRecords(ReadRecordsRequest(BodyWaterMassRecord::class, since)).records.forEach {
                add(BodyRecordRow("Body water", it.time, "%.1f kg".format(it.mass.inKilograms), it.metadata.dataOrigin.packageName))
            }
        }.sortedByDescending { it.time }

        return HealthConnectReport(
            stepsToday = today[StepsRecord.COUNT_TOTAL] ?: 0,
            stepsTodayOrigins = today.dataOrigins.map { it.packageName },
            steps7dByOrigin = byOrigin,
            bodyRecords = body,
        )
    }
}
