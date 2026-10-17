package app.recomp.workout

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RestTimerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun timer() = RestTimer(PreferenceDataStoreFactory.create { File(tmp.root, "workout_state.preferences_pb") })

    @Test
    fun `plus 30 s extends a running rest and its total`() = runBlocking {
        val t = timer()
        t.start(90, now = 0)
        t.add(30, now = 10_000)
        assertEquals(RestState(endsAt = 120_000, totalSec = 120), t.state.first())
    }

    @Test
    fun `plus 30 s after a rest ended starts a fresh 30 s rest`() = runBlocking {
        val t = timer()
        t.start(90, now = 0)
        t.add(30, now = 100_000)
        assertEquals(RestState(endsAt = 130_000, totalSec = 30), t.state.first())
    }

    @Test
    fun `clearing only clears the rest that ended`() = runBlocking {
        val t = timer()
        t.start(60, now = 0)
        t.add(30, now = 50_000)
        t.clearIfEndsAt(60_000) // the alert for the original end raced the +30 s
        assertEquals(90_000L, t.state.first()!!.endsAt)
        t.clearIfEndsAt(90_000)
        assertNull(t.state.first())
    }
}
