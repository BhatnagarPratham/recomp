package app.recomp.data

import app.recomp.data.backup.AutoBackup
import app.recomp.data.backup.BackupRun
import app.recomp.data.backup.BackupStorage
import app.recomp.data.backup.StoredFile
import app.recomp.data.prefs.BackupTarget
import app.recomp.domain.export.BackupRotation
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A folder (or one file) as the storage access framework would show it, with failures on demand. */
class FakeBackupStorage : BackupStorage {
    val files = linkedMapOf<String, ByteArray>()
    var single: ByteArray? = null
    var fail: Exception? = null

    override suspend fun files(target: BackupTarget): List<StoredFile> = files.keys.map { StoredFile(it, "uri:$it") }

    override suspend fun write(target: BackupTarget, name: String?, bytes: ByteArray) {
        fail?.let { throw it }
        if (name == null) single = bytes else files[name] = bytes
    }

    override suspend fun delete(file: StoredFile) {
        files.remove(file.name)
    }
}

/** DA-02 (PRD §12.12, TRD §10 M16): keeping 8 backups, the rolling file, and failures that say what happened. Invented data. */
@RunWith(RobolectricTestRunner::class)
class AutoBackupTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: TestApp
    private lateinit var savedZone: TimeZone
    private val storage = FakeBackupStorage()
    private lateinit var backup: AutoBackup
    private val folder = BackupTarget("content://test/tree/backups", folder = true, label = "Backups")
    private val driveFile = BackupTarget("content://test/document/latest", folder = false, label = "recomp-backup-latest.zip")

    private fun sunday(week: Long): Instant = LocalDate.of(2026, 9, 6).plusWeeks(week).atTime(22, 0).toInstant(ZoneOffset.UTC)

    @Before
    fun setUp() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        app = TestApp(File(tmp.root, "app").apply { mkdirs() })
        backup = AutoBackup(app.backup, storage, app.device, app.days, app.clock)
    }

    @After
    fun tearDown() {
        app.close()
        TimeZone.setDefault(savedZone)
    }

    @Test
    fun `with nowhere chosen it does nothing`() = runBlocking {
        assertEquals(BackupRun.NoTarget, backup.run())
        assertTrue(storage.files.isEmpty())
    }

    @Test
    fun `a folder keeps the newest eight of Recomp's backups and leaves other files alone`() = runBlocking {
        app.device.setBackupTarget(folder)
        storage.files["holiday.jpg"] = byteArrayOf(1)
        storage.files["recomp-csv-2026-09-01.zip"] = byteArrayOf(2)
        (0L until 10).forEach { week ->
            app.clock.instant = sunday(week)
            val run = backup.run()
            assertTrue(run is BackupRun.Done)
        }
        val backups = storage.files.keys.filter { BackupRotation.dateOf(it) != null }.sorted()
        assertEquals((2L until 10).map { BackupRotation.fileName(LocalDate.of(2026, 9, 6).plusWeeks(it)) }, backups)
        assertTrue("holiday.jpg" in storage.files && "recomp-csv-2026-09-01.zip" in storage.files)
        assertEquals(sunday(9).toEpochMilli(), app.device.autoBackup.first().lastSuccessAt)
        assertNull(app.device.autoBackup.first().error)

        // Each file is a real backup that the import accepts.
        val newest = storage.files.getValue(backups.last())
        val prepared = app.backup.prepare(ByteArrayInputStream(newest))
        assertTrue(prepared.manifest.schemaVersion > 0)
        app.backup.discard(prepared)
        assertTrue(ZipInputStream(ByteArrayInputStream(newest)).nextEntry != null)
    }

    @Test
    fun `a second run on the same day replaces that day's file`() = runBlocking {
        app.device.setBackupTarget(folder)
        app.clock.instant = sunday(0)
        backup.run()
        app.clock.instant = sunday(0).plusSeconds(600)
        backup.run()
        assertEquals(1, storage.files.size)
    }

    @Test
    fun `the Drive option overwrites one file`() = runBlocking {
        app.device.setBackupTarget(driveFile)
        app.clock.instant = sunday(0)
        assertEquals(BackupRun.Done("recomp-backup-latest.zip", 0), backup.run())
        val first = storage.single!!
        app.clock.instant = sunday(1)
        backup.run()
        assertTrue(storage.files.isEmpty())
        assertTrue("overwritten with the newer backup", !storage.single!!.contentEquals(first))
    }

    @Test
    fun `a revoked folder says so plainly and isn't retried, and a slow Drive is`() = runBlocking {
        app.device.setBackupTarget(folder)
        app.clock.instant = sunday(0)
        backup.run()
        storage.fail = SecurityException("Permission Denial: writing com.android.externalstorage.documents uri content://…")
        app.clock.instant = sunday(1)
        val revoked = backup.run() as BackupRun.Failed
        assertEquals(false, revoked.retry)
        assertEquals(AutoBackup.revoked(folder), revoked.reason)
        assertTrue(revoked.reason.contains("“Backups”") && revoked.reason.contains("Settings → Data"))
        val state = app.device.autoBackup.first()
        assertEquals(revoked.reason, state.error)
        assertEquals("the last success stays", sunday(0).toEpochMilli(), state.lastSuccessAt)

        storage.fail = FileNotFoundException("gone")
        assertEquals(false, (backup.run() as BackupRun.Failed).retry)

        app.device.setBackupTarget(driveFile)
        storage.fail = IOException("Network unreachable")
        val slow = backup.run() as BackupRun.Failed
        assertEquals(true, slow.retry)
        assertTrue(slow.reason.contains("try again"))

        storage.fail = null
        assertTrue(backup.run() is BackupRun.Done)
        assertNull(app.device.autoBackup.first().error)
    }

    @Test
    fun `the target and its outcome are device state, out of exports`() = runBlocking {
        app.device.setBackupTarget(folder)
        assertTrue(app.prefs.exportable().asMap().keys.none { it.name.startsWith("backup_") })
        app.device.setBackupTarget(null)
        assertNull(app.device.autoBackup.first().target)
    }
}
