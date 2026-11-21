package app.recomp.data

import app.recomp.data.db.PhotoPose
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
}
