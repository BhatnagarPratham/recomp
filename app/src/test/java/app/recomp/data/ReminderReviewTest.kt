package app.recomp.data

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.recomp.data.repo.CustomFood
import app.recomp.data.repo.OnboardingResult
import app.recomp.domain.food.FoodDiet
import app.recomp.domain.food.Origin
import app.recomp.domain.food.Per100g
import app.recomp.domain.food.PortionUnit
import app.recomp.domain.nudge.Reminder
import app.recomp.domain.nudge.ReminderKind
import app.recomp.domain.nudge.Reminders
import app.recomp.domain.nudge.Suggestion
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.Pace
import app.recomp.domain.nutrition.Sex
import app.recomp.domain.persona.GoalPlan
import app.recomp.domain.persona.Presets
import app.recomp.domain.persona.Profile
import app.recomp.reminders.NudgeActions
import app.recomp.reminders.NudgeMessages
import app.recomp.reminders.NudgeOutcome
import app.recomp.reminders.ReminderScheduler
import app.recomp.ui.nudge.EveningCardViewModel
import app.recomp.ui.reminders.blockedNotice
import app.recomp.widget.WidgetLayout
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The fixes from PR #20's review (S1–S4, N2–N8), over real rows. Invented data. */
@RunWith(RobolectricTestRunner::class)
class ReminderReviewTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: TestApp
    private lateinit var savedZone: TimeZone
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Asia/Kolkata")
    private val day0 = LocalDate.of(2026, 11, 16)

    private fun at(d: Int, hour: Int, minute: Int = 0): Long = day0.plusDays(d.toLong()).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** The notification's messages, collected; [broken] makes showing one fail too. */
    private class Messages : NudgeMessages {
        val shown = mutableListOf<String>()
        override fun logged(text: String, entryId: Long) {
            shown += text
        }
        override fun message(title: String, text: String) {
            shown += title
        }
    }

    @Before
    fun setUp() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        app = TestApp(File(tmp.root, "app").apply { mkdirs() })
    }

    @After
    fun tearDown() {
        app.close()
        TimeZone.setDefault(savedZone)
    }

    private suspend fun onboard() {
        val profile = Profile(Sex.MALE, LocalDate.of(1996, 1, 1), heightCm = 175.0, weightKg = 80.0)
        val draft = Presets.REFERENCE.draft.copy(goal = GoalPlan.of(Goal.RECOMP, Pace.STANDARD, null, null, day0), proteinRamp = false)
        app.personas.completeOnboarding(OnboardingResult(draft, Presets.REFERENCE.key, emptyList(), profile, LocalTime.of(4, 0)), now = at(0, 5))
    }

    private suspend fun curd(): Long {
        val id = app.foods.createCustom(CustomFood("Test hung curd", null, Per100g(105.0, 11.0, 4.0, 3.0), FoodDiet.VEGETARIAN, null), now = at(-3, 12))
        repeat(2) { app.foodLog.log(day0.minusDays(1), "dinner", app.foods.food(id)!!, 200.0, PortionUnit("g", 1.0, PortionUnit.Kind.GRAMS), Origin.HOME, at(-1, 20)) }
        return id
    }

    private suspend fun suggestion(): Suggestion = app.snapshots.evening(app.snapshots.load(at(0, 20, 30))).suggestions.single()

    // S1

    @Test
    fun `turning on another reminder while the evening ring is due doesn't drop it`() = runBlocking {
        onboard()
        val before = app.reminders.inputs().copy(reminders = Reminders(weighIn = Reminder(true, LocalTime.of(7, 30)), evening = Reminder(true, LocalTime.of(20, 30))))
        app.prefs.updateReminders { before.reminders }
        assertEquals(listOf(ReminderKind.WEIGH_IN), app.reminders.run(at(0, 7, 31), zone).map { it.kind })
        // 20:33: the inexact alarm hasn't fired yet when the lunch reminder is turned on.
        app.prefs.updateReminders { it.with(ReminderKind.LUNCH, true) }
        app.reminders.settingsChanged(before, app.reminders.inputs(), at(0, 20, 33), zone)
        assertEquals(listOf(ReminderKind.EVENING), app.reminders.run(at(0, 20, 34), zone).map { it.kind })
        // Lunch, turned on after its time, waits for tomorrow.
        assertTrue(ReminderKind.LUNCH in app.device.ledger()!!.handled)
        assertEquals(listOf(ReminderKind.WEIGH_IN, ReminderKind.EVENING), app.device.ledger()!!.sent)
    }

    // S2

    @Test
    fun `a wake that fails backs the alarm off instead of re-arming it for now`() = runBlocking {
        onboard()
        app.prefs.updateReminders { it.with(ReminderKind.EVENING, true) }
        val scheduler = ReminderScheduler(context, app.reminders, app.prefs, WidgetUpdater(context, app.snapshots))
        val now = at(0, 20, 31)
        app.db.close() // the snapshot can't be read: run() throws before it writes the ledger
        assertFalse(scheduler.wake(now))
        val alarm = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.single()
        assertEquals(now + ReminderScheduler.RETRY_AFTER_MS, alarm.triggerAtMs)
        // Once the database is back, the retry sends it, and the next alarm is tomorrow's.
        app.open()
        val later = now + ReminderScheduler.RETRY_AFTER_MS
        assertTrue(ReminderScheduler(context, app.reminders, app.prefs, WidgetUpdater(context, app.snapshots)).wake(later))
        assertEquals(listOf(ReminderKind.EVENING), app.device.ledger()!!.sent)
        assertEquals(at(1, 20, 30), shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.single().triggerAtMs)
    }

    // S3 at the repository: over the day's calories, the smallest portion

    @Test
    fun `over the day's calories the suggestion is the smallest portion`() = runBlocking {
        onboard()
        curd()
        val kcal = app.nutrition.targetOn(day0)!!.kcal
        app.foodLog.quickAdd(day0, "lunch", "Test day", kcal + 300.0, 40.0, null, null, Origin.HOME, now = at(0, 13))
        assertEquals(100.0, suggestion().grams, 0.0) // half the usual 200 g
    }

    // S4, N2, N3, N4

    @Test
    fun `a notification button never crashes, and says when it couldn't log`() = runBlocking {
        onboard()
        curd()
        val s = suggestion()
        val messages = Messages()
        val actions = NudgeActions(app.snapshots, app.days, messages)
        app.db.close()
        assertEquals(NudgeOutcome.FAILED, actions.log(s, day0, "t1", now = at(0, 20, 40)))
        assertEquals(NudgeOutcome.FAILED, actions.undo(5, now = at(0, 20, 41)))
        assertEquals(listOf("Couldn't log it", "Couldn't log it"), messages.shown)
        app.open()
    }

    @Test
    fun `yesterday's suggestion isn't logged into today, a second tap is ignored, and Undo says when there's nothing`() = runBlocking {
        onboard()
        curd()
        val s = suggestion()
        val messages = Messages()
        val actions = NudgeActions(app.snapshots, app.days, messages)
        // Tapped the next morning: refused, nothing logged.
        assertEquals(NudgeOutcome.STALE, actions.log(s, day0, "evening-1", now = at(1, 8)))
        assertTrue(app.foodLog.day(day0.plusDays(1)).isEmpty())
        // The same evening: the first tap logs, a second tap on the same notification doesn't.
        assertEquals(NudgeOutcome.LOGGED, actions.log(s, day0, "evening-2", now = at(0, 20, 40)))
        assertEquals(NudgeOutcome.REPEAT, actions.log(s, day0, "evening-2", now = at(0, 20, 40)))
        val entries = app.foodLog.day(day0)
        assertEquals(1, entries.size)
        // Undo takes it back once; again, there's nothing to undo.
        assertEquals(NudgeOutcome.UNDONE, actions.undo(entries.single().id, now = at(0, 20, 41)))
        assertEquals(NudgeOutcome.NOTHING_TO_UNDO, actions.undo(entries.single().id, now = at(0, 20, 42)))
        assertEquals(listOf("Not logged", "Logged Test hung curd · 400 g in Dinner", "Removed", "Nothing to undo"), messages.shown)
    }

    // N5

    @Test
    fun `Settings says why reminders that are on can't be sent`() {
        assertNull(blockedNotice(anyOn = false, permitted = false, checkinOn = false, remindersChannelOff = true, checkinChannelOff = true))
        assertTrue(blockedNotice(true, permitted = false, checkinOn = true, remindersChannelOff = false, checkinChannelOff = false)!!.startsWith("Notifications are off"))
        assertTrue(blockedNotice(true, true, checkinOn = false, remindersChannelOff = true, checkinChannelOff = false)!!.contains("Reminders notifications are switched off"))
        assertTrue(blockedNotice(true, true, checkinOn = true, remindersChannelOff = false, checkinChannelOff = true)!!.contains("Weekly check-in"))
        assertNull(blockedNotice(true, true, checkinOn = false, remindersChannelOff = false, checkinChannelOff = true))
        assertNull(blockedNotice(true, true, checkinOn = true, remindersChannelOff = false, checkinChannelOff = false))
    }

    // N6

    @Test
    fun `every size the widget can be resized to has a layout that fits`() {
        assertEquals(WidgetLayout.WIDE, WidgetLayout.of(250f, 40f)) // 4×1
        assertEquals(WidgetLayout.NARROW, WidgetLayout.of(110f, 40f)) // 2×1
        assertEquals(WidgetLayout.NARROW, WidgetLayout.of(180f, 60f)) // 3×1
        assertEquals(WidgetLayout.SQUARE, WidgetLayout.of(110f, 110f)) // 2×2
        assertEquals(WidgetLayout.SQUARE, WidgetLayout.of(320f, 180f))
        val info = File("src/main/res/xml/recomp_widget_info.xml").readText()
        assertTrue(info.contains("android:minResizeWidth=\"110dp\"") && info.contains("android:minResizeHeight=\"40dp\""))
    }

    // N7

    @Test
    fun `a tap on Today's card that can't log says so`() = runBlocking {
        onboard()
        val id = curd()
        val s = suggestion()
        val card = EveningCardViewModel(app.snapshots, app.prefs, app.days)
        app.foods.hide(id)
        card.logNow(s, now = at(0, 20, 40))
        assertEquals(EveningCardViewModel.NOT_LOGGED, card.notice.value)
        assertTrue(app.foodLog.day(day0).none { it.foodId == id })
        app.foods.restore(id)
        card.logNow(s, now = at(0, 20, 41))
        assertNull(card.notice.value)
    }

    // N8

    @Test
    fun `readers share one snapshot per change`() = runBlocking {
        onboard()
        val before = app.snapshots.loads
        val first = app.snapshots.shared.first()
        val second = app.snapshots.shared.first()
        assertNotNull(first)
        assertEquals(first, second)
        assertEquals(1, app.snapshots.loads - before)
    }
}
