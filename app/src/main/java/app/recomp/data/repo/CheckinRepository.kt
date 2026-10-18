package app.recomp.data.repo

import androidx.room.withTransaction
import app.recomp.data.db.AppDatabase
import app.recomp.data.db.BodyDao
import app.recomp.data.db.CheckinDao
import app.recomp.data.db.CheckinDecision
import app.recomp.data.db.DailyActivityEntity
import app.recomp.data.db.FoodLogDao
import app.recomp.data.db.MeasurementType
import app.recomp.data.db.NutritionDao
import app.recomp.data.db.NutritionTargetEntity
import app.recomp.data.db.TargetReason
import app.recomp.data.db.WeeklyCheckinEntity
import app.recomp.data.db.toVersion
import app.recomp.data.prefs.DayBoundary
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.domain.checkin.AdaptiveTdee
import app.recomp.domain.checkin.CheckinEvaluation
import app.recomp.domain.checkin.CheckinFlag
import app.recomp.domain.checkin.CheckinFlags
import app.recomp.domain.checkin.CheckinProposal
import app.recomp.domain.checkin.CheckinStatus
import app.recomp.domain.checkin.EstimateGap
import app.recomp.domain.checkin.WaistReading
import app.recomp.domain.nutrition.NutritionTargets
import app.recomp.domain.nutrition.TargetInputs
import app.recomp.domain.nutrition.TargetOverrides
import app.recomp.domain.nutrition.WeekAverage
import app.recomp.domain.persona.GoalPlan
import app.recomp.domain.persona.Profile
import app.recomp.domain.trend.TrendCalculator
import app.recomp.domain.trend.TrendPoint
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A week's check-in as its screen shows it (PRD §12.1). */
data class CheckinView(
    val row: WeeklyCheckinEntity,
    val plan: GoalPlan,
    /** The verdict: the stored flag wins; what isn't stored (not enough data, a missing waist) is judged now. */
    val status: CheckinStatus,
    /** The week's trend and waist numbers, judged now from the data up to the week's end. */
    val evaluation: CheckinEvaluation,
    /** Why there's no maintenance estimate this week, or null when there is one. */
    val gap: EstimateGap?,
    /** The week's averages against its targets, over days logged and not marked incomplete. */
    val week: WeekAverage?,
    /** BMR × activity at today's body data: "the formula said 2,610". */
    val formulaTdee: Int,
    /** The target in force today. */
    val current: NutritionTargetEntity,
    /** The proposal in full while it can still be answered. */
    val proposal: NutritionTargets?,
    /** Where *Adjust* can go (PRD §4.9). */
    val adjustRange: IntRange?,
    /** A calorie target you set yourself (PR-02). *Accept* keeps it; *Use the new target* resets it. */
    val kcalOverride: Int?,
    /** The version *Accept* or *Adjust* wrote, while it's still there. */
    val written: NutritionTargetEntity?,
)

/**
 * The weekly check-in (NU-05, GL-08, PRD §12.1, TRD §10 M5). A week runs Monday to Sunday. Its row is computed on
 * the first launch after it ends, catching up missed weeks in order; each week's estimate starts from the last
 * one's, so the chain carries on whether or not anyone answers. A decided row is frozen. The latest row stays
 * pending, and is recomputed when opened so late logging counts, until the next week's is ready: then it's
 * recomputed one last time and lapses.
 */
@Singleton
class CheckinRepository @Inject constructor(
    private val db: AppDatabase,
    private val checkinDao: CheckinDao,
    private val nutritionDao: NutritionDao,
    private val foodLogDao: FoodLogDao,
    private val bodyDao: BodyDao,
    private val prefs: SettingsPrefs,
    private val days: DayBoundary,
    private val personas: PersonaRepository,
    private val nutrition: NutritionRepository,
) {
    /** Catch-up, open and the decisions each read a row and write it back. */
    private val mutex = Mutex()

    fun observeLatest(): Flow<WeeklyCheckinEntity?> = checkinDao.observeLatest()

    /** Today's card: the latest check-in until you've opened it, or until the next week's replaces it. */
    fun observeCard(): Flow<WeeklyCheckinEntity?> =
        combine(checkinDao.observeLatest(), prefs.checkinSeen) { latest, seen -> latest?.takeIf { seen == null || it.weekStart.isAfter(seen) } }

    /** Computes every week that has ended since the first target and has no row yet, oldest first. */
    suspend fun catchUp(today: LocalDate? = null, now: Long = System.currentTimeMillis()) = withContext(NonCancellable) {
        mutex.withLock {
            val day = today ?: days.of(now)
            val first = nutritionDao.first() ?: return@withLock
            val thisWeek = mondayOf(day)
            var week = checkinDao.latest()?.weekStart?.plusWeeks(1) ?: mondayOf(first.effectiveFrom)
            while (week.isBefore(thisWeek)) {
                // The last one closes first, with its final numbers, because this week's estimate starts from it.
                checkinDao.before(week)?.takeIf { it.pending }?.let { last ->
                    val final = compute(last.weekStart, day, now) ?: last
                    checkinDao.upsert(final.copy(decision = CheckinDecision.LAPSED, decidedAt = now))
                }
                checkinDao.upsert(compute(week, day, now) ?: return@withLock)
                week = week.plusWeeks(1)
            }
        }
    }

    /** Opens a week's check-in: a pending one is recomputed first, and Today's card goes once it's seen. */
    suspend fun open(weekStart: LocalDate, now: Long = System.currentTimeMillis()): CheckinView? {
        val row = withContext(NonCancellable) {
            mutex.withLock {
                val stored = checkinDao.week(weekStart) ?: return@withLock null
                val fresh = if (stored.pending) compute(weekStart, days.of(now), now)?.also { checkinDao.upsert(it) } ?: stored else stored
                val seen = prefs.checkinSeen.first()
                if (seen == null || weekStart.isAfter(seen)) prefs.setCheckinSeen(weekStart)
                fresh
            }
        } ?: return null
        return view(row, now)
    }

    /**
     * *Accept*: a new version from today with the proposal at the new estimate. A calorie target you set yourself
     * stays, unless [useNewTarget], which resets it (PRD §12.1).
     */
    suspend fun accept(weekStart: LocalDate, useNewTarget: Boolean = false, now: Long = System.currentTimeMillis()): Boolean =
        decide(weekStart, now, CheckinDecision.ACCEPTED, resetKcal = useNewTarget) { row, overrides ->
            val proposed = row.proposedKcal ?: return@decide null
            if (overrides.kcal != null && !useNewTarget) overrides.kcal else proposed
        }

    /** *Adjust*: the proposal moved in 50 kcal steps, held to the floor and caps. It replaces a calorie target you set. */
    suspend fun adjust(weekStart: LocalDate, kcal: Int, now: Long = System.currentTimeMillis()): Boolean =
        decide(weekStart, now, CheckinDecision.ADJUSTED, resetKcal = true) { row, _ -> kcal.takeIf { row.proposedKcal != null } }

    /** *Keep*: the target stays. The week's estimate still counts for the next one. */
    suspend fun keep(weekStart: LocalDate, now: Long = System.currentTimeMillis()): Boolean = withContext(NonCancellable) {
        mutex.withLock {
            val row = checkinDao.week(weekStart)?.takeIf { it.pending } ?: return@withLock false
            checkinDao.upsert(row.copy(decision = CheckinDecision.KEPT, decidedAt = now))
            true
        }
    }

    /**
     * Writes the target [kcalFor] gives (null = nothing to write) and freezes the row. The body data then holds the
     * weight the new targets used, the trend weight once there is one (TRD §5.0), as a Settings change does.
     */
    private suspend fun decide(
        weekStart: LocalDate,
        now: Long,
        decision: CheckinDecision,
        resetKcal: Boolean,
        kcalFor: (WeeklyCheckinEntity, TargetOverrides) -> Int?,
    ): Boolean = withContext(NonCancellable) {
        mutex.withLock {
            val row = checkinDao.week(weekStart)?.takeIf { it.pending } ?: return@withLock false
            val today = days.of(now)
            val plan = prefs.currentPersona().goal?.toPlan() ?: return@withLock false
            val profile = personas.currentProfile() ?: return@withLock false
            val inputs = TargetInputs.from(profile, plan, today)
            val overrides = prefs.overrides.first()
            val requested = kcalFor(row, overrides) ?: return@withLock false
            val kcal = if (decision == CheckinDecision.ADJUSTED) requested.coerceIn(CheckinProposal.adjustRange(inputs, row.tdeeNew.toDouble())) else requested
            val kept = if (resetKcal) overrides.copy(kcal = null) else overrides
            val targets = CheckinProposal.targets(inputs, row.tdeeNew.toDouble(), kept, kcal = kcal)
            db.withTransaction {
                val id = nutritionDao.upsert(targets.toVersion(plan.goal, today, TargetReason.CHECKIN, now))
                checkinDao.upsert(row.copy(decision = decision, decidedAt = now, targetId = id))
            }
            prefs.edit {
                setProfile(profile)
                if (resetKcal && overrides.kcal != null) setOverrides(kept)
            }
            true
        }
    }

    // Incomplete days (PRD §12.1)

    fun observeFoodIncomplete(date: LocalDate): Flow<Boolean> = checkinDao.observeIncompleteDays(date, date).map { it.isNotEmpty() }

    /** *Mark day as incomplete* and back: the day is left out of the estimate and the 7-day averages. */
    suspend fun setFoodIncomplete(date: LocalDate, incomplete: Boolean) {
        val row = checkinDao.activity(date) ?: DailyActivityEntity(date)
        checkinDao.upsert(row.copy(foodIncomplete = incomplete))
    }

    // Computing a week

    private suspend fun trend(): List<TrendPoint> =
        TrendCalculator.trend(bodyDao.weighIns().map { it.toWeighIn() }, prefs.currentPersona().weightSource)

    private suspend fun waist(): List<WaistReading> = bodyDao.measurements(MeasurementType.WAIST).map { WaistReading(it.logDate, it.value) }

    /** The week's row from the data now, still pending. Null before there's a goal or a target to start from. */
    private suspend fun compute(week: LocalDate, today: LocalDate, now: Long): WeeklyCheckinEntity? {
        val end = week.plusDays(6)
        val plan = prefs.currentPersona().goal?.toPlan() ?: return null
        val previous = checkinDao.before(week)?.tdeeNew ?: (nutritionDao.targetOn(end) ?: nutritionDao.first())?.tdeeEstimate ?: return null
        val from = end.minusDays(AdaptiveTdee.WEEKLY_WINDOW_DAYS - 1L)
        val points = trend()
        val estimate = AdaptiveTdee.estimate(
            end = end,
            points = points,
            kcalByDay = foodLogDao.intake(from, end).associate { it.logDate to it.kcal },
            incomplete = checkinDao.incompleteDays(from, end).toSet(),
            previous = previous.toDouble(),
        )
        val tdeeNew = estimate.new.roundToInt()
        val flag = CheckinFlags.evaluate(plan.policy, points, waist(), end).flag
        return WeeklyCheckinEntity(
            weekStart = week,
            computedAt = now,
            windowDays = estimate.windowDays,
            loggedDays = estimate.loggedDays,
            weighIns = estimate.weighIns,
            kcalMean = estimate.kcalMean,
            trendStartG = estimate.trendStartKg?.let { (it * 1000).roundToInt() },
            trendEndG = estimate.trendEndKg?.let { (it * 1000).roundToInt() },
            tdeeObs = estimate.observed?.roundToInt(),
            tdeePrev = previous,
            tdeeNew = tdeeNew,
            flag = flag,
            proposedKcal = proposal(flag, plan, tdeeNew, today)?.kcal,
        )
    }

    private suspend fun inputs(plan: GoalPlan, today: LocalDate): Pair<Profile, TargetInputs>? {
        val profile = personas.currentProfile() ?: return null
        return profile to TargetInputs.from(profile, plan, today)
    }

    private suspend fun proposal(flag: CheckinFlag?, plan: GoalPlan, tdeeNew: Int, today: LocalDate): NutritionTargets? {
        if (flag == null) return null
        val (_, inputs) = inputs(plan, today) ?: return null
        val current = nutritionDao.targetOn(today) ?: return null
        return CheckinProposal.of(flag, inputs, tdeeNew.toDouble(), prefs.overrides.first(), current.kcal)
    }

    private suspend fun view(row: WeeklyCheckinEntity, now: Long): CheckinView? {
        val today = days.of(now)
        val plan = prefs.currentPersona().goal?.toPlan() ?: return null
        val (profile, inputs) = inputs(plan, today) ?: return null
        val current = nutritionDao.targetOn(today) ?: return null
        val overrides = prefs.overrides.first()
        val evaluation = CheckinFlags.evaluate(plan.policy, trend(), waist(), row.weekEnd)
        val status = when {
            row.flag != null -> CheckinStatus.FLAGGED
            evaluation.status == CheckinStatus.FLAGGED -> CheckinStatus.ON_TRACK
            else -> evaluation.status
        }
        val open = row.proposedKcal?.takeIf { row.pending }
        return CheckinView(
            row = row,
            plan = plan,
            status = status,
            evaluation = evaluation,
            gap = AdaptiveTdee.gapOf(row.windowDays, row.loggedDays, row.weighIns, row.tdeeObs != null),
            week = nutrition.weekAverage(row.weekStart),
            formulaTdee = personas.targetsFor(profile, plan, TargetOverrides(), today).tdeeKcal.roundToInt(),
            current = current,
            proposal = open?.let { CheckinProposal.targets(inputs, row.tdeeNew.toDouble(), overrides.copy(kcal = null), kcal = it) },
            adjustRange = open?.let { CheckinProposal.adjustRange(inputs, row.tdeeNew.toDouble()) },
            kcalOverride = overrides.kcal,
            written = row.targetId?.let { nutritionDao.target(it) },
        )
    }

    companion object {
        fun mondayOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }
}
