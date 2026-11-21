package app.recomp.data.repo

import android.database.Cursor
import app.recomp.data.db.AppDatabase
import app.recomp.data.health.HealthWriter
import app.recomp.data.prefs.DayBoundary
import app.recomp.data.prefs.DevicePrefs
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.domain.export.FoodRow
import app.recomp.domain.export.HealthWrites
import app.recomp.domain.export.OutKind
import app.recomp.domain.export.OutRecord
import app.recomp.domain.export.SessionRow
import app.recomp.domain.export.SetRow
import app.recomp.domain.export.WeighInRow
import app.recomp.domain.model.LoadType
import app.recomp.domain.model.SessionKind
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What one write sync did. */
sealed interface ShareResult {
    data object Off : ShareResult
    data object NotAllowed : ShareResult
    data class Done(val written: Int, val deleted: Int) : ShareResult
    data class Failed(val reason: String) : ShareResult
}

/**
 * DA-04 (PRD §12.12, TRD §10 M16): your workouts, one nutrition record per meal and your typed weigh-ins, written to
 * Health Connect while *Share with other apps* is on.
 *
 * It reads the tables, not the repositories' save paths: each sync builds the records of the last 30 days from what's
 * there now and writes what changed since the last one (device state keeps a hash per record), and deletes what's
 * gone. So an edit or delete made anywhere, an import included, is followed within the window; an older one waits for
 * *Resync everything*. Sessions read from other apps (M13) and scan weights are never written.
 */
@Singleton
class HealthWriteRepository @Inject constructor(
    private val db: AppDatabase,
    private val prefs: SettingsPrefs,
    private val device: DevicePrefs,
    private val days: DayBoundary,
    private val writer: HealthWriter,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    /** Writes what changed in the window. With [everything], deletes all Recomp's records there and writes every one again. */
    suspend fun sync(everything: Boolean = false): ShareResult = mutex.withLock {
        if (!prefs.healthShare.first()) return ShareResult.Off
        try {
            if (!writer.canWrite()) {
                device.setWriteError(NOT_ALLOWED)
                return ShareResult.NotAllowed
            }
            val now = clock.millis()
            val today = days.of(now)
            val windowStart = HealthWrites.windowStart(today)
            val from = if (everything) null else windowStart
            val current = records(from)
            if (everything) OutKind.entries.forEach { writer.deleteAll(it) }
            val plan = HealthWrites.plan(current, if (everything) emptyMap() else device.written(), from, everything)
            plan.deletes.groupBy({ it.first }, { it.second }).forEach { (kind, ids) -> writer.delete(kind, ids) }
            if (plan.upserts.isNotEmpty()) writer.upsert(plan.upserts, version = now)
            device.setWritten(plan.written.filterValues { !it.date.isBefore(windowStart) }, now)
            ShareResult.Done(plan.upserts.size, plan.deletes.size)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            runCatching { device.setWriteError(reason) }
            ShareResult.Failed(reason)
        }
    }

    /** Turning sharing on writes the last 30 days; turning it off stops writing and forgets what was written. */
    suspend fun setShare(on: Boolean) {
        prefs.setHealthShare(on)
        if (!on) device.clearWritten()
    }

    /** The records Recomp would write for log dates from [from] (all of them when null). */
    suspend fun records(from: LocalDate?): List<OutRecord> = withContext(Dispatchers.IO) {
        val since = from?.toString() ?: "0000-01-01"
        val sql = db.openHelper.readableDatabase
        fun <T> rows(query: String, args: Array<Any>, row: (Cursor) -> T): List<T> = sql.query(query, args).use { c -> buildList { while (c.moveToNext()) add(row(c)) } }

        val sets = rows(SETS, arrayOf(since)) { c ->
            c.getLong(0) to SetRow(c.getString(1), c.longOrNull(2), c.getString(3) == LoadType.ASSISTED.name, c.longOrNull(4)?.toInt())
        }.groupBy({ it.first }, { it.second })
        val sessions = rows(SESSIONS, arrayOf(since)) { c ->
            val id = c.getLong(0)
            val kind = SessionKind.entries.firstOrNull { it.name == c.getString(4) } ?: SessionKind.CARDIO
            SessionRow(
                id = id, logDate = LocalDate.parse(c.getString(1)), startedAt = c.getLong(2), endedAt = c.getLong(3), kind = kind,
                title = c.stringOrNull(6) ?: c.stringOrNull(5) ?: title(kind), importedFrom = c.stringOrNull(7), sets = sets[id].orEmpty(),
            )
        }
        val food = rows(FOOD, arrayOf(since)) { c ->
            FoodRow(LocalDate.parse(c.getString(0)), c.getString(1), c.getDouble(2), c.getDouble(3), c.doubleOrNull(4), c.doubleOrNull(5), c.doubleOrNull(6))
        }
        val weighIns = rows(WEIGH_INS, arrayOf(since)) { c ->
            WeighInRow(c.getLong(0), LocalDate.parse(c.getString(1)), c.getLong(2), c.getLong(3), fromScan = c.getString(4) == "SCAN" || !c.isNull(5))
        }
        val slots = HealthWrites.mealTimes(prefs.mealSlots.first().map { it.key to it.name })
        HealthWrites.workouts(sessions) + HealthWrites.meals(food, slots, prefs.currentDayStart(), clock.zone) + HealthWrites.weights(weighIns)
    }

    private fun Cursor.longOrNull(i: Int) = if (isNull(i)) null else getLong(i)
    private fun Cursor.doubleOrNull(i: Int) = if (isNull(i)) null else getDouble(i)
    private fun Cursor.stringOrNull(i: Int) = if (isNull(i)) null else getString(i)

    companion object {
        const val NOT_ALLOWED = "Health Connect isn't allowing Recomp to write. Turn sharing off and on again to give it permission."

        fun title(kind: SessionKind): String = when (kind) {
            SessionKind.STRENGTH -> "Strength workout"
            SessionKind.YOGA -> "Yoga"
            SessionKind.MOBILITY -> "Mobility"
            SessionKind.CARDIO -> "Cardio"
            SessionKind.SPORT -> "Sport"
        }

        /** Finished sessions only; a running or discarded one isn't a workout. Imported ones are read too, and left out by the rules. */
        private const val SESSIONS = """
            SELECT s.id, s.log_date, s.started_at, s.ended_at, s.kind, r.name, y.style, s.hc_record_id
            FROM workout_session s
            LEFT JOIN routine r ON r.id = s.routine_id
            LEFT JOIN yoga_detail y ON y.session_id = s.id
            WHERE s.status = 'COMPLETED' AND s.ended_at IS NOT NULL AND s.log_date >= ?
            ORDER BY s.started_at, s.id
        """

        private const val SETS = """
            SELECT se.session_id, e.name, st.load_g, e.load_type, st.reps
            FROM set_entry st
            JOIN session_exercise se ON se.id = st.session_exercise_id
            JOIN exercise e ON e.id = se.exercise_id
            JOIN workout_session s ON s.id = se.session_id
            WHERE st.completed_at IS NOT NULL AND s.status = 'COMPLETED' AND s.log_date >= ?
            ORDER BY se.session_id, se.position, se.id, st.set_index
        """

        private const val FOOD = "SELECT log_date, meal_slot, kcal, protein_g, carbs_g, fat_g, fiber_g FROM food_log WHERE log_date >= ? ORDER BY log_date, id"

        private const val WEIGH_INS = "SELECT id, log_date, measured_at, weight_g, source, scan_id FROM body_weight WHERE log_date >= ? ORDER BY measured_at, id"
    }
}
