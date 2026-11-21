package app.recomp.data.repo

import androidx.room.withTransaction
import app.recomp.data.db.AppDatabase
import app.recomp.data.db.ExerciseDao
import app.recomp.data.db.ExerciseEntity
import app.recomp.data.db.ExposureSetRow
import app.recomp.data.db.PersonalRecordEntity
import app.recomp.data.db.RoutineDao
import app.recomp.data.db.WorkoutDao
import app.recomp.data.db.NutritionDao
import app.recomp.data.prefs.DayBoundary
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.domain.model.LoadType
import app.recomp.domain.model.SetType
import app.recomp.domain.nutrition.EnergyBalance
import app.recomp.domain.persona.ExperiencePolicy
import app.recomp.domain.progression.Action
import app.recomp.domain.progression.DatedExposure
import app.recomp.domain.progression.ExerciseConfig
import app.recomp.domain.progression.Exposure
import app.recomp.domain.progression.PersonalRecords
import app.recomp.domain.progression.PrType
import app.recomp.domain.progression.ProgressionContext
import app.recomp.domain.progression.ProgressionEngine
import app.recomp.domain.progression.Recommendation
import app.recomp.domain.progression.SessionSet
import app.recomp.domain.progression.StallReset
import app.recomp.domain.persona.Units
import app.recomp.domain.training.Loads
import app.recomp.domain.training.LoggedSet
import app.recomp.domain.units.LoadUnits
import java.math.RoundingMode
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One line of Today's preview (PRD §7.5): "Bench press 42.5 × 8 ↑". The screen formats [rec] in the persona's units. */
data class PreviewLine(val name: String, val rec: Recommendation) {
    val weightUp: Boolean get() = rec.action == Action.INCREASE && rec.loadG != null
}

/** One past session of an exercise (HM-02). [sets] are formatted at the screen, in the persona's units. */
data class HistoryEntry(
    val sessionId: Long,
    val date: LocalDate,
    val routineName: String?,
    val sets: List<LoggedSet>,
    val loadType: LoadType,
    val bestE1rmG: Int?,
    val records: List<PrType>,
)

/**
 * The progression engine's view of the data (TRD §5.1): history, frozen recommendations, stall
 * choices and PRs. Training never uses AI (PRD §7.12).
 */
@Singleton
class ProgressionRepository @Inject constructor(
    private val db: AppDatabase,
    private val workoutDao: WorkoutDao,
    private val exerciseDao: ExerciseDao,
    private val routineDao: RoutineDao,
    private val prefs: SettingsPrefs,
    private val nutritionDao: NutritionDao,
    private val days: DayBoundary,
    private val body: BodyRepository,
) {
    /** Completed exposures of an exercise, newest first, as of [asOf] (a session start time). */
    suspend fun history(exerciseId: Long, limit: Int = HISTORY_LIMIT, asOf: Long = Long.MAX_VALUE): List<Exposure> =
        workoutDao.exposureSets(exerciseId, limit, asOf).groupExposures().map { (first, sets) ->
            Exposure(first.startedAt, sets.map { it.set.toLogged() })
        }

    /** Rows arrive grouped by exposure, newest first; keeps that order. */
    private fun List<ExposureSetRow>.groupExposures(): List<Pair<ExposureSetRow, List<ExposureSetRow>>> =
        groupBy { it.set.sessionExerciseId }.values.map { it.first() to it }

    /** [asOf] replays an old workout, so the deficit check uses the target in force then. */
    private suspend fun context(exercise: ExerciseEntity, asOf: Long = Long.MAX_VALUE) = ProgressionContext(
        experience = ExperiencePolicy.defaultFor(prefs.currentTraining().experience),
        inDeficit = inDeficit(if (asOf == Long.MAX_VALUE) System.currentTimeMillis() else asOf),
        stallReset = exercise.stallResetAt?.let { StallReset(it, exercise.stallResetKeepsBaseline) },
        bodyWeightG = bodyWeightFor(exercise),
    )

    /**
     * WL-09: an assisted lift's effective load is your trend weight minus the assistance. Only assisted
     * lifts read it, and only today's trend, so every session is compared at the same body weight.
     */
    private suspend fun bodyWeightFor(exercise: ExerciseEntity): Int? =
        if (exercise.loadType == LoadType.ASSISTED) trendWeightG() else null

    /** The trend weight in grams (TRD §5.2), or null before the primary source's first weigh-in. */
    suspend fun trendWeightG(): Int? = body.trendKg()?.let { (it * 1000).roundToInt() }

    /** The trend weight in grams as it stood at [at] (a session's end), for rebuilding assisted PRs (WL-10). */
    suspend fun trendWeightGAt(at: Long): Int? = body.trendKgAt(at)?.let { (it * 1000).roundToInt() }

    /** TRD §5.1: the target in force at [now] against the TDEE estimate stored with it. False until there's a target. */
    suspend fun inDeficit(now: Long = System.currentTimeMillis()): Boolean =
        nutritionDao.targetOn(days.of(now))?.let { EnergyBalance.inDeficit(it.kcal, it.tdeeEstimate) } ?: false

    /** [asOf] limits history to sessions started by then, e.g. to replay what an old workout implied. */
    suspend fun recommend(exercise: ExerciseEntity, targetSets: Int, repMin: Int, repMax: Int, asOf: Long = Long.MAX_VALUE): Recommendation {
        val past = history(exercise.id, asOf = asOf)
        val rec = ProgressionEngine.recommend(
            ExerciseConfig(repMin, repMax, targetSets, exercise.incrementG, exercise.loadType),
            past,
            context(exercise, asOf),
        )
        return onGrid(rec, past.firstOrNull()?.topLoadG(exercise.loadType), exercise.incrementG, currentUnits())
    }

    /** The persona's units (PR-05): targets snap to the lb grid in lb, and screens format loads in them. */
    suspend fun currentUnits(): Units = prefs.currentPersona().units

    val units: Flow<Units> = prefs.persona.map { it.units }.distinctUntilChanged()

    // Stall choices (PO-05). Both reset stall detection from the start of today's session.

    /** *Deload 10%*: today's target drops to 90% at the bottom of the range. */
    suspend fun deloadToday(sessionExerciseId: Long) = db.withTransaction {
        val row = workoutDao.sessionExercise(sessionExerciseId) ?: return@withTransaction
        val session = workoutDao.session(row.sessionId) ?: return@withTransaction
        val exercise = exerciseDao.get(row.exerciseId) ?: return@withTransaction
        val top = history(exercise.id, limit = 1).firstOrNull()?.topLoadG(exercise.loadType) ?: return@withTransaction
        val load = ProgressionEngine.deloadLoad(exercise.loadType, top, exercise.incrementG, bodyWeightFor(exercise))
        val deload = onGrid(
            Recommendation(Action.DELOAD, load, List(row.targetSets) { row.repMin }, assisted = exercise.loadType == LoadType.ASSISTED),
            top, exercise.incrementG, currentUnits(),
        )
        workoutDao.setRecommendation(row.id, encode(deload))
        exerciseDao.update(exercise.copy(stallResetAt = session.startedAt - 1, stallResetKeepsBaseline = false))
    }

    /**
     * M11: a deload marked for the next workout (`deload_pending`, from the weekly programme review or a scheduled
     * deload week) becomes this workout's target: the stall card's *Deload 10%*, 90% at the bottom of the range. Nothing
     * is written here: the mark is used up only when the workout is finished ([consumePendingDeloads]), so a workout
     * you start and discard leaves it waiting. Null when there's no mark, or no history to deload from.
     */
    suspend fun pendingDeload(exerciseId: Long, targetSets: Int, repMin: Int): Recommendation? {
        val exercise = exerciseDao.get(exerciseId)?.takeIf { it.deloadPending } ?: return null
        val top = history(exercise.id, limit = 1).firstOrNull()?.topLoadG(exercise.loadType) ?: return null
        val load = ProgressionEngine.deloadLoad(exercise.loadType, top, exercise.incrementG, bodyWeightFor(exercise))
        return onGrid(
            Recommendation(Action.DELOAD, load, List(targetSets) { repMin }, assisted = exercise.loadType == LoadType.ASSISTED),
            top, exercise.incrementG, currentUnits(),
        )
    }

    /**
     * At *Finish*: each exercise of the workout that had a deload waiting has taken it, once. A deload target resets
     * stall detection from the workout's start, as the stall card's *Deload* does; an exercise that had no history to
     * deload from just loses the mark.
     */
    suspend fun consumePendingDeloads(sessionId: Long) {
        val session = workoutDao.session(sessionId) ?: return
        workoutDao.sessionExercises(sessionId).forEach { row ->
            val exercise = exerciseDao.get(row.exercise.id)?.takeIf { it.deloadPending } ?: return@forEach
            val deloaded = decode(row.entry.recommendationJson)?.action == Action.DELOAD
            exerciseDao.update(
                if (deloaded) exercise.copy(deloadPending = false, stallResetAt = session.startedAt - 1, stallResetKeepsBaseline = false)
                else exercise.copy(deloadPending = false),
            )
        }
    }

    /** *Hold*: quiet until another full stall window passes with no gain over today's best. */
    suspend fun holdStall(sessionExerciseId: Long) = db.withTransaction {
        val row = workoutDao.sessionExercise(sessionExerciseId) ?: return@withTransaction
        val session = workoutDao.session(row.sessionId) ?: return@withTransaction
        val exercise = exerciseDao.get(row.exerciseId) ?: return@withTransaction
        decode(row.recommendationJson)?.let { workoutDao.setRecommendation(row.id, encode(it.copy(stalled = false))) }
        exerciseDao.update(exercise.copy(stallResetAt = session.startedAt - 1, stallResetKeepsBaseline = true))
    }

    // PRs (PO-04)

    /**
     * Finds and stores the session's PRs. Runs while the session is still ACTIVE, so the history it
     * compares against is every earlier completed session.
     */
    suspend fun recordPrs(sessionId: Long, now: Long) {
        val records = workoutDao.sessionExercises(sessionId).flatMap { row ->
            val done = row.sets.filter { it.completedAt != null }.sortedBy { it.setIndex }.map { SessionSet(it.id, it.toLogged()) }
            if (done.isEmpty()) return@flatMap emptyList()
            PersonalRecords.detect(done, history(row.exercise.id, limit = PR_HISTORY_LIMIT), row.exercise.loadType, bodyWeightFor(row.exercise)).map {
                PersonalRecordEntity(
                    exerciseId = row.exercise.id, sessionId = sessionId, type = it.type, value = it.value,
                    loadG = it.loadG, setEntryId = it.setId, achievedAt = now,
                )
            }
        }
        if (records.isNotEmpty()) workoutDao.insertRecords(records)
    }

    fun observeSessionRecords(sessionId: Long) = workoutDao.observeSessionRecords(sessionId)

    // Previews and history

    /** Today's next-workout card (PRD §7.5): each exercise with its target, ↑ where the weight goes up. */
    suspend fun preview(routineId: Long): List<PreviewLine> = routineDao.exercises(routineId).map { row ->
        val rec = recommend(row.exercise, row.entry.targetSets, row.entry.repMin, row.entry.repMax)
        PreviewLine(row.exercise.name, rec)
    }

    /**
     * HM-02: every past session of an exercise, newest first, in two queries. An assisted lift's e1RM
     * uses your trend weight minus the assistance, and is hidden before the first weigh-in (WL-09).
     */
    suspend fun exerciseHistory(exerciseId: Long): List<HistoryEntry> {
        val exercise = exerciseDao.get(exerciseId)
        val loadType = exercise?.loadType ?: LoadType.WEIGHTED
        val bodyWeight = exercise?.let { bodyWeightFor(it) }
        val records = workoutDao.exerciseRecords(exerciseId).groupBy({ it.sessionId }, { it.type })
        return workoutDao.exposureSets(exerciseId, limit = FULL_HISTORY_LIMIT).groupExposures().map { (first, rows) ->
            val sets = rows.map { it.set.toLogged() }
            val lifted = Loads.lifted(sets, loadType, bodyWeight)
            HistoryEntry(
                sessionId = first.sessionId,
                date = first.logDate,
                routineName = first.routineName,
                sets = sets,
                loadType = loadType,
                bestE1rmG = lifted.filter { it.type != SetType.WARMUP && (it.loadG ?: 0) > 0 && it.reps in 1..ProgressionEngine.E1RM_MAX_REPS }
                    .maxOfOrNull { ProgressionEngine.e1rmG(it.loadG!!, it.reps) }?.toInt(),
                records = records[first.sessionId].orEmpty(),
            )
        }
    }

    /** PO-09: every completed session of an exercise, for its chart. */
    suspend fun chartExposures(exerciseId: Long): List<DatedExposure> =
        workoutDao.exposureSets(exerciseId, limit = FULL_HISTORY_LIMIT).groupExposures().map { (first, rows) ->
            DatedExposure(first.logDate, rows.map { it.set.toLogged() })
        }

    @Serializable
    private data class RecommendationJson(
        val action: Action,
        val loadG: Int? = null,
        val reps: List<Int>,
        val stalled: Boolean = false,
        val harderVariation: Boolean = false,
        val steps: Int = 1,
        val assisted: Boolean = false,
    )

    companion object {
        const val HISTORY_LIMIT = 30
        const val PR_HISTORY_LIMIT = 500
        const val FULL_HISTORY_LIMIT = 500
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * PRD §12.16: in lb, a target lands on the exercise's lb grid before it's frozen, so typing what's shown hits it
         * exactly. An increase goes to the grid point nearest the engine's load that's still past what you lifted ([topG]):
         * heavier, or for an assisted lift less assistance. A deload rounds to the easier side (lighter, or more
         * assistance). A repeat or added rep keeps what you lifted, to the nearest 0.01 lb. In kg nothing changes.
         */
        fun onGrid(rec: Recommendation, topG: Int?, incrementG: Int, units: Units): Recommendation {
            val load = rec.loadG ?: return rec
            if (units == Units.METRIC) return rec
            val snapped = when (rec.action) {
                Action.INCREASE -> when {
                    topG == null -> LoadUnits.snap(load, units)
                    rec.assisted -> lessAssistance(topG, load, incrementG, units)
                    else -> LoadUnits.increase(topG, load, incrementG, units)
                }
                Action.DELOAD -> LoadUnits.toGrid(load, incrementG, units, if (rec.assisted) RoundingMode.UP else RoundingMode.DOWN)
                else -> LoadUnits.snap(load, units)
            }
            return rec.copy(loadG = snapped)
        }

        /** [LoadUnits.increase] for assistance, which goes down: the grid point nearest [targetG] below [topG], never under 0. */
        private fun lessAssistance(topG: Int, targetG: Int, incrementG: Int, units: Units): Int {
            if (targetG <= 0 || incrementG <= 0) return LoadUnits.snap(targetG, units)
            val near = LoadUnits.toGrid(targetG, incrementG, units, RoundingMode.HALF_UP)
            if (near < topG) return near
            val down = LoadUnits.step(LoadUnits.toGrid(topG, incrementG, units, RoundingMode.UP), incrementG, up = false, units)
            return if (down < topG) down else LoadUnits.step(down, incrementG, up = false, units)
        }

        /** `session_exercise.recommendation_json` (TRD §5.1). */
        fun encode(rec: Recommendation): String =
            json.encodeToString(RecommendationJson(rec.action, rec.loadG, rec.reps, rec.stalled, rec.harderVariation, rec.steps, rec.assisted))

        fun decode(text: String?): Recommendation? = text?.let {
            runCatching { json.decodeFromString<RecommendationJson>(it) }.getOrNull()
                ?.let { r -> Recommendation(r.action, r.loadG, r.reps, r.stalled, r.harderVariation, r.steps, r.assisted) }
        }
    }
}
