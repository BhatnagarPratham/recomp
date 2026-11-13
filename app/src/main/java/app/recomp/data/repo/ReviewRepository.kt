package app.recomp.data.repo

import app.recomp.data.db.BodyCompositionEntity
import app.recomp.data.db.BodyDao
import app.recomp.data.db.MeasurementType
import app.recomp.data.db.NutritionDao
import app.recomp.data.db.ReviewDao
import app.recomp.data.db.RoutineDao
import app.recomp.data.db.WeekRecordRow
import app.recomp.data.prefs.DayBoundary
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.domain.body.ScanPoint
import app.recomp.domain.body.ScanTrends
import app.recomp.domain.body.WeekScan
import app.recomp.domain.model.SessionKind
import app.recomp.domain.nutrition.TodayNumbers
import app.recomp.domain.nutrition.WeekAverage
import app.recomp.domain.persona.GoalPlan
import app.recomp.domain.persona.Guardrails
import app.recomp.domain.review.CalendarDay
import app.recomp.domain.review.FinishOutlook
import app.recomp.domain.review.OriginShares
import app.recomp.domain.review.ProteinDays
import app.recomp.domain.review.WeeklyReview
import app.recomp.domain.training.PlanSession
import app.recomp.domain.training.WeeklyPlan
import app.recomp.domain.trend.TrendCalculator
import app.recomp.domain.trend.TrendPoint
import app.recomp.domain.trend.WeightPace
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** Sessions done against planned for one extra session (PRD §7.6); [planned] is 0 for "whenever". */
data class ExtraDone(val name: String, val done: Int, val planned: Int)

/** The review's Training section (HM-03): the weekly plan, PRs and lifts that showed a stall card. */
data class TrainingWeek(val strengthDays: Int, val strengthTarget: Int, val extras: List<ExtraDone>, val records: List<WeekRecordRow>, val stalled: List<String>)

/** The Food section: averages against target, protein days hit, and the share of ordered and packaged food. */
data class FoodWeek(val average: WeekAverage?, val protein: ProteinDays, val origins: OriginShares?)

/** The Body section: the trend's change over the week, the waist, and the week's scan against the one before. */
data class BodyWeek(
    val trendKg: Double?,
    val changeKg: Double?,
    val changePct: Double?,
    val waistCm: Double?,
    val waistChangeCm: Double?,
    val scan: WeekScan?,
    /** The goal's pace, for "goal −0.5% a week"; null before there's a goal. */
    val pace: WeightPace? = null,
)

/** Steps and sleep (AR-01, AR-02), once M13 fills `daily_activity`; each part shows only when it has days. */
data class ActivityWeek(val stepsAvg: Int?, val stepsDays: Int, val sleepAvgHours: Double?, val sleepDays: Int)

/** One week's review (HM-03, HM-04, PRD §12.4). [inProgress] is the current week, so far. */
data class WeekReview(
    val weekStart: LocalDate,
    val inProgress: Boolean,
    val food: FoodWeek,
    val training: TrainingWeek,
    val body: BodyWeek,
    /** Null while `daily_activity` has no steps or sleep for the week. */
    val activity: ActivityWeek?,
    val outlook: FinishOutlook?,
    val month: YearMonth,
    val calendar: List<List<CalendarDay?>>,
)

/**
 * The weekly review (HM-03, HM-04, TRD §10 M8). Everything is computed on read, one week (or the calendar's month) at a
 * time. A finished week is judged as it ended; the current week shows what's in so far, with food up to yesterday so a
 * half-logged today doesn't drag the averages down.
 */
@Singleton
class ReviewRepository @Inject constructor(
    private val reviewDao: ReviewDao,
    private val nutritionDao: NutritionDao,
    private val bodyDao: BodyDao,
    private val routineDao: RoutineDao,
    private val prefs: SettingsPrefs,
    private val days: DayBoundary,
    private val personas: PersonaRepository,
    private val nutrition: NutritionRepository,
) {
    /** The weeks there are to review: from the first target's week to this one. */
    suspend fun weeks(today: LocalDate? = null): ClosedRange<LocalDate> {
        val day = today ?: days.today()
        val thisWeek = WeeklyPlan.weekStart(day)
        val first = nutritionDao.first()?.effectiveFrom?.let(WeeklyPlan::weekStart) ?: thisWeek
        return minOf(first, thisWeek)..thisWeek
    }

    suspend fun week(weekStart: LocalDate, today: LocalDate? = null): WeekReview {
        val day = today ?: days.today()
        val end = weekStart.plusDays(6)
        val inProgress = !day.isAfter(end)
        // How far the week has got: its Sunday, or today while it runs.
        val asOf = minOf(end, day)
        return WeekReview(
            weekStart = weekStart,
            inProgress = inProgress,
            food = food(weekStart, end, day, inProgress),
            training = training(weekStart, end),
            body = body(weekStart, asOf),
            activity = activity(weekStart, end),
            outlook = outlook(weekStart, asOf, day),
            month = WeeklyReview.monthOf(weekStart),
            calendar = calendar(WeeklyReview.monthOf(weekStart)),
        )
    }

    private suspend fun food(start: LocalDate, end: LocalDate, today: LocalDate, inProgress: Boolean): FoodWeek {
        val before = if (inProgress) today else end.plusDays(1)
        val logged = nutrition.weekDays(start, before)
        val lastDay = before.minusDays(1)
        val origins = if (lastDay.isBefore(start)) null else WeeklyReview.originShares(reviewDao.kcalByOrigin(start, lastDay).associate { it.origin to it.kcal })
        return FoodWeek(
            average = TodayNumbers.weekAverage(start.plusDays(TodayNumbers.AVERAGE_DAYS), logged),
            protein = WeeklyReview.proteinDays(logged),
            origins = origins,
        )
    }

    private suspend fun training(start: LocalDate, end: LocalDate): TrainingWeek {
        val settings = prefs.currentTraining()
        val sessions = reviewDao.sessions(start, end).map { PlanSession(it.logDate, it.kind, it.routineId) }
        val plans = settings.extras.map { it.toPlan() }
        val progress = WeeklyPlan.progress(start, sessions, settings.strengthDaysPerWeek, plans)
        val names = routineDao.extras().associate { it.id to it.name }
        val stalled = reviewDao.recommendations(start, end)
            .filter { ProgressionRepository.decode(it.recommendation)?.stalled == true }
            .map { it.exercise }
            .distinct()
        return TrainingWeek(
            strengthDays = progress.strengthDays,
            strengthTarget = progress.strengthTarget,
            extras = progress.extras.mapNotNull { e -> names[e.routineId]?.let { ExtraDone(it, e.done, e.planned) } },
            records = reviewDao.records(start, end),
            stalled = stalled,
        )
    }

    private suspend fun trend(asOf: LocalDate): List<TrendPoint> =
        TrendCalculator.trend(bodyDao.weighIns().map { it.toWeighIn() }.filter { !it.date.isAfter(asOf) }, prefs.currentPersona().weightSource)

    private suspend fun scans(): List<ScanPoint> = bodyDao.observeScans().first().map(BodyCompositionEntity::toScanPoint)

    private suspend fun body(start: LocalDate, asOf: LocalDate): BodyWeek {
        val points = trend(asOf)
        // The week's change is the weekly rate anchored at its last weigh-in (TRD §5.2), so it needs one in the week.
        val rate = TrendCalculator.weeklyRate(points)?.takeIf { !it.asOf.isBefore(start) }
        val waist = bodyDao.measurements(MeasurementType.WAIST).filter { !it.logDate.isAfter(asOf) }.sortedBy { it.measuredAt }
        val thisWeek = waist.lastOrNull()?.takeIf { !it.logDate.isBefore(start) }
        val before = thisWeek?.let { w -> waist.lastOrNull { it.measuredAt < w.measuredAt } }
        return BodyWeek(
            trendKg = points.lastOrNull()?.trendKg,
            changeKg = rate?.kgPerWeek,
            changePct = rate?.pctPerWeek,
            waistCm = thisWeek?.value,
            waistChangeCm = before?.let { thisWeek.value - it.value },
            scan = ScanTrends.weekScan(scans(), start, asOf),
            pace = plan(asOf)?.policy?.let(WeightPace::of),
        )
    }

    private suspend fun activity(start: LocalDate, end: LocalDate): ActivityWeek? {
        val row = reviewDao.activity(start, end)
        if (row.stepsDays == 0 && row.sleepDays == 0) return null
        return ActivityWeek(row.stepsAvg?.let { Math.round(it).toInt() }, row.stepsDays, row.sleepAvg, row.sleepDays)
    }

    /**
     * The finish line at the trend's rate as of [asOf] (TRD §5.8), for the goal after the guardrails (PRD §4.9). The rate
     * is the last 4 weeks' change ÷ 4 when the trend reaches that far, which is steadier than one week's, else the week's.
     */
    private suspend fun outlook(start: LocalDate, asOf: LocalDate, today: LocalDate): FinishOutlook? {
        val plan = plan(today) ?: return null
        val points = trend(asOf)
        val anchor = points.lastOrNull()?.takeIf { !it.date.isBefore(start) }
        val kgPerWeek = anchor?.let { a ->
            TrendCalculator.trendAt(points, a.date.minusDays(FOUR_WEEKS))?.let { (a.trendKg - it) / 4 }
                ?: TrendCalculator.weeklyRate(points)?.kgPerWeek
        }
        val scans = scans().filter { !it.date.isAfter(asOf) }
        val bodyFat = ScanTrends.onProtocol(scans).lastOrNull()?.bodyFatPct
        val bodyFatPerWeek = ScanTrends.slopes(scans)?.bodyFatPct?.div(4)
        return WeeklyReview.outlook(plan, asOf, anchor?.trendKg, kgPerWeek, bodyFat, bodyFatPerWeek)
    }

    /** The goal after the guardrails (PRD §4.9), as Settings and the check-in use it. */
    private suspend fun plan(today: LocalDate): GoalPlan? {
        val stored = prefs.currentPersona().goal?.toPlan() ?: return null
        val profile = personas.currentProfile() ?: return null
        return Guardrails.apply(profile, stored, today).plan
    }

    private suspend fun calendar(month: YearMonth): List<List<CalendarDay?>> {
        val from = month.atDay(1)
        val to = month.atEndOfMonth()
        val sessions = reviewDao.sessions(from, to)
        return WeeklyReview.calendar(
            month,
            strength = sessions.filter { it.kind == SessionKind.STRENGTH }.map { it.logDate }.toSet(),
            extra = sessions.filter { it.kind != SessionKind.STRENGTH }.map { it.logDate }.toSet(),
            food = reviewDao.foodDays(from, to).toSet(),
        )
    }

    /** All scans as points, for Body's chart. */
    suspend fun scanPoints(): List<ScanPoint> = scans()

    private companion object {
        const val FOUR_WEEKS = 28L
    }
}

/** A scan as trends see it (TRD §5.6). */
fun BodyCompositionEntity.toScanPoint() = ScanPoint(logDate, measuredAt, skeletalMuscleG / 1000.0, bodyFatG / 1000.0, bodyFatPct, protocolOk)
