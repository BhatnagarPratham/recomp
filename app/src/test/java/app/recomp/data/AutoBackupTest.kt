package app.recomp.data

import app.recomp.data.backup.AutoBackup
import app.recomp.data.backup.BackupManager
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

    /** Like a provider that won't replace a taken name: the new file gets the next free "(n)". */
    var renameTaken = false

    /** Runs in the middle of each write, after the source is read once; the source is read again after it. */
    var during: (suspend () -> Unit)? = null
    var writing = 0
    var mostAtOnce = 0
    val sourcesChanged = mutableListOf<String>()

    override suspend fun write(target: BackupTarget, name: String?, source: File): StoredFile {
        fail?.let { throw it }
        writing++
        mostAtOnce = maxOf(mostAtOnce, writing)
        val bytes = try {
            val first = source.readBytes()
            during?.invoke()
            // Another run must not have touched this run's scratch file meanwhile.
            if (!source.isFile || !source.readBytes().contentEquals(first)) sourcesChanged += source.name
            first
        } finally {
            writing--
        }
        if (name == null) {
            single = bytes
            return StoredFile(target.label, target.uri)
        }
        val actual = if (renameTaken && name in files) {
            val base = name.removeSuffix(".zip")
            (1..99).map { "$base ($it).zip" }.first { it !in files }
        } else name
        files[actual] = bytes
        return StoredFile(actual, "uri:$actual")
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
        assertTrue(app.backup.autoBackupScratchDir.listFiles().orEmpty().isEmpty())

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

    @Test
    fun `two runs at once, Back up now during the weekly job, don't share or corrupt a scratch file`() = runBlocking {
        app.device.setBackupTarget(folder)
        app.clock.instant = sunday(0)
        storage.during = { kotlinx.coroutines.delay(200) }
        val runs = (1..2).map { async(kotlinx.coroutines.Dispatchers.Default) { backup.run() } }.awaitAll()
        assertTrue(runs.all { it is BackupRun.Done })
        assertEquals("one run at a time", 1, storage.mostAtOnce)
        assertEquals(emptyList<String>(), storage.sourcesChanged)
        // The day's file is a whole backup the import accepts, and no scratch file is left.
        val prepared = app.backup.prepare(ByteArrayInputStream(storage.files.values.single()))
        app.backup.discard(prepared)
        assertTrue(app.backup.autoBackupScratchDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `each run builds in its own scratch file`() {
        val a = app.backup.newAutoBackupScratch()
        val b = app.backup.newAutoBackupScratch()
        assertTrue(a != b && a.isFile && b.isFile)
    }

    @Test
    fun `scratch files a killed run left are cleared at launch, but never while a run is going`() = runBlocking {
        val dir = app.backup.autoBackupScratchDir.apply { mkdirs() }
        File(dir, "automatic.zip").writeBytes(byteArrayOf(1))
        app.backup.newAutoBackupScratch().writeBytes(byteArrayOf(2))
        File(dir, "other.txt").writeBytes(byteArrayOf(3))
        assertEquals(2, backup.clearStaleScratch())
        assertEquals(listOf("other.txt"), dir.listFiles().orEmpty().map { it.name })

        // While a run is writing, its scratch file is in use and stays.
        app.device.setBackupTarget(folder)
        app.clock.instant = sunday(0)
        val writing = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        storage.during = { writing.complete(Unit); release.await() }
        val run = async(kotlinx.coroutines.Dispatchers.Default) { backup.run() }
        writing.await()
        assertEquals(0, backup.clearStaleScratch())
        release.complete(Unit)
        assertTrue(run.await() is BackupRun.Done)
        assertEquals(emptyList<String>(), storage.sourcesChanged)
    }

    @Test
    fun `a run first deletes the parts crashed runs left, of any day, and nothing else`() = runBlocking {
        app.device.setBackupTarget(folder)
        storage.files["recomp-backup-2026-09-06.zip.part"] = byteArrayOf(1)
        storage.files["recomp-backup-2026-09-13.zip.part"] = byteArrayOf(2)
        storage.files["recomp-backup-2026-09-13.zip"] = byteArrayOf(3)
        storage.files["notes.zip.part"] = byteArrayOf(4)
        app.clock.instant = sunday(1)
        assertTrue(backup.run() is BackupRun.Done)
        assertEquals(listOf("notes.zip.part", "recomp-backup-2026-09-13.zip"), storage.files.keys.sorted())
    }

    @Test
    fun `a manual export saved into the backup folder is never counted or deleted by the rotation`() = runBlocking {
        app.device.setBackupTarget(folder)
        val export = BackupManager.fileName(LocalDate.of(2026, 9, 6))
        assertEquals("recomp-export-2026-09-06.zip", export)
        assertNull(BackupRotation.dateOf(export))
        storage.files[export] = byteArrayOf(9)
        // Ten weeks of automatic backups, one on the export's own day: the export outlives them all.
        (0L until 10).forEach { week ->
            app.clock.instant = sunday(week)
            assertTrue(backup.run() is BackupRun.Done)
        }
        assertTrue(export in storage.files)
        assertEquals(8, storage.files.keys.count { BackupRotation.dateOf(it) != null })
    }

    @Test
    fun `a new backup the provider renamed is still the one kept, found by its address`() = runBlocking {
        app.device.setBackupTarget(folder)
        storage.renameTaken = true
        // The day already has two files; this run's lands as "(2)".
        storage.files["recomp-backup-2026-09-13.zip"] = byteArrayOf(1)
        storage.files["recomp-backup-2026-09-13 (1).zip"] = byteArrayOf(2)
        app.clock.instant = sunday(1)
        assertTrue(backup.run() is BackupRun.Done)
        val left = storage.files.keys.filter { BackupRotation.dateOf(it) != null }
        assertEquals(listOf("recomp-backup-2026-09-13 (2).zip"), left)
        assertTrue("the new backup, not an old one", storage.files.getValue(left.single()).size > 2)
    }
}
