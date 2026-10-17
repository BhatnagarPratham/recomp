package app.recomp.domain.training

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * `log_date` (TRD §4) is computed once, at write time, from the "day starts at" setting (PR-03), so a
 * late-night session or snack counts toward the day it belongs to.
 */
object LogDates {
    /** PR-03's default. Becomes a setting with the rest of the profile in M3. */
    val DEFAULT_DAY_START: LocalTime = LocalTime.of(4, 0)

    fun of(epochMs: Long, zone: ZoneId, dayStart: LocalTime = DEFAULT_DAY_START): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(zone)
            .minusHours(dayStart.hour.toLong())
            .minusMinutes(dayStart.minute.toLong())
            .toLocalDate()
}
