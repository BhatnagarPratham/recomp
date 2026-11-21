package app.recomp.data.backup

import android.database.Cursor
import app.recomp.data.db.AppDatabase
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.domain.export.Csv
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** What a CSV export held, for the message after it. */
data class CsvSummary(val sets: Int, val sessions: Int, val foods: Int, val weighIns: Int, val scans: Int)

/**
 * DA-03 (PRD §12.12): your data as spreadsheets, in one zip: `workouts.csv` (one row per set, and one row for an
 * activity with no sets), `food.csv` (one row per entry), `weigh-ins.csv` and `scans.csv`. Weights are in kg; times
 * are local ("2026-11-21 18:05") with dates as log dates (PR-03). It's for reading, not for importing back: the full
 * backup (DA-01) does that. It reads the tables directly, so it needs nothing from the repositories.
 */
@Singleton
class CsvExport @Inject constructor(private val db: AppDatabase, private val prefs: SettingsPrefs) {
    suspend fun export(out: OutputStream, zone: ZoneId = ZoneId.systemDefault()): CsvSummary = withContext(Dispatchers.IO) {
        val slots = prefs.mealSlots.first().associate { it.key to it.name }
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
        fun at(ms: Long?) = Csv.text(ms?.let { time.format(Instant.ofEpochMilli(it)) })
        val sql = db.openHelper.readableDatabase
        fun rows(query: String, row: (Cursor) -> List<Csv.Cell>): List<List<Csv.Cell>> =
            sql.query(query).use { c -> buildList { while (c.moveToNext()) add(row(c)) } }

        // Sets, and sessions with no set at all, merged back into one time order.
        fun session(c: Cursor) = Triple(c.long("started_at") ?: 0L, c.long("session_id") ?: 0L, c.long("row_order") ?: 0L)
        val workouts = (sql.query(SETS).use { c -> buildList { while (c.moveToNext()) add(session(c) to workoutRow(c, at = ::at)) } } +
            sql.query(NO_SETS).use { c -> buildList { while (c.moveToNext()) add(session(c) to workoutRow(c, at = ::at)) } })
            .sortedWith(compareBy({ it.first.first }, { it.first.second }, { it.first.third }))
            .map { it.second }
        val food = rows(FOOD) { c ->
            listOf(
                c.text("log_date"), Csv.text(c.string("meal_slot")?.let { slots[it] ?: it }), c.text("name"), Csv.number(c.double("quantity")),
                c.text("unit_label"), Csv.number(c.double("grams"), 1), Csv.number(c.double("kcal"), 0), Csv.number(c.double("protein_g"), 1),
                Csv.number(c.double("carbs_g"), 1), Csv.number(c.double("fat_g"), 1), Csv.number(c.double("fiber_g"), 1),
                c.text("origin"), c.text("source"), at(c.long("logged_at")),
            )
        }
        val weighIns = rows(WEIGH_INS) { c -> listOf(c.text("log_date"), at(c.long("measured_at")), kg(c.long("weight_g")), c.text("source")) }
        val scans = rows(SCANS) { c ->
            listOf(
                c.text("log_date"), at(c.long("measured_at")), c.text("source"), kg(c.long("weight_g")), kg(c.long("skeletal_muscle_g")),
                kg(c.long("body_fat_g")), Csv.number(c.double("body_fat_pct"), 1), Csv.number(c.double("visceral_fat_level"), 1),
                Csv.number(c.long("bmr_kcal")), Csv.number(c.double("ecw_tbw_ratio"), 3), Csv.number(c.long("inbody_score")),
                Csv.number(c.long("mins_since_meal")), Csv.yesNo(c.long("workout_before_scan")?.let { it != 0L }),
                Csv.yesNo(c.long("protocol_ok")?.let { it != 0L }), c.text("notes"),
            )
        }

        ZipOutputStream(out).use { zip ->
            fun entry(name: String, header: List<String>, rows: List<List<Csv.Cell>>) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(Csv.file(header, rows.asSequence()).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry(WORKOUTS_FILE, WORKOUT_COLUMNS, workouts)
            entry(FOOD_FILE, FOOD_COLUMNS, food)
            entry(WEIGH_INS_FILE, WEIGH_IN_COLUMNS, weighIns)
            entry(SCANS_FILE, SCAN_COLUMNS, scans)
        }
        CsvSummary(
            sets = workouts.count { it[8] != Csv.Cell.Empty },
            sessions = sql.query("SELECT COUNT(*) FROM workout_session WHERE status = 'COMPLETED'").use { it.moveToFirst(); it.getInt(0) },
            foods = food.size,
            weighIns = weighIns.size,
            scans = scans.size,
        )
    }

    private fun workoutRow(c: Cursor, at: (Long?) -> Csv.Cell): List<Csv.Cell> = listOf(
        c.text("log_date"), at(c.long("started_at")), at(c.long("ended_at")), c.text("kind"), c.text("routine"), c.text("notes"),
        c.text("exercise"), c.text("load_type"), c.int("set_number"), c.text("set_type"), kg(c.long("load_g")), c.int("reps"),
        c.int("rir"), at(c.long("completed_at")), c.text("style"), c.int("duration_min"),
    )

    private fun kg(grams: Long?): Csv.Cell = Csv.number(grams?.let { it / 1000.0 }, 2)

    private fun Cursor.index(column: String) = getColumnIndexOrThrow(column)
    private fun Cursor.string(column: String): String? = index(column).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.text(column: String): Csv.Cell = Csv.text(string(column))
    private fun Cursor.long(column: String): Long? = index(column).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.int(column: String): Csv.Cell = Csv.number(long(column))
    private fun Cursor.double(column: String): Double? = index(column).let { if (isNull(it)) null else getDouble(it) }

    companion object {
        const val WORKOUTS_FILE = "workouts.csv"
        const val FOOD_FILE = "food.csv"
        const val WEIGH_INS_FILE = "weigh-ins.csv"
        const val SCANS_FILE = "scans.csv"

        /** `recomp-csv-2026-11-21.zip`. */
        fun fileName(date: java.time.LocalDate) = "recomp-csv-$date.zip"

        /**
         * `load_kg` is what the load column holds for the exercise's load type: the weight for WEIGHTED, the added weight
         * for BODYWEIGHT_PLUS, the assistance for ASSISTED, and empty for BODYWEIGHT.
         */
        val WORKOUT_COLUMNS = listOf(
            "date", "started", "ended", "kind", "routine", "session_notes", "exercise", "load_type", "set", "set_type",
            "load_kg", "reps", "rir", "set_done", "activity", "duration_min",
        )
        val FOOD_COLUMNS = listOf("date", "meal", "food", "quantity", "unit", "grams", "kcal", "protein_g", "carbs_g", "fat_g", "fiber_g", "origin", "how_logged", "logged")
        val WEIGH_IN_COLUMNS = listOf("date", "measured", "weight_kg", "source")
        val SCAN_COLUMNS = listOf(
            "date", "measured", "source", "weight_kg", "skeletal_muscle_kg", "body_fat_kg", "body_fat_pct", "visceral_fat_level",
            "bmr_kcal", "ecw_tbw_ratio", "score", "mins_since_meal", "workout_before", "protocol_ok", "notes",
        )

        /** Completed sessions only (a discarded or running one isn't a workout yet): each ticked set, in exercise order. */
        private const val SETS = """
            SELECT s.id AS session_id, se.position * 100000 + st.set_index AS row_order,
                   s.log_date, s.started_at, s.ended_at, s.kind, r.name AS routine, s.notes,
                   e.name AS exercise, e.load_type, st.set_index + 1 AS set_number, st.set_type, st.load_g, st.reps, st.rir, st.completed_at,
                   y.style, y.duration_min
            FROM workout_session s
            JOIN session_exercise se ON se.session_id = s.id
            JOIN exercise e ON e.id = se.exercise_id
            JOIN set_entry st ON st.session_exercise_id = se.id AND st.completed_at IS NOT NULL
            LEFT JOIN routine r ON r.id = s.routine_id
            LEFT JOIN yoga_detail y ON y.session_id = s.id
            WHERE s.status = 'COMPLETED'
        """

        /** A completed session with no ticked set (a yoga class, another app's walk): one row, the set columns empty. */
        private const val NO_SETS = """
            SELECT s.id AS session_id, 0 AS row_order, s.log_date, s.started_at, s.ended_at, s.kind, r.name AS routine, s.notes,
                   NULL AS exercise, NULL AS load_type, NULL AS set_number, NULL AS set_type, NULL AS load_g, NULL AS reps, NULL AS rir,
                   NULL AS completed_at, y.style, y.duration_min
            FROM workout_session s
            LEFT JOIN routine r ON r.id = s.routine_id
            LEFT JOIN yoga_detail y ON y.session_id = s.id
            WHERE s.status = 'COMPLETED' AND NOT EXISTS (
                SELECT 1 FROM session_exercise se JOIN set_entry st ON st.session_exercise_id = se.id
                WHERE se.session_id = s.id AND st.completed_at IS NOT NULL
            )
        """

        private const val FOOD = "SELECT * FROM food_log ORDER BY log_date, logged_at, id"
        private const val WEIGH_INS = "SELECT * FROM body_weight ORDER BY measured_at, id"
        private const val SCANS = "SELECT * FROM body_composition ORDER BY measured_at, id"
    }
}
