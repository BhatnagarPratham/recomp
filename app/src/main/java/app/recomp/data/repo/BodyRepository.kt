package app.recomp.data.repo

import kotlinx.coroutines.flow.map
import java.time.LocalDate
import app.recomp.domain.model.SessionKind
import app.recomp.domain.body.TapeSites
import app.recomp.domain.body.TapeSite
import app.recomp.domain.body.TapeReading
import app.recomp.domain.body.MobilityMarkers
import app.recomp.domain.body.MobilityMarker
import androidx.room.withTransaction
import app.recomp.data.db.AppDatabase
import app.recomp.data.db.BodyCompositionEntity
import app.recomp.data.db.BodyDao
import app.recomp.data.db.BodyWeightEntity
import app.recomp.data.db.MeasurementEntity
import app.recomp.data.db.MeasurementType
import app.recomp.data.db.ScanSource
import app.recomp.data.db.WorkoutDao
import app.recomp.data.prefs.DayBoundary
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.domain.body.ProtocolCheck
import app.recomp.domain.body.ScanProtocol
import app.recomp.domain.body.ScanSlot
import app.recomp.domain.persona.WeightSource
import app.recomp.domain.trend.TrendCalculator
import app.recomp.domain.trend.TrendPoint
import app.recomp.domain.trend.WeeklyRate
import app.recomp.domain.trend.WeightPace
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The weight trend as Body and Today show it (BM-01, TRD §5.2). */
data class BodyTrend(
    val primary: WeightSource,
    /** The primary source's counted weigh-ins with the trend after each, oldest first. */
    val points: List<TrendPoint>,
    /** Every weigh-in, oldest first, for the chart's dots (other sources and later same-day ones hollow). */
    val weighIns: List<BodyWeightEntity>,
    val rate: WeeklyRate?,
    /** Null before onboarding sets a goal. */
    val pace: WeightPace?,
) {
    val latest: TrendPoint? get() = points.lastOrNull()

    /** The trend weight, to 0.1 kg, which stands in for the typed weight once there is one (TRD §5.0). */
    val trendKg: Double? get() = latest?.trendKg?.let { (it * 10).roundToInt() / 10.0 }

    /** Weigh-ins the trend counts, by id. The rest are drawn hollow. */
    val countedIds: Set<Long> by lazy {
        weighIns.filter { it.source == primary }.groupBy { it.logDate }.map { (_, day) -> day.minBy { it.measuredAt }.id }.toSet()
    }
}

/** Segmental lean mass (IB-01's optional field), in grams, as stored in `segmental_lean_json`. */
@Serializable
data class SegmentalLean(val rightArmG: Int, val leftArmG: Int, val trunkG: Int, val rightLegG: Int, val leftLegG: Int)

/** A scan as typed into the form (IB-01). Masses in grams. */
data class ScanInput(
    val measuredAt: Long,
    val weightG: Int,
    val skeletalMuscleG: Int,
    val bodyFatG: Int,
    val bodyFatPct: Double,
    val visceralFatLevel: Double,
    val minsSinceMeal: Int,
    val workoutBefore: Boolean,
    val bmrKcal: Int? = null,
    val ecwTbwRatio: Double? = null,
    val score: Int? = null,
    val segmental: SegmentalLean? = null,
    val notes: String? = null,
    /** Set when the scan starts from an InBody draft (IB-07); null keeps what the scan already has. */
    val source: ScanSource? = null,
)

/** What saving a scan did. [slotSet] is true when this first scan set the usual slot (PRD §7.9). */
data class ScanSaved(val id: Long, val check: ProtocolCheck, val slotSet: ScanSlot?)

/**
 * Weigh-ins, waist and body-composition scans (BM-01, BM-02, IB-01, IB-02). Log dates follow the "day
 * starts at" setting, like every other row (PR-03).
 */
@Singleton
class BodyRepository @Inject constructor(
    private val db: AppDatabase,
    private val bodyDao: BodyDao,
    private val workoutDao: WorkoutDao,
    private val prefs: SettingsPrefs,
    private val days: DayBoundary,
) {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    val scanSlot: Flow<ScanSlot?> = prefs.scanSlot

    suspend fun setScanSlot(slot: ScanSlot) = prefs.setScanSlot(slot)

    // Weigh-ins (BM-01)

    fun observeTrend(): Flow<BodyTrend> = combine(bodyDao.observeWeighIns(), prefs.persona) { rows, persona ->
        trendOf(rows, persona.weightSource, persona.goal?.toPlan()?.policy?.let(WeightPace::of))
    }

    /** The current trend weight, or null before the primary source has a weigh-in. */
    suspend fun trendKg(): Double? = trendOf(bodyDao.weighIns(), prefs.currentPersona().weightSource, null).trendKg

    /**
     * The trend weight as it stood at [at]: from the primary source's weigh-ins measured by then, or null
     * before the first. Rebuilt assisted-lift PRs use it, so they match what finishing the workout found.
     */
    suspend fun trendKgAt(at: Long): Double? =
        trendOf(bodyDao.weighIns().filter { it.measuredAt <= at }, prefs.currentPersona().weightSource, null).trendKg

    private fun trendOf(rows: List<BodyWeightEntity>, primary: WeightSource, pace: WeightPace?): BodyTrend {
        val points = TrendCalculator.trend(rows.map(BodyWeightEntity::toWeighIn), primary)
        return BodyTrend(primary, points, rows, TrendCalculator.weeklyRate(points), pace)
    }

    /** Prefill for the weigh-in sheet: the last weigh-in from [source], else the last from any source. */
    suspend fun lastWeightG(source: WeightSource): Int? = (bodyDao.lastWeighIn(source) ?: bodyDao.lastWeighIn())?.weightG

    /** The weigh-in sheet's starting value: [lastWeightG], else the weight typed in onboarding. */
    suspend fun prefillWeightG(source: WeightSource): Int? =
        lastWeightG(source) ?: prefs.profile.first()?.weightKg?.let { (it * 1000).roundToInt() }

    suspend fun primarySource(): WeightSource = prefs.currentPersona().weightSource

    suspend fun logWeighIn(weightG: Int, source: WeightSource, measuredAt: Long): Long =
        bodyDao.insert(BodyWeightEntity(logDate = days.of(measuredAt), measuredAt = measuredAt, weightG = weightG, source = source))

    /** A scan's own weigh-in stays a *Scan* weigh-in; Body opens the scan's form to edit it. */
    suspend fun updateWeighIn(id: Long, weightG: Int, source: WeightSource, measuredAt: Long) {
        val old = bodyDao.weighIn(id) ?: return
        bodyDao.update(old.copy(weightG = weightG, source = if (old.scanId != null) old.source else source, measuredAt = measuredAt, logDate = days.of(measuredAt)))
    }

    /** Returns the deleted row for *Undo*. */
    suspend fun deleteWeighIn(id: Long): BodyWeightEntity? = bodyDao.weighIn(id)?.also { bodyDao.delete(it) }

    suspend fun restoreWeighIn(row: BodyWeightEntity) {
        if (row.scanId != null && bodyDao.scan(row.scanId) == null) return
        bodyDao.insert(row)
    }

    // Waist (BM-02)

    fun observeWaist(): Flow<List<MeasurementEntity>> = bodyDao.observeMeasurements(MeasurementType.WAIST)

    suspend fun lastWaistCm(): Double? = bodyDao.lastMeasurement(MeasurementType.WAIST)?.value

    suspend fun logWaist(cm: Double, measuredAt: Long): Long =
        bodyDao.insert(MeasurementEntity(logDate = days.of(measuredAt), measuredAt = measuredAt, type = MeasurementType.WAIST, value = cm))

    suspend fun updateMeasurement(id: Long, value: Double, measuredAt: Long) {
        val old = bodyDao.measurement(id) ?: return
        bodyDao.update(old.copy(value = value, measuredAt = measuredAt, logDate = days.of(measuredAt)))
    }

    suspend fun deleteMeasurement(id: Long): MeasurementEntity? = bodyDao.measurement(id)?.also { bodyDao.delete(it) }

    /** A tape site's or mobility marker's readings, newest first, for editing and deleting them (M17). */
    fun observeReadings(type: MeasurementType): Flow<List<MeasurementEntity>> =
        bodyDao.observeAllMeasurements().map { rows -> rows.filter { it.type == type }.sortedByDescending { it.measuredAt } }

    /** Changes a tape or mobility reading; refuses a value out of its range. Returns whether it saved. */
    suspend fun updateReading(id: Long, value: Double, measuredAt: Long): Boolean {
        val old = bodyDao.measurement(id) ?: return false
        if (!validReading(old.type, value)) return false
        updateMeasurement(id, value, measuredAt)
        return true
    }

    suspend fun restoreMeasurement(row: MeasurementEntity) {
        bodyDao.insert(row)
    }

    // Other tape sites (BM-03) and mobility markers (YG-04)

    val tapeSites: Flow<Set<TapeSite>> = prefs.tapeSites

    suspend fun setTapeSites(sites: Set<TapeSite>) = prefs.setTapeSites(sites)

    /** Every tape site's latest reading and its change, waist included. */
    fun observeTape(): Flow<List<TapeReading>> = bodyDao.observeAllMeasurements().map { rows ->
        TapeSites.latest(rows.mapNotNull { row -> row.type.tapeSite()?.let { Triple(it, row.logDate, row.value) } })
    }

    /** The sites the *Log measurements* sheet asks for today: waist, then chosen sites 4+ weeks old (PRD §12.13). */
    suspend fun dueTapeSites(today: LocalDate): List<TapeSite> {
        val last = TapeSite.entries.mapNotNull { site -> bodyDao.lastMeasurement(site.type())?.let { site to it.logDate } }.toMap()
        return TapeSites.due(prefs.tapeSites.first(), last, today)
    }

    suspend fun lastTapeCm(site: TapeSite): Double? = bodyDao.lastMeasurement(site.type())?.value

    /** Saves one sheet's readings together, all at [measuredAt]. */
    suspend fun logTape(readings: Map<TapeSite, Double>, measuredAt: Long) = db.withTransaction {
        readings.forEach { (site, cm) ->
            bodyDao.insert(MeasurementEntity(logDate = days.of(measuredAt), measuredAt = measuredAt, type = site.type(), value = cm))
        }
    }

    /** YG-04: the mobility card's switch, defaulting to on when you have a yoga or mobility session. */
    fun observeMobilityOn(extras: Flow<List<SessionKind>>): Flow<Boolean> =
        combine(prefs.mobilityChoice, extras) { choice, kinds -> MobilityMarkers.enabled(choice, kinds) }

    suspend fun setMobilityMarkers(on: Boolean) = prefs.setMobilityMarkers(on)

    /** Each marker's readings, newest first. */
    fun observeMobility(): Flow<Map<MobilityMarker, List<MeasurementEntity>>> = bodyDao.observeAllMeasurements().map { rows ->
        MobilityMarker.entries.associateWith { m -> rows.filter { it.type == m.type() }.sortedByDescending { it.measuredAt } }
    }

    /** Saves the markers taken in one go; a marker left out wasn't taken. */
    suspend fun logMobility(values: Map<MobilityMarker, Double>, measuredAt: Long) = db.withTransaction {
        values.forEach { (marker, value) ->
            bodyDao.insert(MeasurementEntity(logDate = days.of(measuredAt), measuredAt = measuredAt, type = marker.type(), value = value))
        }
    }

    // Scans (IB-01, IB-02)

    fun observeScans(): Flow<List<BodyCompositionEntity>> = bodyDao.observeScans()

    suspend fun scan(id: Long): BodyCompositionEntity? = bodyDao.scan(id)

    /** Whether a session on that log day had finished by [at] (TRD §5.6), to pre-set "trained before". */
    suspend fun trainedBefore(at: Long): Boolean = workoutDao.completedBefore(days.of(at), at)

    fun check(measuredAt: Long, minsSinceMeal: Int, workoutBefore: Boolean, slot: ScanSlot?): ProtocolCheck =
        ScanProtocol.check(timeOf(measuredAt), slot, minsSinceMeal, workoutBefore)

    private fun timeOf(epochMs: Long): LocalTime = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime()

    /**
     * Saves a scan and its *Scan* weigh-in in one transaction (IB-01). The first scan sets the usual slot from
     * its own day and time; the check uses the slot in force now (TRD §5.6). Once started it runs to the end, so
     * leaving the screen can't save a first scan without its slot.
     */
    suspend fun saveScan(input: ScanInput, id: Long? = null): ScanSaved = withContext(NonCancellable) { saveScanNow(input, id) }

    private suspend fun saveScanNow(input: ScanInput, id: Long?): ScanSaved {
        var slot = prefs.scanSlot.first()
        var slotSet: ScanSlot? = null
        if (slot == null) {
            val at = Instant.ofEpochMilli(input.measuredAt).atZone(zone)
            slot = ScanSlot(at.dayOfWeek, LocalTime.of(at.hour, at.minute))
            slotSet = slot
        }
        val check = check(input.measuredAt, input.minsSinceMeal, input.workoutBefore, slot)
        val logDate = days.of(input.measuredAt)
        // An edit keeps what the form doesn't show, such as where the scan came from (IB-03, IB-07).
        val existing = id?.let { bodyDao.scan(it) }
        val base = existing ?: BodyCompositionEntity(
            measuredAt = 0, logDate = logDate, source = ScanSource.MANUAL, weightG = 0, skeletalMuscleG = 0, bodyFatG = 0, bodyFatPct = 0.0,
            visceralFatLevel = 0.0, minsSinceMeal = 0, workoutBeforeScan = false, minsFromSlot = null, protocolOk = false,
        )
        val row = base.copy(
            source = input.source ?: base.source,
            measuredAt = input.measuredAt,
            logDate = logDate,
            weightG = input.weightG,
            skeletalMuscleG = input.skeletalMuscleG,
            bodyFatG = input.bodyFatG,
            bodyFatPct = input.bodyFatPct,
            visceralFatLevel = input.visceralFatLevel,
            bmrKcal = input.bmrKcal,
            ecwTbwRatio = input.ecwTbwRatio,
            inbodyScore = input.score,
            segmentalLeanJson = input.segmental?.let { Json.encodeToString(it) },
            minsSinceMeal = input.minsSinceMeal,
            workoutBeforeScan = input.workoutBefore,
            minsFromSlot = check.minutesFromSlot,
            protocolOk = check.ok,
            notes = input.notes?.trim()?.takeIf { it.isNotEmpty() },
        )
        val saved = db.withTransaction {
            val scanId = if (existing == null) bodyDao.insert(row) else row.id.also { bodyDao.update(row) }
            val weighIn = bodyDao.weighInForScan(scanId)
            val fresh = BodyWeightEntity(logDate = logDate, measuredAt = input.measuredAt, weightG = input.weightG, source = WeightSource.SCAN, scanId = scanId)
            if (weighIn == null) bodyDao.insert(fresh) else bodyDao.update(fresh.copy(id = weighIn.id))
            scanId
        }
        slotSet?.let { prefs.setScanSlot(it) }
        return ScanSaved(saved, check, slotSet)
    }

    /** The scan's weigh-in goes with it (TRD §5.6). */
    suspend fun deleteScan(id: Long) = withContext(NonCancellable) {
        db.withTransaction {
            bodyDao.weighInForScan(id)?.let { bodyDao.delete(it) }
            bodyDao.scan(id)?.let { bodyDao.delete(it) }
        }
    }

    companion object {
        fun segmental(json: String?): SegmentalLean? = json?.let { runCatching { Json.decodeFromString<SegmentalLean>(it) }.getOrNull() }
    }
}

/** The measurement type a tape site is stored as. */
fun TapeSite.type(): MeasurementType = when (this) {
    TapeSite.WAIST -> MeasurementType.WAIST
    TapeSite.CHEST -> MeasurementType.CHEST
    TapeSite.ARM -> MeasurementType.ARM
    TapeSite.THIGH -> MeasurementType.THIGH
    TapeSite.HIP -> MeasurementType.HIP
}

fun MeasurementType.tapeSite(): TapeSite? = TapeSite.entries.firstOrNull { it.type() == this }

fun MeasurementType.mobilityMarker(): MobilityMarker? = MobilityMarker.entries.firstOrNull { it.type() == this }

/** Tape readings are 10 to 250 cm; mobility values keep the ranges their sheet allows (YG-04). */
fun validReading(type: MeasurementType, value: Double): Boolean = when {
    type.tapeSite() != null -> value in 10.0..250.0
    type == MeasurementType.SIT_AND_REACH -> MobilityMarkers.validReach(value)
    type == MeasurementType.SQUAT_HOLD -> value == Math.floor(value) && MobilityMarkers.validHold(value.toInt())
    type == MeasurementType.TOE_TOUCH -> value == 0.0 || value == 1.0
    else -> true
}

fun MobilityMarker.type(): MeasurementType = when (this) {
    MobilityMarker.SIT_AND_REACH -> MeasurementType.SIT_AND_REACH
    MobilityMarker.SQUAT_HOLD -> MeasurementType.SQUAT_HOLD
    MobilityMarker.TOE_TOUCH -> MeasurementType.TOE_TOUCH
}

