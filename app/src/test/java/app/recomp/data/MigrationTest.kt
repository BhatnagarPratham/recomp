package app.recomp.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.recomp.data.db.AiRequestLogEntity
import app.recomp.data.db.AppDatabase
import app.recomp.data.db.FoodEntity
import app.recomp.data.db.FoodLogEntity
import app.recomp.data.db.FoodUnitEntity
import app.recomp.data.db.MealDraftEntity
import app.recomp.data.db.NutritionTargetEntity
import app.recomp.data.db.TargetReason
import app.recomp.domain.ai.AiKind
import app.recomp.domain.ai.AiStatus
import app.recomp.domain.food.FoodDiet
import app.recomp.domain.food.FoodSource
import app.recomp.domain.food.LogSource
import app.recomp.domain.food.Origin
import app.recomp.domain.model.Equipment
import app.recomp.domain.nutrition.Goal
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every schema change ships a tested Migration (TRD §4). Each test builds the old database from its
 * exported schema in app/schemas, then lets Room open it with the real migration list. Room checks
 * every table, column, index and foreign key after migrating and refuses to open on any mismatch, and
 * destructive fallback is never enabled, so a passing test means the migration is complete.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val schemaDir = File("schemas/app.recomp.data.db.AppDatabase")

    private fun schema(version: Int) =
        Json.parseToJsonElement(File(schemaDir, "$version.json").readText()).jsonObject.getValue("database").jsonObject

    /** Creates an empty database exactly as Room would have at [version], from the exported schema. */
    private fun createAt(version: Int, file: File): SQLiteDatabase {
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val s = schema(version)
        s.getValue("entities").jsonArray.forEach { entity ->
            val e = entity.jsonObject
            val table = e.getValue("tableName").jsonPrimitive.content
            db.execSQL(e.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            e["indices"]?.jsonArray?.forEach { db.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            // FTS tables keep their content table in sync with triggers (v5's food_fts).
            e["contentSyncTriggers"]?.jsonArray?.forEach { db.execSQL(it.jsonPrimitive.content) }
        }
        s.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
        db.version = version
        return db
    }

    private fun openWithRoom(file: File) =
        Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, file.absolutePath)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    @Test
    fun `1 to 2 keeps exercises and adds the training tables`() {
        val file = File(tmp.root, "v1.db")
        createAt(1, file).use { db ->
            db.execSQL(
                "INSERT INTO exercise (id, name, primary_muscle, secondary_muscles, equipment, mechanic, movement_pattern, load_type, " +
                    "increment_g, default_rep_min, default_rep_max, default_rest_sec, is_unilateral, setup_notes, is_custom, archived) " +
                    "VALUES (7, 'My press', 'chest', '[\"triceps\"]', 'MACHINE', 'COMPOUND', 'horizontal_push', 'WEIGHTED', " +
                    "5000, 8, 12, 120, 0, 'seat 4', 1, 0)",
            )
        }

        val room = openWithRoom(file)
        runBlocking {
            val exercise = room.exerciseDao().get(7)!!
            assertEquals("My press", exercise.name)
            assertEquals(Equipment.MACHINE, exercise.equipment)
            assertEquals(listOf("triceps"), exercise.secondaryMuscles)
            assertEquals("seat 4", exercise.setupNotes)
            assertNull(exercise.seedKey)
        }
        room.openHelper.readableDatabase.query("SELECT identity_hash FROM room_master_table").use { c ->
            c.moveToFirst()
            // The checked-in schema for the current version matches the compiled one.
            val current = schemaDir.listFiles()!!.maxOf { it.nameWithoutExtension.toInt() }
            assertEquals(schema(current).getValue("identityHash").jsonPrimitive.content, c.getString(0))
        }
        room.close()
    }

    @Test
    fun `2 to 3 keeps workouts and adds PRs and stall resets`() {
        val file = File(tmp.root, "v2.db")
        createAt(2, file).use { db ->
            db.execSQL(
                "INSERT INTO exercise (id, name, primary_muscle, secondary_muscles, equipment, mechanic, movement_pattern, load_type, " +
                    "increment_g, default_rep_min, default_rep_max, default_rest_sec, is_unilateral, is_custom, archived, seed_key) " +
                    "VALUES (3, 'Bench press', 'chest', '[]', 'BARBELL', 'COMPOUND', 'horizontal_push', 'WEIGHTED', 2500, 8, 12, 150, 0, 0, 0, 'bench_press')",
            )
            db.execSQL("INSERT INTO workout_session (id, kind, status, started_at, ended_at, log_date) VALUES (1, 'STRENGTH', 'COMPLETED', 0, 1, '2026-10-17')")
            db.execSQL("INSERT INTO session_exercise (id, session_id, exercise_id, position, target_sets, rep_min, rep_max, rest_sec, user_overrode) VALUES (1, 1, 3, 0, 3, 8, 12, 150, 0)")
            db.execSQL("INSERT INTO set_entry (session_exercise_id, set_index, set_type, load_g, reps, completed_at) VALUES (1, 0, 'WORKING', 40000, 12, 1)")
        }
        val room = openWithRoom(file)
        runBlocking {
            val bench = room.exerciseDao().get(3)!!
            assertNull(bench.stallResetAt)
            assertEquals(false, bench.stallResetKeepsBaseline)
            assertEquals(1, room.workoutDao().exposureSets(3, 10).size)
            assertEquals(emptyList<Any>(), room.workoutDao().exerciseRecords(3))
        }
        room.close()
    }

    @Test
    fun `3 to 4 keeps progression data and adds versioned targets`() {
        val file = File(tmp.root, "v3.db")
        createAt(3, file).use { db ->
            db.execSQL(
                "INSERT INTO exercise (id, name, primary_muscle, secondary_muscles, equipment, mechanic, movement_pattern, load_type, " +
                    "increment_g, default_rep_min, default_rep_max, default_rest_sec, is_unilateral, is_custom, archived, seed_key, " +
                    "stall_reset_at, stall_reset_keeps_baseline) " +
                    "VALUES (3, 'Bench press', 'chest', '[]', 'BARBELL', 'COMPOUND', 'horizontal_push', 'WEIGHTED', 2500, 8, 12, 150, 0, 0, 0, 'bench_press', 5, 1)",
            )
            db.execSQL("INSERT INTO workout_session (id, kind, status, started_at, ended_at, log_date) VALUES (1, 'STRENGTH', 'COMPLETED', 0, 1, '2026-10-17')")
            db.execSQL("INSERT INTO session_exercise (id, session_id, exercise_id, position, target_sets, rep_min, rep_max, rest_sec, user_overrode) VALUES (1, 1, 3, 0, 3, 8, 12, 150, 0)")
            db.execSQL("INSERT INTO set_entry (id, session_exercise_id, set_index, set_type, load_g, reps, completed_at) VALUES (1, 1, 0, 'WORKING', 40000, 12, 1)")
            db.execSQL("INSERT INTO personal_record (exercise_id, session_id, type, value, load_g, set_entry_id, achieved_at) VALUES (3, 1, 'MAX_LOAD', 40000, 40000, 1, 1)")
        }
        val room = openWithRoom(file)
        runBlocking {
            val bench = room.exerciseDao().get(3)!!
            assertEquals(5L, bench.stallResetAt)
            assertEquals(true, bench.stallResetKeepsBaseline)
            assertEquals(1, room.workoutDao().exerciseRecords(3).size)

            val dao = room.nutritionDao()
            assertNull(dao.targetOn(LocalDate.of(2026, 10, 17)))
            fun row(day: Int, kcal: Int) = NutritionTargetEntity(
                effectiveFrom = LocalDate.of(2026, 10, day), goal = Goal.RECOMP, kcal = kcal, proteinG = 125, fatG = 58, carbsG = 267,
                tdeeEstimate = 2611, bmrKcal = 1899, reason = TargetReason.INITIAL, createdAt = 1,
            )
            dao.upsert(row(17, 2090))
            // Same day: replaced, not added (one version per day).
            dao.upsert(row(17, 2050))
            dao.upsert(row(20, 2000))
            assertEquals(listOf(2050, 2000), dao.all().map { it.kcal })
            assertEquals(2050, dao.targetOn(LocalDate.of(2026, 10, 19))!!.kcal)
            assertEquals(2000, dao.targetOn(LocalDate.of(2026, 11, 1))!!.kcal)
            assertNull(dao.targetOn(LocalDate.of(2026, 10, 16)))
        }
        room.close()
    }

    @Test
    fun `4 to 5 keeps targets and adds foods with a working full-text index`() {
        val file = File(tmp.root, "v4.db")
        createAt(4, file).use { db ->
            db.execSQL("INSERT INTO workout_session (id, kind, status, started_at, ended_at, log_date) VALUES (1, 'STRENGTH', 'COMPLETED', 0, 1, '2026-10-17')")
            db.execSQL(
                "INSERT INTO nutrition_target (effective_from, goal, kcal, protein_g, fat_g, carbs_g, tdee_estimate, bmr_kcal, reason, created_at) " +
                    "VALUES ('2026-10-17', 'RECOMP', 2090, 125, 58, 267, 2611, 1899, 'INITIAL', 1)",
            )
        }
        val room = openWithRoom(file)
        runBlocking {
            assertEquals(2090, room.nutritionDao().targetOn(LocalDate.of(2026, 10, 18))!!.kcal)
            assertEquals(1, room.workoutDao().session(1)!!.id)

            val foods = room.foodDao()
            fun food(name: String, key: String) = FoodEntity(
                name = name, source = FoodSource.SEED, sourceKey = key, diet = FoodDiet.VEGETARIAN,
                kcal100g = 280.0, protein100g = 18.0, carbs100g = 2.0, fat100g = 22.0, fiber100g = 0.0, createdAt = 1,
            )
            val veg = listOf(FoodDiet.VEGAN.name, FoodDiet.VEGETARIAN.name)
            val paneer = foods.insert(food("Paneer", "seed:paneer"))
            foods.insert(food("Paneer tikka", "seed:tikka"))
            assertEquals(listOf("Paneer", "Paneer tikka"), foods.search("pan*", "pan", veg, "INDIAN", 10).map { it.name })

            // The migration's triggers keep the index in sync with edits.
            foods.update(foods.get(paneer)!!.copy(name = "Cottage cheese"))
            assertEquals(listOf("Paneer tikka"), foods.search("pan*", "pan", veg, "INDIAN", 10).map { it.name })
            assertEquals(listOf("Cottage cheese"), foods.search("cott*", "cott", veg, "INDIAN", 10).map { it.name })

            foods.upsertUnits(listOf(FoodUnitEntity(foodId = paneer, label = "piece", grams = 25.0)))
            assertEquals(25.0, foods.units(paneer).single().grams, 0.0)
            val log = room.foodLogDao()
            log.insert(
                FoodLogEntity(
                    logDate = LocalDate.of(2026, 10, 18), mealSlot = "lunch", foodId = paneer, name = "Paneer", quantity = 2.0, unitLabel = "piece",
                    grams = 50.0, kcal = 140.0, proteinG = 9.0, carbsG = 1.0, fatG = 11.0, fiberG = 0.0, origin = Origin.HOME,
                    source = LogSource.MANUAL, loggedAt = 1,
                ),
            )
            assertEquals(140.0, log.day(LocalDate.of(2026, 10, 18)).single().kcal, 0.0)
        }
        room.close()
    }

    @Test
    fun `5 to 6 keeps food logs and saved meals and adds drafts and the AI request log`() {
        val file = File(tmp.root, "v5.db")
        createAt(5, file).use { db ->
            db.execSQL(
                "INSERT INTO food (id, name, source, diet, kcal_100g, protein_100g, carbs_100g, fat_100g, fiber_100g, use_count, archived, created_at) " +
                    "VALUES (1, 'Rajma', 'CUSTOM', 'VEGAN', 140, 8, 20, 3, 6, 2, 0, 1)",
            )
            db.execSQL(
                "INSERT INTO food_log (log_date, meal_slot, food_id, name, quantity, unit_label, grams, kcal, protein_g, carbs_g, fat_g, fiber_g, origin, source, logged_at) " +
                    "VALUES ('2026-10-18', 'lunch', 1, 'Rajma', 1, 'katori', 150, 210, 12, 30, 4.5, 9, 'HOME', 'MANUAL', 5)",
            )
            db.execSQL("INSERT INTO saved_meal (id, name, default_slot, use_count, created_at) VALUES (1, 'Usual lunch', 'lunch', 1, 1)")
            db.execSQL(
                "INSERT INTO saved_meal_item (saved_meal_id, position, food_id, name, quantity, unit_label, grams, kcal, protein_g, origin) " +
                    "VALUES (1, 0, 1, 'Rajma', 1, 'katori', 150, 210, 12, 'HOME')",
            )
        }
        val room = openWithRoom(file)
        runBlocking {
            val day = LocalDate.of(2026, 10, 18)
            assertEquals(210.0, room.foodLogDao().day(day).single().kcal, 0.0)
            assertEquals("Rajma", room.savedMealDao().get(1)!!.items.single().name)
            assertEquals(listOf("Rajma"), room.foodDao().search("raj*", "raj", listOf("VEGAN"), "INDIAN", 10).map { it.name })

            val ai = room.aiDao()
            assertEquals(emptyList<MealDraftEntity>(), ai.observeDrafts(day).first())
            val draftId = ai.insertDraft(MealDraftEntity(createdAt = 1, logDate = day, mealSlot = "dinner", text = "paneer wrap", origin = Origin.ORDERED, reason = AiStatus.TIMEOUT))
            assertEquals(AiStatus.TIMEOUT, ai.draft(draftId)!!.reason)
            assertNull(ai.draft(draftId)!!.photoPath)

            ai.insertRequest(
                AiRequestLogEntity(
                    createdAt = 10, kind = AiKind.MEAL_TEXT, model = "claude-opus-5-5", inputTokens = 2_000, outputTokens = 1_000,
                    cacheReadTokens = 0, cacheWriteTokens = 0, costUsd = 0.028, status = AiStatus.OK, latencyMs = 4_000, responseJson = "{}",
                ),
            )
            assertEquals(0.028, ai.spendSince(0), 1e-12)
            assertEquals(0.0, ai.spendSince(11), 0.0)
            assertEquals(1, ai.observeSpend(0).first().requests)
        }
        room.close()
    }

    @Test
    fun `every exported schema version has a migration path to the current one`() {
        val versions = schemaDir.listFiles()!!.map { it.nameWithoutExtension.toInt() }.sorted()
        assertEquals((1..versions.last()).toList(), versions)
        versions.dropLast(1).forEach { version ->
            val file = File(tmp.root, "from-$version.db")
            createAt(version, file).close()
            openWithRoom(file).apply { openHelper.writableDatabase; close() }
        }
    }
}
