package app.recomp.data

import androidx.health.connect.client.records.ExerciseSessionRecord
import app.recomp.data.db.ScanSource
import app.recomp.data.db.SleepSource
import app.recomp.data.health.ExerciseTypes
import app.recomp.data.health.ExternalWorkout
import app.recomp.data.health.HealthAvailability
import app.recomp.data.health.HealthFeature
import app.recomp.data.health.BackgroundRead
import app.recomp.data.repo.ExtraDraft
import app.recomp.data.repo.OnboardingResult
import app.recomp.data.repo.ScanInput
import app.recomp.data.repo.StepsToday
import app.recomp.data.repo.WorkoutRepository.ExtraEntry
import app.recomp.domain.health.BodyMetric
import app.recomp.domain.health.BodyReading
import app.recomp.domain.health.SleepSession
import app.recomp.domain.health.Span
import app.recomp.domain.model.SessionKind
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.Pace
import app.recomp.domain.nutrition.Sex
import app.recomp.domain.persona.FinishLine
import app.recomp.domain.persona.GoalPlan
import app.recomp.domain.persona.Presets
import app.recomp.domain.persona.Profile
import app.recomp.domain.training.RestReason
import app.recomp.domain.training.WeeklyPlan
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone
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

/**
 * Health Connect in (AR-01, AR-02, AR-04, IB-07; TRD §10 M13), against a scripted Health Connect. All of it is
 * invented: the dates, the counts and the measurements.
 */
@RunWith(RobolectricTestRunner::class)
class HealthFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: TestApp
    private lateinit var savedZone: TimeZone
    private val zone = ZoneId.of("Asia/Kolkata")

    /** A Monday. */
    private val monday = LocalDate.of(2026, 11, 16)
    private val fit = FakeHealthSource.FIT

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long = LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun now(date: LocalDate, hour: Int, minute: Int = 0) {
        app.clock.instant = java.time.Instant.ofEpochMilli(at(date, hour, minute))
    }

    @Before
    fun setUp() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        app = TestApp(File(tmp.root, "app").apply { mkdirs() })
        now(monday, 9)
        runBlocking { HealthFeature.entries.forEach { app.prefs.setHealthFeature(it, true) } }
    }

    @After
    fun tearDown() {
        app.close()
        TimeZone.setDefault(savedZone)
    }

    /** A yoga class on Saturdays and walks whenever, in that order; the day starts at 4 AM. */
    private suspend fun onboard() {
        val profile = Profile(Sex.MALE, monday.minusYears(30), heightCm = 175.0, weightKg = 90.0)
        val plan = GoalPlan.of(Goal.RECOMP, Pace.STANDARD, 78.0, FinishLine.GoalWeight, monday)
        val extras = listOf(ExtraDraft(null, "Yoga class", SessionKind.YOGA, setOf(6)), ExtraDraft(null, "Walks", SessionKind.CARDIO, emptySet()))
        app.personas.completeOnboarding(OnboardingResult(Presets.REFERENCE.draft.copy(goal = plan), Presets.REFERENCE.key, extras, profile, LocalTime.of(4, 0)))
    }

    private suspend fun extra(name: String) = app.training.observeExtras().first().single { it.name == name }

    private fun workout(id: String, type: Int, date: LocalDate, hour: Int, minutes: Int, origin: String = fit) =
        ExternalWorkout(id, origin, at(date, hour), at(date, hour) + minutes * 60_000L, type)

    private suspend fun imported() = app.db.healthDao().importedSince(0)

    private suspend fun progress() =
        WeeklyPlan.progress(monday, app.workouts.observeWeek(monday).first(), 3, app.prefs.currentPersona().extras.map { it.toPlan() })

    // Steps (AR-01)

    @Test
    fun `steps are kept by calendar day, not by the log date`() = runBlocking {
        onboard()
        // 2 AM: still Sunday's log date (the day starts at 4), but Monday on the calendar and in Google Fit.
        now(monday, 2)
        app.checkins.setFoodIncomplete(monday.minusDays(1), true)
        app.healthSource.steps[monday] = 120
        app.healthSource.steps[monday.minusDays(1)] = 9_050
        app.healthSource.steps[monday.minusDays(40)] = 5_000

        app.health.sync()

        assertEquals(monday.minusDays(1), app.days.of(app.clock.millis()))
        assertEquals(120, app.db.healthDao().day(monday)!!.steps)
        assertEquals(9_050, app.db.healthDao().day(monday.minusDays(1))!!.steps)
        assertEquals(StepsToday(120, 8_000), app.health.observeSteps().first())
        // Thirty calendar days, ending today.
        assertEquals(monday.minusDays(29) to monday, app.healthSource.stepRequests.single())
        assertNull(app.db.healthDao().day(monday.minusDays(40)))
        // The day's other facts are untouched.
        assertTrue(app.db.healthDao().day(monday.minusDays(1))!!.foodIncomplete)
    }

    @Test
    fun `a later sync follows the count, and a day whose steps are gone is cleared`() = runBlocking {
        app.healthSource.steps[monday] = 2_000
        app.healthSource.steps[monday.minusDays(2)] = 7_000
        app.health.sync()
        app.healthSource.steps[monday] = 6_240
        app.healthSource.steps.remove(monday.minusDays(2))
        now(monday, 18)
        app.health.sync()

        assertEquals(6_240, app.db.healthDao().day(monday)!!.steps)
        assertNull(app.db.healthDao().day(monday.minusDays(2))!!.steps)
        app.prefs.setStepsGoal(10_000)
        assertEquals(StepsToday(6_240, 10_000), app.health.observeSteps().first())
        app.health.setFeature(HealthFeature.STEPS, false)
        assertNull(app.health.observeSteps().first())
    }

    @Test
    fun `the body card averages the seven days before today`() = runBlocking {
        (1L..3L).forEach { app.healthSource.steps[monday.minusDays(it)] = 6_000 + it * 1_000 }
        app.healthSource.steps[monday] = 300
        app.health.sync()
        val week = app.health.observeWeek().first()
        assertTrue(week.stepsOn)
        assertEquals(8_000, week.stepsAverage)
    }

    // Sleep (AR-02)

    private fun night(morning: LocalDate, fromHour: Int, toHour: Int, toMinute: Int = 0) =
        SleepSession(Span(at(morning.minusDays(1), fromHour), at(morning, toHour, toMinute)))

    @Test
    fun `two apps' overlapping nights are merged into the morning they ended on`() = runBlocking {
        app.healthSource.sleep += night(monday, 23, 6, 30)
        app.healthSource.sleep += SleepSession(Span(at(monday.minusDays(1), 23) + 20 * 60_000L, at(monday, 7)), awake = listOf(Span(at(monday, 6, 30), at(monday, 7))))
        app.healthSource.sleep += night(monday.minusDays(1), 22, 5)

        app.health.sync()

        val today = app.db.healthDao().day(monday)!!
        assertEquals(7.5, today.sleepHours)
        assertEquals(SleepSource.HEALTH_CONNECT, today.sleepSource)
        assertEquals(7.0, app.db.healthDao().day(monday.minusDays(1))!!.sleepHours)
        assertTrue(app.prefs.trackSleep.first())
        assertEquals(7.5, app.health.observeSleep().first()!!.hours)
    }

    @Test
    fun `a typed night survives every later sync`() = runBlocking {
        app.healthSource.sleep += night(monday, 23, 7)
        app.health.sync()
        assertEquals(8.0, app.db.healthDao().day(monday)!!.sleepHours)

        app.health.setTypedSleep(monday, 6.4)
        app.health.setTypedSleep(monday.minusDays(3), 9.0)
        app.healthSource.sleep += night(monday.minusDays(3), 23, 5)
        app.healthSource.touch(HealthFeature.SLEEP)
        app.health.sync()
        app.healthSource.expireTokens()
        app.health.sync()

        val today = app.db.healthDao().day(monday)!!
        assertEquals(6.5, today.sleepHours)
        assertEquals(SleepSource.TYPED, today.sleepSource)
        assertEquals(9.0, app.db.healthDao().day(monday.minusDays(3))!!.sleepHours)
        assertEquals(SleepSource.TYPED, app.db.healthDao().day(monday.minusDays(3))!!.sleepSource)
    }

    @Test
    fun `a tracker's night goes when its session does, and clearing a typed night lets the tracker back in`() = runBlocking {
        val session = night(monday, 23, 7)
        app.healthSource.sleep += session
        app.health.sync()
        app.health.setTypedSleep(monday, 5.0)
        app.health.setTypedSleep(monday, null)
        assertNull(app.db.healthDao().day(monday)!!.sleepHours)
        app.healthSource.touch(HealthFeature.SLEEP)
        app.health.sync()
        assertEquals(8.0, app.db.healthDao().day(monday)!!.sleepHours)

        app.healthSource.sleep -= session
        app.healthSource.touch(HealthFeature.SLEEP)
        app.health.sync()
        assertNull(app.db.healthDao().day(monday)!!.sleepHours)
        assertNull(app.db.healthDao().day(monday)!!.sleepSource)
    }

    @Test
    fun `with nothing changed in Health Connect, sleep isn't read again`() = runBlocking {
        app.healthSource.sleep += night(monday, 23, 7)
        app.health.sync()
        app.health.sync()
        assertEquals(1, app.healthSource.reads[HealthFeature.SLEEP])
    }

    @Test
    fun `track sleep is its own switch and the weigh-in asks only while it's on`() = runBlocking {
        app.health.setFeature(HealthFeature.SLEEP, false)
        assertTrue("turning Health Connect sleep off leaves Track sleep alone", app.prefs.trackSleep.first())
        app.health.setTrackSleep(false)
        assertNull(app.health.sleepFor(monday))
        assertNull(app.health.observeSleep().first())
        app.health.setTrackSleep(true)
        assertNotNull(app.health.sleepFor(monday))
        assertNull(app.health.sleepFor(monday)!!.hours)
    }

    // Other apps' workouts (AR-04)

    @Test
    fun `a walk from another app becomes a read-only cardio session and counts once`() = runBlocking {
        onboard()
        app.healthSource.put(workout("walk-1", ExerciseSessionRecord.EXERCISE_TYPE_WALKING, monday, 7, 35))
        app.health.sync()
        app.health.sync()
        app.healthSource.expireTokens()
        app.health.sync()

        val row = imported().single()
        val walks = extra("Walks")
        assertEquals(SessionKind.CARDIO, row.kind)
        assertEquals(walks.id, row.routineId)
        assertEquals("walk-1", row.hcRecordId)
        assertEquals("from Google Fit", row.notes)
        assertEquals(monday, row.logDate)
        val line = app.health.observeImportedToday().first().single()
        assertEquals("Walk" to 35, line.style to line.durationMin)
        // It counts toward the Walks extra, once, and never as strength.
        val week = progress()
        assertEquals(1, week.extras.single { it.routineId == walks.id }.done)
        assertEquals(0, week.strengthDays)
        assertNull(WeeklyPlan.restDay(monday, app.workouts.observeWeek(monday).first(), 3))
    }

    @Test
    fun `an import counts toward the extra scheduled that day, or toward nothing when there's none of its kind`() = runBlocking {
        onboard()
        val saturday = monday.minusDays(2)
        app.healthSource.put(workout("yoga-1", ExerciseSessionRecord.EXERCISE_TYPE_YOGA, saturday, 8, 60))
        app.healthSource.put(workout("tennis-1", ExerciseSessionRecord.EXERCISE_TYPE_TENNIS, monday, 7, 50))
        app.healthSource.put(workout("stretch-1", ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING, monday, 6, 10))
        app.health.sync()

        val byId = imported().associateBy { it.hcRecordId }
        assertEquals(extra("Yoga class").id, byId.getValue("yoga-1").routineId)
        assertEquals(SessionKind.SPORT to null, byId.getValue("tennis-1").let { it.kind to it.routineId })
        assertEquals(SessionKind.MOBILITY to null, byId.getValue("stretch-1").let { it.kind to it.routineId })
        // Shown on Today all the same.
        assertEquals(listOf("Stretching", "Tennis"), app.health.observeImportedToday().first().map { it.style })
    }

    @Test
    fun `strength types and Recomp's own sessions are never imported`() = runBlocking {
        onboard()
        ExerciseTypes.STRENGTH.forEachIndexed { i, type -> app.healthSource.put(workout("lift-$i", type, monday, 6 + i, 60)) }
        app.healthSource.put(workout("other", ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, monday, 12, 30))
        app.healthSource.put(workout("mine", ExerciseSessionRecord.EXERCISE_TYPE_YOGA, monday, 14, 60, origin = FakeHealthSource.OWN_PACKAGE))
        app.health.sync()
        app.healthSource.expireTokens()
        app.health.sync()

        assertEquals(emptyList<Any>(), imported())
        // A watch's copy of a workout can't make today a rest day.
        assertNull(WeeklyPlan.restDay(monday, app.workouts.observeWeek(monday).first(), 3))
    }

    @Test
    fun `a session overlapping a workout you logged in Recomp is skipped, whichever came first`() = runBlocking {
        onboard()
        val yoga = extra("Yoga class")
        // Logged here first: a class from 07:00 to 08:00.
        app.workouts.logExtra(yoga, ExtraEntry("Hatha", 60, 3, null), now = at(monday, 8))
        app.healthSource.put(workout("watch-yoga", ExerciseSessionRecord.EXERCISE_TYPE_YOGA, monday, 7, 55))
        app.healthSource.put(workout("walk-1", ExerciseSessionRecord.EXERCISE_TYPE_WALKING, monday, 8, 20))
        app.health.sync()
        assertEquals(listOf("walk-1"), imported().map { it.hcRecordId })

        // Imported first, then logged here over the same time: the import goes at the next sync.
        now(monday, 20)
        app.healthSource.put(workout("evening", ExerciseSessionRecord.EXERCISE_TYPE_YOGA, monday, 18, 60))
        app.health.sync()
        assertEquals(setOf("walk-1", "evening"), imported().map { it.hcRecordId }.toSet())
        app.workouts.logExtra(yoga, ExtraEntry("Yin", 45, null, null), now = at(monday, 19))
        app.health.sync()
        assertEquals(listOf("walk-1"), imported().map { it.hcRecordId })
        assertEquals(2, progress().extras.single { it.routineId == yoga.id }.done)
    }

    @Test
    fun `an edit or a delete in the other app is followed`() = runBlocking {
        onboard()
        app.healthSource.put(workout("walk-1", ExerciseSessionRecord.EXERCISE_TYPE_WALKING, monday, 7, 35))
        app.healthSource.put(workout("run-1", ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, monday, 6, 20))
        app.health.sync()
        val before = imported().single { it.hcRecordId == "walk-1" }

        // Lengthened, and turned into a hike.
        app.healthSource.put(workout("walk-1", ExerciseSessionRecord.EXERCISE_TYPE_HIKING, monday, 7, 80))
        app.healthSource.delete("run-1")
        app.health.sync()

        val after = imported().single()
        assertEquals(before.id, after.id)
        assertEquals(before.routineId, after.routineId)
        assertEquals(at(monday, 7) + 80 * 60_000L, after.endedAt)
        val detail = app.db.workoutDao().yogaDetail(after.id)!!
        assertEquals("Hike" to 80, detail.style to detail.durationMin)

        // Re-labelled as strength: it no longer belongs here.
        app.healthSource.put(workout("walk-1", ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, monday, 7, 80))
        app.health.sync()
        assertEquals(emptyList<Any>(), imported())
        assertNull(app.db.workoutDao().yogaDetail(after.id))
    }

    @Test
    fun `a session deleted while the token was expired goes at the full re-read`() = runBlocking {
        onboard()
        app.healthSource.put(workout("walk-1", ExerciseSessionRecord.EXERCISE_TYPE_WALKING, monday, 7, 35))
        app.healthSource.put(workout("walk-2", ExerciseSessionRecord.EXERCISE_TYPE_WALKING, monday.minusDays(1), 7, 35))
        app.health.sync()
        app.healthSource.workouts.remove("walk-2")
        app.healthSource.expireTokens()
        app.health.sync()
        assertEquals(listOf("walk-1"), imported().map { it.hcRecordId })
    }

    @Test
    fun `log class prefills from what you logged, never from an import`() = runBlocking {
        onboard()
        val yoga = extra("Yoga class")
        val saturday = monday.minusDays(2)
        app.healthSource.put(workout("yoga-1", ExerciseSessionRecord.EXERCISE_TYPE_YOGA, saturday, 8, 75))
        app.health.sync()
        assertEquals(yoga.id, imported().single().routineId)
        assertNull("only an import so far", app.workouts.lastExtra(yoga.id))

        app.workouts.logExtra(yoga, ExtraEntry("Hatha", 60, 3, "Hips"), now = at(saturday.minusDays(7), 9))
        assertEquals(ExtraEntry("Hatha", 60, 3, "Hips"), app.workouts.lastExtra(yoga.id))
    }

    // InBody (IB-07)

    private fun scan(prefix: String, at: Long, weightG: Double, fatPct: Double? = 24.6, bmr: Double? = 1_705.0) = listOfNotNull(
        BodyReading("$prefix-w", BodyMetric.WEIGHT, at, weightG),
        fatPct?.let { BodyReading("$prefix-f", BodyMetric.BODY_FAT, at, it) },
        bmr?.let { BodyReading("$prefix-b", BodyMetric.BMR, at, it) },
    )

    private fun typed(at: Long) = ScanInput(
        measuredAt = at, weightG = 79_900, skeletalMuscleG = 33_000, bodyFatG = 19_600, bodyFatPct = 24.5, visceralFatLevel = 8.0, minsSinceMeal = 180, workoutBefore = false,
    )

    @Test
    fun `an InBody measurement becomes one draft, however often it's read`() = runBlocking {
        val measured = at(monday, 8, 5)
        app.healthSource.body += scan("a", measured, 80_000.0)
        app.healthSource.body += scan("old", at(monday.minusDays(7), 8), 80_400.0, fatPct = null, bmr = null)
        app.health.sync()
        app.healthSource.touch(HealthFeature.INBODY)
        app.health.sync()
        app.healthSource.expireTokens()
        app.health.sync()

        val drafts = app.health.observeDrafts().first()
        assertEquals(2, drafts.size)
        val newest = drafts.first()
        assertEquals(measured, newest.measuredAt)
        assertEquals(Triple(80_000, 24.6, 1_705), Triple(newest.weightG, newest.bodyFatPct, newest.bmrKcal))
        assertEquals(listOf("a-b", "a-f", "a-w"), newest.hcIds)
        assertEquals(Triple(80_400, null, null), drafts.last().let { Triple(it.weightG, it.bodyFatPct, it.bmrKcal) })
    }

    @Test
    fun `a scan typed within ten minutes is matched instead of duplicated`() = runBlocking {
        val measured = at(monday, 8, 5)
        app.body.saveScan(typed(measured + 9 * 60_000L))
        app.healthSource.body += scan("a", measured, 80_000.0)
        app.healthSource.body += scan("b", at(monday.minusDays(7), 8), 80_400.0)
        app.health.sync()
        assertEquals(listOf(at(monday.minusDays(7), 8)), app.health.observeDrafts().first().map { it.measuredAt })

        // Typed after its draft appeared: the draft is hidden at once and dropped at the next read.
        app.body.saveScan(typed(at(monday.minusDays(7), 8) - 5 * 60_000L))
        assertEquals(emptyList<Any>(), app.health.observeDrafts().first())
        app.healthSource.touch(HealthFeature.INBODY)
        app.health.sync()
        assertEquals(emptyList<Any>(), app.db.healthDao().drafts())
    }

    @Test
    fun `a dismissed draft and one saved as a scan stay gone after a full re-read`() = runBlocking {
        app.healthSource.body += scan("a", at(monday, 8, 5), 80_000.0)
        app.healthSource.body += scan("b", at(monday.minusDays(7), 8), 80_400.0)
        app.health.sync()
        val (newer, older) = app.health.observeDrafts().first()

        app.health.closeDraft(older.id)
        // Saved from the form with the time moved by an hour: no longer within ten minutes of the measurement.
        val saved = app.body.saveScan(typed(newer.measuredAt + 3_600_000L).copy(source = ScanSource.HEALTH_CONNECT))
        app.health.closeDraft(newer.id)
        assertEquals(ScanSource.HEALTH_CONNECT, app.body.scan(saved.id)!!.source)

        app.healthSource.expireTokens()
        app.health.sync()
        assertEquals(emptyList<Any>(), app.db.healthDao().drafts())
    }

    @Test
    fun `a measurement deleted in Health Connect takes its draft with it`() = runBlocking {
        app.healthSource.body += scan("a", at(monday, 8, 5), 80_000.0)
        app.health.sync()
        app.healthSource.body.clear()
        app.healthSource.touch(HealthFeature.INBODY)
        app.health.sync()
        assertEquals(emptyList<Any>(), app.db.healthDao().drafts())
    }

    // Tokens, permissions and failures (TRD §6.3)

    private suspend fun fillEverything() {
        onboard()
        app.healthSource.steps[monday] = 4_000
        app.healthSource.sleep += night(monday, 23, 7)
        app.healthSource.put(workout("walk-1", ExerciseSessionRecord.EXERCISE_TYPE_WALKING, monday, 7, 35))
        app.healthSource.put(workout("yoga-1", ExerciseSessionRecord.EXERCISE_TYPE_YOGA, monday.minusDays(2), 8, 60))
        app.healthSource.body += scan("a", at(monday, 8, 5), 80_000.0)
    }

    private suspend fun everything() = listOf(
        imported().map { it.copy(id = 0) }.sortedBy { it.startedAt },
        app.db.healthDao().drafts().map { it.copy(id = 0) },
        app.db.healthDao().activity(monday.minusDays(40), monday),
    ).toString() + app.db.workoutDao().let { dao -> imported().map { dao.yogaDetail(it.id)!!.copy(sessionId = 0) }.toString() }

    @Test
    fun `an expired change token means a full re-read of 30 days, which creates no duplicates`() = runBlocking {
        fillEverything()
        app.health.sync()
        val first = everything()
        assertEquals(2, imported().size)

        app.healthSource.expireTokens()
        val report = app.health.sync()!!

        assertEquals(HealthFeature.entries.toSet(), report.read)
        assertEquals(first, everything())
        listOf(HealthFeature.SLEEP, HealthFeature.WORKOUTS, HealthFeature.INBODY).forEach { assertEquals("$it was read in full again", 2, app.healthSource.reads[it]) }
        // And the fresh tokens work: the next sync reads nothing in full.
        app.health.sync()
        assertEquals(2, app.healthSource.reads[HealthFeature.WORKOUTS])
    }

    @Test
    fun `a restored phone starts with a full read and finds what the backup already holds`() = runBlocking {
        fillEverything()
        app.health.sync()
        val first = everything()

        // The device store isn't in any backup. On another phone Health Connect hands out new record ids, too.
        app.close()
        // A new phone: the database and settings came back, the device store didn't.
        val newPhone = File(tmp.root, "restored").apply { mkdirs() }
        File(tmp.root, "app").listFiles()!!.filter { it.name.startsWith("recomp-test.db") || it.name == "settings.preferences_pb" }.forEach { it.copyTo(File(newPhone, it.name)) }
        val restored = TestApp(newPhone)
        restored.clock.instant = app.clock.instant
        restored.healthSource.steps += app.healthSource.steps
        restored.healthSource.sleep += app.healthSource.sleep
        app.healthSource.workouts.values.forEach { restored.healthSource.put(it.copy(id = "new-${it.id}")) }
        restored.healthSource.body += app.healthSource.body.map { it.copy(id = "new-${it.id}") }
        app = restored
        app.health.sync()

        assertEquals(1, app.healthSource.reads[HealthFeature.WORKOUTS])
        assertEquals(setOf("new-walk-1", "new-yoga-1"), imported().map { it.hcRecordId }.toSet())
        assertEquals(first.replace("walk-1", "new-walk-1").replace("yoga-1", "new-yoga-1").replace("a-", "new-a-"), everything())
    }

    @Test
    fun `an import of a backup drops the tokens, so everything is read against the new data`() = runBlocking {
        fillEverything()
        app.health.sync()
        // What an import does to the database (TRD §6.5): the rows are replaced and the header gets a new stamp.
        app.db.openHelper.writableDatabase.execSQL("DELETE FROM workout_session WHERE hc_record_id IS NOT NULL")
        app.db.openHelper.writableDatabase.execSQL("PRAGMA application_id = 424242")

        app.health.sync()

        assertEquals(2, imported().size)
        assertEquals(2, app.healthSource.reads[HealthFeature.WORKOUTS])
    }

    @Test
    fun `only what's on and allowed is read`() = runBlocking {
        fillEverything()
        app.health.setFeature(HealthFeature.INBODY, false)
        app.healthSource.granted = setOf(HealthFeature.STEPS, HealthFeature.INBODY)

        val report = app.health.sync()!!

        assertEquals(setOf(HealthFeature.STEPS), report.read)
        assertEquals(4_000, app.db.healthDao().day(monday)!!.steps)
        assertNull(app.db.healthDao().day(monday)!!.sleepHours)
        assertEquals(emptyList<Any>(), imported())
        assertEquals(emptyList<Any>(), app.db.healthDao().drafts())
        val status = app.health.status()
        assertEquals(setOf(HealthFeature.STEPS), status.active)
        assertTrue(HealthFeature.SLEEP in status.on && HealthFeature.SLEEP !in status.granted)
    }

    @Test
    fun `without Health Connect nothing is read and nothing breaks`() = runBlocking {
        fillEverything()
        app.healthSource.availability = HealthAvailability.UNAVAILABLE
        assertNull(app.health.sync())
        assertFalse(app.health.backgroundSyncWanted())
        app.healthSource.availability = HealthAvailability.AVAILABLE
        HealthFeature.entries.forEach { app.health.setFeature(it, false) }
        assertNull(app.health.sync())
        assertEquals(emptyMap<HealthFeature, Int>(), app.healthSource.reads)
    }

    @Test
    fun `one feature failing leaves the others synced and is tried again in full`() = runBlocking {
        fillEverything()
        app.healthSource.failing = HealthFeature.WORKOUTS

        val report = app.health.sync()!!

        assertEquals(setOf(HealthFeature.WORKOUTS), report.errors.keys)
        assertEquals(4_000, app.db.healthDao().day(monday)!!.steps)
        assertEquals(1, app.db.healthDao().drafts().size)
        assertTrue(app.device.healthSync.first().error!!.startsWith("workouts: "))
        assertNull("no token is kept for a read that failed", app.device.changesToken(HealthFeature.WORKOUTS))

        app.healthSource.failing = null
        app.health.sync()
        assertEquals(2, imported().size)
        assertNull(app.device.healthSync.first().error)
    }

    @Test
    fun `the daily job is wanted only with a feature on and background reads granted`() = runBlocking {
        assertTrue(app.health.backgroundSyncWanted())
        app.healthSource.background = BackgroundRead.NOT_GRANTED
        assertFalse(app.health.backgroundSyncWanted())
        app.healthSource.background = BackgroundRead.UNAVAILABLE
        assertFalse(app.health.backgroundSyncWanted())
    }

    // M8's review reads what Health Connect filled (TRD §10 M13)

    @Test
    fun `steps, sleep and other apps' workouts appear in the weekly review`() = runBlocking {
        onboard()
        val week = WeeklyPlan.weekStart(monday.minusDays(7))
        (0L..6L).forEach { app.healthSource.steps[week.plusDays(it)] = 7_000 + it * 100 }
        app.healthSource.sleep += night(week.plusDays(1), 23, 6)
        app.healthSource.sleep += night(week.plusDays(2), 23, 7)
        app.healthSource.put(workout("walk-1", ExerciseSessionRecord.EXERCISE_TYPE_WALKING, week.plusDays(2), 7, 40))
        app.healthSource.put(workout("yoga-1", ExerciseSessionRecord.EXERCISE_TYPE_YOGA, week.plusDays(5), 8, 60))
        val before = app.reviews.week(week, monday)
        assertNull("hidden while there's nothing", before.activity)

        app.health.sync()

        val review = app.reviews.week(week, monday)
        val activity = review.activity!!
        assertEquals(7 to 7_300, activity.stepsDays to activity.stepsAvg)
        assertEquals(2, activity.sleepDays)
        assertEquals(7.5, activity.sleepAvgHours!!, 1e-9)
        val extras = review.training.extras.associate { it.name to it.done }
        assertEquals(1, extras["Walks"])
        assertEquals(1, extras["Yoga class"])
        assertEquals(0, review.training.strengthDays)
    }
}
