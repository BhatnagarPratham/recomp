package app.recomp.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.domain.nutrition.Sex
import app.recomp.domain.persona.Profile
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A stored setting that no longer decodes is kept aside, never overwritten with defaults (TRD §4). */
@RunWith(RobolectricTestRunner::class)
class SettingsPrefsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val persona = stringPreferencesKey("persona")
    private val personaCopy = stringPreferencesKey("persona_unreadable")
    private val profile = stringPreferencesKey("profile")
    private val profileCopy = stringPreferencesKey("profile_unreadable")

    private fun storeWith(vararg values: Preferences.Pair<*>): Pair<SettingsPrefs, DataStore<Preferences>> = runBlocking {
        val store = PreferenceDataStoreFactory.create { File(tmp.newFolder(), "settings.preferences_pb") }
        store.edit { prefs -> prefs.putAll(*values) }
        SettingsPrefs(store) to store
    }

    // A persona saved by a newer build, with a split this build doesn't know.
    private val fromNewerBuild = """{"split":"FIVE_DAY","strengthDaysPerWeek":5,"diet":"VEGAN"}"""

    @Test
    fun `an unreadable persona is reported and reads as not onboarded`() = runBlocking {
        val (prefs, _) = storeWith(persona to fromNewerBuild)
        assertEquals(listOf("persona"), prefs.unreadable.first())
        assertFalse(prefs.persona.first().onboarded)
        assertNull(prefs.persona.first().split)
    }

    @Test
    fun `writing the persona keeps the unreadable value aside first`() = runBlocking {
        val (prefs, store) = storeWith(persona to fromNewerBuild)
        prefs.updatePersona { it.copy(strengthDaysPerWeek = 4) }

        val raw = store.data.first()
        assertEquals(fromNewerBuild, raw[personaCopy])
        assertEquals(4, prefs.persona.first().strengthDaysPerWeek)
        // The new value reads, so there's nothing left to report.
        assertEquals(emptyList<String>(), prefs.unreadable.first())
    }

    @Test
    fun `a multi-setting edit keeps every unreadable value, and only the first copy`() = runBlocking {
        val (prefs, store) = storeWith(persona to fromNewerBuild, profile to "{broken", personaCopy to "older copy")
        assertEquals(listOf("persona", "profile"), prefs.unreadable.first())
        prefs.edit {
            setProfile(Profile(Sex.FEMALE, LocalDate.of(1990, 1, 1), 160.0, 60.0))
            setDayStart(LocalTime.of(5, 0))
        }
        val raw = store.data.first()
        assertEquals("older copy", raw[personaCopy])
        assertEquals("{broken", raw[profileCopy])
        // The persona wasn't written, so it's still there as it was.
        assertEquals(fromNewerBuild, raw[persona])
        assertEquals(listOf("persona"), prefs.unreadable.first())
    }

    @Test
    fun `readable settings make no copies`() = runBlocking {
        val (prefs, store) = storeWith(persona to """{"split":"PPL"}""")
        prefs.updatePersona { it.copy(strengthDaysPerWeek = 4) }
        assertNull(store.data.first()[personaCopy])
        assertEquals(emptyList<String>(), prefs.unreadable.first())
    }
}
