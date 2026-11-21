package app.recomp.domain.export

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/** DA-02's automatic backup (PRD §12.12): which files to keep in the folder, and when the next one is due. */
object BackupRotation {
    /** A folder on the phone keeps this many. */
    const val KEEP = 8

    /** Sunday night. */
    val DAY: DayOfWeek = DayOfWeek.SUNDAY
    val TIME: LocalTime = LocalTime.of(22, 0)

    /** The single rolling file offered for Google Drive. */
    const val ROLLING_FILE = "recomp-backup-latest.zip"

    /** `recomp-backup-2026-11-22.zip`, and the copies a folder makes of a name that's taken (`… (1).zip`). */
    private val NAME = Regex("""recomp-backup-(\d{4}-\d{2}-\d{2})(?: \((\d+)\))?\.zip""")

    /** 0 for the original name, n for the folder's copy "(n)", which was made later. */
    private fun copyOf(name: String): Int = NAME.matchEntire(name)?.groupValues?.get(2)?.toIntOrNull() ?: 0

    fun fileName(date: LocalDate) = "recomp-backup-$date.zip"

    /** Where a backup is written before it replaces [name]. Not a backup name, so rotation never counts it. */
    fun partName(name: String) = "$name.part"

    /** The date in one of Recomp's backup names, or null for any other file, which is never touched. */
    fun dateOf(name: String): LocalDate? = NAME.matchEntire(name)?.let { runCatching { LocalDate.parse(it.groupValues[1]) }.getOrNull() }

    /**
     * The backups to delete so that the newest [KEEP] stay (by the date in the name; a folder's later copy of a name ranks above it). Only Recomp's
     * own backup names count; anything else in the folder is left alone.
     */
    fun <T> toDelete(files: List<T>, name: (T) -> String, keep: Int = KEEP): List<T> =
        files.mapNotNull { f -> dateOf(name(f))?.let { f to it } }
            .sortedWith(compareByDescending<Pair<T, LocalDate>> { it.second }.thenByDescending { copyOf(name(it.first)) })
            .drop(keep)
            .map { it.first }

    /** The next Sunday at 22:00 strictly after [now]. */
    fun nextRun(now: LocalDateTime): LocalDateTime {
        val thisWeek = now.toLocalDate().with(TemporalAdjusters.nextOrSame(DAY)).atTime(TIME)
        return if (thisWeek.isAfter(now)) thisWeek else thisWeek.plusWeeks(1)
    }
}
