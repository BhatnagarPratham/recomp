package app.recomp.data

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.recomp.data.backup.BackupException
import app.recomp.data.backup.BackupManager
import app.recomp.data.backup.BackupManifest
import app.recomp.data.backup.DatabaseData
import app.recomp.data.backup.DatabaseSnapshot
import app.recomp.data.backup.SchemaItem
import app.recomp.data.repo.ExtraDraft
import app.recomp.data.repo.OnboardingResult
import app.recomp.data.repo.ScanInput
import app.recomp.data.repo.SegmentalLean
import app.recomp.data.repo.WorkoutRepository.ExtraEntry
import app.recomp.domain.food.Origin
import app.recomp.domain.food.PortionUnit
import app.recomp.domain.model.SessionKind
import app.recomp.domain.model.SetType
import app.recomp.domain.nutrition.Goal
import app.recomp.domain.nutrition.Pace
import app.recomp.domain.nutrition.Sex
import app.recomp.domain.persona.FinishLine
import app.recomp.domain.persona.GoalPlan
import app.recomp.domain.persona.Presets
import app.recomp.domain.persona.Profile
import app.recomp.domain.persona.WeightSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Export and import (DA-01, TRD §6.5). The round trip is the required test (TRD §8): export, change
 * everything, import, and every table and setting is back exactly as it was.
 */
@RunWith(RobolectricTestRunner::class)
class BackupTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: TestApp
    private val json = Json { ignoreUnknownKeys = true }
    private val dayMs = 86_400_000L
    private val grams = PortionUnit("g", 1.0, PortionUnit.Kind.GRAMS)

    @Before
    fun setUp() {
        app = TestApp(File(tmp.root, "app").apply { mkdirs() })
    }

    @After
    fun tearDown() = app.close()

    /** Every table and every setting the app writes, with invented values. */
    private suspend fun fill() {
        val today = app.days.today()
        val now = System.currentTimeMillis()
        val profile = Profile(Sex.MALE, today.minusYears(30), heightCm = 175.0, weightKg = 95.0)
        val plan = GoalPlan.of(Goal.RECOMP, Pace.STANDARD, 78.0, FinishLine.GoalWeight, today)
        val yoga = ExtraDraft(null, "Yoga class", SessionKind.YOGA, setOf(6))
        app.personas.completeOnboarding(OnboardingResult(Presets.REFERENCE.draft.copy(goal = plan), Presets.REFERENCE.key, listOf(yoga), profile, LocalTime.of(4, 0)))

        // Food: the basics, a calibrated unit, a logged food, a quick add, a saved meal, a household measure.
        app.foodSeeder.ensureSeeded()
        val paneer = app.foods.search("paneer").first().food
        app.foods.calibrate(paneer.id, "katori", 180.0)
        app.foods.setHouseholdMeasure("glass", 300.0)
        app.foodLog.log(today, "lunch", paneer, 150.0, grams, Origin.HOME)
        app.foodLog.quickAdd(today.minusDays(1), "snacks", "Bar", 210.0, 20.5, null, 7.25, Origin.PACKAGED)
        app.foodLog.saveMeal("My lunch", today, "lunch")

        // Training: a finished workout with a ticked set, a PR, and a yoga class.
        val routine = app.training.observeNextRoutine().first()!!
        val session = app.workouts.startRoutine(routine.id, now - 2 * 3_600_000L)
        val exercise = app.db.workoutDao().sessionExercises(session).first()
        val set = exercise.sets.first { it.setType == SetType.WORKING }
        app.workouts.completeSet(set.id, 40_000, 10, 2, now - 3_000_000L)
        app.workouts.finish(session, now - 2_000_000L)
        app.db.openHelper.writableDatabase.execSQL(
            "INSERT INTO personal_record (exercise_id, session_id, type, value, load_g, set_entry_id, achieved_at) " +
                "VALUES (${exercise.exercise.id}, $session, 'MAX_LOAD', 40000, 40000, ${set.id}, ${now - 3_000_000L})",
        )
        val yogaRoutine = app.training.observeExtras().first().single()
        app.workouts.logExtra(yogaRoutine, ExtraEntry("Hatha", 60, 3, "Hips"), now - dayMs)

        // Body: weigh-ins from two sources, a waist, and a scan (which sets the usual slot).
        app.body.logWeighIn(80_100, WeightSource.HOME_SCALE, now - 3 * dayMs)
        app.body.logWeighIn(80_900, WeightSource.GYM_SCALE, now - 2 * dayMs)
        app.body.logWaist(91.5, now - 3 * dayMs)
        app.body.saveScan(
            ScanInput(
                measuredAt = now - dayMs, weightG = 80_300, skeletalMuscleG = 33_100, bodyFatG = 20_050, bodyFatPct = 25.0, visceralFatLevel = 8.5,
                minsSinceMeal = 180, workoutBefore = false, bmrKcal = 1700, ecwTbwRatio = 0.381, score = 74,
                segmental = SegmentalLean(3_500, 3_400, 26_000, 9_000, 8_900), notes = "Test scan",
            ),
        )
    }

    /** Everything a backup should bring back: every table's rows and every exported setting. */
    private suspend fun dump(): Pair<String, String> {
        val data = DatabaseSnapshot.read(app.db.openHelper.writableDatabase::query)
        return json.encodeToString(data.tables) to json.encodeToString(BackupManager.encodeSettings(app.prefs.exportable()))
    }

    private suspend fun export(): ByteArray = ByteArrayOutputStream().also { app.backup.export(it) }.toByteArray()

    private fun entries(zip: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                put(e.name, z.readBytes().decodeToString())
            }
        }
    }

    private fun zip(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> files.forEach { (name, text) -> z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() } }
    }.toByteArray()

    private suspend fun refused(bytes: ByteArray): String = try {
        app.backup.prepare(ByteArrayInputStream(bytes))
        fail("The backup was accepted")
        ""
    } catch (e: BackupException) {
        e.message!!
    }

    @Test
    fun `export then import gives back identical data`() = runBlocking {
        fill()
        val (tables, settings) = dump()
        // The fixture covers every table, so a new table that breaks the round trip can't hide.
        val empty = DatabaseSnapshot.read(app.db.openHelper.writableDatabase::query).tables.filterValues { it.rows.isEmpty() }.keys
        assertEquals(emptySet<String>(), empty)

        val backup = export()
        val manifest = json.decodeFromString<BackupManifest>(entries(backup).getValue(BackupManager.MANIFEST))
        assertEquals(BackupManager.FORMAT, manifest.format)
        assertEquals(Schemas.current, manifest.schemaVersion)
        assertEquals(1, manifest.count("body_composition"))
        assertEquals(3, manifest.count("body_weight"))
        assertFalse("food_fts is rebuilt, not exported", "food_fts" in manifest.rows)

        // Change everything: rows deleted, added and edited, settings changed.
        val today = app.days.today()
        app.body.deleteWeighIn(app.db.bodyDao().weighIns().first().id)
        app.foodLog.quickAdd(today, "dinner", "Later", 900.0, 10.0, null, null, Origin.ORDERED)
        app.db.openHelper.writableDatabase.execSQL("UPDATE food SET name = 'Renamed' WHERE name LIKE 'Paneer%'")
        app.personas.setWeightSource(WeightSource.GYM_SCALE)
        app.foods.setHouseholdMeasure("glass", null)
        assertNotEquals(tables to settings, dump())

        val prepared = app.backup.prepare(ByteArrayInputStream(backup))
        assertEquals(manifest, prepared.manifest)
        app.backup.restore(prepared)
        assertEquals(tables, dump().first)
        assertEquals(settings, dump().second)

        // The rebuilt full-text index finds the restored names.
        assertTrue(app.foods.search("paneer").isNotEmpty())
        assertTrue(app.foods.search("renamed").isEmpty())
        assertEquals(WeightSource.HOME_SCALE, app.prefs.currentPersona().weightSource)
    }

    @Test
    fun `restore keeps a safety copy of what it replaced, which restores in turn`() = runBlocking {
        fill()
        val backup = export()
        assertNull(app.backup.safetyCopyDate())

        app.body.logWaist(89.0, System.currentTimeMillis())
        val changed = dump()
        app.backup.restore(app.backup.prepare(ByteArrayInputStream(backup)))
        assertNotNull(app.backup.safetyCopyDate())
        assertNotEquals(changed, dump())

        app.backup.restore(app.backup.prepareSafetyCopy())
        assertEquals(changed, dump())
    }

    @Test
    fun `an older backup is upgraded by the app's migrations`() = runBlocking {
        // A database as version 5 left it, with a food entry and a target, exported in the backup format.
        val file = File(tmp.root, "v5.db")
        val data = Schemas.createAt(5, file).use { db ->
            db.execSQL(
                "INSERT INTO nutrition_target (effective_from, goal, kcal, protein_g, fat_g, carbs_g, tdee_estimate, bmr_kcal, reason, created_at) " +
                    "VALUES ('2026-10-17', 'RECOMP', 2090, 125, 58, 267, 2611, 1899, 'INITIAL', 1)",
            )
            db.execSQL(
                "INSERT INTO food (id, name, source, source_key, diet, kcal_100g, protein_100g, carbs_100g, fat_100g, fiber_100g, use_count, archived, created_at) " +
                    "VALUES (1, 'Test dal', 'CUSTOM', NULL, 'VEGAN', 120.5, 7.25, 15, 3, 4, 2, 0, 1)",
            )
            db.execSQL(
                "INSERT INTO food_log (log_date, meal_slot, food_id, name, quantity, unit_label, grams, kcal, protein_g, origin, source, logged_at) " +
                    "VALUES ('2026-10-18', 'lunch', 1, 'Test dal', 1, 'katori', 150, 180.75, 10.875, 'HOME', 'MANUAL', 1)",
            )
            DatabaseSnapshot.read { db.rawQuery(it, null) }
        }
        val manifest = BackupManifest(BackupManager.FORMAT, 1, 5, 1, "test", data.tables.mapValues { it.value.rows.size })
        val old = zip(
            BackupManager.MANIFEST to json.encodeToString(manifest),
            BackupManager.DATABASE to json.encodeToString(data),
            BackupManager.SETTINGS to "{}",
        )

        app.backup.restore(app.backup.prepare(ByteArrayInputStream(old)))
        assertEquals(2090, app.nutrition.targetOn(LocalDate.of(2026, 10, 18))!!.kcal)
        val entry = app.foodLog.day(LocalDate.of(2026, 10, 18)).single()
        assertEquals(180.75, entry.kcal, 0.0)
        assertEquals(10.875, entry.proteinG, 0.0)
        assertEquals(listOf("Test dal"), app.foods.search("dal").map { it.food.name }.filter { it == "Test dal" })
        // Tables added since version 5 start empty.
        assertEquals(emptyList<Any>(), app.db.bodyDao().weighIns())
    }

    @Test
    fun `a backup from a newer version, or one that isn't a backup, is refused before anything changes`() = runBlocking {
        fill()
        val before = dump()
        val good = entries(export())
        val manifest = json.decodeFromString<BackupManifest>(good.getValue(BackupManager.MANIFEST))

        val newer = zip(BackupManager.MANIFEST to json.encodeToString(manifest.copy(schemaVersion = manifest.schemaVersion + 1)), BackupManager.DATABASE to good.getValue(BackupManager.DATABASE))
        assertTrue(refused(newer).contains("newer version"))
        assertEquals(BackupManager.NOT_A_BACKUP, refused("not a zip".toByteArray()))
        assertEquals(BackupManager.NOT_A_BACKUP, refused(zip("notes.txt" to "hello")))
        assertEquals(BackupManager.NOT_A_BACKUP, refused(zip(BackupManager.MANIFEST to json.encodeToString(manifest.copy(format = "other")))))
        assertTrue(refused(zip(BackupManager.MANIFEST to good.getValue(BackupManager.MANIFEST))).startsWith("This backup is damaged"))
        assertTrue(refused(zip(BackupManager.MANIFEST to good.getValue(BackupManager.MANIFEST), BackupManager.DATABASE to "{broken")).startsWith("This backup is damaged"))

        // A schema that does more than create things never runs.
        val data = json.decodeFromString<DatabaseData>(good.getValue(BackupManager.DATABASE))
        val attach = data.copy(schema = data.schema + SchemaItem("table", "x", "x", "ATTACH DATABASE 'elsewhere.db' AS x"))
        assertTrue(refused(zip(BackupManager.MANIFEST to good.getValue(BackupManager.MANIFEST), BackupManager.DATABASE to json.encodeToString(attach))).startsWith("This backup is damaged"))

        // A table that doesn't match its version fails Room's validation in the scratch database.
        val renamed = data.copy(schema = data.schema.map { if (it.name == "measurement") it.copy(sql = it.sql.replace("`value` REAL", "`amount` REAL")) else it })
        val broken = renamed.copy(tables = renamed.tables + ("measurement" to renamed.tables.getValue("measurement").let { t -> t.copy(columns = t.columns.map { c -> if (c == "value") "amount" else c }) }))
        assertTrue(refused(zip(BackupManager.MANIFEST to good.getValue(BackupManager.MANIFEST), BackupManager.DATABASE to json.encodeToString(broken))).startsWith("This backup couldn't be read"))

        assertEquals(before, dump())
    }

    @Test
    fun `import waits for a workout in progress`() = runBlocking {
        fill()
        val backup = export()
        val routine = app.training.observeNextRoutine().first()!!
        app.workouts.startRoutine(routine.id)
        val prepared = app.backup.prepare(ByteArrayInputStream(backup))
        try {
            app.backup.restore(prepared)
            fail("Imported over an active workout")
        } catch (e: BackupException) {
            assertTrue(e.message!!.startsWith("Finish or discard"))
        }
        app.backup.discard(prepared)
    }

    @Test
    fun `a secret in settings is never exported, and survives an import`() = runBlocking {
        fill()
        val planted = "planted-value-4821"
        app.store.edit { it[stringPreferencesKey("ai_api_key")] = planted }
        val backup = export()
        assertFalse(entries(backup).values.any { planted in it })
        assertFalse(entries(backup).getValue(BackupManager.SETTINGS).contains("ai_api_key"))
        // Every other setting is there.
        assertTrue(entries(backup).getValue(BackupManager.SETTINGS).contains("\"persona\""))

        app.backup.restore(app.backup.prepare(ByteArrayInputStream(backup)))
        assertEquals(planted, app.store.data.first()[stringPreferencesKey("ai_api_key")])
    }
}
