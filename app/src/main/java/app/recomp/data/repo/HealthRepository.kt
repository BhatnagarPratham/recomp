package app.recomp.data.repo

import androidx.room.withTransaction
import app.recomp.data.db.AppDatabase
import app.recomp.data.db.BodyDao
import app.recomp.data.db.DailyActivityEntity
import app.recomp.data.db.HealthDao
import app.recomp.data.db.ImportedSessionRow
import app.recomp.data.db.RoutineDao
import app.recomp.data.db.ScanDraftEntity
import app.recomp.data.db.SleepSource
import app.recomp.data.db.WorkoutDao
import app.recomp.data.db.WorkoutSessionEntity
import app.recomp.data.db.YogaDetailEntity
import app.recomp.data.health.BackgroundRead
import app.recomp.data.health.ExerciseTypes
import app.recomp.data.health.ExternalWorkout
import app.recomp.data.health.HealthAvailability
import app.recomp.data.health.HealthChanges
import app.recomp.data.health.HealthFeature
import app.recomp.data.health.HealthSource
import app.recomp.data.prefs.DayBoundary
import app.recomp.data.prefs.DevicePrefs
import app.recomp.data.prefs.HealthSyncState
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.domain.health.ExtraSlot
import app.recomp.domain.health.ImportedWorkouts
import app.recomp.domain.health.InBodyDrafts
import app.recomp.domain.health.SleepNight
import app.recomp.domain.health.Span
import app.recomp.domain.health.Steps
import app.recomp.domain.model.SessionStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Today's steps against the goal (AR-01). [steps] is null until a sync has a count for today. */
data class StepsToday(val steps: Int?, val goal: Int)

/** Last night's sleep (AR-02), for the morning of the log date. [hours] is null until it's typed or synced. */
data class SleepToday(val date: LocalDate, val hours: Double?, val source: SleepSource?)

/** Body's activity card: the 7-day averages, each null when that feature is off or has nothing yet. */
data class BodyActivity(val stepsOn: Boolean, val stepsAverage: Int?, val stepsGoal: Int, val sleepOn: Boolean, val sleepAverage: Double?, val sleepNights: Int)

/** Settings → Health Connect (PRD §12.9). [on] is what you turned on; [granted] is what Health Connect allows. */
data class HealthStatus(
    val availability: HealthAvailability,
    val on: Set<HealthFeature>,
    val granted: Set<HealthFeature>,
    val background: BackgroundRead,
    val sync: HealthSyncState,
) {
    /** On and allowed: what a sync reads. */
    val active: Set<HealthFeature> get() = on intersect granted
}

/** What a sync did: the features it read and what failed, by feature. */
data class SyncReport(val read: Set<HealthFeature>, val errors: Map<HealthFeature, String>)

/**
 * Health Connect in (AR-01, AR-02, AR-04, IB-07; TRD §6.3, §10 M13). A sync reads each feature that's turned on and
 * allowed: steps by calendar day, the night's sleep, other apps' workouts as read-only extra sessions, and InBody
 * measurements as scan drafts. It's incremental through one change token per feature, kept in the `device` store,
 * and every step is safe to repeat, so a full re-read of the last 30 days creates no duplicates. Nothing is
 * written to Health Connect.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class HealthRepository @Inject constructor(
    private val db: AppDatabase,
    private val dao: HealthDao,
    private val bodyDao: BodyDao,
    private val workoutDao: WorkoutDao,
    private val routineDao: RoutineDao,
    private val prefs: SettingsPrefs,
    private val device: DevicePrefs,
    private val days: DayBoundary,
    private val source: HealthSource,
    private val clock: Clock,
) {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val mutex = Mutex()

    private fun calendarDay(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    // Settings

    val features: Flow<Set<HealthFeature>> = prefs.healthFeatures
    val stepsGoal: Flow<Int> = prefs.stepsGoal
    val trackSleep: Flow<Boolean> = prefs.trackSleep
    val syncState: Flow<HealthSyncState> = device.healthSync

    suspend fun status(): HealthStatus {
        val availability = source.availability()
        val available = availability == HealthAvailability.AVAILABLE
        return HealthStatus(
            availability = availability,
            on = prefs.healthFeatures.first(),
            granted = if (available) source.granted() else emptySet(),
            background = if (available) source.backgroundRead() else BackgroundRead.UNAVAILABLE,
            sync = device.healthSync.first(),
        )
    }

    /** Turning a feature off drops its change token, so turning it on again reads the last 30 days afresh. What it already read stays. */
    suspend fun setFeature(feature: HealthFeature, on: Boolean) {
        prefs.setHealthFeature(feature, on)
        if (!on) device.setChangesToken(feature, null)
    }

    suspend fun setStepsGoal(goal: Int) = prefs.setStepsGoal(goal)

    suspend fun setTrackSleep(on: Boolean) = prefs.setTrackSleep(on)

    /** Whether the daily background job has anything it may read. */
    suspend fun backgroundSyncWanted(): Boolean =
        source.availability() == HealthAvailability.AVAILABLE && prefs.healthFeatures.first().isNotEmpty() && source.backgroundRead() == BackgroundRead.GRANTED

    // Sync

    /** Reads every feature that's on and allowed. Null when there was nothing to read. One at a time. */
    suspend fun sync(): SyncReport? = mutex.withLock {
        if (source.availability() != HealthAvailability.AVAILABLE) return null
        val wanted = prefs.healthFeatures.first()
        if (wanted.isEmpty()) return null
        val active = wanted intersect source.granted()
        // The tokens belong to the data they were issued against: an import since then means reading it all again.
        device.bindToDatabase(databaseStamp())
        val now = clock.millis()
        val errors = mutableMapOf<HealthFeature, String>()
        // Without the background permission Health Connect only answers while the app is in front. A sync begun at
        // launch that's still going when you leave stops there, and the next one picks up, rather than reading short.
        val anyTime = source.backgroundRead() == BackgroundRead.GRANTED
        val read = mutableSetOf<HealthFeature>()
        for (feature in active) {
            if (!anyTime && !source.foreground()) break
            try {
                when (feature) {
                    HealthFeature.STEPS -> syncSteps(now)
                    // Any change re-reads all 30 nights on purpose: a night is merged from every app's sessions, so a
                    // partial read would merge only some of them (N6).
                    HealthFeature.SLEEP -> withChanges(feature, full = { rereadSleep(now) }) { rereadSleep(now) }
                    HealthFeature.WORKOUTS -> {
                        withChanges(feature, full = { rereadWorkouts(now) }) { applyWorkoutChanges(it, now) }
                        reconcileImports(now)
                    }
                    HealthFeature.INBODY -> withChanges(feature, full = { rereadInBody(now) }) { rereadInBody(now) }
                }
                read += feature
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors[feature] = e.message ?: e.javaClass.simpleName
            }
        }
        if (read.isNotEmpty() || errors.isNotEmpty()) {
            device.setSynced(now, errors.entries.joinToString("; ") { "${it.key.name.lowercase()}: ${it.value}" }.ifEmpty { null })
        }
        SyncReport(read, errors)
    }

    /** A sync that failed before reading anything, e.g. from the daily job: Settings shows it (N5). */
    suspend fun recordFailure(e: Exception) = device.setSyncError(e.message ?: e.javaClass.simpleName)

    private fun databaseStamp(): Int = db.openHelper.readableDatabase.query("PRAGMA application_id").use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /**
     * The change-token protocol (TRD §6.3). With no token, or one that expired after 30 days without a sync, a
     * fresh token is taken first and then everything is read again, so a change made during the read shows up
     * next time. The new token is stored only once the work succeeded: a failed sync repeats it.
     */
    private suspend fun withChanges(feature: HealthFeature, full: suspend () -> Unit, incremental: suspend (HealthChanges) -> Unit) {
        val changes = device.changesToken(feature)?.let { source.changes(feature, it) }
        if (changes == null || changes.expired) {
            val fresh = source.token(feature)
            full()
            device.setChangesToken(feature, fresh)
        } else {
            if (changes.changed) incremental(changes)
            device.setChangesToken(feature, changes.nextToken)
        }
    }

    /** Steps by calendar day (AR-01): Health Connect's aggregate for each of the last 30 days, the phone's own count included. */
    private suspend fun syncSteps(now: Long) {
        val today = calendarDay(now)
        val from = today.minusDays(WINDOW_DAYS - 1)
        val counts = source.stepsByDay(from, today)
        db.withTransaction {
            val existing = dao.activity(from, today).associateBy { it.logDate }
            generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.forEach { date ->
                val steps = counts[date]?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt()
                val before = existing[date]?.steps
                if (steps != null) {
                    dao.ensureDay(date)
                    if (steps != before || date == today) dao.setSteps(date, steps, now)
                } else if (before != null) {
                    dao.setSteps(date, null, now)
                }
            }
        }
    }

    /** Each of the last 30 mornings from the tracker's sessions (AR-02). A night you typed is never touched. */
    private suspend fun rereadSleep(now: Long) {
        val today = days.of(now)
        val from = today.minusDays(WINDOW_DAYS - 1)
        // Health Connect allows 30 days before the first grant, so nothing older is asked for (S4).
        val readFrom = now - WINDOW_DAYS * DAY_MS
        val sessions = source.sleepSessions(Span(SleepNight.window(from, zone).start.coerceIn(readFrom, now), now), readFrom)
        db.withTransaction {
            generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.forEach { date ->
                val window = SleepNight.window(date, zone, until = now)
                val hours = SleepNight.hours(sessions, window)?.takeIf { it > 0 }
                if (hours != null) {
                    dao.ensureDay(date)
                    dao.setSyncedSleep(date, hours)
                } else {
                    dao.clearSyncedSleep(date)
                }
            }
        }
    }

    private suspend fun extraSlots(): List<ExtraSlot> {
        val routines = routineDao.extras().associateBy { it.id }
        return prefs.currentPersona().extras.mapNotNull { extra -> routines[extra.routineId]?.let { ExtraSlot(it.id, it.kind, extra.toPlan().days) } }
    }

    /**
     * The last 30 days of other apps' sessions, the longest first so that of two apps' copies of one session the
     * longer is kept (S2). An import Health Connect no longer has is dropped only when its app returned other sessions
     * in this read: right after a restore, Health Connect's own data may not be back yet, and the imports stay (N2).
     */
    private suspend fun rereadWorkouts(now: Long) {
        val since = now - WINDOW_DAYS * DAY_MS
        val workouts = source.workouts(Span(since, now)).sortedWith(compareByDescending<ExternalWorkout> { it.end - it.start }.thenBy { it.start })
        val slots = extraSlots()
        db.withTransaction {
            workouts.forEach { applyWorkout(it, slots, now) }
            val ids = workouts.map { it.id }.toSet()
            val answered = workouts.map { origin(it) }.toSet()
            dao.importedSince(since).filter { it.hcRecordId !in ids && it.notes in answered }.forEach { dao.deleteImportedRow(it.id) }
        }
    }

    /**
     * What changed. When a deleted session was one Recomp kept, the window is read again, so another app's copy that
     * was skipped as a duplicate of it comes back (S2).
     */
    private suspend fun applyWorkoutChanges(changes: HealthChanges, now: Long) {
        val slots = extraSlots()
        var keptOneDeleted = false
        db.withTransaction {
            changes.deletedIds.forEach { id ->
                if (dao.imported(id) != null) keptOneDeleted = true
                dao.deleteImported(id)
            }
            changes.workouts.sortedByDescending { it.end - it.start }.forEach { applyWorkout(it, slots, now) }
        }
        if (keptOneDeleted) rereadWorkouts(now)
    }

    private fun origin(workout: ExternalWorkout) = "from ${source.appName(workout.origin)}"

    /**
     * Saves one session from another app as a completed extra session, or follows an edit to it (AR-04). Never kept:
     * strength and vague types; anything overlapping a strength workout you logged here; and the shorter of two
     * imports of one kind that share more than half of the shorter one's time, which are two apps' copies of one
     * session (S2).
     */
    private suspend fun applyWorkout(workout: ExternalWorkout, slots: List<ExtraSlot>, now: Long) {
        var existing = dao.imported(workout.id) ?: dao.importedAt(workout.start, workout.end)
        val kind = ExerciseTypes.kind(workout.type)
        if (kind == null || workout.end <= workout.start || dao.ownStrengthOverlapping(workout.start, workout.end, now).isNotEmpty()) {
            existing?.let { dao.deleteImportedRow(it.id) }
            return
        }
        val copies = dao.importedOverlapping(kind, workout.start, workout.end, existing?.id ?: 0).filter { other ->
            ImportedWorkouts.sameSession(Span(workout.start, workout.end), Span(other.startedAt, other.endedAt ?: other.startedAt))
        }
        if (copies.any { (it.endedAt ?: it.startedAt) - it.startedAt >= workout.end - workout.start }) {
            existing?.let { dao.deleteImportedRow(it.id) }
            return
        }
        val logDate = days.of(workout.start)
        // This one is the longer copy: it replaces the others, keeping the extra the first one counted toward.
        val inherited = copies.firstOrNull { it.routineId != null && it.logDate == logDate }?.routineId
        copies.forEach { dao.deleteImportedRow(it.id) }
        val style = ExerciseTypes.label(workout.type)
        val minutes = ((workout.end - workout.start + 30_000) / 60_000).toInt().coerceAtLeast(1)
        val notes = origin(workout)
        if (existing == null) {
            workoutDao.insertExtraSession(
                WorkoutSessionEntity(
                    routineId = inherited ?: ImportedWorkouts.routineFor(kind, logDate.dayOfWeek, slots),
                    kind = kind,
                    status = SessionStatus.COMPLETED,
                    startedAt = workout.start,
                    endedAt = workout.end,
                    logDate = logDate,
                    notes = notes,
                    hcRecordId = workout.id,
                ),
                YogaDetailEntity(sessionId = 0, style = style, durationMin = minutes),
            )
        } else {
            // The extra it counts toward is chosen once, and again only if the edit moved it to another day or kind.
            val routineId = if (existing.kind == kind && existing.logDate == logDate) existing.routineId else ImportedWorkouts.routineFor(kind, logDate.dayOfWeek, slots)
            workoutDao.updateSession(
                existing.copy(routineId = routineId, kind = kind, startedAt = workout.start, endedAt = workout.end, logDate = logDate, notes = notes, hcRecordId = workout.id),
            )
            dao.setDetail(existing.id, style, minutes)
        }
    }

    /**
     * After each sync, so a workout you logged after the import wins (TRD §6.3):
     * - an import overlapping a strength workout you logged here goes;
     * - an import of a kind you also logged yourself that day stays visible but counts toward nothing (S1). An
     *   extra's logged times are *Save* minus its length, so the day and kind are what match, not the clock;
     * - one that counted toward nothing only because of that counts again once your session is gone.
     */
    private suspend fun reconcileImports(now: Long) {
        val slots = extraSlots()
        db.withTransaction {
            dao.importedSince(now - WINDOW_DAYS * DAY_MS).forEach { row ->
                when {
                    dao.ownStrengthOverlapping(row.startedAt, row.endedAt ?: row.startedAt, now).isNotEmpty() -> dao.deleteImportedRow(row.id)
                    dao.ownExtraOn(row.logDate, row.kind) -> if (row.routineId != null) dao.setRoutine(row.id, null)
                    row.routineId == null -> ImportedWorkouts.routineFor(row.kind, row.logDate.dayOfWeek, slots)?.let { dao.setRoutine(row.id, it) }
                }
            }
        }
    }

    /**
     * InBody measurements of the last 30 days as drafts (IB-07). One you dismissed or saved stays gone, one that
     * matches a scan typed within ten minutes makes no draft, and a measurement deleted in Health Connect takes its
     * draft with it.
     */
    private suspend fun rereadInBody(now: Long) {
        val since = now - WINDOW_DAYS * DAY_MS
        val readings = source.bodyReadings(Span(since, now))
        val groups = InBodyDrafts.group(readings)
        val handled = device.inBodyHandled()
        db.withTransaction {
            val scanTimes = dao.scanTimes()
            val drafts = dao.drafts()
            val kept = mutableSetOf<Long>()
            groups.forEach { group ->
                val existing = drafts.firstOrNull { d ->
                    d.id !in kept && (d.hcIds.any { it in group.ids } || abs(d.measuredAt - group.measuredAt) <= InBodyDrafts.SAME_MEASUREMENT_MS)
                }
                if (group.ids.any { it in handled } || InBodyDrafts.matchesScan(group.measuredAt, scanTimes)) return@forEach
                val row = ScanDraftEntity(
                    id = existing?.id ?: 0, measuredAt = group.measuredAt, weightG = group.weightG, bodyFatPct = group.bodyFatPct, bmrKcal = group.bmrKcal, hcIds = group.ids,
                )
                if (existing == null) kept += dao.insert(row)
                else {
                    kept += existing.id
                    if (row != existing) dao.update(row)
                }
            }
            drafts.filter { it.id !in kept && it.measuredAt >= since }.forEach { dao.deleteDraft(it.id) }
        }
        device.keepInBodyHandled(readings.map { it.id }.toSet())
    }

    // Steps (AR-01)

    /** Today's steps and the goal, by calendar day; null while steps are off. */
    fun observeSteps(): Flow<StepsToday?> {
        val today = DayBoundary.ticks(LocalTime.MIDNIGHT, zone, clock::millis)
        return combine(prefs.healthFeatures, prefs.stepsGoal, today.flatMapLatest { dao.observeDay(it) }) { features, goal, day ->
            if (HealthFeature.STEPS in features) StepsToday(day?.steps, goal) else null
        }
    }

    // Sleep (AR-02)

    /** Last night's sleep for today's log date; null while *Track sleep* is off. */
    fun observeSleep(): Flow<SleepToday?> = days.observeToday(clock::millis).flatMapLatest { today ->
        combine(prefs.trackSleep, dao.observeDay(today)) { on, day -> if (on) SleepToday(today, day?.sleepHours, day?.sleepSource) else null }
    }

    /** The night for the morning of [date], or null when *Track sleep* is off. */
    suspend fun sleepFor(date: LocalDate): SleepToday? =
        if (prefs.trackSleep.first()) dao.day(date).let { SleepToday(date, it?.sleepHours, it?.sleepSource) } else null

    suspend fun logDate(epochMs: Long): LocalDate = days.of(epochMs)

    /** What you type wins (PRD §12.9): it's marked TYPED, so no later sync replaces it. Null clears the night. */
    suspend fun setTypedSleep(date: LocalDate, hours: Double?) = db.withTransaction {
        if (hours == null) {
            dao.clearSleep(date)
        } else {
            dao.ensureDay(date)
            dao.setTypedSleep(date, SleepNight.toHalfHours(hours.coerceIn(SleepNight.TYPED_RANGE)))
        }
    }

    // Body's activity card

    fun observeWeek(): Flow<BodyActivity> = days.observeToday(clock::millis).flatMapLatest { today ->
        combine(prefs.healthFeatures, prefs.stepsGoal, prefs.trackSleep, dao.observeActivity(today.minusDays(8), today)) { features, goal, sleepOn, rows ->
            val calendarToday = calendarDay(clock.millis())
            val nights = nightsBefore(rows, today)
            BodyActivity(
                stepsOn = HealthFeature.STEPS in features,
                stepsAverage = Steps.weekAverage(rows.mapNotNull { r -> r.steps?.let { r.logDate to it } }.toMap(), calendarToday),
                stepsGoal = goal,
                sleepOn = sleepOn,
                sleepAverage = nights.takeIf { it.isNotEmpty() }?.average(),
                sleepNights = nights.size,
            )
        }
    }

    /** The nights of the last seven mornings, today's included once it's known. */
    private fun nightsBefore(rows: List<DailyActivityEntity>, today: LocalDate): List<Double> =
        rows.filter { it.logDate.isAfter(today.minusDays(7)) }.mapNotNull { it.sleepHours }

    // Other apps' workouts (AR-04)

    /** What other apps recorded today, e.g. "Walk · 35 min · from Google Fit". */
    fun observeImportedToday(): Flow<List<ImportedSessionRow>> = days.observeToday(clock::millis).flatMapLatest { dao.observeImportedOn(it) }

    // InBody drafts (IB-07)

    /** Drafts waiting on Body, newest first. One whose scan you've since typed is hidden; the next sync drops it. */
    fun observeDrafts(): Flow<List<ScanDraftEntity>> = combine(dao.observeDrafts(), bodyDao.observeScans()) { drafts, scans ->
        val times = scans.map { it.measuredAt }
        drafts.filter { !InBodyDrafts.matchesScan(it.measuredAt, times) }
    }

    suspend fun draft(id: Long): ScanDraftEntity? = dao.draft(id)

    /** *Dismiss*, or the draft was saved as a scan: it goes, and its records are remembered so it doesn't come back. */
    suspend fun closeDraft(id: Long) {
        val draft = dao.draft(id) ?: return
        device.addInBodyHandled(draft.hcIds)
        dao.deleteDraft(id)
    }

    companion object {
        /** A first read, and a re-read after an expired token, go this far back: what Health Connect allows with no history permission. */
        const val WINDOW_DAYS = 30L
        private const val DAY_MS = 86_400_000L
    }
}
