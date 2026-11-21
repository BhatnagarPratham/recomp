package app.recomp.data.repo

import android.content.Context
import app.recomp.data.db.PhotoDao
import app.recomp.data.db.PhotoPose
import app.recomp.data.db.ProgressPhotoEntity
import app.recomp.data.prefs.DayBoundary
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** A photo row with whether its file is still there (a missing one shows a placeholder). */
data class PhotoItem(val photo: ProgressPhotoEntity, val file: File, val present: Boolean)

/**
 * Progress photos (BM-04, PRD §12.13). Images live only in app-private `files/photos/`: never in the gallery,
 * never in Android's own backup (its rules are include-only), and never sent to AI. They leave the phone only
 * in a backup with *Include photos*.
 */
@Singleton
class PhotoRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val dao: PhotoDao,
    private val days: DayBoundary,
) {
    private val filesDir: File = context.filesDir

    /** `files/photos/`, created on first use. */
    val dir: File get() = File(filesDir, PHOTO_DIR).apply { mkdirs() }

    fun observe(): Flow<List<ProgressPhotoEntity>> = dao.observeAll()

    fun observeLatestDate(): Flow<LocalDate?> = dao.observeLatestDate()

    fun fileOf(photo: ProgressPhotoEntity): File = File(filesDir, photo.filePath)

    suspend fun items(photos: List<ProgressPhotoEntity>): List<PhotoItem> = withContext(Dispatchers.IO) {
        photos.map { PhotoItem(it, fileOf(it), fileOf(it).isFile) }
    }

    /** A new, empty target file for the camera to write into. Its name never says anything about you. */
    fun newCaptureFile(pose: PhotoPose, now: Long = System.currentTimeMillis()): File =
        File(dir, "${pose.name.lowercase()}-$now.jpg")

    /**
     * Records a capture once the camera has written [file]. A cancelled or empty capture leaves no row and no file.
     * Returns the new row's id, or null.
     */
    suspend fun saveCapture(file: File, pose: PhotoPose, takenAt: Long = System.currentTimeMillis()): Long? = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() == 0L) {
            file.delete()
            return@withContext null
        }
        require(file.canonicalPath.startsWith(dir.canonicalPath + File.separator)) { "Photos are only stored in $PHOTO_DIR" }
        dao.insert(ProgressPhotoEntity(logDate = days.of(takenAt), takenAt = takenAt, pose = pose, filePath = "$PHOTO_DIR/${file.name}"))
    }

    /** Deletes the row and its file. */
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        val photo = dao.get(id) ?: return@withContext
        dao.delete(photo)
        fileOf(photo).delete()
    }

    companion object {
        /** Under `files/`; Android's backup rules never include it. */
        const val PHOTO_DIR = "photos"
        const val SUGGEST_EVERY_DAYS = 28L

        /** Body suggests new photos every 4 weeks, with no notification; and at the start, before any. */
        fun suggest(latest: LocalDate?, today: LocalDate): Boolean =
            latest == null || ChronoUnit.DAYS.between(latest, today) >= SUGGEST_EVERY_DAYS

        /** The dates that have a photo of [pose], newest first, for *Compare*. */
        fun datesFor(photos: List<ProgressPhotoEntity>, pose: PhotoPose): List<LocalDate> =
            photos.filter { it.pose == pose }.map { it.logDate }.distinct().sortedDescending()
    }
}
