package app.recomp.ui

import app.recomp.ui.onboarding.GoalText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Settings' note when what a change would save differs from the target in force (PRD §12.1, TRD §5.4). */
class TargetTextTest {
    @Test
    fun `no note when Settings and today's target agree, or before there's a target`() {
        assertNull(GoalText.inForceNote(1870, 1870, checkinWaiting = true))
        assertNull(GoalText.inForceNote(1870, null, checkinWaiting = false))
    }

    @Test
    fun `a waiting proposal says to answer it`() {
        assertEquals(
            "Today's target is ${GoalText.kcal(2000)} until you answer the check-in or change something here.",
            GoalText.inForceNote(1860, 2000, checkinWaiting = true),
        )
    }

    @Test
    fun `after Keep or an Adjust, only a change here moves it`() {
        assertEquals("Today's target is ${GoalText.kcal(1820)} until you change something here.", GoalText.inForceNote(1870, 1820, checkinWaiting = false))
    }
}
