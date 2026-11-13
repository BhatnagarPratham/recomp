package app.recomp.data

import app.recomp.data.db.DailyActivityEntity
import app.recomp.data.db.NutritionTargetEntity
import app.recomp.data.db.PersonalRecordEntity
import app.recomp.data.db.TargetReason
import app.recomp.data.repo.ExtraDraft
import app.recomp.data.repo.OnboardingResult
import app.recomp.data.repo.ProgressionRepository
import app.recomp.data.repo.ScanInput
import app.recomp.data.repo.WorkoutRepository.ExtraEntry
import app.recomp.domain.food.Origin
import app.recomp.domain.model.SessionKind
import app.recomp.domain.model.SetType
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.Pace
import app.recomp.domain.nutrition.Sex
import app.recomp.domain.persona.FinishLine
import app.recomp.domain.persona.GoalPlan
import app.recomp.domain.persona.Presets
import app.recomp.domain.persona.Profile
import app.recomp.domain.persona.Projection
import app.recomp.domain.persona.WeightSource
import app.recomp.domain.progression.Action
import app.recomp.domain.progression.PrType
import app.recomp.domain.progression.Recommendation
import app.recomp.domain.review.FinishOutlook
import app.recomp.domain.review.ProteinDays
import app.recomp.domain.trend.TrendCalculator
import app.recomp.domain.trend.WeighIn
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The weekly review (HM-03, HM-04, PRD §12.4): each aggregate on fixed, invented data. */
@RunWith(RobolectricTestRunner::class)
class ReviewFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: TestApp
    private val reviews get() = app.reviews

    /** Onboarding day, a Monday. Week 0 is days 0–6, week 1 days 7–13. */
    private val day0 = LocalDate.of(2026, 6, 1)

    private fun date(d: Int): LocalDate = day0.plusDays(d.toLong())

    private fun at(d: Int, hour: Int = 12): Long = LocalDateTime.of(date(d), LocalTime.of(hour, 0)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        app = TestApp(File(tmp.root, "app").apply { mkdirs() })
    }

    @After
    fun tearDown() = app.close()

    /** Invented: male, 30, 175 cm, 84 kg, recomp to 76 kg; a yoga class on Saturdays. Initial target 2,000 kcal. */
    private suspend fun onboard(plan: GoalPlan = GoalPlan.of(Goal.RECOMP, Pace.STANDARD, 76.0, FinishLine.GoalWeight, day0)) {
        val profile = Profile(Sex.MALE, LocalDate.of(1996, 1, 1), heightCm = 175.0, weightKg = 84.0)
        val yoga = ExtraDraft(null, "Yoga class", SessionKind.YOGA, setOf(6))
        app.personas.completeOnboarding(OnboardingResult(Presets.REFERENCE.draft.copy(goal = plan), Presets.REFERENCE.key, listOf(yoga), profile, LocalTime.of(4, 0)), now = at(0))
    }

    /** A strength workout on day [d] with one ticked set; returns the session and its first exercise. */
    private suspend fun workout(d: Int, hour: Int = 18): Pair<Long, Long> {
        val routine = app.training.observeNextRoutine().first()!!
        val session = app.workouts.startRoutine(routine.id, at(d, hour))
        val exercise = app.db.workoutDao().sessionExercises(session).first()
        val set = exercise.sets.first { it.setType == SetType.WORKING }
        app.workouts.completeSet(set.id, 40_000, 10, 2, at(d, hour) + 600_000)
        app.workouts.finish(session, at(d, hour) + 1_200_000)
        return session to exercise.exercise.id
    }

    private suspend fun eat(d: Int, kcal: Double, protein: Double, origin: Origin = Origin.HOME, slot: String = "lunch") =
        app.foodLog.quickAdd(date(d), slot, "Test", kcal, protein, null, null, origin, now = at(d, 13))

    @Test
    fun `the weeks run from the first target's week to this one`() = runBlocking {
        onboard()
        assertEquals(date(0)..date(14), reviews.weeks(date(20)))
        assertEquals(date(0)..date(0), reviews.weeks(date(3)))
    }

    @Test
    fun `training counts strength days and each extra session against the plan, with the week's PRs and stalls`() = runBlocking {
        onboard()
        val (monday, _) = workout(0)
        workout(0, hour = 20) // a second session the same day is still one strength day
        val (wednesday, exerciseId) = workout(2)
        val yoga = app.training.observeExtras().first().single()
        app.workouts.logExtra(yoga, ExtraEntry("Hatha", 60, 3, null), at(5, 9))
        val (nextWeek, _) = workout(8)
        // Wednesday's first exercise showed a stall card.
        val stalledRow = app.db.workoutDao().sessionExercises(wednesday).first()
        app.db.workoutDao().setRecommendation(stalledRow.entry.id, ProgressionRepository.encode(Recommendation(Action.REPEAT, 40_000, listOf(10, 10, 10), stalled = true)))

        val week = reviews.week(date(0), today = date(20)).training
        val target = app.prefs.currentTraining().strengthDaysPerWeek
        assertEquals(2, week.strengthDays)
        assertEquals(target, week.strengthTarget)
        assertEquals("Yoga class", week.extras.single().name)
        assertEquals(1, week.extras.single().done)
        assertEquals(1, week.extras.single().planned)
        assertEquals(listOf(stalledRow.exercise.name), week.stalled)
        val next = reviews.week(date(7), today = date(20)).training
        assertEquals(1, next.strengthDays)
        assertEquals(0, next.extras.single().done)
        assertTrue(next.stalled.isEmpty())
        // First workouts set no PRs (there's nothing to beat), so the test sets one in each week: each week lists its own.
        assertTrue(week.records.isEmpty())
        suspend fun pr(session: Long, kg: Int) = app.db.workoutDao().insertRecords(
            listOf(PersonalRecordEntity(exerciseId = exerciseId, sessionId = session, type = PrType.MAX_LOAD, value = kg * 1000L, loadG = kg * 1000, achievedAt = 1)),
        )
        pr(wednesday, 42)
        pr(nextWeek, 45)
        val records = reviews.week(date(0), today = date(20)).training.records
        assertEquals(listOf(42_000L), records.map { it.value })
        assertEquals(stalledRow.exercise.name, records.single().exercise)
        assertEquals(listOf(45_000L), reviews.week(date(7), today = date(20)).training.records.map { it.value })
        assertTrue(monday > 0)
    }

    @Test
    fun `food averages, protein days hit past the ramp's baseline week, and the ordered and packaged share`() = runBlocking {
        onboard()
        // Week 0 is the protein ramp's baseline week (80 g a day), so week 1's target is 80 + 15 = 95 g.
        (0..6).forEach { eat(it, 2000.0, 80.0) }
        eat(7, 1500.0, 100.0)
        eat(7, 500.0, 0.0, Origin.ORDERED, "dinner")
        eat(8, 2000.0, 91.0) // 95.8%: hit
        eat(9, 1600.0, 90.0) // 94.7%: missed
        eat(9, 400.0, 0.0, Origin.PACKAGED, "snacks")
        eat(10, 2000.0, 60.0)
        // An incomplete day is left out of everything here, its ordered food included.
        eat(11, 3000.0, 200.0, Origin.ORDERED)
        app.checkins.setFoodIncomplete(date(11), true)
        // The target changes on Thursday (day 10): the averages compare against each day's own target.
        app.db.nutritionDao().upsert(NutritionTargetEntity(effectiveFrom = date(10), goal = Goal.RECOMP, kcal = 1500, proteinG = 122, fatG = 50, carbsG = 150, tdeeEstimate = 2000, bmrKcal = 1789, reason = TargetReason.MANUAL, createdAt = 1))

        val baseline = reviews.week(date(0), today = date(20)).food
        assertEquals(ProteinDays(0, 0), baseline.protein)
        assertEquals(7, baseline.average!!.daysLogged)

        val food = reviews.week(date(7), today = date(20)).food
        assertEquals(ProteinDays(hit = 2, counted = 4), food.protein)
        assertEquals(4, food.average!!.daysLogged)
        assertEquals(2000, food.average!!.kcal)
        assertEquals(85, food.average!!.proteinG)
        assertEquals((2000 * 3 + 1500) / 4, food.average!!.kcalTarget)
        assertEquals(95, food.average!!.proteinTargetG)
        assertEquals(8000.0, food.origins!!.totalKcal, 0.001)
        assertEquals(6.25, food.origins!!.orderedPct, 1e-9)
        assertEquals(5.0, food.origins!!.packagedPct, 1e-9)
    }

    @Test
    fun `the current week shows what's in so far, with food up to yesterday`() = runBlocking {
        onboard()
        eat(14, 2000.0, 100.0)
        eat(15, 1800.0, 100.0)
        eat(16, 900.0, 30.0, Origin.ORDERED) // today, half-logged
        val week = reviews.week(date(14), today = date(16))
        assertTrue(week.inProgress)
        assertEquals(2, week.food.average!!.daysLogged)
        assertEquals(1900, week.food.average!!.kcal)
        assertEquals(0.0, week.food.origins!!.orderedPct, 0.0)
        // On Monday nothing has finished yet.
        assertNull(reviews.week(date(14), today = date(14)).food.average)
        assertNull(reviews.week(date(14), today = date(14)).food.origins)
    }

    @Test
    fun `body shows the week's trend change, its waist against the last one, and its scan against the previous`() = runBlocking {
        onboard()
        (0..13).forEach { app.body.logWeighIn(((84.0 - 0.1 * it) * 1000).roundToInt(), WeightSource.HOME_SCALE, at(it, 7)) }
        app.body.logWaist(90.0, at(6, 8))
        app.body.logWaist(89.4, at(13, 8))
        fun scan(d: Int, muscle: Int, fat: Int, pct: Double) = ScanInput(at(d, 7), 83_000, muscle, fat, pct, 8.0, minsSinceMeal = 180, workoutBefore = false)
        app.body.saveScan(scan(3, 33_000, 20_000, 24.0))
        app.body.saveScan(scan(10, 33_400, 19_200, 23.2))

        val body = reviews.week(date(7), today = date(20)).body
        val points = TrendCalculator.trend((0..13).map { WeighIn(date(it), at(it, 7), ((84.0 - 0.1 * it) * 1000).roundToInt(), WeightSource.HOME_SCALE) }, WeightSource.HOME_SCALE)
        val rate = TrendCalculator.weeklyRate(points)!!
        assertEquals(points.last().trendKg, body.trendKg!!, 1e-9)
        assertEquals(rate.kgPerWeek, body.changeKg!!, 1e-9)
        assertEquals(89.4, body.waistCm!!, 1e-9)
        assertEquals(-0.6, body.waistChangeCm!!, 1e-9)
        val change = body.scan!!.change!!
        assertEquals(0.4, change.muscleKg, 1e-9)
        assertTrue(change.muscleWithinNoise)
        assertEquals(-0.8, change.bodyFatPct, 1e-9)
        assertTrue(change.bodyFatWithinNoise)
        // Week 0 has its first scan: nothing to compare it with.
        assertNull(reviews.week(date(0), today = date(20)).body.scan!!.change)
    }

    @Test
    fun `the finish line runs at the trend's rate, and says so when moving away`() = runBlocking {
        onboard()
        (0..13).forEach { app.body.logWeighIn(((84.0 - 0.1 * it) * 1000).roundToInt(), WeightSource.HOME_SCALE, at(it, 7)) }
        val points = TrendCalculator.trend((0..13).map { WeighIn(date(it), at(it, 7), ((84.0 - 0.1 * it) * 1000).roundToInt(), WeightSource.HOME_SCALE) }, WeightSource.HOME_SCALE)
        // Under 4 weeks of trend, the week's rate is the rate.
        val rate = TrendCalculator.weeklyRate(points)!!.kgPerWeek
        val weeks = Projection.weeksTo(points.last().trendKg, 76.0, rate)!!
        assertEquals(FinishOutlook.Around(Projection.finishDate(date(13), weeks), 76.0), reviews.week(date(7), today = date(20)).outlook)

        // Gaining instead: moving away.
        (14..27).forEach { app.body.logWeighIn(((82.7 + 0.15 * (it - 13)) * 1000).roundToInt(), WeightSource.HOME_SCALE, at(it, 7)) }
        assertEquals(FinishOutlook.MovingAway, reviews.week(date(21), today = date(30)).outlook)
    }

    @Test
    fun `steps and sleep show only once daily activity has them`() = runBlocking {
        onboard()
        assertNull(reviews.week(date(0), today = date(20)).activity)
        // A day marked incomplete has a row but no steps or sleep: still nothing to show.
        app.checkins.setFoodIncomplete(date(2), true)
        assertNull(reviews.week(date(0), today = date(20)).activity)
        app.db.checkinDao().upsert(DailyActivityEntity(date(3), steps = 8_000))
        app.db.checkinDao().upsert(DailyActivityEntity(date(4), steps = 10_001, sleepHours = 7.5))
        val activity = reviews.week(date(0), today = date(20)).activity!!
        assertEquals(9_001, activity.stepsAvg)
        assertEquals(2, activity.stepsDays)
        assertEquals(7.5, activity.sleepAvgHours!!, 0.0)
        assertEquals(1, activity.sleepDays)
    }

    @Test
    fun `a week with nothing logged is empty, not an error`() = runBlocking {
        onboard()
        (0..6).forEach { app.body.logWeighIn(84_000, WeightSource.HOME_SCALE, at(it, 7)) }
        val gap = reviews.week(date(21), today = date(30))
        assertNull(gap.food.average)
        assertEquals(ProteinDays(0, 0), gap.food.protein)
        assertNull(gap.food.origins)
        assertEquals(0, gap.training.strengthDays)
        assertTrue(gap.training.records.isEmpty())
        assertNull(gap.body.changeKg)
        assertNull(gap.body.waistCm)
        assertNull(gap.body.scan)
        assertNull(gap.activity)
        // The trend last seen still shows, and the finish line has no rate without a weigh-in that week.
        assertEquals(84.0, gap.body.trendKg!!, 1e-9)
        assertEquals(FinishOutlook.NeedsData(), gap.outlook)
    }

    @Test
    fun `the calendar marks the month's workouts, extra sessions and logged days`() = runBlocking {
        onboard()
        workout(0)
        val yoga = app.training.observeExtras().first().single()
        app.workouts.logExtra(yoga, ExtraEntry("Hatha", 60, 3, null), at(5, 9))
        eat(1, 2000.0, 100.0)
        val review = reviews.week(date(0), today = date(20))
        assertEquals(YearMonth.of(2026, 6), review.month)
        val days = review.calendar.flatten().filterNotNull().associateBy { it.date }
        assertTrue(days.getValue(date(0)).strength)
        assertTrue(days.getValue(date(5)).extra)
        assertTrue(days.getValue(date(1)).food)
        assertEquals(30, days.size)
        assertEquals(27, days.values.count { !it.strength && !it.extra && !it.food })
        assertNotNull(review.calendar.first().first())
    }
}
