package app.recomp.data.repo

import androidx.room.withTransaction
import app.recomp.data.db.AppDatabase
import app.recomp.data.db.ExerciseDao
import app.recomp.data.db.ExerciseEntity
import app.recomp.data.db.ExposureSetRow
import app.recomp.data.db.PersonalRecordEntity
import app.recomp.data.db.RoutineDao
import app.recomp.data.db.WorkoutDao
import app.recomp.data.prefs.TrainingPrefs
import app.recomp.domain.model.SetType
import app.recomp.domain.persona.ExperiencePolicy
import app.recomp.domain.progression.Action
import app.recomp.domain.progression.ExerciseConfig
import app.recomp.domain.progression.Exposure
import app.recomp.domain.progression.PersonalRecords
import app.recomp.domain.progression.PrType
import app.recomp.domain.progression.ProgressionContext
import app.recomp.domain.progression.ProgressionEngine
import app.recomp.domain.progression.Recommendation
import app.recomp.domain.progression.SessionSet
import app.recomp.domain.progression.StallReset
import app.recomp.domain.progression.Targets
import app.recomp.domain.training.Loads
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One line of Today's preview (PRD §7.5): "Bench press 42.5 × 8 ↑". */
data class PreviewLine(val name: String, val target: String, val weightUp: Boolean)

/** One past session of an exercise (HM-02). */
data class HistoryEntry(
    val sessionId: Long,
    val date: LocalDate,
    val routineName: String?,
    val sets: String,
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
    private val prefs: TrainingPrefs,
) {
    /** Completed exposures of an exercise, newest first, as of [asOf] (a session start time). */
    suspend fun history(exerciseId: Long, limit: Int = HISTORY_LIMIT, asOf: Long = Long.MAX_VALUE): List<Exposure> =
        workoutDao.exposureSets(exerciseId, limit, asOf).groupExposures().map { (first, sets) ->
            Exposure(first.startedAt, sets.map { it.set.toLogged() })
        }

    /** Rows arrive grouped by exposure, newest first; keeps that order. */
    private fun List<ExposureSetRow>.groupExposures(): List<Pair<ExposureSetRow, List<ExposureSetRow>>> =
        groupBy { it.set.sessionExerciseId }.values.map { it.first() to it }

    private suspend fun context(exercise: ExerciseEntity) = ProgressionContext(
        experience = ExperiencePolicy.defaultFor(prefs.current().experience),
        stallReset = exercise.stallResetAt?.let { StallReset(it, exercise.stallResetKeepsBaseline) },
    )

    /** [asOf] limits history to sessions started by then, e.g. to replay what an old workout implied. */
    suspend fun recommend(exercise: ExerciseEntity, targetSets: Int, repMin: Int, repMax: Int, asOf: Long = Long.MAX_VALUE): Recommendation =
        ProgressionEngine.recommend(
            ExerciseConfig(repMin, repMax, targetSets, exercise.incrementG, exercise.loadType),
            history(exercise.id, asOf = asOf),
            context(exercise),
        )

    // Stall choices (PO-05). Both reset stall detection from the start of today's session.

    /** *Deload 10%*: today's target drops to 90% at the bottom of the range. */
    suspend fun deloadToday(sessionExerciseId: Long) = db.withTransaction {
        val row = workoutDao.sessionExercise(sessionExerciseId) ?: return@withTransaction
        val session = workoutDao.session(row.sessionId) ?: return@withTransaction
        val exercise = exerciseDao.get(row.exerciseId) ?: return@withTransaction
        val top = history(exercise.id, limit = 1).firstOrNull()?.topLoadG ?: return@withTransaction
        val deload = Recommendation(Action.DELOAD, ProgressionEngine.deloadLoad(top, exercise.incrementG), List(row.targetSets) { row.repMin })
        workoutDao.setRecommendation(row.id, encode(deload))
        exerciseDao.update(exercise.copy(stallResetAt = session.startedAt - 1, stallResetKeepsBaseline = false))
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
            PersonalRecords.detect(done, history(row.exercise.id, limit = PR_HISTORY_LIMIT)).map {
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
        PreviewLine(row.exercise.name, if (rec.action == Action.BASELINE) "first time" else Targets.describe(rec), rec.action == Action.INCREASE && rec.loadG != null)
    }

    /** HM-02: every past session of an exercise, newest first, in two queries. */
    suspend fun exerciseHistory(exerciseId: Long): List<HistoryEntry> {
        val records = workoutDao.exerciseRecords(exerciseId).groupBy({ it.sessionId }, { it.type })
        return workoutDao.exposureSets(exerciseId, limit = FULL_HISTORY_LIMIT).groupExposures().map { (first, rows) ->
            val sets = rows.map { it.set.toLogged() }
            HistoryEntry(
                sessionId = first.sessionId,
                date = first.logDate,
                routineName = first.routineName,
                sets = Loads.describe(sets),
                bestE1rmG = sets.filter { it.type != SetType.WARMUP && (it.loadG ?: 0) > 0 && it.reps in 1..ProgressionEngine.E1RM_MAX_REPS }
                    .maxOfOrNull { ProgressionEngine.e1rmG(it.loadG!!, it.reps) }?.toInt(),
                records = records[first.sessionId].orEmpty(),
            )
        }
    }

    @Serializable
    private data class RecommendationJson(
        val action: Action,
        val loadG: Int? = null,
        val reps: List<Int>,
        val stalled: Boolean = false,
        val harderVariation: Boolean = false,
    )

    companion object {
        const val HISTORY_LIMIT = 30
        const val PR_HISTORY_LIMIT = 500
        const val FULL_HISTORY_LIMIT = 500
        private val json = Json { ignoreUnknownKeys = true }

        /** `session_exercise.recommendation_json` (TRD §5.1). */
        fun encode(rec: Recommendation): String =
            json.encodeToString(RecommendationJson(rec.action, rec.loadG, rec.reps, rec.stalled, rec.harderVariation))

        fun decode(text: String?): Recommendation? = text?.let {
            runCatching { json.decodeFromString<RecommendationJson>(it) }.getOrNull()
                ?.let { r -> Recommendation(r.action, r.loadG, r.reps, r.stalled, r.harderVariation) }
        }
    }
}
