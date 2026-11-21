package app.recomp.data.food

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import app.recomp.domain.ai.AiImage
import app.recomp.domain.ai.PhotoScale
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Photos for camera food (NU-19, NU-20; TRD §10 M18). Every photo is decoded already downscaled to about 1,500 px
 * ([PhotoScale]), turned upright from its EXIF orientation by [ImageDecoder], and re-encoded as JPEG, so what an
 * AI sees and what a draft keeps is small. Meal photos live in app-private storage only while their meal is a draft
 * (PRD §12.14): saving the meal deletes its photo, and [sweep] removes any a draft no longer points at.
 */
@Singleton
class PhotoStore @Inject constructor(@ApplicationContext private val context: Context) {
    val dir: File get() = File(context.filesDir, DIR).apply { mkdirs() }

    /** A photo from the gallery ([uri]) or our camera ([file]), scaled to [maxEdge]. */
    suspend fun decode(uri: Uri, maxEdge: Int = PhotoScale.MAX_EDGE): Bitmap = withContext(Dispatchers.IO) {
        decode(ImageDecoder.createSource(context.contentResolver, uri), maxEdge)
    }

    suspend fun decode(file: File, maxEdge: Int = PhotoScale.MAX_EDGE): Bitmap = withContext(Dispatchers.IO) {
        decode(ImageDecoder.createSource(file), maxEdge)
    }

    private fun decode(source: ImageDecoder.Source, maxEdge: Int): Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val (w, h) = PhotoScale.size(info.size.width, info.size.height, maxEdge)
        decoder.setTargetSize(w, h)
        // Software, so it can be compressed and handed to Tesseract.
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }

    /** The photo as an AI request carries it. */
    fun image(bitmap: Bitmap): AiImage = AiImage(MEDIA_TYPE, jpeg(bitmap))

    fun image(path: String): AiImage? = File(path).takeIf { it.isFile && it.parentFile == dir }?.let { AiImage(MEDIA_TYPE, it.readBytes()) }

    /** Keeps a meal photo for its draft; returns its path. */
    suspend fun keep(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        File(dir, "${UUID.randomUUID()}.jpg").also { it.writeBytes(jpeg(bitmap)) }.absolutePath
    }

    /** Deletes a kept photo. Paths outside [dir] are ignored, so a damaged backup can't point it anywhere else. */
    fun delete(path: String?) {
        path?.let(::File)?.takeIf { it.parentFile == dir }?.delete()
    }

    /**
     * Deletes kept photos no draft points at, once they're older than [graceMs]: a photo on a screen that hasn't
     * saved a draft yet is left alone for that long.
     */
    suspend fun sweep(referenced: Set<String>, now: Long = System.currentTimeMillis(), graceMs: Long = GRACE_MS) = withContext(Dispatchers.IO) {
        dir.listFiles().orEmpty().filter { it.absolutePath !in referenced && now - it.lastModified() > graceMs }.forEach { it.delete() }
    }

    private fun jpeg(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, PhotoScale.JPEG_QUALITY, it) }.toByteArray()

    companion object {
        const val DIR = "meal_photos"
        const val MEDIA_TYPE = "image/jpeg"
        const val GRACE_MS = 24 * 60 * 60_000L
    }
}
