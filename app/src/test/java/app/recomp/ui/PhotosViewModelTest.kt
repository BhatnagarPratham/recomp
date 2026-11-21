package app.recomp.ui

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import app.recomp.data.TestApp
import app.recomp.data.db.PhotoPose
import app.recomp.ui.body.PhotoText
import app.recomp.ui.body.PhotosViewModel
import java.io.File
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
import org.robolectric.Shadows.shadowOf

/**
 * The capture's state lives in the saved state (M17 review S1): when Android recreates Recomp while the camera
 * app is in front, the photo it took is still saved, and nothing is left behind when there's no camera app.
 */
@RunWith(RobolectricTestRunner::class)
class PhotosViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: TestApp

    @Before
    fun setUp() {
        app = TestApp(File(tmp.root, "app").apply { mkdirs() })
    }

    @After
    fun tearDown() = app.close()

    private fun viewModel(state: SavedStateHandle) = PhotosViewModel(app.photos, app.days, state)

    /** viewModelScope runs on the main looper, with the file work on IO in between. */
    private fun settle() = repeat(20) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(25)
    }

    @Test
    fun `a capture whose result arrives after the activity was recreated is still saved`() = runBlocking {
        val state = SavedStateHandle()
        val file = viewModel(state).startCapture(PhotoPose.SIDE)
        // The camera app writes the photo while Recomp is gone; the new screen gets a new ViewModel and the
        // restored saved state.
        file.writeBytes(ByteArray(64) { it.toByte() })
        val restored = SavedStateHandle(state.keys().associateWith { state.get<Any>(it) })
        viewModel(restored).finishCapture(ok = true)
        settle()

        val row = app.db.photoDao().all().single()
        assertEquals(PhotoPose.SIDE, row.pose)
        assertEquals("photos/${file.name}", row.filePath)
        assertTrue(file.exists())
        assertNull(restored.get<String>(PhotosViewModel.CAPTURE_FILE))
    }

    @Test
    fun `a cancelled capture, or a phone with no camera app, leaves no file and no row`() = runBlocking {
        val vm = viewModel(SavedStateHandle())
        val cancelled = vm.startCapture(PhotoPose.FRONT).apply { writeBytes(ByteArray(8)) }
        vm.finishCapture(ok = false)
        settle()
        assertFalse(cancelled.exists())

        // A capture target can exist before the launch fails (some providers touch it); it goes too.
        val none = vm.startCapture(PhotoPose.BACK).apply { writeBytes(ByteArray(0)) }
        vm.cameraMissing()
        settle()
        assertFalse(none.exists())
        assertEquals(PhotoText.NO_CAMERA, vm.message.value)
        assertTrue(app.db.photoDao().all().isEmpty())
    }

    @Test
    fun `a saved state naming a file outside the photos folder is ignored`() = runBlocking {
        val outside = File(app.photos.dir.parentFile, "keep.txt").apply { writeText("keep") }
        val state = SavedStateHandle(mapOf(PhotosViewModel.CAPTURE_POSE to "FRONT", PhotosViewModel.CAPTURE_FILE to "../keep.txt"))
        viewModel(state).finishCapture(ok = false)
        settle()
        assertTrue(outside.exists())
        assertTrue(app.db.photoDao().all().isEmpty())
    }
}
