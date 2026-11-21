package app.recomp.data.repo

import androidx.room.withTransaction
import app.recomp.data.ai.CoachSchema
import app.recomp.data.db.AppDatabase
import app.recomp.data.db.ExerciseDao
import app.recomp.data.db.NutritionDao
import app.recomp.data.db.ProgrammeReviewDao
import app.recomp.data.db.ProgrammeReviewEntity
import app.recomp.data.db.RoutineDao
import app.recomp.data.db.RoutineExerciseEntity
import app.recomp.data.db.TargetReason
import app.recomp.data.prefs.DayBoundary
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.domain.ai.AiState
import app.recomp.domain.ai.AiStatus
import app.recomp.domain.checkin.CheckinProposal
import app.recomp.domain.coach.BodyFacts
import app.recomp.domain.coach.Change
import app.recomp.domain.coach.CheckContext
import app.recomp.domain.coach.CoachPrompt
import app.recomp.domain.coach.CoachReply
import app.recomp.domain.coach.DeloadSchedule
import app.recomp.domain.coach.FoodFacts
import app.recomp.domain.coach.GoalFacts
import app.recomp.domain.coach.LibraryLine
import app.recomp.domain.coach.MuscleSets
import app.recomp.domain.coach.ProgrammeFacts
import app.recomp.domain.coach.ProgrammeReport
import app.recomp.domain.coach.ProgrammeReports
import app.recomp.domain.coach.ReportSections
import app.recomp.domain.coach.RoutineFacts
import app.recomp.domain.coach.RoutineLine
import app.recomp.domain.coach.Suggestion
import app.recomp.domain.coach.SuggestionCheck
import app.recomp.domain.coach.TargetTerms
import app.recomp.domain.coach.TrainingFacts
import app.recomp.domain.model.SessionKind
import app.recomp.domain.nutrition.GoalPolicy
import app.recomp.domain.nutrition.NutritionCalculator
import app.recomp.domain.nutrition.TargetInputs
import app.recomp.domain.persona.ExperiencePolicy
import app.recomp.domain.persona.FinishLine
import app.recomp.domain.persona.GoalPlan
import app.recomp.domain.persona.Profile
import app.recomp.domain.persona.Guardrails
import app.recomp.domain.progression.Action
import app.recomp.domain.training.WeeklyPlan
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import app.recomp.data.prefs.TrainingSettings
import app.recomp.domain.units.UnitStyle
import app.recomp.domain.units.UnitMath

/** A review as its screen shows it: what was sent, and the reply's suggestions checked against the app as it is now. */
data class ProgrammeReviewView(
    val row: ProgrammeReviewEntity,
    val summary: String?,
    val suggestions: List<Suggestion>,
    val applied: Set<Int>,
    /** The report's refs, to name exercises and routines in the suggestions. */
    val names: Map<Long, String>,
    val routineNames: Map<Long, String>,
)

/** How a send ended: a stored review, or why nothing was sent or nothing usable came back. */
sealed interface ReviewSendResult {
    data class Done(val reviewId: Long) : ReviewSendResult
    data class NotReady(val state: AiState) : ReviewSendResult
    /** The request was logged and costed; [reviewId] keeps what was sent. */
    data class Failed(val status: AiStatus, val reviewId: Long?) : ReviewSendResult
}

/** What *Undo* puts back after an applied suggestion. */
sealed interface ReviewUndo {
    data class Routine(val reviewId: Long, val index: Int, val routineId: Long, val rows: List<RoutineExerciseEntity>) : ReviewUndo
    data class StrengthDays(val reviewId: Long, val index: Int, val days: Int) : ReviewUndo
    /** [weekBefore] is the last deload week before a whole-week deload set it, which *Undo* puts back. */
    data class Deload(val reviewId: Long, val index: Int, val exerciseIds: List<Long>, val setWeek: Boolean = false, val weekBefore: LocalDate? = null) : ReviewUndo
}

/**
 * The weekly programme review with AI (HM-06, PRD §12.7, TRD §10 M11): the report from M8's aggregates, the request
 * through M7's providers, its history, and *Apply* through the existing paths. Nothing changes without a tap, and the
 * progression engine stays rules: a deload is only a mark the next workout turns into the stall card's deload.
 * Also the scheduled deload week (PRD §4.3), which needs no AI.
 */
@Singleton
class ProgrammeReviewRepository @Inject constructor(
    private val db: AppDatabase,
    private val dao: ProgrammeReviewDao,
    private val exerciseDao: ExerciseDao,
    private val routineDao: RoutineDao,
    private val nutritionDao: NutritionDao,
    private val prefs: SettingsPrefs,
    private val days: DayBoundary,
    private val personas: PersonaRepository,
    private val training: TrainingRepository,
    private val progression: ProgressionRepository,
    private val reviews: ReviewRepository,
    private val ai: AiRepository,
) {
    fun observeHistory(): Flow<List<ProgrammeReviewEntity>> = dao.observeAll()

    // The report

    /** What the report is built from: the week's aggregates, the routines, the library and the targets. Nothing personal. */
    suspend fun facts(weekStart: LocalDate, today: LocalDate? = null): ProgrammeFacts {
        val day = today ?: days.today()
        val week = reviews.week(weekStart, day)
        return ProgrammeFacts(goal = goalFacts(day), food = foodFacts(week), training = trainingFacts(weekStart, week), body = bodyFacts(week, reportStyle()))
    }

    suspend fun report(weekStart: LocalDate, sections: ReportSections, today: LocalDate? = null): ProgrammeReport =
        ProgrammeReports.build(facts(weekStart, today), sections, reportStyle())

    /** The report is in the person's units (PR-05), with a "." decimal mark for the AI. */
    private suspend fun reportStyle(): UnitStyle = UnitStyle(prefs.currentPersona().units)

    private suspend fun plan(day: LocalDate): GoalPlan? {
        val stored = prefs.currentPersona().goal?.toPlan() ?: return null
        val profile = personas.currentProfile() ?: return null
        return Guardrails.apply(profile, stored, day).plan
    }

    /**
     * GL-09 (M19): while pregnant or breastfeeding, an estimate below the formula's TDEE is never maintenance, in the
     * report or the check of its suggestions, so no suggestion can land under it.
     */
    private suspend fun maintenanceFor(profile: Profile, plan: GoalPlan, day: LocalDate, estimate: Double): Double =
        if (profile.pregnant) maxOf(estimate, NutritionCalculator.startingTargets(TargetInputs.from(profile, plan, day, personas.leanMassKg(day))).tdeeKcal) else estimate

    private suspend fun goalFacts(day: LocalDate): GoalFacts? {
        val plan = plan(day) ?: return null
        val target = nutritionDao.targetOn(day) ?: return null
        val profile = personas.currentProfile() ?: return null
        val maintenance = maintenanceFor(profile, plan, day, personas.maintenance()?.kcal?.toDouble() ?: target.tdeeEstimate.toDouble())
        return GoalFacts(
            goal = plan.goal,
            pace = plan.pace?.name?.lowercase()?.replaceFirstChar { it.uppercase() },
            finishLine = when (plan.finishLine) {
                null -> null
                FinishLine.GoalWeight -> "goal weight"
                is FinishLine.ByDate -> "a date"
                is FinishLine.BodyFat -> "body fat %"
            },
            kcal = target.kcal,
            proteinG = target.proteinG,
            maintenanceKcal = maintenance,
            referenceKg = plan.goalWeightKg ?: profile.weightKg,
        )
    }

    private fun foodFacts(week: WeekReview): FoodFacts? {
        val avg = week.food.average ?: return FoodFacts(0, 0, 0, 0, 0, 0, 0, null, null)
        return FoodFacts(
            daysLogged = avg.daysLogged,
            kcal = avg.kcal,
            kcalTarget = avg.kcalTarget,
            proteinG = avg.proteinG,
            proteinTargetG = avg.proteinTargetG,
            proteinHit = week.food.protein.hit,
            proteinCounted = week.food.protein.counted,
            orderedPct = week.food.origins?.orderedPct,
            packagedPct = week.food.origins?.packagedPct,
        )
    }

    private suspend fun trainingFacts(weekStart: LocalDate, week: WeekReview): TrainingFacts {
        val settings = prefs.currentTraining()
        val routines = routineDao.rotation().map { r ->
            RoutineFacts(
                r.id,
                r.name,
                routineDao.exercises(r.id).map { row ->
                    val rec = progression.recommend(row.exercise, row.entry.targetSets, row.entry.repMin, row.entry.repMax)
                    val load = rec.loadG?.takeIf { rec.action != Action.BASELINE }?.let { kg(it) + if (rec.assisted) " assistance" else "" }
                    RoutineLine(row.exercise.id, row.exercise.name, row.entry.targetSets, row.entry.repMin, row.entry.repMax, load)
                },
            )
        }
        val t = week.training
        val sessions = (listOf("strength ${t.strengthDays}" + (t.strengthTarget?.let { " of $it" } ?: "") + " days") +
            t.extras.map { e -> "${e.name} ${e.done}" + (e.planned?.takeIf { it > 0 }?.let { " of $it" } ?: "") }).joinToString(", ")
        val records = t.records.groupBy { it.exercise }.map { (name, prs) -> "$name (" + prs.map { it.type.name.lowercase().replace('_', ' ') }.distinct().joinToString(", ") + ")" }
        val sets = MuscleSets.weekly(dao.workingSets(weekStart, weekStart.plusDays(6)).map { MuscleSets.Work(it.primaryMuscle, it.secondaryMuscles, it.sets) })
        return TrainingFacts(
            experience = settings.experience.name.lowercase(),
            split = settings.split?.label ?: "custom",
            strengthDaysPerWeek = settings.strengthDaysPerWeek,
            routines = routines,
            sessions = sessions,
            records = records,
            stalled = t.stalled,
            setsPerMuscle = sets,
            library = exerciseDao.observeActive().first().map { LibraryLine(it.id, it.name, it.primaryMuscle) },
        )
    }

    private fun bodyFacts(week: WeekReview, style: UnitStyle): BodyFacts {
        val b = week.body
        val scan = b.scan?.change?.let { c ->
            "muscle ${signed(UnitMath.weightOf(c.muscleKg, style.units))} ${style.weight}" + (if (c.muscleWithinNoise) " (within noise)" else "") +
                ", body fat ${signed(c.bodyFatPct)} points" + (if (c.bodyFatWithinNoise) " (within noise)" else "")
        }
        return BodyFacts(b.changeKg, b.changePct, b.waistChangeCm, listOfNotNull(scan))
    }

    // Sending

    /** Builds the report for [weekStart] as ticked and sends it. The screen sends the report it previewed instead. */
    suspend fun send(weekStart: LocalDate, sections: ReportSections, now: Long = System.currentTimeMillis()): ReviewSendResult =
        send(weekStart, report(weekStart, sections, days.of(now)), now)

    /**
     * Sends [report] exactly as it was previewed, text and refs, even if the week has changed since, and keeps what
     * was sent and what came back. The reply is checked when it's shown, against the app as it is then.
     */
    suspend fun send(weekStart: LocalDate, report: ProgrammeReport, now: Long = System.currentTimeMillis()): ReviewSendResult {
        val sent = ai.coach(CoachPrompt.system(report.terms), report.text, CoachSchema.schema)
        sent.notReady?.let { return ReviewSendResult.NotReady(it) }
        val reply = sent.json?.let { json -> runCatching { CoachSchema.parse(json.toString()) }.getOrNull() }
        if (sent.status == AiStatus.OK && reply == null) sent.requestId?.let { ai.markInvalid(it) }
        val stored = Stored(reply?.let(::StoredReply), report.exercises, report.routines, report.maintenanceKcal, report.referenceKg)
        val id = dao.insert(
            ProgrammeReviewEntity(
                weekStart = weekStart,
                createdAt = now,
                sections = report.sections.key,
                sentText = report.text,
                aiRequestId = sent.requestId,
                responseJson = if (reply != null) json.encodeToString(Stored.serializer(), stored) else null,
            ),
        )
        return if (reply != null) ReviewSendResult.Done(id) else ReviewSendResult.Failed(if (sent.status == AiStatus.OK) AiStatus.INVALID else sent.status, id)
    }

    // Reading back

    suspend fun view(reviewId: Long): ProgrammeReviewView? {
        val row = dao.get(reviewId) ?: return null
        val stored = row.responseJson?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
        val reply = stored?.reply?.toReply()
        val sections = sectionsOf(row.sections)
        val checked = if (reply != null && stored != null) SuggestionCheck.check(reply, context(stored, sections.terms)) else emptyList()
        // An applied suggestion keeps the change it made: checked again, a swap's old exercise is gone and would read
        // as an unknown one.
        val suggestions = checked.mapIndexed { i, s -> stored?.applied?.get(i)?.toChange()?.let { s.copy(change = it, adviceOnly = null) } ?: s }
        val ids = stored?.exercises?.values.orEmpty()
        return ProgrammeReviewView(
            row = row,
            summary = reply?.summary,
            suggestions = suggestions,
            applied = applied(row),
            names = ids.mapNotNull { id -> exerciseDao.get(id)?.let { id to it.name } }.toMap(),
            routineNames = stored?.routines?.values.orEmpty().mapNotNull { id -> routineDao.get(id)?.let { id to it.name } }.toMap(),
        )
    }

    private suspend fun context(stored: Stored, terms: TargetTerms): CheckContext {
        val day = days.today()
        val profile = personas.currentProfile()
        val plan = plan(day)
        val estimate = personas.maintenance()?.kcal?.toDouble() ?: nutritionDao.targetOn(day)?.tdeeEstimate?.toDouble() ?: 0.0
        val maintenance = if (profile != null && plan != null) maintenanceFor(profile, plan, day, estimate) else estimate
        // A reply's % and g/kg mean the report's numbers; the guardrails are today's.
        val routineIds = stored.routines.values.toSet()
        val kcalRange = if (profile != null && plan != null && maintenance > 0) {
            val range = CheckinProposal.adjustRange(TargetInputs.from(profile, plan, day, personas.leanMassKg(day)), maintenance)
            // PRD §4.9: below BMI 18.5 there's no deficit at all, and under 18 none deeper than the plan's own.
            val noDeficit = !Guardrails.deficitAllowed(profile)
            val minorFloor = if (Guardrails.isMinor(profile, day)) nutritionDao.targetOn(day)?.kcal else null
            val low = when {
                noDeficit -> max(range.first, roundUp10(maintenance))
                minorFloor != null -> max(range.first, minorFloor.coerceAtMost(roundUp10(maintenance)))
                else -> range.first
            }
            low..max(low, range.last)
        } else {
            IntRange.EMPTY
        }
        return CheckContext(
            terms = terms,
            exerciseRefs = stored.exercises,
            routineRefs = stored.routines.filterValues { id -> routineDao.get(id)?.archived == false },
            routineExercises = routineIds.associateWith { id -> routineDao.exercises(id).map { it.entry.exerciseId } },
            library = exerciseDao.observeActive().first().map { it.id }.toSet(),
            maintenanceKcal = stored.maintenanceKcal ?: maintenance,
            referenceKg = stored.referenceKg ?: plan?.goalWeightKg ?: profile?.weightKg ?: 0.0,
            kcalRange = kcalRange,
            proteinGPerKg = plan?.goal?.let(GoalPolicy::proteinRange) ?: 0.0..0.0,
        )
    }

    // Applying

    /** A target suggestion's before and after, from Settings' own preview (PS-02), shown before it's saved. */
    suspend fun previewTarget(change: Change): TargetPreview? {
        val current = prefs.overrides.first()
        val overrides = when (change) {
            is Change.Calories -> current.copy(kcal = change.kcal)
            is Change.Protein -> current.copy(proteinG = change.grams)
            else -> return null
        }
        return personas.preview(overrides = overrides)
    }

    /**
     * Applies suggestion [index] of review [reviewId] through the usual path, and records it. Returns what *Undo* puts
     * back, or null for a target change (a new target version, undone like any other) or when it no longer applies.
     */
    suspend fun apply(reviewId: Long, index: Int, now: Long = System.currentTimeMillis()): ReviewUndo? = applying.withLock {
        val view = view(reviewId) ?: return@withLock null
        // Once only, however fast the taps.
        if (index in view.applied) return@withLock null
        val change = view.suggestions.getOrNull(index)?.change ?: return@withLock null
        val undo: ReviewUndo? = when (change) {
            is Change.Calories -> {
                personas.save(overrides = prefs.overrides.first().copy(kcal = change.kcal), now = now, reason = TargetReason.REVIEW)
                null
            }
            is Change.Protein -> {
                personas.save(overrides = prefs.overrides.first().copy(proteinG = change.grams), now = now, reason = TargetReason.REVIEW)
                null
            }
            is Change.Swap -> routineChange(reviewId, index, change.routineId) { rows ->
                val replacement = exerciseDao.get(change.replacementId) ?: return@routineChange rows
                rows.map { if (it.exerciseId == change.exerciseId) training.planRowFor(replacement, it.targetSets).copy(scheme = it.scheme, supersetGroup = it.supersetGroup) else it }
            }
            is Change.Add -> routineChange(reviewId, index, change.routineId) { rows ->
                val exercise = exerciseDao.get(change.exerciseId) ?: return@routineChange rows
                rows + training.planRowFor(exercise).copy(routineId = change.routineId, position = rows.size)
            }
            is Change.Remove -> routineChange(reviewId, index, change.routineId) { rows -> rows.filter { it.exerciseId != change.exerciseId } }
            is Change.Sets -> routineChange(reviewId, index, change.routineId) { rows ->
                rows.map { if (it.exerciseId == change.exerciseId) it.copy(targetSets = change.sets) else it }
            }
            is Change.RepRange -> routineChange(reviewId, index, change.routineId) { rows ->
                rows.map { if (it.exerciseId == change.exerciseId) it.copy(repMin = change.min, repMax = change.max) else it }
            }
            is Change.StrengthDays -> {
                val before = prefs.currentTraining().strengthDaysPerWeek
                prefs.updatePersona { it.copy(strengthDaysPerWeek = change.days) }
                ReviewUndo.StrengthDays(reviewId, index, before)
            }
            is Change.DeloadExercise -> deload(reviewId, index, listOf(change.exerciseId))
            Change.DeloadAll -> {
                // A deload week from the review counts for the scheduled card too.
                val before = prefs.deloadWeek.first()
                prefs.setDeloadWeek(WeeklyPlan.weekStart(days.of(now)))
                (deload(reviewId, index, dao.rotationExercises()) as ReviewUndo.Deload).copy(setWeek = true, weekBefore = before)
            }
        }
        markApplied(reviewId, index, change)
        undo
    }

    /** Serializes *Apply* and *Undo*, so two taps can't both read a routine before either writes it. */
    private val applying = Mutex()

    private suspend fun routineChange(reviewId: Long, index: Int, routineId: Long, edit: suspend (List<RoutineExerciseEntity>) -> List<RoutineExerciseEntity>): ReviewUndo {
        val before = routineDao.exercises(routineId).map { it.entry }
        training.saveRoutineExercises(routineId, edit(before).mapIndexed { i, row -> row.copy(id = 0, position = i, routineId = routineId) })
        return ReviewUndo.Routine(reviewId, index, routineId, before)
    }

    private suspend fun deload(reviewId: Long, index: Int, ids: List<Long>): ReviewUndo {
        val already = dao.pendingDeloads().toSet()
        val marked = ids.filter { it !in already }
        if (marked.isNotEmpty()) dao.markDeload(marked)
        return ReviewUndo.Deload(reviewId, index, marked)
    }

    /** Puts back what [undo] says and unmarks the suggestion as applied. */
    suspend fun undo(undo: ReviewUndo) = applying.withLock {
        when (undo) {
            is ReviewUndo.Routine -> {
                training.saveRoutineExercises(undo.routineId, undo.rows.map { it.copy(id = 0) })
                markApplied(undo.reviewId, undo.index, null)
            }
            is ReviewUndo.StrengthDays -> {
                prefs.updatePersona { it.copy(strengthDaysPerWeek = undo.days) }
                markApplied(undo.reviewId, undo.index, null)
            }
            is ReviewUndo.Deload -> {
                if (undo.exerciseIds.isNotEmpty()) dao.clearDeload(undo.exerciseIds)
                if (undo.setWeek) prefs.restoreDeloadWeek(undo.weekBefore)
                markApplied(undo.reviewId, undo.index, null)
            }
        }
    }

    /** Records suggestion [index] as applied with the [change] it made, or (null) as undone. */
    private suspend fun markApplied(reviewId: Long, index: Int, change: Change?) = db.withTransaction {
        val row = dao.get(reviewId) ?: return@withTransaction
        val applied = applied(row).let { if (change != null) it + index else it - index }
        val stored = row.responseJson?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
        val updated = stored?.copy(applied = if (change != null) stored.applied + (index to StoredChange.of(change)) else stored.applied - index)
        dao.update(
            row.copy(
                appliedJson = json.encodeToString(appliedSerializer, applied.sorted()),
                responseJson = updated?.let { json.encodeToString(Stored.serializer(), it) } ?: row.responseJson,
            ),
        )
    }

    private fun applied(row: ProgrammeReviewEntity): Set<Int> =
        runCatching { json.decodeFromString(appliedSerializer, row.appliedJson).toSet() }.getOrDefault(emptySet())

    // The scheduled deload week (PRD §4.3)

    /** Whether Train offers a deload week today, and how many weeks it's been. Null when it doesn't. */
    fun observeDeloadDue(): Flow<Long?> =
        combine(prefs.training, prefs.deloadWeek, prefs.deloadSnoozedUntil, days.observeToday()) { t: TrainingSettings, week: LocalDate?, snoozed: LocalDate?, today: LocalDate ->
            val since: LocalDate? = week ?: dao.firstStrengthDay()
            val policy = ExperiencePolicy.defaultFor(t.experience)
            if (since != null && DeloadSchedule.due(policy, since, today, snoozed)) DeloadSchedule.weeksSince(since, today) else null
        }

    /** *Deload this week*: every exercise in the rotation is marked, like the review's *Deload the whole week*. */
    suspend fun acceptDeloadWeek() {
        val ids = dao.rotationExercises()
        if (ids.isNotEmpty()) dao.markDeload(ids)
        prefs.setDeloadWeek(WeeklyPlan.weekStart(days.today()))
    }

    /** *Not now*: the card waits a week. */
    suspend fun snoozeDeloadWeek() = prefs.setDeloadSnoozedUntil(days.today().plusDays(DeloadSchedule.SNOOZE_DAYS))

    // Storage

    /**
     * What `programme_review.response_json` holds: the reply, with the report's refs it names exercises and routines
     * by, the maintenance and reference weight its relative targets were stated against, and each applied
     * suggestion's change.
     */
    @Serializable
    data class Stored(
        val reply: StoredReply?,
        val exercises: Map<String, Long>,
        val routines: Map<String, Long>,
        val maintenanceKcal: Double? = null,
        val referenceKg: Double? = null,
        val applied: Map<Int, StoredChange> = emptyMap(),
    )

    /** A [Change] as stored: [a] and [b] are the numbers (kcal, grams, sets, reps, days), ids 0 where unused. */
    @Serializable
    data class StoredChange(val kind: String, val routineId: Long = 0, val exerciseId: Long = 0, val replacementId: Long = 0, val a: Int = 0, val b: Int = 0) {
        fun toChange(): Change? = when (kind) {
            "CALORIES" -> Change.Calories(a)
            "PROTEIN" -> Change.Protein(a)
            "SWAP" -> Change.Swap(routineId, exerciseId, replacementId)
            "ADD" -> Change.Add(routineId, exerciseId)
            "REMOVE" -> Change.Remove(routineId, exerciseId)
            "SETS" -> Change.Sets(routineId, exerciseId, a)
            "REP_RANGE" -> Change.RepRange(routineId, exerciseId, a, b)
            "STRENGTH_DAYS" -> Change.StrengthDays(a)
            "DELOAD_EXERCISE" -> Change.DeloadExercise(exerciseId)
            "DELOAD_ALL" -> Change.DeloadAll
            else -> null
        }

        companion object {
            fun of(c: Change): StoredChange = when (c) {
                is Change.Calories -> StoredChange("CALORIES", a = c.kcal)
                is Change.Protein -> StoredChange("PROTEIN", a = c.grams)
                is Change.Swap -> StoredChange("SWAP", c.routineId, c.exerciseId, c.replacementId)
                is Change.Add -> StoredChange("ADD", c.routineId, c.exerciseId)
                is Change.Remove -> StoredChange("REMOVE", c.routineId, c.exerciseId)
                is Change.Sets -> StoredChange("SETS", c.routineId, c.exerciseId, a = c.sets)
                is Change.RepRange -> StoredChange("REP_RANGE", c.routineId, c.exerciseId, a = c.min, b = c.max)
                is Change.StrengthDays -> StoredChange("STRENGTH_DAYS", a = c.days)
                is Change.DeloadExercise -> StoredChange("DELOAD_EXERCISE", exerciseId = c.exerciseId)
                Change.DeloadAll -> StoredChange("DELOAD_ALL")
            }
        }
    }

    @Serializable
    data class StoredSuggestion(val kind: String, val routineRef: String, val exerciseRef: String, val replacementRef: String, val min: Double, val max: Double, val reason: String)

    @Serializable
    data class StoredReply(val summary: String, val suggestions: List<StoredSuggestion>) {
        constructor(r: CoachReply) : this(r.summary, r.suggestions.map { StoredSuggestion(it.kind, it.routineRef, it.exerciseRef, it.replacementRef, it.min, it.max, it.reason) })

        fun toReply() = CoachReply(summary, suggestions.map { app.recomp.domain.coach.RawSuggestion(it.kind, it.routineRef, it.exerciseRef, it.replacementRef, it.min, it.max, it.reason) })
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val appliedSerializer = kotlinx.serialization.builtins.ListSerializer(Int.serializer())

        fun roundUp10(kcal: Double): Int = (Math.ceil(kcal / 10) * 10).toInt()

        fun sectionsOf(key: String): ReportSections {
            val parts = key.split(',').toSet()
            return ReportSections(goal = "goal" in parts, food = "food" in parts, training = "training" in parts, body = "body" in parts)
        }

        fun kg(grams: Int): String = (Math.round(grams / 100.0) / 10.0).toString().removeSuffix(".0") + " kg"

        fun signed(v: Double): String {
            val r = Math.round(v * 10) / 10.0
            return (if (r > 0) "+" else "") + r
        }
    }
}
