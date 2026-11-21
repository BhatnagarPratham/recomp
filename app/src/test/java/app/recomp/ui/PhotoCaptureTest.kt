package app.recomp.ui

import app.recomp.ui.body.PhotoCapture
import app.recomp.ui.body.PhotoCapture.Step
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** BM-04: the camera permission comes before the intent, and the camera can only write into files/photos/. */
class PhotoCaptureTest {
    @Test
    fun `the camera is launched only once the permission is granted`() {
        assertEquals(Step.ASK_PERMISSION, PhotoCapture.next(granted = false, askedBefore = false))
        assertEquals(Step.LAUNCH_CAMERA, PhotoCapture.next(granted = true, askedBefore = false))
        assertEquals(Step.LAUNCH_CAMERA, PhotoCapture.next(granted = true, askedBefore = true))
        assertEquals(Step.EXPLAIN_DENIED, PhotoCapture.next(granted = false, askedBefore = true))
    }

    @Test
    fun `the manifest declares the camera permission and a provider for files-photos only`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.CAMERA"))
        assertTrue(manifest.contains("\${applicationId}.photos"))
        assertEquals("app.recomp.photos", PhotoCapture.authority("app.recomp"))
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/xml/photo_paths.xml"))
        val paths = doc.documentElement.childNodes.let { nodes -> (0 until nodes.length).mapNotNull { nodes.item(it) as? Element } }
        assertEquals(listOf("files-path" to "photos/"), paths.map { it.tagName to it.getAttribute("path") })
    }
}
