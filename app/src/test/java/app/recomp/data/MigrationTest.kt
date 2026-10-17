package app.recomp.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.recomp.data.db.AppDatabase
import app.recomp.domain.model.Equipment
import java.io.File
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
            assertEquals(1, room.workoutDao().exposureRows(3, 10).size)
            assertEquals(emptyList<Any>(), room.workoutDao().exerciseRecords(3))
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
