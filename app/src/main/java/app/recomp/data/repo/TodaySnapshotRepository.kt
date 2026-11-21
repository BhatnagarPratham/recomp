package app.recomp.data.repo

import app.recomp.data.db.NudgeDao
import app.recomp.data.db.WeeklyCheckinEntity
import app.recomp.data.prefs.DayBoundary
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.domain.food.MealSlot
import app.recomp.domain.model.SessionKind
import app.recomp.domain.nudge.DayNumbers
import app.recomp.domain.nudge.MealClock
import app.recomp.domain.nudge.NudgeFood
import app.recomp.domain.nudge.NudgeLine
import app.recomp.domain.nudge.Nudges
import app.recomp.domain.nudge.Suggestion
import app.recomp.domain.nutrition.GoalPolicy
import app.recomp.domain.nutrition.Intake
import app.recomp.domain.nutrition.MainNumber
import app.recomp.domain.nutrition.TodayNumbers
import app.recomp.domain.training.RestReason
import app.recomp.domain.training.WeeklyPlan
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Today's food against its targets. Null on the snapshot before onboarding has written a target. */
data class SnapshotFood(
    val kcalTarget: Int,
    val kcalEaten: Int,
    /** Null while the protein ramp measures your baseline week. */
    val proteinTargetG: Int?,
    val proteinEatenG: Int,
    /** The goal's main number (GL-07). */
    val main: MainNumber,
)

/**
 * Today at a glance (TRD §10 M12): what the widget, the reminders' skip rules, the notifications' text and
 * Today's evening card all read, so they can't disagree.
 */
data class TodaySnapshot(
    val date: LocalDate,
    /** The goal policy in force; null before onboarding. */
    val policy: GoalPolicy?,
    val food: SnapshotFood?,
    /** Today's steps once M13 tracks them; null until then. */
    val steps: Int?,
    /** The rotation's next routine; null before the training setup or with an empty rotation. */
    val nextRoutine: String?,
    /** PO-11: how many of the next routine's lifts are due for a weight increase. */
    val liftsDue: Int,
    /** HM-01: why today is a rest day, or null when the next routine is up. */
    val rest: RestReason?,
    val strengthDone: Boolean,
    val workoutActive: Boolean,
    val weighedIn: Boolean,
    val scanned: Boolean,
    /** The meal slots with an entry today. */
    val mealsLogged: Set<String>,
    /** The weekly check-in you haven't opened yet, if one is waiting. */
    val checkin: WeeklyCheckinEntity?,
) {
    fun numbers(): DayNumbers? = food?.let {
        DayNumbers(it.kcalTarget, it.kcalEaten, it.proteinTargetG, it.proteinEatenG, steps, if (steps != null) Nudges.DEFAULT_STEP_GOAL else null)
    }
}

/** The evening nudge (GL-08, NU-23): what's left, and for protein, your own foods to close the gap with. */
data class EveningNudge(val lines: List<NudgeLine>, val suggestions: List<Suggestion>) {
    companion object {
        val NONE = EveningNudge(emptyList(), emptyList())
    }
}

/** A suggestion that was logged, for *Undo*. */
data class LoggedSuggestion(val entryId: Long, val suggestion: Suggestion, val slot: MealSlot)

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class TodaySnapshotRepository @Inject constructor(
    private val days: DayBoundary,
    private val prefs: SettingsPrefs,
    private val nutrition: NutritionRepository,
    private val training: TrainingRepository,
    private val workouts: WorkoutRepository,
    private val progression: ProgressionRepository,
    private val checkins: CheckinRepository,
    private val foods: FoodRepository,
    private val foodLog: FoodLogRepository,
    private val nudgeDao: NudgeDao,
) {
    /** How many snapshots have been built, so a test can see the shared flow builds one per change. */
    @Volatile
    var loads = 0
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The snapshot every live reader shares (review N8): Today's evening card and the widget's updater build it once
     * per change between them, not once each.
     */
    val shared: Flow<TodaySnapshot> by lazy { observe().shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1) }

    suspend fun load(now: Long = System.currentTimeMillis()): TodaySnapshot {
        loads++
        val today = days.of(now)
        val settings = training.settings.first()
        val week = workouts.observeWeek(today).first()
        val next = if (settings.setupDone) training.observeNextRoutine().first() else null
        return TodaySnapshot(
            date = today,
            policy = prefs.persona.first().goal?.toPlan()?.policy,
            food = nutrition.observeTargets(today).first()?.let { t ->
                val eaten = nutrition.observeIntake(today, today).first()[today] ?: Intake.NONE
                SnapshotFood(t.target.kcal, eaten.kcal, t.protein.todayG, eaten.proteinG, TodayNumbers.main(t.target.goal, t.target.kcal, t.protein, eaten))
            },
            steps = nudgeDao.steps(today),
            nextRoutine = next?.name,
            liftsDue = next?.let { r -> progression.preview(r.id).count { it.weightUp } } ?: 0,
            rest = if (settings.setupDone) WeeklyPlan.restDay(today, week, settings.strengthDaysPerWeek) else null,
            strengthDone = week.any { it.logDate == today && it.kind == SessionKind.STRENGTH },
            workoutActive = workouts.active() != null,
            weighedIn = nudgeDao.weighedIn(today),
            scanned = nudgeDao.scanned(today),
            mealsLogged = nudgeDao.mealsLogged(today).toSet(),
            checkin = checkins.observeCard().first(),
        )
    }

    /**
     * The snapshot, again whenever something it shows changes: food logged, a workout started or finished, the
     * targets or the rotation changed, a weigh-in or scan, the check-in, and the day rolling over.
     */
    fun observe(now: () -> Long = System::currentTimeMillis): Flow<TodaySnapshot> = days.observeToday(now).flatMapLatest { today ->
        val food = combine(nutrition.observeTargets(today), nutrition.observeIntake(today, today), prefs.persona) { _, _, _ -> }
        val train = combine(training.observeNextRoutine(), workouts.observeWeek(today), workouts.observeActive(), training.settings) { _, _, _, _ -> }
        val body = combine(nudgeDao.observeWeighIns(today), nudgeDao.observeScans(today), nudgeDao.observeSteps(today), checkins.observeCard()) { _, _, _, _ -> }
        combine(food, train, body) { _, _, _ -> }.conflate().map { load(now()) }
    }.distinctUntilChanged()

    /** Tonight's nudge for a snapshot, with suggestions when protein is what's short. */
    suspend fun evening(snapshot: TodaySnapshot): EveningNudge {
        val policy = snapshot.policy ?: return EveningNudge.NONE
        val numbers = snapshot.numbers() ?: return EveningNudge.NONE
        val lines = Nudges.evening(policy, numbers)
        val protein = lines.filterIsInstance<NudgeLine.ProteinLeft>().firstOrNull() ?: return EveningNudge(lines, emptyList())
        return EveningNudge(lines, Nudges.suggest(candidates(), protein.grams, numbers.kcalTarget - numbers.kcalEaten))
    }

    /** Your most-logged foods the diet allows, each with the unit and amount you usually log it in. */
    private suspend fun candidates(): List<NudgeFood> {
        val filter = foods.filter.first()
        return nudgeDao.mostLogged(filter.diets, filter.region.name, Nudges.POOL).mapNotNull { food ->
            val choice = foods.portion(food.id) ?: return@mapNotNull null
            NudgeFood(food.id, food.name, food.per100g, food.useCount, choice.unit, choice.quantity * choice.unit.grams)
        }
    }

    /** The meal a suggestion tapped at [now] goes into: the meal for the time of day (PRD §12.5). */
    suspend fun slotAt(now: Long, zone: ZoneId = ZoneId.systemDefault()): MealSlot? =
        MealClock.slotAt(prefs.mealSlots.first(), timeOf(now, zone))

    /** One tap (NU-23): logs the suggested portion into the meal for the time of day. Null when the food or every meal is gone. */
    suspend fun log(suggestion: Suggestion, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): LoggedSuggestion? {
        val slot = slotAt(now, zone) ?: return null
        val choice = foods.portion(suggestion.foodId) ?: return null
        if (choice.food.archived) return null
        val id = foodLog.log(days.of(now), slot.key, choice.food, suggestion.quantity, suggestion.unit, choice.origin, now)
        return LoggedSuggestion(id, suggestion, slot)
    }

    /** *Undo*: the entry goes, and so does the use it added. False when it was already gone. */
    suspend fun undo(logged: LoggedSuggestion): Boolean = undo(logged.entryId)

    suspend fun undo(entryId: Long): Boolean {
        if (foodLog.entry(entryId) == null) return false
        foodLog.undoInsert(listOf(entryId))
        return true
    }

    companion object {
        fun timeOf(epochMs: Long, zone: ZoneId): LocalTime = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime()
    }
}
