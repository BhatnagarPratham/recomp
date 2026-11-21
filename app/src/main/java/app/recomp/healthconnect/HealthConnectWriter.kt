package app.recomp.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import app.recomp.data.health.HealthWriter
import app.recomp.domain.export.OutKind
import app.recomp.domain.export.OutMeal
import app.recomp.domain.export.OutMealType
import app.recomp.domain.export.OutRecord
import app.recomp.domain.export.OutWeight
import app.recomp.domain.export.OutWorkout
import app.recomp.domain.model.SessionKind
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass

/**
 * Health Connect's write side (DA-04). Records are manual entries with Recomp's `clientRecordId` and the write time as
 * `clientRecordVersion`, so a later write of the same id replaces the earlier one, even after a reinstall or restore.
 * Writes go in chunks of at most 1,000, as Health Connect asks.
 */
@Singleton
class HealthConnectWriter @Inject constructor(@ApplicationContext private val context: Context) : HealthWriter {
    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    override fun available(): Boolean = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    override suspend fun canWrite(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE &&
            client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)

    override suspend fun upsert(records: List<OutRecord>, version: Long) {
        records.map { toRecord(it, version) }.chunked(CHUNK).forEach { client.insertRecords(it) }
    }

    override suspend fun delete(kind: OutKind, ids: List<String>) {
        ids.chunked(CHUNK).forEach { chunk ->
            try {
                client.deleteRecords(type(kind), emptyList(), chunk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One id Health Connect doesn't have can fail the whole call: go one by one, and let a missing one go.
                chunk.forEach { id -> runCatching { client.deleteRecords(type(kind), emptyList(), listOf(id)) } }
            }
        }
    }

    override suspend fun deleteAll(kind: OutKind) {
        // A delete by time range only ever removes the calling app's records.
        client.deleteRecords(type(kind), TimeRangeFilter.before(Instant.now().plusSeconds(366L * 24 * 3600)))
    }

    private fun type(kind: OutKind): KClass<out Record> = when (kind) {
        OutKind.WORKOUT -> ExerciseSessionRecord::class
        OutKind.MEAL -> NutritionRecord::class
        OutKind.WEIGHT -> WeightRecord::class
    }

    private fun offset(at: Instant) = ZoneId.systemDefault().rules.getOffset(at)

    private fun toRecord(record: OutRecord, version: Long): Record {
        val metadata = Metadata.manualEntry(clientRecordId = record.id, clientRecordVersion = version)
        return when (record) {
            is OutWorkout -> ExerciseSessionRecord(
                startTime = record.start, startZoneOffset = offset(record.start),
                endTime = record.end, endZoneOffset = offset(record.end),
                metadata = metadata,
                exerciseType = exerciseType(record.sessionKind, record.title),
                title = record.title,
                notes = record.notes,
            )
            is OutMeal -> NutritionRecord(
                startTime = record.start, startZoneOffset = offset(record.start),
                endTime = record.end, endZoneOffset = offset(record.end),
                metadata = metadata,
                energy = Energy.kilocalories(record.kcal),
                protein = Mass.grams(record.proteinG),
                totalCarbohydrate = record.carbsG?.let(Mass::grams),
                totalFat = record.fatG?.let(Mass::grams),
                dietaryFiber = record.fiberG?.let(Mass::grams),
                name = record.name,
                mealType = when (record.mealType) {
                    OutMealType.BREAKFAST -> MealType.MEAL_TYPE_BREAKFAST
                    OutMealType.LUNCH -> MealType.MEAL_TYPE_LUNCH
                    OutMealType.DINNER -> MealType.MEAL_TYPE_DINNER
                    OutMealType.SNACK -> MealType.MEAL_TYPE_SNACK
                    OutMealType.UNKNOWN -> MealType.MEAL_TYPE_UNKNOWN
                },
            )
            is OutWeight -> WeightRecord(time = record.at, zoneOffset = offset(record.at), weight = Mass.kilograms(record.grams / 1000.0), metadata = metadata)
        }
    }

    companion object {
        const val CHUNK = 1_000

        /** DA-04's permissions, asked for together when you turn *Share with other apps* on. */
        val PERMISSIONS: Set<String> = setOf(
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),
            HealthPermission.getWritePermission(NutritionRecord::class),
            HealthPermission.getWritePermission(WeightRecord::class),
        )

        /**
         * Recomp's kinds as Health Connect's types: strength training, yoga, stretching, and for cardio and sport the
         * activity its style names when M13's table knows it ("Walk", "Badminton"), else "other workout".
         */
        fun exerciseType(kind: SessionKind, title: String = ""): Int = when (kind) {
            SessionKind.STRENGTH -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
            SessionKind.YOGA -> ExerciseSessionRecord.EXERCISE_TYPE_YOGA
            SessionKind.MOBILITY -> ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING
            SessionKind.CARDIO, SessionKind.SPORT ->
                app.recomp.data.health.ExerciseTypes.typeFor(title)?.takeIf { app.recomp.data.health.ExerciseTypes.kind(it) == kind }
                    ?: ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
        }
    }
}
