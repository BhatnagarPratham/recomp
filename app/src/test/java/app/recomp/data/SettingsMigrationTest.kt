package app.recomp.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.recomp.data.prefs.ExtraSchedule
import app.recomp.data.prefs.LegacyTrainingMigration
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.domain.persona.Experience
import app.recomp.domain.persona.SplitTemplate
import app.recomp.domain.training.LogDates
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** M1/M2's `training` settings move into the persona on the first read after the upgrade (TRD §4). */
@RunWith(RobolectricTestRunner::class)
class SettingsMigrationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val legacy = stringPreferencesKey("training")

    /** Writes [json] the way M2 did, with no migrations, then reopens the file as the app now does. */
    private fun upgradeFrom(json: String?, extra: (MutablePreferences) -> Unit = {}): SettingsPrefs = runBlocking {
        val file = File(tmp.newFolder(), "settings.preferences_pb")
        // DataStore allows one active instance per file, so the old one gets its own scope and is closed first.
        val job = SupervisorJob()
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }.edit { prefs ->
            if (json != null) prefs[legacy] = json
            extra(prefs)
        }
        job.cancelAndJoin()
        SettingsPrefs(PreferenceDataStoreFactory.create(migrations = listOf(LegacyTrainingMigration)) { file })
    }

    @Test
    fun `the training setup becomes the persona's training part`() = runBlocking {
        val prefs = upgradeFrom(
            """{"split":"PPL","experience":"INTERMEDIATE","strengthDaysPerWeek":4,"extras":[{"routineId":9,"days":[6]}],"keepScreenOn":true}""",
        )
        val persona = prefs.persona.first()
        assertEquals(SplitTemplate.PPL, persona.split)
        assertEquals(Experience.INTERMEDIATE, persona.experience)
        assertEquals(4, persona.strengthDaysPerWeek)
        assertEquals(listOf(ExtraSchedule(9, setOf(6))), persona.extras)
        // There's no goal or body data yet, so onboarding still has to run.
        assertFalse(persona.onboarded)
        assertNull(prefs.profile.first())

        val training = prefs.training.first()
        assertTrue(training.keepScreenOn)
        assertTrue(training.setupDone)
        assertEquals(LogDates.DEFAULT_DAY_START, prefs.dayStart.first())
    }

    @Test
    fun `defaults omitted from the old JSON come through as defaults`() = runBlocking {
        val prefs = upgradeFrom("""{"split":"FULL_BODY_AB"}""")
        val training = prefs.training.first()
        assertEquals(SplitTemplate.FULL_BODY_AB, training.split)
        assertEquals(Experience.BEGINNER, training.experience)
        assertEquals(3, training.strengthDaysPerWeek)
        assertFalse(training.keepScreenOn)
    }

    @Test
    fun `the old key is deleted, so the migration runs once`() = runBlocking {
        val prefs = upgradeFrom("""{"split":"PPL"}""")
        prefs.updatePersona { it.copy(strengthDaysPerWeek = 5) }
        assertEquals(5, prefs.persona.first().strengthDaysPerWeek)
        assertFalse(LegacyTrainingMigration.shouldMigrate(emptyPreferences()))
    }

    @Test
    fun `an existing persona is never overwritten by stale training settings`() = runBlocking {
        val prefs = upgradeFrom("""{"split":"PPL"}""") { it[stringPreferencesKey("persona")] = """{"split":"UPPER_LOWER","strengthDaysPerWeek":4}""" }
        assertEquals(SplitTemplate.UPPER_LOWER, prefs.persona.first().split)
    }

    @Test
    fun `a fresh install has nothing to migrate`() = runBlocking {
        val prefs = upgradeFrom(null)
        assertNull(prefs.persona.first().split)
        assertFalse(prefs.training.first().setupDone)
    }

    @Test
    fun `unreadable old settings are dropped rather than crashing`() = runBlocking {
        val prefs = upgradeFrom("not json")
        assertNull(prefs.persona.first().split)
    }
}
