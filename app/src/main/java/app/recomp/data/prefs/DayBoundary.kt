package app.recomp.data.prefs

import app.recomp.domain.training.LogDates
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/** `log_date` from the "day starts at" setting (PR-03, TRD §4), so every writer uses the same boundary. */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DayBoundary @Inject constructor(private val prefs: SettingsPrefs) {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    suspend fun of(epochMs: Long): LocalDate = LogDates.of(epochMs, zone, prefs.currentDayStart())

    suspend fun today(now: Long = System.currentTimeMillis()): LocalDate = of(now)

    /** Today's log date, again at each day boundary and whenever the setting changes. */
    fun observeToday(now: () -> Long = System::currentTimeMillis): Flow<LocalDate> =
        prefs.dayStart.flatMapLatest { start -> ticks(start, zone, now) }

    companion object {
        /** Re-checks at least this often, so a changed clock or a sleeping phone can't leave the date stale for long. */
        const val MAX_WAIT_MS = 60_000L

        /** The log date now, then each time it changes. */
        fun ticks(dayStart: LocalTime, zone: ZoneId, now: () -> Long): Flow<LocalDate> = flow {
            while (true) {
                val t = now()
                emit(LogDates.of(t, zone, dayStart))
                delay(min(LogDates.nextBoundary(t, zone, dayStart) - t, MAX_WAIT_MS).coerceAtLeast(1))
            }
        }.distinctUntilChanged()
    }
}
