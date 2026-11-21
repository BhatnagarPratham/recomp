package app.recomp.domain.export

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** DA-02 (PRD §12.12): the newest 8 of Recomp's backups stay; nothing else in the folder is touched. */
class BackupRotationTest {
    @Test
    fun `the newest eight stay and only Recomp's backups are ever deleted`() {
        val sundays = (0L until 11).map { LocalDate.of(2026, 9, 6).plusWeeks(it) }
        val files = sundays.map(BackupRotation::fileName).shuffled(java.util.Random(4)) +
            listOf("holiday.jpg", "recomp-csv-2026-11-01.zip", "recomp-backup-latest.zip", "recomp-backup-2026-13-40.zip", "Recomp-backup-2026-09-01.zip")
        val gone = BackupRotation.toDelete(files, { it })
        assertEquals(sundays.take(3).map(BackupRotation::fileName).toSet(), gone.toSet())
        assertEquals(emptyList<String>(), BackupRotation.toDelete(files.take(8), { it }))
    }

    @Test
    fun `a copy the folder made of a taken name counts, and as the later file it outranks the original`() {
        assertEquals(LocalDate.of(2026, 11, 22), BackupRotation.dateOf("recomp-backup-2026-11-22 (1).zip"))
        assertNull(BackupRotation.dateOf("recomp-backup-2026-11-22.zip.partial"))
        val files = (1..8).map { "recomp-backup-2026-11-0$it.zip" } + "recomp-backup-2026-11-01 (1).zip"
        assertEquals(listOf("recomp-backup-2026-11-01.zip"), BackupRotation.toDelete(files, { it }))
    }

    @Test
    fun `the next run is the coming Sunday at 22 00`() {
        assertEquals(LocalDateTime.of(2026, 11, 22, 22, 0), BackupRotation.nextRun(LocalDateTime.of(2026, 11, 21, 23, 50)))
        assertEquals(LocalDateTime.of(2026, 11, 22, 22, 0), BackupRotation.nextRun(LocalDateTime.of(2026, 11, 22, 21, 59)))
        assertEquals(LocalDateTime.of(2026, 11, 29, 22, 0), BackupRotation.nextRun(LocalDateTime.of(2026, 11, 22, 22, 0)))
    }

    @Test
    fun `a part file being written is never counted or deleted as a backup`() {
        val part = BackupRotation.partName(BackupRotation.fileName(LocalDate.of(2026, 11, 22)))
        assertEquals("recomp-backup-2026-11-22.zip.part", part)
        assertNull(BackupRotation.dateOf(part))
        val files = (1..9).map { "recomp-backup-2026-11-0$it.zip" } + part
        assertEquals(listOf("recomp-backup-2026-11-01.zip"), BackupRotation.toDelete(files, { it }))
    }
}
