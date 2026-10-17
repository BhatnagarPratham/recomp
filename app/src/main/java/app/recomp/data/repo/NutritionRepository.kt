package app.recomp.data.repo

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
import app.recomp.domain.nutrition.TodayNumbers
import app.recomp.domain.nutrition.WeekAverage
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** Today's nutrition card (GL-07, GL-10, NU-02, NU-03). */
data class TodayNutrition(
    val today: LocalDate,
    val target: NutritionTargetEntity,
    val protein: ProteinGoal,
    val eaten: Intake,
    val main: MainNumber,
    val week: WeekAverage?,
)

/** Versioned targets, the protein ramp and Today's numbers (TRD §5.3, §5.9). */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class NutritionRepository @Inject constructor(
    private val nutritionDao: NutritionDao,
    private val prefs: SettingsPrefs,
    private val days: DayBoundary,
) {
    /**
     * Food eaten per log date in [from]..[to], for days with food logged only.
     * TODO(M3b): sum `food_log` by `log_date`. Until food logging exists nothing is ever logged.
     */
    fun observeIntake(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, Intake>> = flowOf(emptyMap())

    suspend fun targetOn(date: LocalDate): NutritionTargetEntity? = nutritionDao.targetOn(date)

    fun observeToday(): Flow<TodayNutrition?> =
        combine(days.observeToday(), prefs.ramp) { today, ramp -> today to ramp }.flatMapLatest { (today, ramp) ->
            val from = minOf(ramp?.countFrom ?: today, today.minusDays(TodayNumbers.AVERAGE_DAYS))
            combine(nutritionDao.observeAll(), prefs.persona, observeIntake(from, today)) { targets, persona, intake ->
                val target = targets.lastOrNull { !it.effectiveFrom.isAfter(today) } ?: return@combine null
                val proteinByDay = intake.mapValues { it.value.proteinG }
                fun proteinOn(date: LocalDate, fullG: Int) = ProteinRamp.goal(fullG, persona.rampEnabled(), ramp, date, proteinByDay)

                val protein = proteinOn(today, target.proteinG)
                val eaten = intake[today] ?: Intake.NONE
                val logged = intake.filterKeys { it.isBefore(today) }.map { (date, day) ->
                    val then = targets.lastOrNull { !it.effectiveFrom.isAfter(date) } ?: target
                    LoggedDay(date, day, then.kcal, proteinOn(date, then.proteinG).todayG)
                }
                TodayNutrition(
                    today = today,
                    target = target,
                    protein = protein,
                    eaten = eaten,
                    main = TodayNumbers.main(target.goal, target.kcal, protein, eaten),
                    week = TodayNumbers.weekAverage(today, logged),
                )
            }
        }

    /**
     * Locks the ramp's baseline once its week is found (TRD §5.3), so later edits to those days never
     * move it. A no-op until M3b logs food.
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
