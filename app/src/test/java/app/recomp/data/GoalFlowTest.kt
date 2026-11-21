package app.recomp.data

import app.recomp.data.db.CheckinDecision
import app.recomp.data.db.NutritionTargetEntity
import app.recomp.data.db.TargetReason
import app.recomp.data.prefs.StoredGoal
import app.recomp.data.prefs.StoredProfile
import app.recomp.data.repo.OnboardingResult
import app.recomp.data.repo.WeekPlan
import app.recomp.ui.checkin.CheckinText
import app.recomp.ui.checkin.ReviewText
import app.recomp.domain.checkin.CheckinFlag
import app.recomp.domain.checkin.CheckinStatus
import app.recomp.domain.food.Origin
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.Pace
import app.recomp.domain.nutrition.Sex
import app.recomp.domain.nutrition.TargetOverrides
import app.recomp.domain.persona.FinishLine
import app.recomp.domain.persona.GoalPlan
import app.recomp.domain.persona.GoalProgress
import app.recomp.domain.persona.GoalStatus
import app.recomp.domain.persona.GuardrailNote
import app.recomp.domain.persona.Presets
import app.recomp.domain.persona.Profile
import app.recomp.domain.persona.WeightSource
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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

/**
 * M19 over real storage (GL-06, GL-09, PS-04; PRD §12.15): Goal reached, the maintenance break's two target
 * versions and its automatic resume, the pregnancy guardrail, and what a goal save keeps. Invented data.
 */
@RunWith(RobolectricTestRunner::class)
class GoalFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: TestApp
    private val goals get() = app.goals
    private val targets get() = app.db.nutritionDao()
    private val checkinDao get() = app.db.checkinDao()

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

    /** Invented body data: 30, 175 cm, 80 kg, ×1.375, so the formula says about 2,405 kcal. */
    private val profile = Profile(Sex.MALE, LocalDate.of(1996, 1, 1), heightCm = 175.0, weightKg = 80.0)

    private suspend fun onboard(goal: Goal = Goal.FAT_LOSS, goalKg: Double? = null, finish: FinishLine? = null, who: Profile = profile) {
        val plan = GoalPlan.of(goal, Pace.STANDARD, goalKg, finish, day0)
        app.personas.completeOnboarding(OnboardingResult(Presets.REFERENCE.draft.copy(goal = plan), Presets.REFERENCE.key, emptyList(), who, LocalTime.of(4, 0)), now = at(0))
    }

    private suspend fun plan(): GoalPlan = app.prefs.currentPersona().goal!!.toPlan()

    private suspend fun weigh(d: Int, kg: Double) = app.body.logWeighIn((kg * 1000).roundToInt(), WeightSource.HOME_SCALE, at(d, 7))

    private suspend fun eat(d: Int, kcal: Double = 2400.0) = app.foodLog.quickAdd(date(d), "lunch", "Test day", kcal, 120.0, null, null, Origin.HOME, now = at(d, 13))

    /** Calories are the maintenance estimate on the 10 kcal grid. */
    private fun atMaintenance(row: NutritionTargetEntity) = kotlin.math.abs(row.kcal - row.tdeeEstimate) <= 5

    private suspend fun status(d: Int): GoalStatus {
        // The flow follows the real clock's day, so the state is judged here as the card would on day [d].
        return GoalProgress.status(plan(), date(d))
    }

    // The maintenance break

    @Test
    fun `a break is two target versions, and the goal resumes on the first launch after its end date`() = runBlocking {
        onboard()
        val deficit = targets.targetOn(date(0))!!
        assertEquals(Goal.FAT_LOSS, deficit.goal)
        assertTrue(deficit.kcal < deficit.tdeeEstimate)

        // Not before 12 weeks.
        assertEquals(GoalStatus.None, status(83))
        assertFalse(goals.startBreak(2, at(83)))
        assertTrue(status(84) is GoalStatus.BreakDue)

        assertTrue(goals.startBreak(2, at(84)))
        val rest = targets.targetOn(date(84))!!
        assertEquals(TargetReason.BREAK, rest.reason)
        assertEquals(date(84), rest.effectiveFrom)
        assertEquals(Goal.MAINTAIN, rest.goal)
        // Maintenance: the formula's TDEE on the 10 kcal grid, with the goal's own protein.
        assertTrue(atMaintenance(rest))
        assertEquals(deficit.proteinG, rest.proteinG)
        assertEquals(date(98), plan().breakUntil)
        assertEquals(Goal.FAT_LOSS, plan().goal)
        assertEquals(GoalStatus.OnBreak(plan(), date(98)), status(90))
        // Earlier days keep their targets.
        assertEquals(deficit, targets.targetOn(date(83)))

        // Launches during the break change nothing.
        goals.refresh(at(90))
        goals.refresh(at(97))
        assertEquals(2, targets.all().size)
        assertEquals(date(98), plan().breakUntil)

        // Five days late: the first launch on or after the end date resumes, from that day.
        goals.refresh(at(103))
        val back = targets.targetOn(date(103))!!
        assertEquals(TargetReason.RESUME, back.reason)
        assertEquals(date(103), back.effectiveFrom)
        assertEquals(Goal.FAT_LOSS, back.goal)
        assertEquals(deficit.kcal, back.kcal)
        assertEquals(listOf(TargetReason.INITIAL, TargetReason.BREAK, TargetReason.RESUME), targets.all().map { it.reason })
        assertNull(plan().breakUntil)
        // The 12 weeks count again from the end date, not from the late launch.
        assertEquals(date(98), plan().lastBreakEndedOn)
        assertEquals(day0, plan().startedOn)
        assertEquals(GoalStatus.None, status(98 + 83))
        assertTrue(status(98 + 84) is GoalStatus.BreakDue)
        // Another launch writes nothing more.
        goals.refresh(at(104))
        assertEquals(3, targets.all().size)
    }

    @Test
    fun `the break resumes on its end date itself, and survives the app being killed`() = runBlocking {
        onboard(Goal.RECOMP)
        assertTrue(goals.startBreak(1, at(84)))
        app.restart()
        assertEquals(date(91), plan().breakUntil)
        app.goals.refresh(at(91, 9))
        assertEquals(TargetReason.RESUME, targets.targetOn(date(91))!!.reason)
        assertEquals(Goal.RECOMP, targets.targetOn(date(91))!!.goal)
        assertEquals(date(91), plan().lastBreakEndedOn)
    }

    @Test
    fun `End early brings the goal's targets back that day`() = runBlocking {
        onboard()
        assertFalse(goals.endBreakEarly(at(84)))
        assertTrue(goals.startBreak(2, at(84)))
        assertTrue(goals.endBreakEarly(at(88)))
        val back = targets.targetOn(date(88))!!
        assertEquals(TargetReason.RESUME, back.reason)
        assertEquals(Goal.FAT_LOSS, back.goal)
        assertNull(plan().breakUntil)
        assertEquals(date(88), plan().lastBreakEndedOn)
        // The break's own days keep maintenance.
        assertEquals(TargetReason.BREAK, targets.targetOn(date(87))!!.reason)
        goals.refresh(at(98))
        assertEquals(3, targets.all().size)
    }

    @Test
    fun `Not now asks again after 4 weeks`() = runBlocking {
        onboard()
        assertFalse(goals.snoozeBreak(at(50)))
        assertTrue(goals.snoozeBreak(at(84)))
        assertEquals(1, targets.all().size)
        assertEquals(GoalStatus.None, status(84))
        assertEquals(GoalStatus.None, status(111))
        assertTrue(status(112) is GoalStatus.BreakDue)
        // It can still be accepted meanwhile, and only for 1 or 2 weeks.
        assertFalse(goals.startBreak(3, at(100)))
        assertTrue(goals.startBreak(1, at(100)))
        assertNull(plan().breakSnoozedUntil)
    }

    @Test
    fun `during a break your own calorie target waits, and a body-data change keeps maintenance`() = runBlocking {
        onboard()
        app.personas.save(overrides = TargetOverrides(kcal = 1800), now = at(10))
        assertEquals(1800, targets.targetOn(date(10))!!.kcal)

        assertTrue(goals.startBreak(2, at(84)))
        val rest = targets.targetOn(date(84))!!
        assertTrue(atMaintenance(rest))
        assertEquals(1800, app.prefs.overrides.first().kcal)

        // Settings recomputes at maintenance too, and doesn't mistake the break for a change of goal.
        val preview = app.personas.preview(profile = profile.copy(heightCm = 176.0))
        assertEquals(Goal.MAINTAIN, preview.targetGoal)
        assertEquals(Goal.FAT_LOSS, preview.plan.goal)
        assertNull(preview.after.overrides.kcal)
        assertEquals(date(98), preview.plan.breakUntil)

        // Your number is back when the goal resumes.
        goals.refresh(at(98))
        assertEquals(1800, targets.targetOn(date(98))!!.kcal)
        assertEquals(TargetReason.RESUME, targets.targetOn(date(98))!!.reason)
    }

    @Test
    fun `no flag or proposal during a break, nor until the flag's window is back on the goal`() = runBlocking {
        onboard()
        // A fat-loss goal with a flat scale on 2,100 kcal: losing under half the pace, and maintenance is well under
        // the formula's, so the check-in proposes less.
        (0..125).forEach { weigh(it, 80.0); eat(it, 2100.0) }
        app.checkins.catchUp(date(84), at(84))
        val flagged = checkinDao.latest()!!
        assertEquals(date(77), flagged.weekStart)
        assertEquals(CheckinFlag.LOSS_SLOW, flagged.flag)
        assertNotNull(flagged.proposedKcal)

        // The break is accepted in that check-in: opened again, the week has nothing left to answer.
        assertTrue(goals.startBreak(1, at(84, 13)))
        assertFalse(app.checkins.accept(flagged.weekStart, now = at(84, 14)))
        val view = app.checkins.open(flagged.weekStart, at(84, 14))!!
        assertTrue(view.quiet)
        assertNull(view.proposal)
        assertNull(view.row.flag)
        assertEquals(CheckinStatus.ON_TRACK, view.status)
        assertEquals(TargetReason.BREAK, targets.targetOn(date(84))!!.reason)

        // The break's own week: the estimate still updates, and nothing is flagged or proposed.
        app.checkins.catchUp(date(91), at(91))
        val resting = checkinDao.latest()!!
        assertEquals(date(84), resting.weekStart)
        assertNotNull(resting.tdeeObs)
        assertNull(resting.flag)
        assertNull(resting.proposedKcal)

        // Back on the goal from day 91. Two more weeks until both weekly rates are after the break.
        goals.refresh(at(91, 13))
        assertEquals(TargetReason.RESUME, targets.targetOn(date(91))!!.reason)
        app.checkins.catchUp(date(98), at(98))
        assertNull(checkinDao.latest()!!.flag)
        assertTrue(app.checkins.open(date(91), at(98))!!.quiet)
        app.checkins.catchUp(date(105), at(105))
        assertNull(checkinDao.latest()!!.flag)
        app.checkins.catchUp(date(112), at(112))
        val judged = checkinDao.latest()!!
        assertEquals(date(105), judged.weekStart)
        assertEquals(CheckinFlag.LOSS_SLOW, judged.flag)
        assertFalse(app.checkins.open(judged.weekStart, at(112))!!.quiet)
        assertTrue(checkinDao.all().none { it.decision == CheckinDecision.ACCEPTED })
    }

    // Goal reached

    @Test
    fun `the goal is reached on the trend, not on one weigh-in`() = runBlocking {
        onboard(Goal.RECOMP, goalKg = 78.0, finish = FinishLine.GoalWeight)
        (0..20).forEach { weigh(it, 79.0) }
        // One low morning: the reading is under the goal weight, the trend isn't.
        weigh(21, 77.2)
        assertTrue(app.body.trendKg()!! > 78.0)
        goals.refresh(at(21))
        assertNull(plan().reachedOn)

        // The scale stays there, and the trend follows it down across the line.
        var d = 22
        while (app.body.trendKg()!! > 78.0) weigh(d++, 77.2)
        assertTrue(d > 23)
        goals.refresh(at(d))
        assertEquals(date(d), plan().reachedOn)
        val reached = status(d) as GoalStatus.Reached
        assertTrue(reached.offerMaintenance)
        // Once reached it stays reached, whatever tomorrow's weigh-in says.
        weigh(d + 1, 79.5)
        weigh(d + 2, 79.5)
        goals.refresh(at(d + 2))
        assertEquals(date(d), plan().reachedOn)
        assertEquals(1, targets.all().size)
    }

    @Test
    fun `a date finish line is reached when the day comes`() = runBlocking {
        onboard(Goal.LEAN_BULK, finish = FinishLine.ByDate(date(112)))
        goals.refresh(at(111))
        assertNull(plan().reachedOn)
        goals.refresh(at(115))
        assertEquals(date(115), plan().reachedOn)
        assertTrue(status(115) is GoalStatus.Reached)
    }

    @Test
    fun `after reaching, 2 weeks at maintenance end with a choice, and Keep going clears the finish line`() = runBlocking {
        onboard(Goal.RECOMP, goalKg = 78.0, finish = FinishLine.GoalWeight)
        (0..30).forEach { weigh(it, 77.5) }
        goals.refresh(at(30))
        assertEquals(date(30), plan().reachedOn)

        // Only the 2 weeks are offered here.
        assertFalse(goals.startBreak(1, at(30)))
        assertTrue(goals.startBreak(2, at(30)))
        assertEquals(TargetReason.BREAK, targets.targetOn(date(30))!!.reason)
        assertEquals(date(44), (status(40) as GoalStatus.Reached).maintenanceUntil)
        assertFalse(goals.endBreakEarly(at(35)))

        // The 2 weeks pass: no deficit comes back on its own.
        goals.refresh(at(50))
        assertEquals(2, targets.all().size)
        assertTrue((status(50) as GoalStatus.Reached).maintenanceDone)
        assertEquals(Goal.MAINTAIN, targets.targetOn(date(50))!!.goal)

        assertTrue(goals.keepGoing(at(50)))
        val back = targets.targetOn(date(50))!!
        assertEquals(TargetReason.RESUME, back.reason)
        assertEquals(Goal.RECOMP, back.goal)
        assertNull(plan().finishLine)
        assertNull(plan().reachedOn)
        assertEquals(78.0, plan().goalWeightKg)
        assertEquals(GoalStatus.None, status(50))
        // With no finish line, nothing is reached again.
        goals.refresh(at(51))
        assertNull(plan().reachedOn)
        assertFalse(goals.keepGoing(at(51)))
    }

    @Test
    fun `Keep going straight away writes no target, and a new goal from the flow starts afresh`() = runBlocking {
        onboard(Goal.RECOMP, goalKg = 78.0, finish = FinishLine.GoalWeight)
        (0..30).forEach { weigh(it, 77.5) }
        goals.refresh(at(30))
        assertTrue(goals.keepGoing(at(30)))
        assertEquals(1, targets.all().size)
        assertNull(plan().finishLine)

        // Choose a new goal: the goal flow's save.
        val today = app.days.today()
        app.personas.save(plan = GoalPlan.of(Goal.LEAN_BULK, Pace.STANDARD, null, null, today))
        assertEquals(Goal.LEAN_BULK, plan().goal)
        assertEquals(today, plan().startedOn)
        assertEquals(TargetReason.MANUAL, targets.all().last().reason)
    }

    // Goal switch (PS-04)

    @Test
    fun `a pace or goal-weight edit keeps the goal's start, and a change of goal resets it`() = runBlocking {
        onboard(Goal.RECOMP, goalKg = 78.0, finish = FinishLine.GoalWeight)
        val today = app.days.today()
        assertTrue(today.isAfter(day0))

        // The goal flow builds its plan as of today, whatever was edited.
        app.personas.save(plan = GoalPlan.of(Goal.RECOMP, Pace.GENTLE, 76.0, FinishLine.GoalWeight, today))
        assertEquals(day0, plan().startedOn)
        assertEquals(Pace.GENTLE, plan().pace)
        assertEquals(76.0, plan().goalWeightKg)

        // Body data and your own targets never touch it either.
        app.personas.save(profile = profile.copy(heightCm = 176.0))
        app.personas.save(overrides = TargetOverrides(proteinG = 150))
        assertEquals(day0, plan().startedOn)

        app.personas.save(plan = GoalPlan.of(Goal.FAT_LOSS, Pace.STANDARD, 76.0, FinishLine.GoalWeight, today))
        assertEquals(today, plan().startedOn)
        assertEquals(Goal.FAT_LOSS, plan().goal)
    }

    @Test
    fun `a goal edit ends a running break, and the persona round-trips its new fields`() = runBlocking {
        onboard()
        val today = app.days.today()
        val marked = plan().copy(breakUntil = today.plusDays(5), breakSnoozedUntil = today.plusDays(9), reachedOn = today)
        app.prefs.updatePersona { it.copy(goal = StoredGoal.of(marked)) }
        app.restart()
        assertEquals(today.plusDays(5), plan().breakUntil)
        assertEquals(today.plusDays(9), plan().breakSnoozedUntil)
        assertEquals(today, plan().reachedOn)
        assertTrue(app.prefs.problems(app.prefs.exportable()).isEmpty())

        app.personas.save(plan = GoalPlan.of(Goal.FAT_LOSS, Pace.GENTLE, null, null, today))
        assertNull(plan().breakUntil)
        assertNull(plan().reachedOn)
        assertEquals(today, plan().lastBreakEndedOn)
        assertEquals(day0, plan().startedOn)
        assertEquals(Goal.FAT_LOSS, targets.all().last().goal)
    }

    @Test
    fun `a persona and profile stored before M19 read as no break, not reached and not pregnant`() {
        val json = Json { ignoreUnknownKeys = true }
        val goal = json.decodeFromString<StoredGoal>("""{"goal":"RECOMP","pace":"STANDARD","lossRatePctPerWeek":0.5,"proteinGPerKg":1.6,"goalWeightKg":78.0,"finishLine":{"kind":"GOAL_WEIGHT"},"startedOn":"2026-06-01"}""").toPlan()
        assertNull(goal.reachedOn)
        assertNull(goal.breakUntil)
        assertNull(goal.lastBreakEndedOn)
        assertNull(goal.breakSnoozedUntil)
        assertFalse(goal.onBreak)
        val old = json.decodeFromString<StoredProfile>("""{"sex":"FEMALE","birthDate":"1994-03-01","heightCm":165.0,"weightKg":70.0}""").toProfile()
        assertFalse(old.pregnant)
        assertTrue(StoredProfile.of(old.copy(pregnant = true)).toProfile().pregnant)
    }

    // Pregnancy and breastfeeding (GL-09)

    @Test
    fun `switching it on moves an active deficit to Maintain, with the preview saying why`() = runBlocking {
        onboard()
        val today = app.days.today()
        val preview = app.personas.preview(profile = profile.copy(pregnant = true))
        assertEquals(listOf(GuardrailNote.PREGNANT_MAINTAIN), preview.notes)
        assertEquals(Goal.MAINTAIN, preview.plan.goal)
        assertTrue(preview.changes)
        assertEquals(0.0, preview.after.energyAdjustmentKcal, 0.0)

        app.personas.save(profile = profile.copy(pregnant = true))
        assertTrue(app.prefs.profile.first()!!.pregnant)
        assertEquals(Goal.MAINTAIN, plan().goal)
        // A change of goal like any other: it starts today.
        assertEquals(today, plan().startedOn)
        val row = targets.all().last()
        assertEquals(Goal.MAINTAIN, row.goal)
        assertEquals(TargetReason.MANUAL, row.reason)

        // Switching it off doesn't bring the deficit back by itself: you choose a goal again.
        app.personas.save(profile = profile.copy(pregnant = false))
        assertEquals(Goal.MAINTAIN, plan().goal)
        // While it's on, the goal flow can't save a deficit either.
        app.personas.save(profile = profile.copy(pregnant = true))
        app.personas.save(plan = GoalPlan.of(Goal.RECOMP, Pace.STANDARD, 78.0, FinishLine.GoalWeight, today))
        assertEquals(Goal.MAINTAIN, plan().goal)
        // A lean bulk is still there.
        app.personas.save(plan = GoalPlan.of(Goal.LEAN_BULK, Pace.STANDARD, null, null, today))
        assertEquals(Goal.LEAN_BULK, plan().goal)
    }

    @Test
    fun `a calorie target of your own below maintenance isn't used, or kept, while it's on`() = runBlocking {
        onboard(Goal.MAINTAIN)
        app.personas.save(overrides = TargetOverrides(kcal = 1900, proteinG = 140))
        assertEquals(1900, targets.all().last().kcal)

        val preview = app.personas.preview(profile = profile.copy(pregnant = true))
        assertEquals(listOf(GuardrailNote.PREGNANT_KCAL_AT_MAINTENANCE), preview.notes)
        app.personas.save(profile = profile.copy(pregnant = true))
        val row = targets.all().last()
        assertTrue(atMaintenance(row))
        // Protein stays yours; the calorie number is gone, so it can't come back by itself later.
        assertEquals(140, row.proteinG)
        assertEquals(TargetOverrides(proteinG = 140), app.prefs.overrides.first())

        // Setting one below maintenance again is refused the same way; one above it is fine.
        app.personas.save(overrides = TargetOverrides(kcal = 2000, proteinG = 140))
        assertNull(app.prefs.overrides.first().kcal)
        app.personas.save(overrides = TargetOverrides(kcal = 2600, proteinG = 140))
        assertEquals(2600, targets.all().last().kcal)
    }

    @Test
    fun `the check-in never offers a deficit while it's on, even for a goal stored as a deficit`() = runBlocking {
        onboard()
        (0..41).forEach { weigh(it, 80.0); eat(it, 2100.0) }
        // Without the switch, this flat scale on fat loss proposes a lower target.
        app.checkins.catchUp(date(35), at(35))
        val before = checkinDao.latest()!!
        assertEquals(CheckinFlag.LOSS_SLOW, before.flag)
        assertNotNull(before.proposedKcal)
        // The switch arrives in the body data without a goal save (an import, say): the stored goal is still fat loss.
        app.prefs.edit { setProfile(profile.copy(pregnant = true)) }
        // The proposal made before it can no longer be seen or answered.
        val stale = app.checkins.open(before.weekStart, at(35, 13))!!
        assertNull(stale.proposal)
        assertFalse(app.checkins.accept(before.weekStart, now = at(35, 14)))
        assertFalse(app.checkins.adjust(before.weekStart, 1500, at(35, 14)))

        app.checkins.catchUp(date(42), at(42))
        val latest = checkinDao.latest()!!
        assertNull(latest.flag)
        assertNull(latest.proposedKcal)
        val view = app.checkins.open(latest.weekStart, at(42))!!
        assertEquals(Goal.MAINTAIN, view.plan.goal)
        assertEquals(listOf(GuardrailNote.PREGNANT_MAINTAIN), view.notes)
        assertTrue(view.pregnant)
        assertNull(view.proposal)
        assertEquals(CheckinStatus.ON_TRACK, view.status)
        assertEquals(CheckinText.PREGNANT_VERDICT, CheckinText.verdict(view))
        assertTrue(targets.all().none { it.reason == TargetReason.CHECKIN })
    }

    @Test
    fun `a steady pregnancy gain never talks the target down (B1)`() = runBlocking {
        // Invented: 165 cm, 62 kg, Maintain, eating the starting target and gaining 0.4 kg a week.
        val woman = Profile(Sex.FEMALE, LocalDate.of(1994, 3, 1), heightCm = 165.0, weightKg = 62.0, pregnant = true)
        onboard(Goal.MAINTAIN, who = woman)
        val start = targets.targetOn(date(0))!!
        val formulaTdee = start.tdeeEstimate
        (0..62).forEach { weigh(it, 62.0 + 0.4 / 7 * it); eat(it, start.kcal.toDouble()) }
        for (monday in listOf(21, 28, 35, 42, 49, 56, 63)) {
            app.checkins.catchUp(date(monday), at(monday))
            val week = checkinDao.latest()!!
            assertNull("week of ${week.weekStart}", week.flag)
            assertNull("week of ${week.weekStart}", week.proposedKcal)
        }
        // The estimate reads the gain as stored energy and falls well under the formula's TDEE...
        val estimate = checkinDao.latest()!!.tdeeNew
        assertTrue("$estimate vs $formulaTdee", estimate < formulaTdee - 150)
        // ...but neither Settings nor a save uses it: the targets stay at the formula's maintenance.
        val preview = app.personas.preview(profile = woman.copy(heightCm = 165.5))
        assertTrue("${preview.after.kcal}", preview.after.kcal >= start.kcal)
        assertFalse(preview.after.adaptiveTdee)
        app.personas.save(profile = woman.copy(heightCm = 165.5))
        assertTrue(targets.all().all { it.kcal >= start.kcal })
        assertTrue(targets.all().none { it.reason == TargetReason.CHECKIN })

        // Switched off, the estimate counts again.
        app.personas.save(profile = woman.copy(heightCm = 165.5, pregnant = false))
        assertTrue(app.personas.preview().after.adaptiveTdee)
    }

    @Test
    fun `a break that ended while the app was closed resumes before the check-ins are caught up (S1)`() = runBlocking {
        onboard()
        (0..133).forEach { weigh(it, 80.0); eat(it, 2100.0) }
        app.checkins.catchUp(date(84), at(84))
        assertTrue(goals.startBreak(1, at(84, 13)))
        // Not opened again until day 133; catch-up runs first, as at launch.
        app.checkins.catchUp(date(133), at(133))
        val resume = targets.all().last()
        assertEquals(TargetReason.RESUME, resume.reason)
        assertEquals(date(133), resume.effectiveFrom)
        assertEquals(date(91), plan().lastBreakEndedOn)
        // The break's week and the 2 after it are quiet; from there fat loss's flag is judged again, the latest included.
        val rows = checkinDao.all().filter { !it.weekStart.isBefore(date(84)) }
        assertEquals(listOf(date(84), date(91), date(98)), rows.filter { it.flag == null }.map { it.weekStart })
        assertTrue(rows.filter { it.weekStart.isAfter(date(98)) }.all { it.flag == CheckinFlag.LOSS_SLOW })
        // The resume already used the latest maintenance estimate, so the newest week has nothing over 50 kcal to propose.
        assertEquals(CheckinFlag.LOSS_SLOW, checkinDao.latest()!!.flag)
        assertTrue(targets.targetOn(date(133))!!.kcal < targets.targetOn(date(84))!!.kcal)
    }

    @Test
    fun `the weekly review shows a break week at maintenance, and the goal underneath it afterwards (S2, N1)`() = runBlocking {
        onboard()
        (0..120).forEach { weigh(it, 80.0 - 0.02 * it); eat(it, 2100.0) }
        assertTrue(goals.startBreak(2, at(84)))
        // In the break: the trend, no pace and no finish line.
        val resting = app.reviews.week(date(84), today = date(88))
        assertTrue(resting.body.atMaintenance)
        assertNull(resting.body.pace)
        assertNull(resting.outlook)
        assertTrue(ReviewText.trend(resting.body).endsWith(ReviewText.MAINTENANCE_WEEK))
        // The week before the break is judged by the goal as before.
        val before = app.reviews.week(date(77), today = date(88))
        assertFalse(before.body.atMaintenance)
        assertNotNull(before.body.pace)

        // Back on the goal, then a switch to a lean bulk: the break's weeks were fat loss, not Maintain.
        goals.refresh(at(98))
        app.personas.save(plan = GoalPlan.of(Goal.LEAN_BULK, Pace.STANDARD, null, null, date(112)), now = at(112))
        val earlier = app.reviews.week(date(84), today = date(113))
        assertEquals(WeekPlan.EarlierGoal(Goal.FAT_LOSS, since = date(112)), earlier.plan)
        assertTrue(earlier.body.atMaintenance)
        assertNull(earlier.outlook)
    }

    @Test
    fun `a break can't start a deficit back up through the guardrail`() = runBlocking {
        onboard()
        assertTrue(goals.startBreak(1, at(84)))
        // It's switched on during the break, straight into the body data.
        app.prefs.edit { setProfile(profile.copy(pregnant = true)) }
        goals.refresh(at(91))
        val back = targets.targetOn(date(91))!!
        assertEquals(TargetReason.RESUME, back.reason)
        assertEquals(Goal.MAINTAIN, back.goal)
        assertEquals(Goal.MAINTAIN, plan().goal)
        assertNull(plan().breakUntil)
    }
}
