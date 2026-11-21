package app.recomp.data

import app.recomp.data.backup.AutoBackup
import app.recomp.data.backup.BackupRun
import app.recomp.data.backup.BackupStorage
import app.recomp.data.backup.StoredFile
import app.recomp.data.db.PhotoPose
import app.recomp.data.db.ProgressPhotoEntity
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

    override suspend fun write(target: BackupTarget, name: String?, source: File) {
        fail?.let { throw it }
        val bytes = source.readBytes()
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

    @Test
    fun `photos go in the automatic backup only when switched on, and come back on restore`() = runBlocking {
        app.device.setBackupTarget(folder)
        assertEquals(false, app.device.autoBackup.first().includePhotos)
        val front = app.photos.newCaptureFile(PhotoPose.FRONT, 1).apply { writeBytes(ByteArray(64) { it.toByte() }) }
        app.photos.saveCapture(front, PhotoPose.FRONT, takenAt = 1)
        app.clock.instant = sunday(0)
        backup.run()
        fun photoEntries(bytes: ByteArray) = ZipInputStream(ByteArrayInputStream(bytes)).use { z -> generateSequence { z.nextEntry }.map { it.name }.filter { it.startsWith("photos/") }.toList() }
        assertEquals(emptyList<String>(), photoEntries(storage.files.values.single()))

        app.device.setBackupPhotos(true)
        app.clock.instant = sunday(1)
        backup.run()
        val withPhotos = storage.files.getValue(BackupRotation.fileName(LocalDate.of(2026, 9, 13)))
        assertEquals(listOf("photos/${front.name}"), photoEntries(withPhotos))
        // The scratch file in the cache is gone once the backup is written.
        assertTrue(!app.backup.autoBackupScratch.exists())

        // Restoring that backup brings the photo back, through the import's checks.
        val bytes = front.readBytes()
        app.photos.delete(app.db.photoDao().all().single().id)
        app.backup.restore(app.backup.prepare(ByteArrayInputStream(withPhotos)))
        val row = app.db.photoDao().all().single()
        assertTrue(app.photos.fileOf(row)!!.readBytes().contentEquals(bytes))
    }

    @Test
    fun `an automatic backup with a photo row pointing outside the photos folder can't be restored`() = runBlocking {
        app.device.setBackupTarget(folder)
        app.device.setBackupPhotos(true)
        app.db.photoDao().insert(ProgressPhotoEntity(logDate = LocalDate.of(2026, 9, 1), takenAt = 1, pose = PhotoPose.SIDE, filePath = "datastore/settings.preferences_pb"))
        app.clock.instant = sunday(0)
        backup.run()
        val refused = runCatching { app.backup.prepare(ByteArrayInputStream(storage.files.values.single())) }.exceptionOrNull()
        assertTrue(refused?.message.orEmpty(), refused?.message.orEmpty().contains("outside the photos folder"))
    }

    /** A scan with an InBody screenshot, as M15 keeps it: a file in files/scan_images named by the scan's image_path. */
    private suspend fun scanWithImage(): Pair<String, ByteArray> {
        val name = "0b5a9c2e-1f3d-4a6b-8c7d-2e4f6a8b0c1d.png"
        val bytes = ByteArray(96) { (it * 3).toByte() }
        File(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "scan_images").apply { mkdirs() }.resolve(name).writeBytes(bytes)
        app.db.openHelper.writableDatabase.execSQL(
            "INSERT INTO body_composition (measured_at, log_date, source, weight_g, skeletal_muscle_g, body_fat_g, body_fat_pct, visceral_fat_level, " +
                "mins_since_meal, workout_before_scan, protocol_ok, image_path) VALUES (1, '2026-09-01', 'MANUAL', 80000, 33000, 20000, 25.0, 8.0, 180, 0, 1, '$name')",
        )
        return name to bytes
    }

    private fun entries(bytes: ByteArray, prefix: String) =
        ZipInputStream(ByteArrayInputStream(bytes)).use { z -> generateSequence { z.nextEntry }.map { it.name }.filter { it.startsWith(prefix) }.toList() }

    @Test
    fun `scan images follow the photos switch and come back on restore`() = runBlocking {
        app.device.setBackupTarget(folder)
        val (name, bytes) = scanWithImage()
        app.clock.instant = sunday(0)
        backup.run()
        assertEquals(emptyList<String>(), entries(storage.files.values.single(), "scan_images/"))

        app.device.setBackupPhotos(true)
        app.clock.instant = sunday(1)
        backup.run()
        val withImages = storage.files.getValue(BackupRotation.fileName(LocalDate.of(2026, 9, 13)))
        assertEquals(listOf("scan_images/$name"), entries(withImages, "scan_images/"))
        val manifest = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<app.recomp.data.backup.BackupManifest>(
            ZipInputStream(ByteArrayInputStream(withImages)).use { z -> generateSequence { z.nextEntry }.first { it.name == "manifest.json" }; z.readBytes().decodeToString() },
        )
        assertEquals(1, manifest.scanImages)

        // The file is lost on the phone; restoring the backup brings it back where the scan points.
        val dir = File(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "scan_images")
        dir.deleteRecursively()
        app.backup.restore(app.backup.prepare(ByteArrayInputStream(withImages)))
        assertTrue(File(dir, name).readBytes().contentEquals(bytes))
        assertTrue(!app.backup.incomingScanImages.exists())
    }

    @Test
    fun `a backup whose scan points outside the scan images folder can't be restored`() = runBlocking {
        app.device.setBackupTarget(folder)
        app.device.setBackupPhotos(true)
        scanWithImage()
        app.db.openHelper.writableDatabase.execSQL("UPDATE body_composition SET image_path = '../datastore/settings.preferences_pb'")
        app.clock.instant = sunday(0)
        backup.run()
        val refused = runCatching { app.backup.prepare(ByteArrayInputStream(storage.files.values.single())) }.exceptionOrNull()
        assertTrue(refused?.message.orEmpty(), refused?.message.orEmpty().contains("scan image that points outside"))
    }

    @Test
    fun `a same-day copy the folder named with (1) is cleaned up, leaving one file for the day`() = runBlocking {
        app.device.setBackupTarget(folder)
        // What the phone had: the day's first file and a second one the folder named "(1)".
        storage.files["recomp-backup-2026-09-06.zip"] = byteArrayOf(1)
        storage.files["recomp-backup-2026-09-06 (1).zip"] = byteArrayOf(2)
        app.clock.instant = sunday(1)
        backup.run()
        assertEquals(
            listOf("recomp-backup-2026-09-06 (1).zip", BackupRotation.fileName(LocalDate.of(2026, 9, 13))),
            storage.files.keys.filter { BackupRotation.dateOf(it) != null }.sorted(),
        )
    }

    @Test
    fun `a third backup on the same day is the one kept, and the run's file is still there`() = runBlocking {
        app.device.setBackupTarget(folder)
        storage.files["recomp-backup-2026-09-13.zip"] = byteArrayOf(1)
        storage.files["recomp-backup-2026-09-13 (1).zip"] = byteArrayOf(2)
        app.clock.instant = sunday(1)
        val run = backup.run() as BackupRun.Done
        assertEquals("recomp-backup-2026-09-13.zip", run.name)
        assertEquals(listOf("recomp-backup-2026-09-13.zip"), storage.files.keys.filter { BackupRotation.dateOf(it) != null })
        assertTrue(storage.files.getValue(run.name).size > 2)
    }
}
