package app.recomp.ui

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import app.recomp.data.TestApp
import app.recomp.data.db.CheckinDecision
import app.recomp.data.repo.ExtraDraft
import app.recomp.data.repo.OnboardingResult
import app.recomp.domain.model.SessionKind
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.Pace
import app.recomp.domain.nutrition.Sex
import app.recomp.domain.persona.FinishLine
import app.recomp.domain.persona.GoalPlan
import app.recomp.domain.persona.Presets
import app.recomp.domain.persona.Profile
import app.recomp.domain.training.WeeklyPlan
import app.recomp.ui.checkin.CheckinText
import app.recomp.ui.checkin.CheckinViewModel
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
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
 * The review's ‹ and › (HM-03): the week shown stays until the next has loaded, the range holds, and an answer goes to
 * the check-in it was given for. The ViewModel reads the real clock, so the invented data is placed around today.
 */
@RunWith(RobolectricTestRunner::class)
class CheckinViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: TestApp
    private lateinit var thisWeek: LocalDate
    private lateinit var first: LocalDate

    @Before
    fun setUp() = runBlocking {
        app = TestApp(File(tmp.root, "app").apply { mkdirs() })
        // Onboarded three weeks ago, with the day starting at midnight so "today" is the calendar's.
        first = WeeklyPlan.weekStart(LocalDate.now()).minusWeeks(3)
        val profile = Profile(Sex.MALE, LocalDate.of(1996, 1, 1), heightCm = 175.0, weightKg = 84.0)
        val plan = GoalPlan.of(Goal.RECOMP, Pace.STANDARD, 76.0, FinishLine.GoalWeight, first)
        val yoga = ExtraDraft(null, "Yoga class", SessionKind.YOGA, setOf(6))
        val onboarded = first.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        app.personas.completeOnboarding(OnboardingResult(Presets.REFERENCE.draft.copy(goal = plan), Presets.REFERENCE.key, listOf(yoga), profile, LocalTime.MIDNIGHT), now = onboarded)
        thisWeek = WeeklyPlan.weekStart(app.days.today())
    }

    @After
    fun tearDown() = app.close()

    private fun viewModel(week: LocalDate) = CheckinViewModel(SavedStateHandle(mapOf("weekStart" to week.toString())), app.checkins, app.reviews)

    /** Runs the main looper until [done]: the repositories hop to Room's and DataStore's threads and back. */
    private fun await(done: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!done()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() < until) { "Timed out" }
            Thread.sleep(5)
        }
    }

    @Test
    fun `‹ and › move within the weeks there are, keeping the week shown until the next has loaded`() {
        val vm = viewModel(thisWeek)
        await { vm.ui.value.loaded }
        val opened = vm.ui.value
        assertEquals(first..thisWeek, opened.weeks)
        assertEquals("This week", opened.title)
        assertNull(opened.checkin)
        assertFalse(opened.hasNext)
        assertTrue(opened.hasPrevious)
        // Nothing after this week.
        vm.show(previous = false)
        assertEquals(thisWeek, vm.ui.value.week)

        vm.show(previous = true)
        val last = thisWeek.minusWeeks(1)
        // Straight away, the week asked for moves and the one shown stays, title and all: no blank screen.
        val moving = vm.ui.value
        assertEquals(last, moving.week)
        assertTrue(moving.loading)
        assertEquals(thisWeek, moving.review!!.weekStart)
        assertEquals("This week", moving.title)
        await { !vm.ui.value.loading }
        val shown = vm.ui.value
        assertEquals(last, shown.review!!.weekStart)
        assertEquals(last, shown.checkin!!.row.weekStart)
        assertEquals(CheckinText.title(last), shown.title)

        // Back to the first week, and no further.
        repeat(5) { vm.show(previous = true) }
        assertEquals(first, vm.ui.value.week)
        assertFalse(vm.ui.value.hasPrevious)
        await { !vm.ui.value.loading }
        assertEquals(first, vm.ui.value.review!!.weekStart)
    }

    @Test
    fun `an answer goes to the check-in it was given for, even when it finishes after the week changed`() {
        val last = thisWeek.minusWeeks(1)
        val vm = viewModel(last)
        await { vm.ui.value.loaded }
        assertTrue(vm.ui.value.checkin!!.row.pending)

        vm.keep()
        assertTrue(vm.busy.value)
        // The week moves on while the answer is saving.
        vm.show(previous = false)
        await { !vm.busy.value && !vm.ui.value.loading }

        assertEquals(CheckinDecision.KEPT, runBlocking { app.db.checkinDao().week(last)!!.decision })
        // The screen shows the week asked for, not the answered one reloaded over it, and no error.
        assertEquals(thisWeek, vm.ui.value.week)
        assertEquals(thisWeek, vm.ui.value.review!!.weekStart)
        assertNull(vm.ui.value.checkin)
        assertNull(vm.error.value)

        // Back on the answered week, it shows the answer.
        vm.show(previous = true)
        await { !vm.ui.value.loading }
        assertEquals(CheckinDecision.KEPT, vm.ui.value.checkin!!.row.decision)
    }
}
