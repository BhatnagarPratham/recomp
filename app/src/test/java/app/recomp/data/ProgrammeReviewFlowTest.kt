package app.recomp.data

import app.recomp.data.ai.AiEffort
import app.recomp.data.ai.AiResult
import app.recomp.data.db.TargetReason
import app.recomp.data.db.WeeklyCheckinEntity
import app.recomp.data.repo.OnboardingResult
import app.recomp.data.repo.ProgressionRepository
import app.recomp.data.repo.ReviewSendResult
import app.recomp.data.repo.ReviewUndo
import app.recomp.domain.ai.AiKind
import app.recomp.domain.ai.AiState
import app.recomp.domain.ai.AiStatus
import app.recomp.domain.coach.AdviceOnly
import app.recomp.domain.coach.Change
import app.recomp.domain.coach.ReportSections
import app.recomp.domain.model.SetType
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.Pace
import app.recomp.domain.nutrition.Sex
import app.recomp.domain.persona.Experience
import app.recomp.domain.persona.FinishLine
import app.recomp.domain.persona.GoalPlan
import app.recomp.domain.persona.Presets
import app.recomp.domain.persona.Profile
import app.recomp.domain.progression.Action
import app.recomp.domain.progression.ProgressionEngine
import app.recomp.domain.training.WeeklyPlan
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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

/** HM-06, TRD §10 M11: the weekly programme review end to end, on invented data, with a scripted provider. */
@RunWith(RobolectricTestRunner::class)
class ProgrammeReviewFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: TestApp
    private val reviews get() = app.programmeReviews
    private lateinit var week: LocalDate

    @Before
    fun setUp() {
        app = TestApp(File(tmp.root, "app").apply { mkdirs() })
        // The repository reads the real clock's today, so the invented weeks sit just before it.
        week = WeeklyPlan.weekStart(LocalDate.now()).minusWeeks(1)
    }

    @After
    fun tearDown() = app.close()

    private fun at(day: LocalDate, hour: Int = 12): Long = day.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Invented: a fat-loss plan for a 175 cm, 84 kg man of 30; [weightKg] 54 puts him under BMI 18.5. */
    private suspend fun onboard(weightKg: Double = 84.0) {
        val start = week.minusWeeks(3)
        val plan = GoalPlan.of(Goal.FAT_LOSS, Pace.STANDARD, 76.0, FinishLine.GoalWeight, start)
        val profile = Profile(Sex.MALE, LocalDate.of(1996, 1, 1), heightCm = 175.0, weightKg = weightKg)
        app.personas.completeOnboarding(OnboardingResult(Presets.REFERENCE.draft.copy(goal = plan), Presets.REFERENCE.key, emptyList(), profile, LocalTime.MIDNIGHT), now = at(start))
    }

    private suspend fun ready() {
        app.ai.setEnabled(true)
        app.ai.saveKey("test-key")
    }

    /** One strength workout on [day] with every set ticked at 40 kg × 10. */
    private suspend fun workout(day: LocalDate) {
        val routine = app.training.observeNextRoutine().first()!!
        val session = app.workouts.startRoutine(routine.id, at(day, 18))
        app.db.workoutDao().sessionExercises(session).forEach { ex ->
            ex.sets.filter { it.setType == SetType.WORKING }.forEach { app.workouts.completeSet(it.id, 40_000, 10, 2, at(day, 18) + 600_000) }
        }
        app.workouts.finish(session, at(day, 19))
    }

    private fun reply(json: String) {
        app.sender.next = { call -> AiResult(AiStatus.OK, Json.parseToJsonElement(json).jsonObject, json, call.target.model, emptyList(), 1_000) }
    }

    private fun suggestion(kind: String, routine: String = "", exercise: String = "", replacement: String = "", value: Double = 0.0, max: Double = value) =
        """{"kind":"$kind","routine_ref":"$routine","exercise_ref":"$exercise","replacement_ref":"$replacement","value":{"min":$value,"max":$max},"reason":"Because."}"""

    @Test
    fun `the report is built from the week, with targets relative unless body trends are ticked`() = runBlocking {
        onboard()
        workout(week)
        val report = reviews.report(week, ReportSections())
        assertTrue(report.text, "## Training" in report.text)
        assertTrue("Calories: " in report.text && "% of maintenance" in report.text)
        assertTrue("g per kg" in report.text)
        assertFalse("## Body trends" in report.text)
        assertFalse(" kcal" in report.text)
        // Never sent: sex, age, height (the profile's numbers never reach the facts).
        listOf("male", "175", "1996", "30 years").forEach { assertFalse(it, it.lowercase() in report.text.lowercase()) }
        assertTrue(report.routines.isNotEmpty() && report.exercises.isNotEmpty())
        assertTrue("Working sets per muscle" in report.text)
        // The split by its name, as Train shows it.
        assertTrue(report.text, "Split: ${app.prefs.currentTraining().split!!.label}." in report.text)

        val body = reviews.report(week, ReportSections(body = true))
        assertTrue("## Body trends" in body.text)
        assertTrue(" kcal a day" in body.text)
    }

    @Test
    fun `send goes through the gate as COACH, at medium effort with a minute, and keeps what was sent`() = runBlocking {
        onboard()
        // Not ready: nothing is sent and nothing is kept.
        assertEquals(ReviewSendResult.NotReady(AiState.OFF), reviews.send(week, ReportSections()))
        assertTrue(app.sender.calls.isEmpty())
        assertTrue(app.db.programmeReviewDao().observeAll().first().isEmpty())

        ready()
        reply("""{"summary":"A steady week.","suggestions":[${suggestion("ADVICE")}]}""")
        val sections = ReportSections(note = "only 2 days next week")
        val preview = reviews.report(week, sections).text
        val result = reviews.send(week, sections, now = at(week.plusDays(7), 9)) as ReviewSendResult.Done
        val call = app.sender.calls.single()
        assertEquals(AiEffort.MEDIUM, call.effort)
        assertEquals(60L, call.timeout.seconds)
        assertEquals(preview, call.user)
        assertTrue("only 2 days next week" in call.user)
        val row = app.db.programmeReviewDao().get(result.reviewId)!!
        assertEquals(preview, row.sentText)
        assertEquals("goal,food,training,note", row.sections)
        val log = app.db.aiDao().request(row.aiRequestId!!)!!
        assertEquals(AiKind.COACH, log.kind)
        assertEquals(AiStatus.OK, log.status)
        val view = reviews.view(result.reviewId)!!
        assertEquals("A steady week.", view.summary)
        assertEquals(1, view.suggestions.size)

        // A reply that doesn't fit the schema is logged as invalid, and what was sent is still kept.
        reply("""{"summary":"x","suggestions":[{"kind":7}]}""")
        val failed = reviews.send(week, ReportSections()) as ReviewSendResult.Failed
        assertEquals(AiStatus.INVALID, failed.status)
        assertEquals(AiStatus.INVALID, app.db.aiDao().request(app.db.programmeReviewDao().get(failed.reviewId!!)!!.aiRequestId!!)!!.status)
        assertNull(reviews.view(failed.reviewId!!)!!.summary)
    }

    @Test
    fun `every kind applies through the usual path, and routine changes undo`() = runBlocking {
        onboard()
        ready()
        workout(week)
        val report = reviews.report(week, ReportSections())
        val routineId = report.routines.getValue("r1")
        val rows = app.db.routineDao().exercises(routineId).map { it.entry }
        val refOf = report.exercises.entries.associate { (ref, id) -> id to ref }
        val first = refOf.getValue(rows[0].exerciseId)
        val second = refOf.getValue(rows[1].exerciseId)
        val spare = report.exercises.entries.first { (_, id) -> rows.none { it.exerciseId == id } }.key
        reply(
            """{"summary":"s","suggestions":[${suggestion("SETS", "r1", first, value = 4.0)},${suggestion("REP_RANGE", "r1", second, value = 5.0, max = 8.0)},""" +
                """${suggestion("SWAP", "r1", first, spare)},${suggestion("STRENGTH_DAYS", value = 4.0)},${suggestion("CALORIES", value = -15.0)}]}""",
        )
        val id = (reviews.send(week, ReportSections()) as ReviewSendResult.Done).reviewId
        val view = reviews.view(id)!!
        assertTrue(view.suggestions.all { it.change != null })

        // Sets: applied, then undone.
        val undoSets = reviews.apply(id, 0) as ReviewUndo.Routine
        assertEquals(4, app.db.routineDao().exercises(routineId).first { it.entry.exerciseId == rows[0].exerciseId }.entry.targetSets)
        assertEquals(setOf(0), reviews.view(id)!!.applied)
        reviews.undo(undoSets)
        assertEquals(rows.map { it.exerciseId to it.targetSets }, app.db.routineDao().exercises(routineId).map { it.entry.exerciseId to it.entry.targetSets })
        assertEquals(emptySet<Int>(), reviews.view(id)!!.applied)

        // Rep range.
        reviews.apply(id, 1)
        val ranged = app.db.routineDao().exercises(routineId).first { it.entry.exerciseId == rows[1].exerciseId }.entry
        assertEquals(5 to 8, ranged.repMin to ranged.repMax)

        // Swap, undone.
        val undoSwap = reviews.apply(id, 2) as ReviewUndo.Routine
        assertEquals(report.exercises.getValue(spare), app.db.routineDao().exercises(routineId)[0].entry.exerciseId)
        reviews.undo(undoSwap)
        assertEquals(rows[0].exerciseId, app.db.routineDao().exercises(routineId)[0].entry.exerciseId)

        // Strength days, undone.
        val before = app.prefs.currentTraining().strengthDaysPerWeek
        val undoDays = reviews.apply(id, 3)!!
        assertEquals(4, app.prefs.currentTraining().strengthDaysPerWeek)
        reviews.undo(undoDays)
        assertEquals(before, app.prefs.currentTraining().strengthDaysPerWeek)

        // Calories: previewed, then saved as a target version from the review.
        val change = view.suggestions[4].change as Change.Calories
        val preview = reviews.previewTarget(change)!!
        assertEquals(change.kcal, preview.after.kcal)
        assertNull(reviews.apply(id, 4, now = at(LocalDate.now())))
        val target = app.db.nutritionDao().targetOn(app.days.today())!!
        assertEquals(change.kcal, target.kcal)
        assertEquals(TargetReason.REVIEW, target.reason)
    }

    @Test
    fun `add and remove keep the routine's other exercises, and protein saves a target`() = runBlocking {
        onboard()
        ready()
        val report = reviews.report(week, ReportSections())
        val routineId = report.routines.getValue("r1")
        val rows = app.db.routineDao().exercises(routineId).map { it.entry }
        val refOf = report.exercises.entries.associate { (ref, id) -> id to ref }
        val spare = report.exercises.entries.first { (_, id) -> rows.none { it.exerciseId == id } }.key
        reply("""{"summary":"s","suggestions":[${suggestion("ADD", "r1", spare)},${suggestion("REMOVE", "r1", refOf.getValue(rows.last().exerciseId))},${suggestion("PROTEIN", value = 2.2)}]}""")
        val id = (reviews.send(week, ReportSections()) as ReviewSendResult.Done).reviewId
        val undoAdd = reviews.apply(id, 0) as ReviewUndo.Routine
        assertEquals(rows.size + 1, app.db.routineDao().exercises(routineId).size)
        assertEquals(report.exercises.getValue(spare), app.db.routineDao().exercises(routineId).last().entry.exerciseId)
        reviews.undo(undoAdd)
        reviews.apply(id, 1)
        assertEquals(rows.dropLast(1).map { it.exerciseId }, app.db.routineDao().exercises(routineId).map { it.entry.exerciseId })
        reviews.apply(id, 2, now = at(LocalDate.now()))
        // 2.2 g per kg of the 76 kg goal weight.
        assertEquals(167, app.db.nutritionDao().targetOn(app.days.today())!!.proteinG)
    }

    @Test
    fun `a pending deload becomes the next finished workout's target once, and survives a discarded one`() = runBlocking {
        onboard()
        ready()
        workout(week)
        val report = reviews.report(week, ReportSections())
        val next = app.training.observeNextRoutine().first()!!
        val exerciseId = app.db.routineDao().exercises(next.id).first().entry.exerciseId
        val ref = report.exercises.entries.first { it.value == exerciseId }.key
        reply("""{"summary":"s","suggestions":[${suggestion("DELOAD_EXERCISE", exercise = ref)},${suggestion("DELOAD_ALL")}]}""")
        val id = (reviews.send(week, ReportSections()) as ReviewSendResult.Done).reviewId
        // The exercise has no history yet in this routine's rotation; give it one first.
        workout(week.plusDays(2))
        workout(week.plusDays(4))
        reviews.apply(id, 0)
        assertTrue(app.db.exerciseDao().get(exerciseId)!!.deloadPending)

        val routine = app.db.routineDao().exercises(next.id)
        val session = app.workouts.startRoutine(next.id, at(LocalDate.now(), 7))
        val row = app.db.workoutDao().sessionExercises(session).first { it.exercise.id == exerciseId }
        val rec = ProgressionRepository.decode(row.entry.recommendationJson)!!
        assertEquals(Action.DELOAD, rec.action)
        // 90% of 40 kg, floored to the exercise's increment, as the stall card's deload.
        val exercise = app.db.exerciseDao().get(exerciseId)!!
        assertEquals(ProgressionEngine.deloadLoad(exercise.loadType, 40_000, exercise.incrementG, null), rec.loadG)
        assertEquals(List(routine.first().entry.targetSets) { routine.first().entry.repMin }, rec.reps)
        // Starting doesn't use it up: a workout started and discarded leaves the deload, and the stall clock, alone.
        val stallBefore = app.db.exerciseDao().get(exerciseId)!!.stallResetAt
        assertTrue(app.db.exerciseDao().get(exerciseId)!!.deloadPending)
        app.workouts.discard(session)
        assertTrue(app.db.exerciseDao().get(exerciseId)!!.deloadPending)
        assertEquals(stallBefore, app.db.exerciseDao().get(exerciseId)!!.stallResetAt)
        // The next start still deloads, and finishing it uses the deload up, resetting the stall clock from its start.
        val started = at(LocalDate.now(), 8)
        val taken = app.workouts.startRoutine(next.id, started)
        assertEquals(Action.DELOAD, ProgressionRepository.decode(app.db.workoutDao().sessionExercises(taken).first { it.exercise.id == exerciseId }.entry.recommendationJson)!!.action)
        app.workouts.finish(taken, started + 3_600_000)
        assertFalse(app.db.exerciseDao().get(exerciseId)!!.deloadPending)
        assertEquals(started - 1, app.db.exerciseDao().get(exerciseId)!!.stallResetAt)
        // Once: the next start is the usual target.
        val again = app.workouts.startRoutine(next.id, at(LocalDate.now(), 10))
        val rec2 = ProgressionRepository.decode(app.db.workoutDao().sessionExercises(again).first { it.exercise.id == exerciseId }.entry.recommendationJson)!!
        assertTrue(rec2.action != Action.DELOAD)
        app.workouts.discard(again)

        // The whole week marks every exercise in the rotation and counts as the scheduled deload week; undo clears
        // only what it marked and puts the deload week back.
        assertNull(app.prefs.deloadWeek.first())
        val undo = reviews.apply(id, 1) as ReviewUndo.Deload
        val rotation = app.db.programmeReviewDao().rotationExercises()
        assertEquals(rotation.toSet(), app.db.programmeReviewDao().pendingDeloads().toSet())
        assertEquals(WeeklyPlan.weekStart(app.days.today()), app.prefs.deloadWeek.first())
        reviews.undo(undo)
        assertTrue(app.db.programmeReviewDao().pendingDeloads().isEmpty())
        assertNull(app.prefs.deloadWeek.first())
    }

    @Test
    fun `an applied swap, add or remove reads as applied, and a second apply does nothing`() = runBlocking {
        onboard()
        ready()
        val report = reviews.report(week, ReportSections())
        val push = report.routines.getValue("r1")
        val pull = report.routines.getValue("r2")
        val refOf = report.exercises.entries.associate { (ref, id) -> id to ref }
        val pushRows = app.db.routineDao().exercises(push).map { it.entry }
        val pullRows = app.db.routineDao().exercises(pull).map { it.entry }
        val spare = report.exercises.entries.filter { (_, id) -> pushRows.none { it.exerciseId == id } && pullRows.none { it.exerciseId == id } }.map { it.key }
        reply(
            """{"summary":"s","suggestions":[${suggestion("SWAP", "r1", refOf.getValue(pushRows[0].exerciseId), spare[0])},""" +
                """${suggestion("ADD", "r2", spare[1])},${suggestion("REMOVE", "r2", refOf.getValue(pullRows.last().exerciseId))}]}""",
        )
        val id = (reviews.send(week, report) as ReviewSendResult.Done).reviewId
        (0..2).forEach { reviews.apply(id, it) }
        // A second apply of the add doesn't add it again.
        assertNull(reviews.apply(id, 1))
        assertEquals(1, app.db.routineDao().exercises(pull).count { it.entry.exerciseId == report.exercises.getValue(spare[1]) })
        val view = reviews.view(id)!!
        assertEquals(setOf(0, 1, 2), view.applied)
        view.suggestions.forEach { s ->
            assertNotNull(s.change)
            assertNull(s.adviceOnly)
        }
        assertEquals(Change.Swap(push, pushRows[0].exerciseId, report.exercises.getValue(spare[0])), view.suggestions[0].change)
    }

    @Test
    fun `send sends the previewed report, even when the week changed after the preview`() = runBlocking {
        onboard()
        ready()
        reply("""{"summary":"s","suggestions":[]}""")
        val preview = reviews.report(week, ReportSections())
        // A workout finishes between the preview and Send.
        workout(week.plusDays(1))
        assertTrue(reviews.report(week, ReportSections()).text != preview.text)
        val id = (reviews.send(week, preview) as ReviewSendResult.Done).reviewId
        assertEquals(preview.text, app.sender.calls.last().user)
        assertEquals(preview.text, app.db.programmeReviewDao().get(id)!!.sentText)
    }

    @Test
    fun `a relative calorie suggestion converts with the maintenance the report stated`() = runBlocking {
        onboard()
        ready()
        reply("""{"summary":"s","suggestions":[${suggestion("CALORIES", value = -15.0)}]}""")
        val report = reviews.report(week, ReportSections())
        val stated = report.maintenanceKcal!!
        val id = (reviews.send(week, report) as ReviewSendResult.Done).reviewId
        // A check-in's estimate then moves maintenance.
        app.db.checkinDao().upsert(
            WeeklyCheckinEntity(week, computedAt = 1, windowDays = 21, loggedDays = 20, weighIns = 20, tdeeObs = 2_600, tdeePrev = 2_460, tdeeNew = 2_600),
        )
        assertEquals(2_600, app.personas.maintenance()!!.kcal)
        val s = reviews.view(id)!!.suggestions.single()
        val kcal = (s.change as Change.Calories).kcal
        assertEquals(Math.round(stated * 0.85 / 10).toInt() * 10, kcal)
        assertTrue(kcal != Math.round(2_600 * 0.85 / 10).toInt() * 10)
    }

    @Test
    fun `no deficit under BMI 18_5, and unknown exercises are advice`() = runBlocking {
        onboard(weightKg = 54.0)
        ready()
        reply("""{"summary":"s","suggestions":[${suggestion("CALORIES", value = -10.0)},${suggestion("CALORIES", value = 5.0)},${suggestion("SWAP", "r1", "e1", "e999")},${suggestion("SETS", "r9", "e1", value = 3.0)}]}""")
        val id = (reviews.send(week, ReportSections()) as ReviewSendResult.Done).reviewId
        val view = reviews.view(id)!!
        assertEquals(AdviceOnly.OUTSIDE_GUARDRAILS, view.suggestions[0].adviceOnly)
        assertNotNull(view.suggestions[1].change)
        assertEquals(AdviceOnly.UNKNOWN_EXERCISE, view.suggestions[2].adviceOnly)
        assertEquals(AdviceOnly.UNKNOWN_EXERCISE, view.suggestions[3].adviceOnly)
        // Advice can't be applied.
        assertNull(reviews.apply(id, 0))
        assertEquals(emptySet<Int>(), reviews.view(id)!!.applied)
    }

    @Test
    fun `the scheduled deload week is offered to intermediate lifters, and accepting marks the rotation`() = runBlocking {
        onboard()
        app.prefs.updatePersona { it.copy(experience = Experience.BEGINNER) }
        workout(LocalDate.now().minusWeeks(7))
        assertNull(reviews.observeDeloadDue().first())
        app.prefs.updatePersona { it.copy(experience = Experience.INTERMEDIATE) }
        assertEquals(7L, reviews.observeDeloadDue().first())
        reviews.snoozeDeloadWeek()
        assertNull(reviews.observeDeloadDue().first())
        reviews.acceptDeloadWeek()
        assertNull(reviews.observeDeloadDue().first())
        assertEquals(app.db.programmeReviewDao().rotationExercises().toSet(), app.db.programmeReviewDao().pendingDeloads().toSet())
    }
}
