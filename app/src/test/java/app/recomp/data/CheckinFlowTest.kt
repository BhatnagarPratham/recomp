package app.recomp.data

import app.recomp.data.db.CheckinDecision.ACCEPTED
import app.recomp.data.db.CheckinDecision.ADJUSTED
import app.recomp.data.db.CheckinDecision.KEPT
import app.recomp.data.db.CheckinDecision.LAPSED
import app.recomp.data.db.CheckinDecision.PENDING
import app.recomp.data.db.DailyActivityEntity
import app.recomp.data.db.TargetReason
import app.recomp.data.db.WeeklyCheckinEntity
import app.recomp.data.repo.CheckinSettingsException
import app.recomp.data.repo.OnboardingResult
import app.recomp.domain.checkin.CheckinFlag
import app.recomp.domain.checkin.CheckinProposal
import app.recomp.domain.checkin.CheckinStatus
import app.recomp.domain.checkin.EstimateGap
import app.recomp.domain.food.Origin
import app.recomp.domain.nutrition.EnergyBalance
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.Pace
import app.recomp.domain.nutrition.Sex
import app.recomp.domain.nutrition.TargetOverrides
import app.recomp.domain.persona.GoalPlan
import app.recomp.domain.persona.GuardrailNote
import app.recomp.domain.persona.Presets
import app.recomp.domain.persona.Profile
import app.recomp.domain.persona.WeightSource
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The weekly check-in (NU-05, GL-08, PRD §12.1): catch-up, the estimate's chain, frozen rows, the answers. Invented data. */
@RunWith(RobolectricTestRunner::class)
class CheckinFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: TestApp
    private val checkins get() = app.checkins
    private val dao get() = app.db.checkinDao()

    /** Onboarding day, a Monday. */
    private val day0 = LocalDate.of(2026, 6, 1)

    private fun date(d: Int): LocalDate = day0.plusDays(d.toLong())

    private fun at(d: Int, hour: Int = 12): Long = LocalDateTime.of(date(d), LocalTime.of(hour, 0)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        app = TestApp(File(tmp.root, "app").apply { mkdirs() })
    }

    @After
    fun tearDown() = app.close()

    /** Invented body data: male, 30, 175 cm, 80 kg, ×1.375, so the formula says about 2,405 kcal. */
    private suspend fun onboard(goal: Goal = Goal.MAINTAIN) {
        val profile = Profile(Sex.MALE, LocalDate.of(1996, 1, 1), heightCm = 175.0, weightKg = 80.0)
        val plan = GoalPlan.of(goal, Pace.STANDARD, null, null, day0)
        val draft = Presets.REFERENCE.draft.copy(goal = plan)
        app.personas.completeOnboarding(OnboardingResult(draft, Presets.REFERENCE.key, emptyList(), profile, LocalTime.of(4, 0)), now = at(0))
    }

    private suspend fun weigh(d: Int, kg: Double) = app.body.logWeighIn((kg * 1000).roundToInt(), WeightSource.HOME_SCALE, at(d, 7))

    private suspend fun eat(d: Int, kcal: Double = 2600.0) = app.foodLog.quickAdd(date(d), "lunch", "Test day", kcal, 120.0, null, null, Origin.HOME, now = at(d, 13))

    /** Gaining 0.05 kg a day on 2,600 kcal, weighed and logged every day: maintenance is about 2,215. */
    private suspend fun gaining(days: IntRange, food: IntRange = days) {
        days.forEach { weigh(it, 80.0 + 0.05 * it) }
        food.forEach { eat(it) }
    }

    private suspend fun catchUp(d: Int, hour: Int = 12) = checkins.catchUp(date(d), at(d, hour))

    /** Six weeks of a maintenance diet that drifts upward, checked in on the Monday after: the last week proposes. */
    private suspend fun drifting(): WeeklyCheckinEntity {
        onboard()
        gaining(0..41)
        catchUp(42)
        return dao.latest()!!.also { assertNotNull(it.proposedKcal) }
    }

    @Test
    fun `the first launch after weeks end computes each one in order, and only the latest stays open`() = runBlocking {
        onboard()
        catchUp(28)
        val rows = dao.all()
        assertEquals(listOf(date(0), date(7), date(14), date(21)), rows.map { it.weekStart })
        assertEquals(listOf(LAPSED, LAPSED, LAPSED, PENDING), rows.map { it.decision })
        // Nothing logged: no estimate, so the first target's TDEE carries forward.
        val initial = app.db.nutritionDao().first()!!.tdeeEstimate
        assertEquals(2405, initial)
        rows.forEach {
            assertNull(it.tdeeObs)
            assertEquals(initial, it.tdeePrev)
            assertEquals(initial, it.tdeeNew)
        }

        // Again the same day: nothing new. Monday before the 4 AM day start is still Sunday.
        catchUp(28)
        checkins.catchUp(now = at(35, 2))
        assertEquals(4, dao.all().size)
        // The next week's closes this one.
        catchUp(35)
        assertEquals(listOf(LAPSED, PENDING), dao.all().takeLast(2).map { it.decision })
        assertEquals(date(28), dao.latest()!!.weekStart)
    }

    @Test
    fun `nothing is computed before onboarding`() = runBlocking {
        catchUp(28)
        assertTrue(dao.all().isEmpty())
    }

    @Test
    fun `the estimate chains from week to week and the goal's flag proposes`() = runBlocking {
        val latest = drifting()
        val rows = dao.all()
        assertEquals(6, rows.size)
        // Two weeks without 17 days of food yet, then an estimate every week.
        assertEquals(listOf(EstimateGap.FOOD_DAYS, EstimateGap.FOOD_DAYS), rows.take(2).map { checkins.open(it.weekStart, at(42))!!.gap })
        rows.drop(2).forEach { assertNotNull(it.tdeeObs) }
        rows.zipWithNext().forEach { (a, b) -> assertEquals(a.tdeeNew, b.tdeePrev) }
        rows.forEach { assertTrue(abs(it.tdeeNew - it.tdeePrev) <= 150) }
        assertEquals(2215.0, latest.tdeeObs!!.toDouble(), 60.0)
        assertTrue(latest.tdeeNew < 2405)
        assertEquals(21, latest.windowDays)
        assertEquals(21, latest.loggedDays)
        // Over 1% in 4 weeks: Maintain's flag. Its target is the estimate itself, on the 10 kcal grid.
        assertEquals(CheckinFlag.DRIFT, latest.flag)
        assertEquals((latest.tdeeNew / 10.0).roundToInt() * 10, latest.proposedKcal)
    }

    @Test
    fun `the screen shows the week, the estimate against the formula, and the proposal`() = runBlocking {
        val latest = drifting()
        val view = checkins.open(latest.weekStart, at(42))!!
        assertEquals(CheckinStatus.FLAGGED, view.status)
        assertNull(view.gap)
        // The formula at the trend weight, which has risen about 1.6 kg.
        assertTrue(view.formulaTdee in 2410..2450)
        assertEquals(7, view.week!!.daysLogged)
        assertEquals(2600, view.week!!.kcal)
        assertEquals(2400, view.current.kcal)
        assertEquals(latest.proposedKcal, view.proposal!!.kcal)
        assertTrue(view.proposal!!.kcal in view.adjustRange!!)
        assertTrue(view.evaluation.fourWeekPct!! > 1.0)
        assertNull(view.written)
    }

    @Test
    fun `accept writes a check-in target from today with the new estimate`() = runBlocking {
        val latest = drifting()
        assertTrue(app.personas.checkinWaiting.first())
        assertTrue(checkins.accept(latest.weekStart, now = at(42, 18)))
        assertFalse(app.personas.checkinWaiting.first())
        val row = dao.week(latest.weekStart)!!
        assertEquals(ACCEPTED, row.decision)
        assertEquals(at(42, 18), row.decidedAt)
        val target = app.db.nutritionDao().targetOn(date(42))!!
        assertEquals(row.targetId, target.id)
        assertEquals(target, app.personas.observeTargetOn(date(42)).first())
        assertEquals(TargetReason.CHECKIN, target.reason)
        assertEquals(date(42), target.effectiveFrom)
        assertEquals(row.tdeeNew, target.tdeeEstimate)
        assertEquals(latest.proposedKcal, target.kcal)
        // The body data now holds the trend weight the target used (TRD §5.0).
        assertEquals(app.body.trendKg()!!, app.prefs.profile.first()!!.weightKg, 0.0)
        assertEquals(target, checkins.open(latest.weekStart, at(42, 19))!!.written)
        // Answered once: frozen.
        assertFalse(checkins.keep(latest.weekStart, at(42, 19)))
        assertFalse(checkins.accept(latest.weekStart, now = at(42, 19)))
    }

    @Test
    fun `a decided row stays frozen, and the next week starts from its estimate`() = runBlocking {
        val latest = drifting()
        checkins.keep(latest.weekStart, at(42))
        val frozen = dao.week(latest.weekStart)!!
        assertEquals(KEPT, frozen.decision)
        assertEquals(1, app.db.nutritionDao().all().size)

        // Late logging for that week changes nothing about it, opened or not.
        eat(40, 900.0)
        weigh(41, 90.0)
        assertEquals(frozen, checkins.open(latest.weekStart, at(43))!!.row)
        catchUp(49)
        assertEquals(frozen, dao.week(latest.weekStart))
        assertEquals(frozen.tdeeNew, dao.week(date(42))!!.tdeePrev)
    }

    @Test
    fun `a pending week is recomputed when opened, so late logging counts`() = runBlocking {
        onboard()
        gaining(0..41, food = 0..34)
        catchUp(42)
        val before = dao.latest()!!
        assertEquals(14, before.loggedDays)
        assertNull(before.tdeeObs)

        (35..41).forEach { eat(it) }
        val view = checkins.open(before.weekStart, at(43))!!
        assertEquals(21, view.row.loggedDays)
        assertNotNull(view.row.tdeeObs)
        assertEquals(at(43), view.row.computedAt)
        assertEquals(view.row, dao.latest())
    }

    @Test
    fun `an unanswered proposal lapses when the next week's is ready, and can't be answered after`() = runBlocking {
        val latest = drifting()
        checkins.open(latest.weekStart, at(42))
        catchUp(49)
        val lapsed = dao.week(latest.weekStart)!!
        assertEquals(LAPSED, lapsed.decision)
        assertEquals(at(49), lapsed.decidedAt)
        assertFalse(checkins.accept(latest.weekStart, now = at(49)))
        assertTrue(app.db.nutritionDao().all().none { it.reason == TargetReason.CHECKIN })
        // Its estimate still counts.
        assertEquals(lapsed.tdeeNew, dao.latest()!!.tdeePrev)
    }

    @Test
    fun `Today's card shows until the check-in is opened or the next one is ready`() = runBlocking {
        onboard()
        assertNull(checkins.observeCard().first())
        catchUp(7)
        assertEquals(date(0), checkins.observeCard().first()?.weekStart)
        checkins.open(date(0), at(7))
        assertNull(checkins.observeCard().first())
        catchUp(14)
        assertEquals(date(7), checkins.observeCard().first()?.weekStart)
        // Opening an older week doesn't bring a seen one back.
        checkins.open(date(7), at(14))
        checkins.open(date(0), at(14))
        assertNull(checkins.observeCard().first())
    }

    @Test
    fun `your calorie target stays on accept, unless you use the new target`() = runBlocking {
        val latest = drifting()
        app.prefs.edit { setOverrides(TargetOverrides(kcal = 2500, proteinG = 150)) }
        assertEquals(2500, checkins.open(latest.weekStart, at(42))!!.kcalOverride)
        checkins.accept(latest.weekStart, now = at(42))
        val kept = app.db.nutritionDao().targetOn(date(42))!!
        assertEquals(2500, kept.kcal)
        assertEquals(150, kept.proteinG)
        assertEquals(latest.tdeeNew, kept.tdeeEstimate)
        assertEquals(TargetOverrides(kcal = 2500, proteinG = 150), app.prefs.overrides.first())
    }

    @Test
    fun `use the new target resets your calorie target and keeps your protein`() = runBlocking {
        val latest = drifting()
        app.prefs.edit { setOverrides(TargetOverrides(kcal = 2500, proteinG = 150)) }
        checkins.accept(latest.weekStart, useNewTarget = true, now = at(42))
        val target = app.db.nutritionDao().targetOn(date(42))!!
        assertEquals(latest.proposedKcal, target.kcal)
        assertEquals(150, target.proteinG)
        assertEquals(TargetOverrides(proteinG = 150), app.prefs.overrides.first())
    }

    @Test
    fun `adjust writes the stepped target, held to the floor and caps`() = runBlocking {
        val latest = drifting()
        app.prefs.edit { setOverrides(TargetOverrides(kcal = 2500)) }
        val view = checkins.open(latest.weekStart, at(42))!!
        val range = view.adjustRange!!
        val down = CheckinProposal.step(view.proposal!!.kcal, up = false, range)
        assertTrue(checkins.adjust(latest.weekStart, down, now = at(42)))
        assertEquals(ADJUSTED, dao.week(latest.weekStart)!!.decision)
        val target = app.db.nutritionDao().targetOn(date(42))!!
        assertEquals(down, target.kcal)
        assertEquals(TargetReason.CHECKIN, target.reason)
        // Adjusting sets the calories here, so your own calorie target goes.
        assertEquals(TargetOverrides(), app.prefs.overrides.first())
    }

    @Test
    fun `adjust can't go past the caps`() = runBlocking {
        val latest = drifting()
        val range = checkins.open(latest.weekStart, at(42))!!.adjustRange!!
        checkins.adjust(latest.weekStart, 100, now = at(42))
        assertEquals(range.first, app.db.nutritionDao().targetOn(date(42))!!.kcal)
    }

    @Test
    fun `days marked incomplete are left out of the estimate`() = runBlocking {
        onboard()
        gaining(0..41)
        (35..39).forEach { checkins.setFoodIncomplete(date(it), true) }
        catchUp(42)
        val row = dao.latest()!!
        assertEquals(16, row.loggedDays)
        assertNull(row.tdeeObs)
        assertEquals(EstimateGap.FOOD_DAYS, checkins.open(row.weekStart, at(42))!!.gap)
        assertEquals(2, checkins.open(row.weekStart, at(42))!!.week!!.daysLogged)

        // And back.
        checkins.setFoodIncomplete(date(39), false)
        assertFalse(checkins.observeFoodIncomplete(date(39)).first())
        assertTrue(checkins.observeFoodIncomplete(date(38)).first())
        assertEquals(17, checkins.open(row.weekStart, at(43))!!.row.loggedDays)
    }

    @Test
    fun `days marked incomplete are left out of Today's 7-day average`() = runBlocking {
        onboard()
        val today = app.days.today()
        app.foodLog.quickAdd(today.minusDays(1), "lunch", "Short day", 900.0, 40.0, null, null, Origin.HOME)
        app.foodLog.quickAdd(today.minusDays(2), "lunch", "Full day", 2300.0, 110.0, null, null, Origin.HOME)
        assertEquals(2, app.nutrition.observeToday().first()!!.week!!.daysLogged)

        checkins.setFoodIncomplete(today.minusDays(1), true)
        val week = app.nutrition.observeToday().first()!!.week!!
        assertEquals(1, week.daysLogged)
        assertEquals(2300, week.kcal)
        // Every day incomplete reads as nothing logged.
        checkins.setFoodIncomplete(today.minusDays(2), true)
        assertNull(app.nutrition.observeToday().first()!!.week)
    }

    // Review fixes (PR #10)

    /** A woman of 175 cm on a cut, onboarded at [startKg], losing [perDay] kg a day on [kcal]. Invented. */
    private suspend fun cutting(startKg: Double, perDay: Double, kcal: Double) {
        val profile = Profile(Sex.FEMALE, LocalDate.of(1996, 1, 1), heightCm = 175.0, weightKg = startKg)
        val plan = GoalPlan.of(Goal.FAT_LOSS, Pace.STANDARD, null, null, day0)
        app.personas.completeOnboarding(OnboardingResult(Presets.REFERENCE.draft.copy(goal = plan), Presets.REFERENCE.key, emptyList(), profile, LocalTime.of(4, 0)), now = at(0))
        (0..41).forEach { weigh(it, startKg - perDay * it); eat(it, kcal) }
        catchUp(42)
    }

    @Test
    fun `a cut whose trend falls below BMI 18_5 gets no deficit from the check-in, as in Settings`() = runBlocking {
        // The review's probe: the trend reaches 56.6 kg, BMI 18.48, while losing slowly.
        cutting(startKg = 56.9, perDay = 0.01, kcal = 2200.0)
        val latest = dao.latest()!!
        val view = checkins.open(latest.weekStart, at(42))!!
        assertEquals(Goal.MAINTAIN, view.plan.goal)
        assertEquals(listOf(GuardrailNote.LOW_BMI_MAINTAIN), view.notes)
        assertEquals(Goal.MAINTAIN, app.personas.preview().plan.goal)
        // Judged as Maintain, a 0.5% drift isn't a flag, so nothing is proposed, let alone a deficit.
        assertNull(view.row.flag)
        assertNull(view.row.proposedKcal)
        assertFalse(checkins.accept(latest.weekStart, now = at(42)))
        assertTrue(app.db.nutritionDao().all().none { it.reason == TargetReason.CHECKIN })
    }

    @Test
    fun `accepting under the guardrails writes the guarded goal and stores it, as a Settings save does`() = runBlocking {
        // Losing fast enough that Maintain's 1% drift fires once the trend is under BMI 18.5.
        cutting(startKg = 58.0, perDay = 0.05, kcal = 1800.0)
        val latest = dao.latest()!!
        assertEquals(CheckinFlag.DRIFT, latest.flag)
        val view = checkins.open(latest.weekStart, at(42))!!
        assertEquals(listOf(GuardrailNote.LOW_BMI_MAINTAIN), view.notes)
        assertTrue(checkins.accept(latest.weekStart, now = at(42)))
        val target = app.db.nutritionDao().targetOn(date(42))!!
        assertEquals(Goal.MAINTAIN, target.goal)
        assertEquals(latest.proposedKcal, target.kcal)
        assertFalse(EnergyBalance.inDeficit(target.kcal, target.tdeeEstimate))
        assertEquals(Goal.MAINTAIN, app.prefs.currentPersona().goal!!.goal)
    }

    @Test
    fun `two answers at once write once, and the second is refused`() = runBlocking {
        val latest = drifting()
        val answers = coroutineScope {
            listOf(async { checkins.accept(latest.weekStart, now = at(42)) }, async { checkins.keep(latest.weekStart, at(42)) }).awaitAll()
        }
        assertEquals(1, answers.count { it })
        assertEquals(if (answers[0]) ACCEPTED else KEPT, dao.week(latest.weekStart)!!.decision)
        assertTrue(app.db.nutritionDao().all().count { it.reason == TargetReason.CHECKIN } <= 1)
    }

    @Test
    fun `settings that fail after the target is saved say so, and the answer stands`() = runBlocking {
        val latest = drifting()
        app.store.failWrites = true
        val thrown = runCatching { checkins.accept(latest.weekStart, now = at(42)) }.exceptionOrNull()
        app.store.failWrites = false
        assertTrue("$thrown", thrown is CheckinSettingsException)
        assertEquals(ACCEPTED, dao.week(latest.weekStart)!!.decision)
        assertEquals(TargetReason.CHECKIN, app.db.nutritionDao().targetOn(date(42))!!.reason)
    }

    @Test
    fun `opening an old week catches up first, so a week that should have lapsed can't be answered`() = runBlocking {
        val latest = drifting()
        // A week later, opened straight from Settings with no catch-up from Today's card.
        val view = checkins.open(latest.weekStart, at(50))!!
        assertEquals(LAPSED, view.row.decision)
        assertNull(view.proposal)
        assertEquals(PENDING, dao.week(date(42))!!.decision)
        assertFalse(checkins.accept(latest.weekStart, now = at(50)))
    }

    @Test
    fun `the decision date is the log date, under the day boundary`() = runBlocking {
        val latest = drifting()
        // 01:00 on Tuesday is still Monday's log day with the 4 AM start.
        checkins.accept(latest.weekStart, now = at(43, 1))
        val view = checkins.open(latest.weekStart, at(43, 2))!!
        assertEquals(date(42), view.written!!.effectiveFrom)
        assertEquals(date(42), view.decidedOn)
    }

    @Test
    fun `marking a day incomplete keeps its steps and sleep`() = runBlocking {
        onboard()
        dao.upsert(DailyActivityEntity(date(3), steps = 8_000, stepsSyncedAt = 5, sleepHours = 7.5))
        checkins.setFoodIncomplete(date(3), true)
        assertEquals(DailyActivityEntity(date(3), foodIncomplete = true, steps = 8_000, stepsSyncedAt = 5, sleepHours = 7.5), dao.activity(date(3)))
        checkins.setFoodIncomplete(date(3), false)
        assertEquals(DailyActivityEntity(date(3), foodIncomplete = false, steps = 8_000, stepsSyncedAt = 5, sleepHours = 7.5), dao.activity(date(3)))
        // A day with no row yet gets one with only the flag.
        checkins.setFoodIncomplete(date(4), true)
        assertEquals(DailyActivityEntity(date(4), foodIncomplete = true), dao.activity(date(4)))
    }

    @Test
    fun `weeks closed in the same catch-up keep no proposal, but one you could have seen does`() = runBlocking {
        drifting()
        val rows = dao.all()
        // Week 4 fired too, but nobody saw it: it lapsed in the catch-up that computed it.
        assertEquals(CheckinFlag.DRIFT, rows[4].flag)
        assertNull(rows[4].proposedKcal)
        rows.dropLast(1).forEach { assertNull(it.proposedKcal) }
        assertNotNull(rows.last().proposedKcal)
        // The last one was pending, so it could have been seen; it keeps its proposal when it lapses.
        catchUp(49)
        assertNotNull(dao.week(rows.last().weekStart)!!.proposedKcal)
        assertEquals(LAPSED, dao.week(rows.last().weekStart)!!.decision)
    }

    @Test
    fun `a clock that ran ahead leaves no unanswered future weeks behind`() = runBlocking {
        onboard()
        gaining(0..41)
        catchUp(70)
        assertEquals(date(63), dao.latest()!!.weekStart)
        checkins.keep(date(63), at(70))
        // Back to the real date: the future weeks nobody answered go, the answer stays, and last week is open again.
        catchUp(42)
        assertNull(dao.week(date(42)))
        assertNull(dao.week(date(56)))
        assertEquals(KEPT, dao.week(date(63))!!.decision)
        assertEquals(PENDING, dao.week(date(35))!!.decision)
        // A week later, the week that has now ended is computed and last week lapses.
        catchUp(49)
        assertEquals(PENDING, dao.week(date(42))!!.decision)
        assertEquals(LAPSED, dao.week(date(35))!!.decision)
    }

    @Test
    fun `a log date that moves back across Monday midnight changes nothing`() = runBlocking {
        onboard()
        gaining(0..41)
        // Monday 03:00, then "day starts at" moves past it, so the log date is Sunday again an hour later.
        catchUp(42, hour = 3)
        val monday = dao.all()
        checkins.catchUp(date(41), at(42, 4))
        assertEquals(monday, dao.all())
        assertEquals(PENDING, dao.week(date(35))!!.decision)
        assertEquals(LAPSED, dao.week(date(28))!!.decision)
        // The next Monday carries on as usual.
        catchUp(49)
        assertEquals(PENDING, dao.week(date(42))!!.decision)
        assertEquals(LAPSED, dao.week(date(35))!!.decision)
        assertEquals(monday.dropLast(1), dao.all().take(monday.size - 1))
    }
}
