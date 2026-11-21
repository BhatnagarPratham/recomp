package app.recomp.ui

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import app.recomp.data.TestApp
import app.recomp.data.db.ScanDraftEntity
import app.recomp.data.db.ScanSource
import app.recomp.domain.body.SinceMeal
import app.recomp.ui.body.ScanDone
import app.recomp.ui.body.ScanViewModel
import java.io.ByteArrayInputStream
import java.io.File
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
import org.robolectric.Shadows.shadowOf

/**
 * A shared InBody screenshot's scan form (IB-03, PRD §12.11): prefilled from Health Connect's InBody draft from the
 * same time (IB-07) when there is one, and saved with the image. Invented numbers.
 */
@RunWith(RobolectricTestRunner::class)
class ScanShareViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var app: TestApp

    @Before
    fun setUp() {
        app = TestApp(File(tmp.root, "app").apply { mkdirs() })
    }

    @After
    fun tearDown() = app.close()

    private fun await(done: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!done()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() < until) { "Timed out" }
            Thread.sleep(5)
        }
    }

    private fun image(): String = runBlocking { app.scanImages.stage(ByteArrayInputStream(ByteArray(512) { 3 }), "image/png")!! }

    private fun viewModel(image: String) = ScanViewModel(SavedStateHandle(mapOf("image" to image)), app.body, app.health, app.scanImages)

    @Test
    fun `a shared screenshot is prefilled from the InBody draft from the same time, which the save closes`() {
        val measuredAt = System.currentTimeMillis() - 20 * 60_000L
        val draftId = runBlocking {
            // An older measurement too, from the day before: not this one.
            app.db.healthDao().insert(ScanDraftEntity(measuredAt = measuredAt - 26 * 3_600_000L, weightG = 96_000, bodyFatPct = 31.0, bmrKcal = 1780))
            app.db.healthDao().insert(ScanDraftEntity(measuredAt = measuredAt, weightG = 95_000, bodyFatPct = 30.5, bmrKcal = 1796))
        }
        val name = image()
        val vm = viewModel(name)
        await { vm.form.value.loaded && vm.image.value != null }
        val form = vm.form.value
        assertEquals(measuredAt, form.at)
        assertEquals("95.0", form.weight)
        assertEquals("30.5", form.fatPct)
        assertEquals("1796", form.bmr)

        vm.update { it.copy(muscle = "33", visceral = "8", sinceMeal = SinceMeal.OVER_3H) }
        vm.save()
        await { vm.done.value != null }
        assertTrue(vm.done.value is ScanDone.Saved)
        val scan = runBlocking { app.body.observeScans().first().single() }
        assertEquals(name, scan.imagePath)
        assertEquals(ScanSource.HEALTH_CONNECT, scan.source)
        assertEquals(1796, scan.bmrKcal)
        // The draft is done; the older one still waits.
        assertNull(runBlocking { app.health.draft(draftId) })
        assertEquals(1, runBlocking { app.db.healthDao().drafts().size })
    }

    @Test
    fun `without a draft from the same time, the form starts empty and the scan is typed in by hand`() {
        runBlocking { app.db.healthDao().insert(ScanDraftEntity(measuredAt = System.currentTimeMillis() - 30 * 3_600_000L, weightG = 96_000)) }
        val name = image()
        val vm = viewModel(name)
        await { vm.form.value.loaded && vm.image.value != null }
        assertEquals("", vm.form.value.weight)
        vm.update { it.copy(weight = "95", muscle = "33", fatMass = "29", visceral = "8", sinceMeal = SinceMeal.OVER_3H) }
        vm.save()
        await { vm.done.value != null }
        val scan = runBlocking { app.body.observeScans().first().single() }
        assertEquals(name, scan.imagePath)
        assertEquals(ScanSource.MANUAL, scan.source)
        assertEquals(1, runBlocking { app.db.healthDao().drafts().size })
    }
}
