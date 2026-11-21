package app.recomp.data

import android.media.ExifInterface
import app.recomp.data.db.PhotoPose
import app.recomp.data.db.ProgressPhotoEntity
import app.recomp.ui.body.tiles
import java.util.Base64
import app.recomp.data.repo.PhotoDelete
import app.recomp.data.repo.PhotoRepository
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Progress photos (BM-04) against a real database and app-private files. The "images" are invented bytes. */
@RunWith(RobolectricTestRunner::class)
class PhotoFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: TestApp
    private val photos get() = app.photos

    @Before
    fun setUp() {
        app = TestApp(File(tmp.root, "app").apply { mkdirs() })
    }

    @After
    fun tearDown() = app.close()

    private fun capture(pose: PhotoPose, at: Long, bytes: Int = 64): File =
        photos.newCaptureFile(pose, at).apply { writeBytes(ByteArray(bytes) { it.toByte() }) }

    @Test
    fun `a capture is saved under files-photos with a row, and delete removes both`() = runBlocking {
        val file = capture(PhotoPose.FRONT, 1_000)
        assertEquals("photos", file.parentFile!!.name)
        assertFalse("never named after you", file.name.contains("recomp", ignoreCase = true))
        val id = photos.saveCapture(file, PhotoPose.FRONT, takenAt = 1_000)!!
        val row = photos.observe().first().single()
        assertEquals(id, row.id)
        assertEquals("photos/${file.name}", row.filePath)
        assertTrue(photos.items(listOf(row)).single().present)

        photos.delete(id)
        assertTrue(photos.observe().first().isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun `a cancelled capture leaves nothing behind`() = runBlocking {
        val empty = photos.newCaptureFile(PhotoPose.SIDE, 2_000).apply { writeBytes(ByteArray(0)) }
        assertNull(photos.saveCapture(empty, PhotoPose.SIDE))
        assertFalse(empty.exists())
        assertNull(photos.saveCapture(photos.newCaptureFile(PhotoPose.SIDE, 3_000), PhotoPose.SIDE))
        assertTrue(photos.observe().first().isEmpty())
    }

    @Test
    fun `a row whose file went missing is still listed, marked missing`() = runBlocking {
        val file = capture(PhotoPose.BACK, 4_000)
        photos.saveCapture(file, PhotoPose.BACK, takenAt = 4_000)
        file.delete()
        val item = photos.items(photos.observe().first()).single()
        assertFalse(item.present)
    }

    @Test
    fun `new photos are suggested every 4 weeks, and compare lists a pose's dates`() {
        val today = LocalDate.of(2026, 11, 21)
        assertTrue(PhotoRepository.suggest(null, today))
        assertFalse(PhotoRepository.suggest(today.minusDays(27), today))
        assertTrue(PhotoRepository.suggest(today.minusDays(28), today))
    }

    @Test
    fun `compare lists one pose's dates, newest first, once each`() = runBlocking {
        val day = 86_400_000L
        photos.saveCapture(capture(PhotoPose.FRONT, 1_000), PhotoPose.FRONT, takenAt = 1_000)
        photos.saveCapture(capture(PhotoPose.SIDE, 1_001), PhotoPose.SIDE, takenAt = 1_001)
        photos.saveCapture(capture(PhotoPose.FRONT, 1_000 + 30 * day), PhotoPose.FRONT, takenAt = 1_000 + 30 * day)
        photos.saveCapture(capture(PhotoPose.FRONT, 1_002 + 30 * day), PhotoPose.FRONT, takenAt = 1_002 + 30 * day)
        val all = photos.observe().first()
        val fronts = PhotoRepository.datesFor(all, PhotoPose.FRONT)
        assertEquals(2, fronts.size)
        assertTrue(fronts[0].isAfter(fronts[1]))
        assertEquals(1, PhotoRepository.datesFor(all, PhotoPose.SIDE).size)
        assertTrue(PhotoRepository.datesFor(all, PhotoPose.BACK).isEmpty())
        assertEquals(fronts[0], photos.observeLatestDate().first())
    }

    // Review fixes (M17 review S2, S3, N1, N4)

    @Test
    fun `a row whose path leaves the photos folder never reads or deletes another file`() = runBlocking {
        val canary = File(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "datastore/canary.txt").apply { parentFile!!.mkdirs(); writeText("keep") }
        listOf("datastore/canary.txt", "photos/../datastore/canary.txt", "photos/a/../../datastore/canary.txt").forEach { path ->
            val id = app.db.photoDao().insert(ProgressPhotoEntity(logDate = LocalDate.of(2026, 11, 1), takenAt = 1, pose = PhotoPose.FRONT, filePath = path))
            val row = app.db.photoDao().get(id)!!
            assertNull(photos.fileOf(row))
            assertFalse(photos.items(listOf(row)).single().present)
            assertEquals(PhotoDelete.DELETED, photos.delete(id))
            assertNull(app.db.photoDao().get(id))
            assertEquals(path, "keep", canary.readText())
        }
    }

    @Test
    fun `a file that can't be deleted keeps its row, so it can be deleted again`() = runBlocking {
        val id = photos.saveCapture(capture(PhotoPose.BACK, 3_000), PhotoPose.BACK, takenAt = 3_000)!!
        val row = app.db.photoDao().get(id)!!
        // A non-empty directory where the file was can't be removed with File.delete().
        val file = photos.fileOf(row)!!
        file.delete()
        File(file, "stuck").apply { parentFile!!.mkdirs(); writeText("x") }
        assertEquals(PhotoDelete.FILE_KEPT, photos.delete(id))
        assertEquals(id, app.db.photoDao().get(id)?.id)
        File(file, "stuck").delete()
        assertEquals(PhotoDelete.DELETED, photos.delete(id))
        assertNull(app.db.photoDao().get(id))
    }

    /** The first millisecond of the log day after [t]'s, found by halving (the day can start at any hour). */
    private suspend fun nextDayStart(t: Long): Long {
        val day = app.days.of(t)
        var lo = t
        var hi = t + 25 * 60 * 60 * 1000L
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (app.days.of(mid) == day) lo = mid else hi = mid
        }
        return hi
    }

    @Test
    fun `a retake checked before the day changed and saved after it replaces nothing`() = runBlocking {
        val midnight = nextDayStart(10L * 24 * 60 * 60 * 1000)
        val before = midnight - 60_000
        val after = midnight + 60_000
        val dayOne = app.days.of(before)
        val dayTwo = app.days.of(after)
        assertTrue(dayOne != dayTwo)
        // A front photo on each day: one from late on day one, and one already taken just after the change.
        val late = capture(PhotoPose.FRONT, before - 60_000).also { photos.saveCapture(it, PhotoPose.FRONT, takenAt = before - 60_000) }
        val early = capture(PhotoPose.FRONT, midnight).also { photos.saveCapture(it, PhotoPose.FRONT, takenAt = midnight) }
        // The screen asked "Replace today's front photo?" on day one; the camera came back on day two.
        val retake = capture(PhotoPose.FRONT, after)
        photos.saveCapture(retake, PhotoPose.FRONT, takenAt = after, checkedFor = dayOne)
        val rows = photos.observe().first()
        assertEquals(setOf(late, early, retake).map { "photos/${it.name}" }.toSet(), rows.map { it.filePath }.toSet())
        assertEquals("filed under the save's day", dayTwo, rows.single { it.filePath == "photos/${retake.name}" }.logDate)
        assertTrue(late.exists() && early.exists())

        // Checked and saved on the same day, the retake replaces that day's photo as asked.
        val again = capture(PhotoPose.FRONT, after + 60_000)
        photos.saveCapture(again, PhotoPose.FRONT, takenAt = after + 60_000, checkedFor = dayTwo)
        assertEquals(setOf(late, again).map { "photos/${it.name}" }.toSet(), photos.observe().first().map { it.filePath }.toSet())
        assertFalse(early.exists() || retake.exists())
    }

    @Test
    fun `a delete that can't update the safety copy says so, and the photo is still gone from the phone`() = runBlocking {
        val file = capture(PhotoPose.SIDE, 2_000)
        val id = photos.saveCapture(file, PhotoPose.SIDE, takenAt = 2_000)!!
        // A safety copy that can't be rewritten (here, unreadable; on a phone, a full disk).
        app.backup.safetyCopyFile.apply { parentFile?.mkdirs() }.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(PhotoDelete.SAFETY_COPY_KEPT, photos.delete(id))
        assertNull(app.db.photoDao().get(id))
        assertFalse(file.exists())

        // Without a safety copy, or with one it can update, it's a plain delete.
        app.backup.safetyCopyFile.delete()
        val other = photos.saveCapture(capture(PhotoPose.BACK, 3_000), PhotoPose.BACK, takenAt = 3_000)!!
        assertEquals(PhotoDelete.DELETED, photos.delete(other))
    }

    @Test
    fun `a retake on the same day replaces that pose's photo, and every photo of a date gets a tile`() = runBlocking {
        val day = 1_000L
        val first = capture(PhotoPose.FRONT, day)
        photos.saveCapture(first, PhotoPose.FRONT, takenAt = day)
        assertTrue(photos.has(PhotoPose.FRONT, app.days.of(day)))
        assertFalse(photos.has(PhotoPose.SIDE, app.days.of(day)))
        val second = capture(PhotoPose.FRONT, day + 60_000)
        photos.saveCapture(second, PhotoPose.FRONT, takenAt = day + 60_000)
        assertEquals(listOf("photos/${second.name}"), photos.observe().first().map { it.filePath })
        assertFalse(first.exists())

        // Two rows for one pose and date (an old backup, say) both show, so both can be opened and deleted.
        app.db.photoDao().insert(ProgressPhotoEntity(logDate = app.days.of(day), takenAt = day - 5, pose = PhotoPose.FRONT, filePath = "photos/front-older.jpg"))
        val items = photos.items(photos.observe().first())
        val shown = tiles(items)
        assertEquals(listOf(PhotoPose.FRONT, PhotoPose.FRONT, PhotoPose.SIDE, PhotoPose.BACK), shown.map { it.first })
        assertEquals(2, shown.count { it.second != null })
        assertEquals(items.map { it.photo.id }.toSet(), shown.mapNotNull { it.second?.photo?.id }.toSet())
    }

    @Test
    fun `files no row points at are swept once old, never while an import finishes, and never a fresh capture`() = runBlocking {
        val now = 10L * 24 * 3_600_000
        val kept = capture(PhotoPose.FRONT, 1).also { photos.saveCapture(it, PhotoPose.FRONT, takenAt = 1) }
        val orphan = File(photos.dir, "side-2.jpg").apply { writeBytes(ByteArray(8)); setLastModified(now - 2 * 24 * 3_600_000) }
        val fresh = File(photos.dir, "back-3.jpg").apply { writeBytes(ByteArray(8)); setLastModified(now - 60_000) }
        kept.setLastModified(now - 5 * 24 * 3_600_000)

        app.backup.incomingPhotos.mkdirs()
        assertEquals(0, photos.sweepOrphans(now))
        assertTrue(orphan.exists())
        app.backup.incomingPhotos.deleteRecursively()

        assertEquals(1, photos.sweepOrphans(now))
        assertFalse(orphan.exists())
        assertTrue(fresh.exists())
        assertTrue(kept.exists())
    }

    @Test
    fun `a capture loses the camera's location tags, and a sideways photo is turned upright`() = runBlocking {
        val file = photos.newCaptureFile(PhotoPose.FRONT, 4_000).apply { writeBytes(Base64.getDecoder().decode(TINY_JPEG)) }
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "12/1,58/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "77/1,35/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        assertEquals("N", ExifInterface(file.path).getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF))
        val id = photos.saveCapture(file, PhotoPose.FRONT, takenAt = 4_000)!!
        val exif = ExifInterface(file.path)
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
        assertEquals(90, photos.items(listOf(app.db.photoDao().get(id)!!)).single().rotation)
        assertEquals(listOf(0, 90, 180, 270), listOf(ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_ROTATE_270).map(PhotoRepository::degrees))
    }

    private companion object {
        /** An 8×8 grey JPEG made for this test: not a photo of anything. */
        const val TINY_JPEG = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDABALDA4MChAODQ4SERATGCgaGBYWGDEjJR0oOjM9PDkzODdASFxOQERXRTc4UG1RV19iZ2hnPk1xeXBkeFxlZ2P/wAALCAAIAAgBAREA/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/9oACAEBAAA/ACv/2Q=="
    }
}
