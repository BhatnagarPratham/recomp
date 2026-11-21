package app.recomp.data.food

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.googlecode.tesseract.android.TessBaseAPI
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Reads a label photo's text on the phone (NU-19). A seam, so tests and the AI path don't need the native library. */
fun interface LabelReader {
    suspend fun read(photo: Bitmap): String
}

/**
 * Tesseract, on the phone, with nothing sent anywhere (PRD §12.14, TRD §7). The English `tessdata_fast` model
 * (Apache-2.0, `assets/tessdata`) is copied to app storage once, since Tesseract reads it from a file.
 */
@Singleton
class TesseractLabelReader @Inject constructor(@ApplicationContext private val context: Context) : LabelReader {
    private val lock = Mutex()

    override suspend fun read(photo: Bitmap): String = lock.withLock {
        withContext(Dispatchers.Default) {
            val root = model()
            val api = TessBaseAPI()
            try {
                check(api.init(root.absolutePath, LANGUAGE)) { "Tesseract didn't start" }
                api.pageSegMode = PAGE_SEG_MODE
                api.setImage(gray(photo))
                api.utF8Text.orEmpty()
            } finally {
                api.recycle()
            }
        }
    }

    /** The model's folder, copying the asset the first time, or when [MODEL_VERSION] says the bundled one changed. */
    private fun model(): File {
        val root = File(context.filesDir, "tesseract")
        val target = File(root, "tessdata/$LANGUAGE.traineddata")
        val marker = File(root, "version")
        if (!target.isFile || marker.takeIf { it.isFile }?.readText() != MODEL_VERSION) {
            target.parentFile!!.mkdirs()
            val part = File(target.parentFile, "${target.name}.part")
            context.assets.open("tessdata/$LANGUAGE.traineddata").use { input -> part.outputStream().use { input.copyTo(it) } }
            check(part.renameTo(target)) { "Couldn't place the OCR model" }
            marker.writeText(MODEL_VERSION)
        }
        return root
    }

    /** Grey, which Tesseract reads at least as well as colour and faster. */
    private fun gray(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) })
        return out
    }

    companion object {
        const val LANGUAGE = "eng"

        /** tessdata_fast's commit for the bundled model; change it with the model so phones copy the new one. */
        const val MODEL_VERSION = "tessdata_fast-8741641"

        /**
         * One uniform block of text: a nutrition table read row by row. M18's trial on invented labels (FSSAI, EU
         * kJ/kcal, US, and a photographed one) read every row this way in 0.3–0.4 s; auto (PSM 3) and columns
         * (PSM 4) lost rows, and scaling up 2x only doubled the time.
         */
        const val PAGE_SEG_MODE = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK

        /** Label photos are decoded larger than AI photos, since small print needs the pixels. */
        const val OCR_MAX_EDGE = 2_000
    }
}
