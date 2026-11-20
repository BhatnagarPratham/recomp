package app.recomp.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.recomp.data.prefs.ExtraSchedule
import app.recomp.data.prefs.StoredPersona
import app.recomp.domain.training.LogDates
import app.recomp.data.prefs.SettingsKeys
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.domain.food.MealSlot
import app.recomp.domain.food.MealTimes
import app.recomp.domain.food.SnackAllowance
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
import org.junit.Assert.assertTrue
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
    fun `a persona that decodes but can't become a plan is unreadable too, and never crashes a read`() = runBlocking {
        val day9 = """{"split":"PPL","extras":[{"routineId":3,"days":[9]}]}"""
        val badGoal = """{"split":"PPL","goal":{"goal":"FAT_LOSS","lossRatePctPerWeek":-0.5,"proteinGPerKg":2.0,"startedOn":"2026-10-01"}}"""
        val both = """{"split":"PPL","goal":{"goal":"RECOMP","lossRatePctPerWeek":0.5,"surplusFraction":0.1,"proteinGPerKg":1.6,"startedOn":"2026-10-01"}}"""
        listOf(day9, badGoal, both).forEach { stored ->
            val (prefs, _) = storeWith(persona to stored)
            assertEquals(stored, listOf("persona"), prefs.unreadable.first())
            assertEquals(stored, StoredPersona(), prefs.persona.first())
            assertEquals(stored, emptyList<ExtraSchedule>(), prefs.training.first().extras)
            assertEquals(stored, listOf("persona"), prefs.problems(prefsOf(stored)))
        }
    }

    @Test
    fun `a scan slot or day start out of range reads as unset`() = runBlocking {
        val (prefs, _) = storeWith(stringPreferencesKey("scan_slot") to """{"day":9,"minutes":600}""", intPreferencesKey("day_start_minutes") to 99_999)
        assertNull(prefs.scanSlot.first())
        assertEquals(LogDates.DEFAULT_DAY_START, prefs.dayStart.first())
        assertEquals(listOf("scan_slot"), prefs.unreadable.first())
    }

    private fun prefsOf(personaJson: String) = mutablePreferencesOf(persona to personaJson)

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

    @Test
    fun `meal slots saved before M9 read without a start, and a start you set is kept`() = runBlocking {
        val old = """[{"key":"breakfast","name":"Breakfast"},{"key":"meal-1","name":"Pre-workout","removed":true}]"""
        val (prefs, _) = storeWith(stringPreferencesKey("meal_slots") to old)
        assertEquals(listOf(MealSlot("breakfast", "Breakfast"), MealSlot("meal-1", "Pre-workout", removed = true)), prefs.mealSlots.first())
        assertEquals("the default start still applies", 5 * 60, MealTimes.start(prefs.mealSlots.first()[0]))
        assertEquals(emptyList<String>(), prefs.problems(mutablePreferencesOf(stringPreferencesKey("meal_slots") to old)))

        prefs.edit { mealSlots = mealSlots.map { if (it.key == "meal-1") it.copy(startMinutes = 17 * 60 + 30) else it } }
        assertEquals(17 * 60 + 30, prefs.mealSlots.first()[1].startMinutes)
        assertNull(prefs.mealSlots.first()[0].startMinutes)

        // A start outside the day is refused on import and ignored on read.
        val bad = """[{"key":"breakfast","name":"Breakfast","start":5000}]"""
        assertEquals(listOf("meal_slots"), prefs.problems(mutablePreferencesOf(stringPreferencesKey("meal_slots") to bad)))
        assertNull(storeWith(stringPreferencesKey("meal_slots") to bad).first.mealSlots.first()[0].startMinutes)
    }

    @Test
    fun `the snack allowance is off until set, and only the offered amounts are kept`() = runBlocking {
        val (prefs, store) = storeWith()
        assertNull(prefs.snackAllowance.first())
        prefs.setSnackAllowance(SnackAllowance(150))
        assertEquals(SnackAllowance(150, "snacks"), prefs.snackAllowance.first())
        assertEquals("""{"kcal":150}""", store.data.first()[stringPreferencesKey("snack_allowance")])
        prefs.setSnackAllowance(SnackAllowance(250, "meal-1"))
        assertEquals(SnackAllowance(250, "meal-1"), prefs.snackAllowance.first())
        assertEquals(emptyList<String>(), prefs.problems(store.data.first()))
        assertTrue("it travels with a settings export", SettingsKeys.exported("snack_allowance"))
        prefs.setSnackAllowance(null)
        assertNull(prefs.snackAllowance.first())
        assertFalse(store.data.first().contains(stringPreferencesKey("snack_allowance")))
        assertTrue(runCatching { prefs.setSnackAllowance(SnackAllowance(125)) }.isFailure)

        // A value this version doesn't offer reads as off, and an import with one is refused.
        val odd = """{"kcal":125,"slot":"snacks"}"""
        assertNull(storeWith(stringPreferencesKey("snack_allowance") to odd).first.snackAllowance.first())
        assertEquals(listOf("snack_allowance"), prefs.problems(mutablePreferencesOf(stringPreferencesKey("snack_allowance") to odd)))
        assertEquals(listOf("snack_allowance"), prefs.problems(mutablePreferencesOf(stringPreferencesKey("snack_allowance") to "not json")))
    }
}
