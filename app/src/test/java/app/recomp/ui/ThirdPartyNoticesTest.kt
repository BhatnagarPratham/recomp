package app.recomp.ui

import androidx.test.core.app.ApplicationProvider
import app.recomp.ui.food.ThirdPartyNotices
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** M18 review S3: the bundled OCR model and native libraries ship with their licences, shown from Food data. */
@RunWith(RobolectricTestRunner::class)
class ThirdPartyNoticesTest {
    @Test
    fun `the notices name every bundled component and carry the licence texts`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val text = ThirdPartyNotices.read(context)
        listOf("Tesseract", "tessdata_fast", "Tesseract4Android", "ZXing", "CameraX", "Leptonica", "libpng", "zlib", "Independent JPEG Group", "Open Database License")
            .forEach { assertTrue(it, text.contains(it)) }
        assertTrue(text.contains("Version 2.0, January 2004"))
        assertTrue(text.contains("PNG Reference Library License version 2"))
        // The model's own licence sits beside it.
        val model = context.assets.open("tessdata/LICENSE").bufferedReader().use { it.readText() }
        assertTrue(model.contains("Apache License") && model.contains("END OF TERMS AND CONDITIONS"))
    }
}
