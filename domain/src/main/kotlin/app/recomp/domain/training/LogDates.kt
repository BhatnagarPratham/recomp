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
    /** PR-03's default for the "day starts at" setting. Callers always pass the setting itself. */
    val DEFAULT_DAY_START: LocalTime = LocalTime.of(4, 0)

    /** The setting's choices: midnight to 6 AM in hourly steps (PRD §7.1). */
    val DAY_START_CHOICES: List<LocalTime> = (0..6).map { LocalTime.of(it, 0) }

    fun of(epochMs: Long, zone: ZoneId, dayStart: LocalTime): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(zone)
            .minusHours(dayStart.hour.toLong())
            .minusMinutes(dayStart.minute.toLong())
            .toLocalDate()
}
