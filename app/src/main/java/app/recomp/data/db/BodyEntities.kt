package app.recomp.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import app.recomp.domain.body.ProtocolIssue
import app.recomp.domain.body.ScanProtocol
import app.recomp.domain.persona.WeightSource
import app.recomp.domain.trend.WeighIn
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/** Where a scan's numbers came from (TRD §4). M4 has manual entry; the others are P1 (IB-03, IB-07). */
enum class ScanSource { MANUAL, SHARE_AI, HEALTH_CONNECT }

/** Tape measurements (BM-02, BM-03), in cm. Mobility markers (YG-04) add types later. */
enum class MeasurementType { WAIST, CHEST, ARM, THIGH, HIP }

/**
 * A body-composition scan (IB-01, IB-02). Masses are integer grams. [protocolOk] and [minsFromSlot] are
 * the conditions check as it was when the scan was saved (TRD §5.6).
 */
@Entity(tableName = "body_composition", indices = [Index("measured_at")])
data class BodyCompositionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "measured_at") val measuredAt: Long,
    @ColumnInfo(name = "log_date") val logDate: LocalDate,
    val source: ScanSource = ScanSource.MANUAL,
    @ColumnInfo(name = "weight_g") val weightG: Int,
    @ColumnInfo(name = "skeletal_muscle_g") val skeletalMuscleG: Int,
    @ColumnInfo(name = "body_fat_g") val bodyFatG: Int,
    @ColumnInfo(name = "body_fat_pct") val bodyFatPct: Double,
    @ColumnInfo(name = "visceral_fat_level") val visceralFatLevel: Double,
    @ColumnInfo(name = "bmr_kcal") val bmrKcal: Int? = null,
    @ColumnInfo(name = "ecw_tbw_ratio") val ecwTbwRatio: Double? = null,
    @ColumnInfo(name = "inbody_score") val inbodyScore: Int? = null,
    /** `{rightArmG, leftArmG, trunkG, rightLegG, leftLegG}` (TRD §5.6). */
    @ColumnInfo(name = "segmental_lean_json") val segmentalLeanJson: String? = null,
    @ColumnInfo(name = "mins_since_meal") val minsSinceMeal: Int,
    @ColumnInfo(name = "workout_before_scan") val workoutBeforeScan: Boolean,
    /** Minutes from the usual slot's time of day; null when there was no slot. */
    @ColumnInfo(name = "mins_from_slot") val minsFromSlot: Int?,
    @ColumnInfo(name = "protocol_ok") val protocolOk: Boolean,
    @ColumnInfo(name = "image_path") val imagePath: String? = null,
    val notes: String? = null,
) {
    /** Why it was off protocol, from what was stored. */
    val issues: Set<ProtocolIssue>
        get() = buildSet {
            if ((minsFromSlot ?: 0) > ScanProtocol.TIME_WINDOW_MIN) add(ProtocolIssue.TIME)
            if (minsSinceMeal < ScanProtocol.MIN_SINCE_MEAL) add(ProtocolIssue.MEAL)
            if (workoutBeforeScan) add(ProtocolIssue.WORKOUT)
        }
}

/**
 * A weigh-in (BM-01), tagged with its source. A scan's weigh-in carries [scanId] and goes with the scan.
 * The trend is computed on read (TRD §5.2), never stored.
 */
@Entity(
    tableName = "body_weight",
    foreignKeys = [ForeignKey(entity = BodyCompositionEntity::class, parentColumns = ["id"], childColumns = ["scan_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("log_date"), Index("scan_id")],
)
data class BodyWeightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "log_date") val logDate: LocalDate,
    @ColumnInfo(name = "measured_at") val measuredAt: Long,
    @ColumnInfo(name = "weight_g") val weightG: Int,
    val source: WeightSource,
    @ColumnInfo(name = "scan_id") val scanId: Long? = null,
) {
    fun toWeighIn() = WeighIn(logDate, measuredAt, weightG, source)
}

/** A tape measurement (BM-02), in cm. */
@Entity(tableName = "measurement", indices = [Index(value = ["type", "log_date"])])
data class MeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "log_date") val logDate: LocalDate,
    @ColumnInfo(name = "measured_at") val measuredAt: Long,
    val type: MeasurementType,
    val value: Double,
)

@Dao
interface BodyDao {
    @Query("SELECT * FROM body_weight ORDER BY measured_at")
    fun observeWeighIns(): Flow<List<BodyWeightEntity>>

    @Query("SELECT * FROM body_weight ORDER BY measured_at")
    suspend fun weighIns(): List<BodyWeightEntity>

    @Query("SELECT * FROM body_weight WHERE id = :id")
    suspend fun weighIn(id: Long): BodyWeightEntity?

    /** The latest weigh-in from [source], to prefill the next one. */
    @Query("SELECT * FROM body_weight WHERE source = :source ORDER BY measured_at DESC LIMIT 1")
    suspend fun lastWeighIn(source: WeightSource): BodyWeightEntity?

    @Query("SELECT * FROM body_weight ORDER BY measured_at DESC LIMIT 1")
    suspend fun lastWeighIn(): BodyWeightEntity?

    @Query("SELECT * FROM body_weight WHERE scan_id = :scanId")
    suspend fun weighInForScan(scanId: Long): BodyWeightEntity?

    @Insert
    suspend fun insert(weighIn: BodyWeightEntity): Long

    @Update
    suspend fun update(weighIn: BodyWeightEntity)

    @Delete
    suspend fun delete(weighIn: BodyWeightEntity)

    @Query("SELECT * FROM measurement WHERE type = :type ORDER BY measured_at")
    fun observeMeasurements(type: MeasurementType): Flow<List<MeasurementEntity>>

    @Query("SELECT * FROM measurement WHERE type = :type ORDER BY measured_at DESC LIMIT 1")
    suspend fun lastMeasurement(type: MeasurementType): MeasurementEntity?

    @Query("SELECT * FROM measurement WHERE id = :id")
    suspend fun measurement(id: Long): MeasurementEntity?

    @Insert
    suspend fun insert(measurement: MeasurementEntity): Long

    @Update
    suspend fun update(measurement: MeasurementEntity)

    @Delete
    suspend fun delete(measurement: MeasurementEntity)

    /** Newest first: the scan table (HM-02). */
    @Query("SELECT * FROM body_composition ORDER BY measured_at DESC")
    fun observeScans(): Flow<List<BodyCompositionEntity>>

    @Query("SELECT * FROM body_composition WHERE id = :id")
    suspend fun scan(id: Long): BodyCompositionEntity?

    @Query("SELECT COUNT(*) FROM body_composition")
    suspend fun scanCount(): Int

    @Insert
    suspend fun insert(scan: BodyCompositionEntity): Long

    @Update
    suspend fun update(scan: BodyCompositionEntity)

    @Delete
    suspend fun delete(scan: BodyCompositionEntity)
}
