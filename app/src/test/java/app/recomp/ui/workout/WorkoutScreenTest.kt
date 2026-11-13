package app.recomp.ui.workout

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import app.recomp.data.TestApp
import app.recomp.data.repo.TrainingSetup
import app.recomp.domain.persona.Experience
import app.recomp.domain.persona.SplitTemplate
import app.recomp.workout.RestTimer
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The workout screen is the most-used screen, so supersets must not change a plain workout (TRD §10,
 * M6 risks): ticking moves the dock to the next set, with rest; in a superset it moves to the next
 * exercise's set with no rest, then rests after the round. Invented loads.
 */
@RunWith(RobolectricTestRunner::class)
// A phone-sized screen: Robolectric's default 320 × 470 dp leaves the cards under the set dock.
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class WorkoutScreenTest {
    @get:Rule(order = 0)
    val tmp = TemporaryFolder()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private lateinit var app: TestApp
    private lateinit var restTimer: RestTimer

    @Before
    fun setUp() = runBlocking {
        app = TestApp(File(tmp.root, "app").apply { mkdirs() })
        restTimer = RestTimer(PreferenceDataStoreFactory.create { File(tmp.root, "workout_state.preferences_pb") })
        app.training.applySetup(TrainingSetup(SplitTemplate.PPL, Experience.BEGINNER, 3, emptyList(), emptyMap(), emptyMap()))
    }

    @After
    fun tearDown() = app.close()

    /** Push, with last time's bench and shoulder press logged so every set has ghost values to tick. */
    private suspend fun pushWithHistory(): Long {
        val push = app.training.observeRotation().first().first()
        val earlier = app.workouts.startRoutine(push.id, now = 1_000_000)
        app.workouts.observeSessionExercises(earlier).first().take(2).forEachIndexed { i, ex ->
            ex.sets.forEach { app.workouts.completeSet(it.id, if (i == 0) 40_000 else 15_000, 10, null, now = 1_060_000) }
        }
        app.workouts.finish(earlier, endedAt = 4_600_000)
        return app.workouts.startRoutine(push.id, now = 90_000_000)
    }

    private fun show(sessionId: Long) {
        val viewModel = WorkoutViewModel(SavedStateHandle(mapOf("sessionId" to sessionId)), app.workouts, app.training, app.progression, restTimer)
        compose.setContent { WorkoutScreen(onBack = {}, onOpenExercise = {}, onOpenHistory = {}, onFinished = {}, onDiscarded = {}, viewModel = viewModel) }
    }

    private fun awaitText(text: String) = compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun rest() = runBlocking { restTimer.state.first() }

    private fun tickInDock() = compose.onNodeWithContentDescription("Log this set").performClick()

    @Test
    fun `in a plain workout ticking selects the exercise's next set and starts its rest`() {
        val session = runBlocking { pushWithHistory() }
        show(session)
        awaitText("Bench press · set 1 of 3")

        tickInDock()
        awaitText("Bench press · set 2 of 3")
        compose.waitUntil(5_000) { rest() != null }
        assertEquals(150, rest()!!.totalSec)
        compose.onNodeWithText("Skip").assertExists()
        assertEquals(1, runBlocking { app.workouts.observeSessionExercises(session).first().first().sets.count { it.completedAt != null } })
    }

    @Test
    fun `the first heavy lift offers warm-ups, and Add puts them in to tick first`() {
        val session = runBlocking { pushWithHistory() }
        show(session)
        // Today's bench target is 40 kg: 40%, 60% and 80%, rounded down to 2.5 kg, and never under the 20 kg bar.
        awaitText("Warm-up: 20 × 8 · 22.5 × 5 · 30 × 3")
        // Only the first compound lift with a target load gets the line.
        assertEquals(1, compose.onAllNodesWithText("Warm-up:", substring = true).fetchSemanticsNodes().size)

        compose.onNodeWithContentDescription("Add warm-up sets").performClick()
        awaitText("Bench press · set 1 of 6 · warm-up")
        compose.onNodeWithText("Warm-up: 20 × 8 · 22.5 × 5 · 30 × 3").assertDoesNotExist()
    }

    @Test
    fun `in a superset ticking selects the next exercise's set with no rest, then rests after the round`() {
        val session = runBlocking {
            pushWithHistory().also { id ->
                app.workouts.supersetWithNext(id, app.workouts.observeSessionExercises(id).first().first().entry.id)
            }
        }
        show(session)
        awaitText("Bench press · set 1 of 3")
        compose.onNodeWithText("Superset · 1 of 2").assertExists()

        tickInDock()
        awaitText("Dumbbell shoulder press · set 1 of 3")
        compose.waitForIdle()
        assertNull(rest())
        compose.onNodeWithText("Skip").assertDoesNotExist()

        tickInDock()
        awaitText("Bench press · set 2 of 3")
        compose.waitUntil(5_000) { rest() != null }
        // The round ends on the shoulder press, so its rest runs.
        val shoulderRest = runBlocking { app.workouts.observeSessionExercises(session).first()[1].entry.restSec }
        assertEquals(shoulderRest, rest()!!.totalSec)
        compose.onNodeWithText("Skip").assertExists()
    }
}
