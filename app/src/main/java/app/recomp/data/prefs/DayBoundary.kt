package app.recomp.data.prefs

import app.recomp.domain.training.LogDates
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** `log_date` from the "day starts at" setting (PR-03, TRD §4), so every writer uses the same boundary. */
@Singleton
class DayBoundary @Inject constructor(private val prefs: SettingsPrefs) {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    suspend fun of(epochMs: Long): LocalDate = LogDates.of(epochMs, zone, prefs.currentDayStart())

    suspend fun today(now: Long = System.currentTimeMillis()): LocalDate = of(now)

    /** Today's log date, again whenever the setting changes. */
    fun observeToday(now: () -> Long = System::currentTimeMillis): Flow<LocalDate> =
        prefs.dayStart.map { LogDates.of(now(), zone, it) }
}
