package app.recomp.data.repo

import app.recomp.data.db.CheckinDao
import app.recomp.data.db.FoodLogDao
import app.recomp.data.db.NutritionDao
import app.recomp.data.db.NutritionTargetEntity
import app.recomp.data.prefs.DayBoundary
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.domain.nutrition.Baseline
import app.recomp.domain.nutrition.Intake
import app.recomp.domain.nutrition.LoggedDay
import app.recomp.domain.nutrition.MainNumber
import app.recomp.domain.nutrition.ProteinGoal
import app.recomp.domain.nutrition.ProteinRamp
import app.recomp.domain.nutrition.RampState
import app.recomp.domain.nutrition.TodayNumbers
import app.recomp.domain.nutrition.WeekAverage
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/** Today's nutrition card (GL-07, GL-10, NU-02, NU-03). */
data class TodayNutrition(
    val today: LocalDate,
    val target: NutritionTargetEntity,
    val protein: ProteinGoal,
    val eaten: Intake,
    val main: MainNumber,
    val week: WeekAverage?,
)

/** The targets in force on a day, with that day's protein after the ramp (NU-03). */
data class DayTargets(val target: NutritionTargetEntity, val protein: ProteinGoal)

/** Versioned targets, the protein ramp and Today's numbers (TRD §5.3, §5.9). */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class NutritionRepository @Inject constructor(
    private val nutritionDao: NutritionDao,
    private val foodLogDao: FoodLogDao,
    private val checkinDao: CheckinDao,
    private val prefs: SettingsPrefs,
    private val days: DayBoundary,
) {
    /** Food eaten per log date in [from]..[to] (`food_log` summed by `log_date`), for days with food logged only. */
    fun observeIntake(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, Intake>> =
        foodLogDao.observeIntake(from, to).map { rows -> rows.associate { it.logDate to Intake(it.kcal.roundToInt(), it.proteinG.roundToInt()) } }

    /** The day view's targets (NU-16): the version in force on [date] and its ramped protein. Null before any target. */
    fun observeTargets(date: LocalDate): Flow<DayTargets?> = prefs.ramp.flatMapLatest { ramp ->
        val from = minOf(ramp?.countFrom ?: date, date)
        combine(nutritionDao.observeAll(), prefs.persona, observeIntake(from, date)) { targets, persona, intake ->
            val target = targets.lastOrNull { !it.effectiveFrom.isAfter(date) } ?: return@combine null
            DayTargets(target, ProteinRamp.goal(target.proteinG, persona.rampEnabled(), ramp, date, intake.mapValues { it.value.proteinG }))
        }
    }

    suspend fun targetOn(date: LocalDate): NutritionTargetEntity? = nutritionDao.targetOn(date)

    fun observeToday(): Flow<TodayNutrition?> =
        combine(days.observeToday(), prefs.ramp) { today, ramp -> today to ramp }.flatMapLatest { (today, ramp) ->
            val from = minOf(ramp?.countFrom ?: today, today.minusDays(TodayNumbers.AVERAGE_DAYS))
            val incomplete = checkinDao.observeIncompleteDays(today.minusDays(TodayNumbers.AVERAGE_DAYS), today)
            combine(nutritionDao.observeAll(), prefs.persona, observeIntake(from, today), incomplete) { targets, persona, intake, skipped ->
                val target = targets.lastOrNull { !it.effectiveFrom.isAfter(today) } ?: return@combine null
                val protein = ProteinRamp.goal(target.proteinG, persona.rampEnabled(), ramp, today, intake.mapValues { it.value.proteinG })
                val eaten = intake[today] ?: Intake.NONE
                TodayNutrition(
                    today = today,
                    target = target,
                    protein = protein,
                    eaten = eaten,
                    main = TodayNumbers.main(target.goal, target.kcal, protein, eaten),
                    week = TodayNumbers.weekAverage(today, loggedDays(targets, target, persona.rampEnabled(), ramp, intake, skipped.toSet(), before = today)),
                )
            }
        }

    /**
     * The 7-day average for the week starting [weekStart] (PRD §12.1): its logged days against their own targets,
     * leaving out days marked incomplete. Null when none were logged.
     */
    suspend fun weekAverage(weekStart: LocalDate): WeekAverage? {
        val end = weekStart.plusDays(TodayNumbers.AVERAGE_DAYS - 1)
        val ramp = prefs.ramp.first()
        val from = minOf(ramp?.countFrom ?: weekStart, weekStart)
        val intake = foodLogDao.intake(from, end).associate { it.logDate to Intake(it.kcal.roundToInt(), it.proteinG.roundToInt()) }
        val targets = nutritionDao.all()
        val fallback = targets.lastOrNull { !it.effectiveFrom.isAfter(end) } ?: return null
        val days = loggedDays(targets, fallback, prefs.currentPersona().rampEnabled(), ramp, intake, checkinDao.incompleteDays(weekStart, end).toSet(), before = end.plusDays(1))
        return TodayNumbers.weekAverage(end.plusDays(1), days)
    }

    /**
     * Logged days before [before] with each day's own targets and ramped protein (TRD §5.9); a day before the first
     * version uses [fallback]. Days marked incomplete are left out (PRD §12.1, decided).
     */
    private fun loggedDays(
        targets: List<NutritionTargetEntity>,
        fallback: NutritionTargetEntity,
        rampOn: Boolean,
        ramp: RampState?,
        intake: Map<LocalDate, Intake>,
        incomplete: Set<LocalDate>,
        before: LocalDate,
    ): List<LoggedDay> {
        val proteinByDay = intake.mapValues { it.value.proteinG }
        return intake.filterKeys { it.isBefore(before) && it !in incomplete }.map { (date, day) ->
            val then = targets.lastOrNull { !it.effectiveFrom.isAfter(date) } ?: fallback
            LoggedDay(date, day, then.kcal, ProteinRamp.goal(then.proteinG, rampOn, ramp, date, proteinByDay).todayG)
        }
    }

    /**
     * Locks the ramp's baseline once its week is found (TRD §5.3), so later edits to those days never
     * move it.
     */
    suspend fun lockRampBaseline() {
        val state = prefs.ramp.first() ?: return
        if (state.baselineEnd != null) return
        val today = days.today()
        val protein = observeIntake(state.countFrom, today).first().mapValues { it.value.proteinG }
        val found = ProteinRamp.baseline(state.countFrom, today, protein) as? Baseline.Done ?: return
        prefs.edit { setRamp(state.copy(baselineEnd = found.end, baselineMeanG = found.meanG)) }
    }
}
