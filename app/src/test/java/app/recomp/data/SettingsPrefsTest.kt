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
import app.recomp.data.prefs.VolumeBand
import app.recomp.domain.training.LogDates
import app.recomp.data.prefs.SettingsKeys
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.recomp.data.health.HealthFeature
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

    @Test
    fun `the weekly sets band defaults to 10 to 20, and a backup with a nonsense band is refused`() = runBlocking {
        val min = intPreferencesKey("volume_band_min")
        val max = intPreferencesKey("volume_band_max")
        val (prefs, _) = storeWith()
        assertEquals(VolumeBand(10, 20), prefs.volumeBand.first())
        prefs.setVolumeBand(6, 12)
        assertEquals(VolumeBand(6, 12), prefs.volumeBand.first())
        assertEquals(emptyList<String>(), prefs.problems(mutablePreferencesOf(min to 6, max to 12)))
        assertEquals(emptyList<String>(), prefs.problems(mutablePreferencesOf(max to 25)))
        assertEquals(listOf("volume_band_min", "volume_band_max"), prefs.problems(mutablePreferencesOf(min to 12, max to 6)))
        assertEquals(listOf("volume_band_min", "volume_band_max"), prefs.problems(mutablePreferencesOf(min to 30)))
        assertEquals(listOf("volume_band_min"), prefs.problems(mutablePreferencesOf(stringPreferencesKey("volume_band_min") to "ten")))

        // A stored band that makes no sense reads as the default.
        val (odd, _) = storeWith(min to 40, max to 5)
        assertEquals(VolumeBand(), odd.volumeBand.first())
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

    // Health Connect (M13)

    @Test
    fun `health settings default to off, clamp the step goal and ignore a feature this version doesn't know`() = runBlocking {
        val (fresh, _) = storeWith()
        assertEquals(emptySet<HealthFeature>(), fresh.healthFeatures.first())
        assertEquals(8_000, fresh.stepsGoal.first())
        assertFalse(fresh.trackSleep.first())

        val (prefs, _) = storeWith(stringSetPreferencesKey("health_features") to setOf("STEPS", "HEART_RATE"), intPreferencesKey("steps_goal") to 250_000)
        assertEquals(setOf(HealthFeature.STEPS), prefs.healthFeatures.first())
        assertEquals(20_000, prefs.stepsGoal.first())
        prefs.setStepsGoal(100)
        assertEquals(2_000, prefs.stepsGoal.first())
    }

    @Test
    fun `turning on Health Connect sleep turns on track sleep, and turning it off leaves that alone`() = runBlocking {
        val (prefs, _) = storeWith()
        prefs.setHealthFeature(HealthFeature.STEPS, true)
        assertFalse(prefs.trackSleep.first())
        prefs.setHealthFeature(HealthFeature.SLEEP, true)
        assertTrue(prefs.trackSleep.first())
        prefs.setHealthFeature(HealthFeature.SLEEP, false)
        assertTrue(prefs.trackSleep.first())
        assertEquals(setOf(HealthFeature.STEPS), prefs.healthFeatures.first())
    }

    @Test
    fun `a backup with health settings of the wrong type is refused, and good ones are exported`() = runBlocking {
        val (prefs, store) = storeWith(
            stringPreferencesKey("health_features") to "STEPS", stringPreferencesKey("steps_goal") to "8000", stringPreferencesKey("track_sleep") to "yes",
        )
        assertEquals(listOf("steps_goal", "track_sleep", "health_features"), prefs.problems(store.data.first()))

        val (good, goodStore) = storeWith()
        good.setHealthFeature(HealthFeature.SLEEP, true)
        good.setStepsGoal(9_000)
        assertEquals(emptyList<String>(), good.problems(goodStore.data.first()))
        assertEquals(setOf("health_features", "steps_goal", "track_sleep"), good.exportable().asMap().keys.map { it.name }.toSet())
    }
}
