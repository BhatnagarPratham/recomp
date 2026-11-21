package app.recomp.data.body

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared InBody screenshots (IB-03, TRD §6.7), kept as files in app-private storage under `files/scan_images`. They
 * never leave the phone: no AI reads them, nothing uploads them, and Android's backup leaves them out, since its rules
 * (`data_extraction_rules.xml`) list only the database and the settings. A scan's `image_path` is the file's name.
 */
@Singleton
class ScanImages(private val dir: File) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, DIR))

    /**
     * Copies a shared image in as soon as it arrives, since the permission to read it ends with the share. Null when
     * it isn't an image this can keep, or is larger than [MAX_BYTES].
     */
    suspend fun stage(input: InputStream, mimeType: String?): String? = withContext(Dispatchers.IO) {
        val ext = EXTENSIONS[mimeType?.lowercase()] ?: return@withContext null
        dir.mkdirs()
        val name = "${UUID.randomUUID()}.$ext"
        val out = File(dir, name)
        var total = 0L
        val ok = input.use { src ->
            out.outputStream().use { dst ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) return@use false
                    dst.write(buf, 0, n)
                }
                total > 0
            }
        }
        if (ok) name else null.also { out.delete() }
    }

    /** The file for a stored name, or null for a name that isn't one of ours. */
    fun file(name: String): File? = name.takeIf { NAME.matches(it) }?.let { File(dir, it) }?.takeIf { it.isFile }

    fun delete(name: String?) {
        name?.let(::file)?.delete()
    }

    /** Removes images no scan keeps (a share you backed out of) once they're older than [olderThanMs]. */
    suspend fun clean(kept: Set<String>, now: Long, olderThanMs: Long = STAGED_FOR_MS) = withContext(Dispatchers.IO) {
        dir.listFiles().orEmpty().filter { it.name !in kept && now - it.lastModified() > olderThanMs }.forEach { it.delete() }
    }

    companion object {
        const val DIR = "scan_images"
        const val MAX_BYTES = 20L * 1024 * 1024
        const val STAGED_FOR_MS = 24 * 3_600_000L
        private val EXTENSIONS = mapOf("image/jpeg" to "jpg", "image/jpg" to "jpg", "image/png" to "png", "image/webp" to "webp")
        private val NAME = Regex("[0-9a-f-]{36}\\.(jpg|png|webp)")
    }
}
