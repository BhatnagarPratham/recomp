package app.recomp.data

import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.recomp.data.backup.BackupException
import app.recomp.data.backup.BackupManager
import app.recomp.data.backup.BackupManifest
import app.recomp.data.backup.DatabaseData
import app.recomp.data.backup.DatabaseSnapshot
import app.recomp.data.backup.PendingOutcome
import app.recomp.data.backup.PendingRestore
import app.recomp.data.backup.SchemaItem
import app.recomp.data.backup.SettingValue
import app.recomp.data.db.AiRequestLogEntity
import app.recomp.data.db.MealDraftEntity
import app.recomp.data.db.readers
import app.recomp.data.repo.ExtraDraft
import app.recomp.data.repo.OnboardingResult
import app.recomp.data.repo.ScanInput
import app.recomp.data.repo.SegmentalLean
import app.recomp.data.repo.WorkoutRepository.ExtraEntry
import app.recomp.domain.ai.AiKind
import app.recomp.domain.ai.AiStatus
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
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

        // AI (M3c): a draft, a logged request and the AI settings.
        app.prefs.setAiEnabled(true)
        app.prefs.setAiMonthlyCap(5.0)
        app.prefs.setAiNoticeSeen()
        app.db.aiDao().insertDraft(MealDraftEntity(createdAt = now, logDate = today, mealSlot = "dinner", text = "paneer wrap", origin = Origin.ORDERED, reason = AiStatus.TIMEOUT))
        app.db.aiDao().insertRequest(
            AiRequestLogEntity(
                createdAt = now, kind = AiKind.MEAL_TEXT, model = "claude-opus-5-5", inputTokens = 2_000, outputTokens = 900, cacheReadTokens = 0,
                cacheWriteTokens = 1_500, costUsd = 0.0261, status = AiStatus.OK, latencyMs = 4_200, responseJson = "{\"items\":[]}",
            ),
        )

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
    fun `every table is read back through Room before an import`() = runBlocking {
        val tables = DatabaseSnapshot.dataTables(DatabaseSnapshot.schema(app.db.openHelper.writableDatabase::query)).toSet()
        assertEquals(tables, app.db.backupCheckDao().readers().keys)
    }

    @Test
    fun `a backup with a broken link, an unknown value or unreadable settings is refused`() = runBlocking {
        fill()
        val before = dump()
        val good = entries(export())
        val manifest = good.getValue(BackupManager.MANIFEST)
        val data = json.decodeFromString<DatabaseData>(good.getValue(BackupManager.DATABASE))
        fun withRows(table: String, edit: (List<String>, List<JsonElement>) -> List<JsonElement>): String {
            val t = data.tables.getValue(table)
            return json.encodeToString(data.copy(tables = data.tables + (table to t.copy(rows = t.rows.map { edit(t.columns, it) }))))
        }
        fun set(column: String, value: JsonElement) = { columns: List<String>, row: List<JsonElement> -> row.toMutableList().also { it[columns.indexOf(column)] = value } }

        // A scan weigh-in whose scan isn't in the file.
        val orphan = withRows("body_weight", set("scan_id", JsonPrimitive(999L)))
        assertTrue(refused(zip(BackupManager.MANIFEST to manifest, BackupManager.DATABASE to orphan, BackupManager.SETTINGS to good.getValue(BackupManager.SETTINGS))).contains("point at rows"))
        // A source this version doesn't know, as a newer build might write.
        val unknown = withRows("body_weight", set("source", JsonPrimitive("SMART_SCALE")))
        assertTrue(refused(zip(BackupManager.MANIFEST to manifest, BackupManager.DATABASE to unknown, BackupManager.SETTINGS to good.getValue(BackupManager.SETTINGS))).contains("can't read in body_weight"))
        // Settings out of range or of the wrong kind.
        val settings = json.decodeFromString<Map<String, SettingValue>>(good.getValue(BackupManager.SETTINGS))
        listOf(
            "day_start_minutes" to SettingValue("int", JsonPrimitive(99_999)),
            "scan_slot" to SettingValue("string", JsonPrimitive("{\"day\":9,\"minutes\":600}")),
            "keep_screen_on" to SettingValue("string", JsonPrimitive("yes")),
            // Decodes, but can't become a plan: a weekday 9, and a goal that loses at a negative rate.
            "persona" to SettingValue("string", JsonPrimitive("""{"split":"PPL","extras":[{"routineId":3,"days":[9]}]}""")),
            "persona" to SettingValue("string", JsonPrimitive("""{"split":"PPL","goal":{"goal":"FAT_LOSS","lossRatePctPerWeek":-0.5,"proteinGPerKg":2.0,"startedOn":"2026-10-01"}}""")),
        ).forEach { (key, bad) ->
            val message = refused(zip(BackupManager.MANIFEST to manifest, BackupManager.DATABASE to good.getValue(BackupManager.DATABASE), BackupManager.SETTINGS to json.encodeToString(settings + (key to bad))))
            assertTrue(message, message.contains(key))
        }
        assertEquals(before, dump())
        // The app still writes normally afterwards.
        assertTrue(app.body.logWeighIn(80_000, WeightSource.HOME_SCALE, System.currentTimeMillis()) > 0)
    }

    @Test
    fun `a backup missing a table's rows, or whose summary disagrees with its rows, is refused`() = runBlocking {
        fill()
        val before = dump()
        val good = entries(export())
        val manifest = json.decodeFromString<BackupManifest>(good.getValue(BackupManager.MANIFEST))
        val data = json.decodeFromString<DatabaseData>(good.getValue(BackupManager.DATABASE))
        fun file(m: BackupManifest, d: DatabaseData) =
            zip(BackupManager.MANIFEST to json.encodeToString(m), BackupManager.DATABASE to json.encodeToString(d), BackupManager.SETTINGS to good.getValue(BackupManager.SETTINGS))

        // The schema defines body_weight, but its rows are gone: importing would empty it without a word.
        val noWeighIns = data.copy(tables = data.tables - "body_weight")
        assertTrue(refused(file(manifest.copy(rows = manifest.rows - "body_weight"), noWeighIns)).contains("no rows section for body_weight"))
        // The confirmation would show counts the file doesn't hold.
        assertTrue(refused(file(manifest.copy(rows = manifest.rows + ("workout_session" to 999)), data)).contains("doesn't match"))
        assertEquals(before, dump())
    }

    @Test
    fun `a failure inside the restore transaction leaves everything untouched`() = runBlocking {
        fill()
        val backup = export()
        app.body.logWeighIn(80_500, WeightSource.HOME_SCALE, System.currentTimeMillis())
        val before = dump()
        val prepared = app.backup.prepare(ByteArrayInputStream(backup))
        // Damage the checked scratch database, so the last foreign-key check inside the transaction fails.
        SQLiteDatabase.openDatabase(prepared.scratch.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("INSERT INTO body_weight (log_date, measured_at, weight_g, source, scan_id) VALUES ('2026-10-18', 1, 80000, 'SCAN', 999)")
        }
        val message = try {
            app.backup.restore(prepared)
            fail("Restored a broken database")
            ""
        } catch (e: BackupException) {
            e.message!!
        }
        assertTrue(message, message.contains("point at rows"))
        assertEquals(before, dump())
        assertFalse(app.backup.pendingFile.exists())
        assertNull(app.backup.finishPendingRestore())
        // The connection isn't left inside a transaction.
        assertTrue(app.body.logWeighIn(80_000, WeightSource.HOME_SCALE, System.currentTimeMillis()) > 0)
    }

    @Test
    fun `settings that fail after the database is replaced are finished at the next launch`() = runBlocking {
        fill()
        val (tables, settings) = dump()
        val backup = export()
        app.body.logWaist(88.0, System.currentTimeMillis())
        app.personas.setWeightSource(WeightSource.GYM_SCALE)

        app.store.failWrites = true
        val message = try {
            app.backup.restore(app.backup.prepare(ByteArrayInputStream(backup)))
            fail("The settings write didn't fail")
            ""
        } catch (e: BackupException) {
            e.message!!
        }
        assertTrue(message, message.startsWith("Your data was replaced, but the settings couldn't be"))
        // The database is the backup's; the settings aren't yet, and the marker says so.
        assertEquals(tables, dump().first)
        assertEquals(WeightSource.GYM_SCALE, app.prefs.currentPersona().weightSource)
        assertTrue(app.backup.pendingFile.exists())

        // Next launch, still failing: kept for later.
        assertEquals(PendingOutcome.SETTINGS_FAILED, app.backup.finishPendingRestore())
        assertTrue(app.backup.pendingFile.exists())

        app.store.failWrites = false
        app.restart()
        assertEquals(PendingOutcome.FINISHED, app.backup.finishPendingRestore())
        assertEquals(tables to settings, dump())
        assertFalse(app.backup.pendingFile.exists())
        assertNull(app.backup.finishPendingRestore())
    }

    @Test
    fun `a marker from an import that never committed is dropped at launch`() = runBlocking {
        fill()
        val before = dump()
        app.backup.pendingFile.parentFile!!.mkdirs()
        app.backup.pendingFile.writeText(json.encodeToString(PendingRestore(token = 424_242, settings = emptyMap(), exportedAt = 1)))
        assertEquals(PendingOutcome.NOT_APPLIED, app.backup.finishPendingRestore())
        assertEquals(before, dump())
        assertFalse(app.backup.pendingFile.exists())
    }

    @Test
    fun `a file over the size budget is refused while it's read, before it's decoded`() = runBlocking {
        fill()
        val before = dump()
        val good = entries(export())
        app.backup.maxBytes = 64 * 1024
        // Squeezes into a small zip, but would unpack to more than the budget.
        val padded = """{"schema":[],"tables":{},"padding":"${"x".repeat(200 * 1024)}"}"""
        assertEquals(BackupManager.TOO_BIG, refused(zip(BackupManager.MANIFEST to good.getValue(BackupManager.MANIFEST), BackupManager.DATABASE to padded)))
        assertEquals(before, dump())
        // A real backup fits easily.
        app.backup.maxBytes = 4L * 1024 * 1024
        app.backup.discard(app.backup.prepare(ByteArrayInputStream(export())))
    }

    @Test
    fun `a failed export still closes its file`() = runBlocking {
        var closed = false
        val out = object : ByteArrayOutputStream() {
            override fun close() {
                closed = true
                super.close()
            }
        }
        app.db.close()
        try {
            app.backup.export(out)
            fail("Exported from a closed database")
        } catch (e: Exception) {
            // Expected: nothing to read.
        }
        assertTrue(closed)
        app.restart()
    }

    @Test
    fun `the AI key never leaves its own store`() = runBlocking {
        fill()
        val planted = "planted-value-7310"
        app.keys.save(planted)
        val backup = export()
        assertFalse(entries(backup).values.any { planted in it })
        app.keys.clear()
        app.backup.restore(app.backup.prepare(ByteArrayInputStream(backup)))
        // Import never touches the key store either way.
        assertNull(app.keys.key())
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
