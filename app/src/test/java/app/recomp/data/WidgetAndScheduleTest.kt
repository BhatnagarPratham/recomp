package app.recomp.data

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.recomp.data.repo.OnboardingResult
import app.recomp.domain.food.Origin
import app.recomp.domain.nudge.ReminderKind
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.Pace
import app.recomp.domain.nutrition.Sex
import app.recomp.domain.nutrition.TargetOverrides
import app.recomp.domain.persona.GoalPlan
import app.recomp.domain.persona.Presets
import app.recomp.domain.persona.Profile
import app.recomp.reminders.ReminderReceiver
import app.recomp.reminders.ReminderScheduler
import app.recomp.widget.WidgetState
import app.recomp.widget.WidgetUpdater
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The widget's state (HM-05) after each thing that must redraw it, and the reminder alarm after a restart, a clock
 * change and a time-zone change (TRD §6.6). Invented data.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetAndScheduleTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: TestApp
    private lateinit var savedZone: TimeZone
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val india = ZoneId.of("Asia/Kolkata")

    /** A Monday. */
    private val day0 = LocalDate.of(2026, 11, 16)

    private fun at(d: Int, hour: Int, minute: Int = 0, zone: ZoneId = india): Long =
        day0.plusDays(d.toLong()).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(india))
        app = TestApp(File(tmp.root, "app").apply { mkdirs() })
    }

    @After
    fun tearDown() {
        app.close()
        TimeZone.setDefault(savedZone)
    }

    private suspend fun onboard(goal: Goal) {
        val profile = Profile(Sex.MALE, LocalDate.of(1996, 1, 1), heightCm = 175.0, weightKg = 80.0)
        val draft = Presets.REFERENCE.draft.copy(goal = GoalPlan.of(goal, Pace.STANDARD, null, null, day0), proteinRamp = false)
        app.personas.completeOnboarding(OnboardingResult(draft, Presets.REFERENCE.key, emptyList(), profile, LocalTime.of(4, 0)), now = at(0, 5))
    }

    private suspend fun widget(d: Int, hour: Int) = WidgetState.of(app.snapshots.load(at(d, hour)))

    // The widget's state

    @Test
    fun `before onboarding the widget asks you to finish setting up`() = runBlocking {
        assertFalse(widget(0, 9).ready)
    }

    @Test
    fun `the widget follows a food log, a finished workout, a target change and the day rolling over`() = runBlocking {
        onboard(Goal.RECOMP)
        val kcal = app.nutrition.targetOn(day0)!!.kcal
        val first = widget(0, 9)
        assertEquals(WidgetState(true, "Protein left", "128 g", "Calories ${"%,d".format(kcal)} left", "Push next"), first)

        // Food logged.
        app.foodLog.quickAdd(day0, "lunch", "Test lunch", 700.0, 40.0, null, null, Origin.HOME, now = at(0, 13))
        val fed = widget(0, 13)
        assertEquals("88 g", fed.mainValue)
        assertEquals("Calories ${"%,d".format(kcal - 700)} left", fed.second)

        // A workout in progress, then finished: a rest day.
        val push = app.training.observeRotation().first().first()
        val session = app.workouts.startRoutine(push.id, now = at(0, 18))
        assertEquals("Workout in progress", widget(0, 18).workout)
        app.workouts.observeSessionExercises(session).first().first().sets.first().let { app.workouts.completeSet(it.id, 20_000, 8, rir = null, now = at(0, 18, 10)) }
        app.workouts.finish(session, endedAt = at(0, 19))
        assertEquals("Rest day", widget(0, 19).workout)

        // The targets changed.
        app.personas.save(overrides = TargetOverrides(proteinG = 150), now = at(0, 20))
        assertEquals("110 g", widget(0, 20).mainValue)

        // Past midnight it's still today; at "day starts at" the new day begins, with the next routine up.
        assertEquals("110 g", widget(1, 3).mainValue)
        val tomorrow = widget(1, 4)
        assertEquals("150 g", tomorrow.mainValue)
        assertEquals("Pull next", tomorrow.workout)
    }

    @Test
    fun `the main number follows the goal, with protein beside calories`() = runBlocking {
        onboard(Goal.FAT_LOSS)
        val kcal = app.nutrition.targetOn(day0)!!.kcal
        val protein = app.nutrition.targetOn(day0)!!.proteinG
        assertEquals("Calories left", widget(0, 9).mainLabel)
        assertEquals("%,d".format(kcal), widget(0, 9).mainValue)
        assertEquals("Protein $protein g left", widget(0, 9).second)
        // Over the target is a plain number.
        app.foodLog.quickAdd(day0, "dinner", "Test dinner", kcal + 150.0, protein + 20.0, null, null, Origin.HOME, now = at(0, 20))
        assertEquals("150 over", widget(0, 21).mainValue)
        assertEquals("Protein 0 g left", widget(0, 21).second)
    }

    @Test
    fun `the snapshot flow emits again when food is logged`() = runBlocking {
        onboard(Goal.RECOMP)
        val now = at(0, 13)
        val before = app.snapshots.observe { now }.first()
        assertEquals(0, before.food!!.proteinEatenG)
        app.foodLog.quickAdd(day0, "lunch", "Test lunch", 700.0, 40.0, null, null, Origin.HOME, now = now)
        assertEquals(40, app.snapshots.observe { now }.first().food!!.proteinEatenG)
    }

    // The alarm: a restart, a clock change, a time-zone change

    private fun scheduler() = ReminderScheduler(context, app.reminders, app.prefs, WidgetUpdater(context, app.snapshots))

    private fun alarm() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.singleOrNull()

    @Test
    fun `with no reminder on and no widget, there's no alarm`() = runBlocking {
        scheduler().reschedule(at(0, 6))
        assertNull(alarm())
    }

    @Test
    fun `the alarm is inexact, may fire in Doze, and is set for the next reminder`() = runBlocking {
        app.prefs.updateReminders { it.with(ReminderKind.EVENING, true) }
        scheduler().reschedule(at(0, 6))
        val set = alarm()!!
        assertEquals(at(0, 20, 30), set.triggerAtMs)
        assertEquals(AlarmManager.RTC_WAKEUP, set.type)
        assertTrue(set.isAllowWhileIdle)
        assertNull("no exact alarm, so no exact-alarm permission", set.alarmClockInfo)
        // Turning it off takes the alarm away.
        app.prefs.updateReminders { it.with(ReminderKind.EVENING, false) }
        scheduler().reschedule(at(0, 6))
        assertNull(alarm())
    }

    @Test
    fun `a restart clears alarms, and scheduling again restores the same one`() = runBlocking {
        app.prefs.updateReminders { it.with(ReminderKind.WEIGH_IN, true) }
        scheduler().reschedule(at(0, 6))
        // Android drops every alarm when the phone restarts (and when the app is updated).
        val manager = context.getSystemService(AlarmManager::class.java)
        shadowOf(manager).scheduledAlarms.toList().forEach { manager.cancel(it.operation!!) }
        assertNull(alarm())
        app.restart()
        scheduler().reschedule(at(0, 6, 30))
        assertEquals(at(0, 7, 30), alarm()!!.triggerAtMs)
    }

    @Test
    fun `the receiver wakes for a restart, an app update, and a clock or time-zone change`() {
        assertEquals(
            setOf(ReminderReceiver.ACTION_WAKE, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED),
            ReminderReceiver.ACTIONS,
        )
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val receiver = manifest.substringAfter("android:name=\".reminders.ReminderReceiver\"").substringBefore("</receiver>")
        (ReminderReceiver.ACTIONS - ReminderReceiver.ACTION_WAKE).forEach { assertTrue("the manifest listens for $it", receiver.contains("\"$it\"")) }
        assertTrue(manifest.contains("android.permission.RECEIVE_BOOT_COMPLETED"))
        assertFalse("inexact alarms only", manifest.contains("EXACT_ALARM"))
    }

    @Test
    fun `after a time-zone change the alarm moves to the same clock time in the new zone`() = runBlocking {
        onboard(Goal.RECOMP)
        app.prefs.updateReminders { it.with(ReminderKind.EVENING, true) }
        val now = at(0, 15) // 15:00 in India is 09:30 in London
        scheduler().reschedule(now)
        assertEquals(at(0, 20, 30), alarm()!!.triggerAtMs)

        val london = ZoneId.of("Europe/London")
        TimeZone.setDefault(TimeZone.getTimeZone(london))
        scheduler().reschedule(now)
        assertEquals(at(0, 20, 30, london), alarm()!!.triggerAtMs)
        assertEquals(5 * 3_600_000L + 30 * 60_000L, alarm()!!.triggerAtMs - at(0, 20, 30))

        // Sent there at 20:30; flying back east the same evening doesn't send it again.
        assertEquals(listOf(ReminderKind.EVENING), app.reminders.run(at(0, 20, 30, london), london).map { it.kind })
        TimeZone.setDefault(TimeZone.getTimeZone(india))
        assertTrue(app.reminders.run(at(0, 20, 30, london) + 60_000, india).isEmpty())
        scheduler().reschedule(at(0, 20, 30, london) + 60_000)
        assertEquals(at(1, 20, 30), alarm()!!.triggerAtMs)
    }

    @Test
    fun `a clock set forward past a reminder sends it at the next wake if it's recent, and the alarm moves on`() = runBlocking {
        app.prefs.updateReminders { it.with(ReminderKind.EVENING, true) }
        onboard(Goal.RECOMP)
        scheduler().reschedule(at(0, 15))
        // The clock jumps to 20:45: the wake that follows a clock change runs what's due.
        assertEquals(listOf(ReminderKind.EVENING), app.reminders.run(at(0, 20, 45), india).map { it.kind })
        scheduler().reschedule(at(0, 20, 45))
        assertEquals(at(1, 20, 30), alarm()!!.triggerAtMs)
    }
}
