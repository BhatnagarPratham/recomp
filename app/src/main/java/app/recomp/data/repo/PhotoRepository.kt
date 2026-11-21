// The platform ExifInterface's bugs that lint warns about were fixed in API 25; minSdk is 34, so no extra library.
@file:Suppress("ExifInterface")

package app.recomp.data.repo

import android.content.Context
import android.media.ExifInterface
import app.recomp.data.backup.BackupManager
import app.recomp.data.db.PhotoDao
import app.recomp.data.db.PhotoPaths
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

/**
 * A photo row with its file and whether it's still there (a missing one shows a placeholder). [file] is null when
 * the row's path isn't a valid photo path, and [rotation] is the EXIF orientation in degrees.
 */
data class PhotoItem(val photo: ProgressPhotoEntity, val file: File?, val present: Boolean, val rotation: Int = 0)

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
    private val backup: BackupManager,
) {
    private val filesDir: File = context.filesDir

    /** `files/photos/`, created on first use. */
    val dir: File get() = File(filesDir, PHOTO_DIR).apply { mkdirs() }

    fun observe(): Flow<List<ProgressPhotoEntity>> = dao.observeAll()

    fun observeLatestDate(): Flow<LocalDate?> = dao.observeLatestDate()

    /** The row's file, only when its path is `photos/<name>.jpg` and really inside that folder; otherwise null. */
    fun fileOf(photo: ProgressPhotoEntity): File? {
        val name = PhotoPaths.name(photo.filePath) ?: return null
        return File(dir, name).takeIf { inside(it) }
    }

    private fun inside(file: File) = file.canonicalPath.startsWith(dir.canonicalPath + File.separator)

    suspend fun items(photos: List<ProgressPhotoEntity>): List<PhotoItem> = withContext(Dispatchers.IO) {
        photos.map { photo ->
            val file = fileOf(photo)
            val present = file?.isFile == true
            PhotoItem(photo, file, present, if (present) rotationOf(file!!) else 0)
        }
    }

    /** A new, empty target file for the camera to write into. Its name never says anything about you. */
    fun newCaptureFile(pose: PhotoPose, now: Long = System.currentTimeMillis()): File =
        File(dir, "${pose.name.lowercase()}-$now.jpg")

    /** The capture file a pending capture named, for after the activity was recreated; null for anything else. */
    fun captureFile(name: String): File? = name.takeIf { PhotoPaths.NAME.matches(it) }?.let { File(dir, it) }?.takeIf { inside(it) }

    /**
     * Records a capture once the camera has written [file]. A cancelled or empty capture leaves no row and no file.
     * The camera's location tags are removed. A retake replaces the same pose's photo from the same day, which
     * the screen asks about first, so the grid always shows every photo. Returns the new row's id, or null.
     */
    suspend fun saveCapture(file: File, pose: PhotoPose, takenAt: Long = System.currentTimeMillis()): Long? = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() == 0L) {
            file.delete()
            return@withContext null
        }
        require(inside(file) && PhotoPaths.NAME.matches(file.name)) { "Photos are only stored in $PHOTO_DIR" }
        stripLocation(file)
        val date = days.of(takenAt)
        val replaced = dao.all().filter { it.logDate == date && it.pose == pose }
        val id = dao.insert(ProgressPhotoEntity(logDate = date, takenAt = takenAt, pose = pose, filePath = "$PHOTO_DIR/${file.name}"))
        replaced.forEach { delete(it.id) }
        id
    }

    /** Whether a photo of [pose] was already taken on [date], so a new one would replace it. */
    suspend fun has(pose: PhotoPose, date: LocalDate): Boolean = dao.all().any { it.logDate == date && it.pose == pose }

    /**
     * Deletes the photo: its file first, so a file that can't be removed keeps its row (and stays visible to
     * delete again), then the row, then its copy in the safety copy. Returns false when the file couldn't go.
     */
    suspend fun delete(id: Long): Boolean = withContext(Dispatchers.IO) {
        val photo = dao.get(id) ?: return@withContext true
        val file = fileOf(photo)
        if (file != null && file.exists() && !file.delete()) return@withContext false
        dao.delete(photo)
        runCatching { backup.dropFromSafetyCopy(photo.filePath) }
        true
    }

    /**
     * Removes files in `files/photos/` that no row points at and that are older than [minAgeMs] (so a capture
     * the camera is still writing is never touched), unless an import is finishing. Such files can't be seen or
     * deleted in the app. Returns how many went.
     */
    suspend fun sweepOrphans(now: Long = System.currentTimeMillis(), minAgeMs: Long = ORPHAN_AGE_MS): Int = withContext(Dispatchers.IO) {
        if (backup.importInProgress()) return@withContext 0
        val named = dao.all().mapNotNull { PhotoPaths.name(it.filePath) }.toSet()
        dir.listFiles().orEmpty().count { f -> f.isFile && f.name !in named && now - f.lastModified() >= minAgeMs && f.delete() }
    }

    companion object {
        /** Under `files/`; Android's backup rules never include it. */
        const val PHOTO_DIR = PhotoPaths.DIR
        const val ORPHAN_AGE_MS = 24 * 60 * 60 * 1000L

        private val LOCATION_TAGS = listOf(
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_AREA_INFORMATION, ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF,
            ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF, ExifInterface.TAG_GPS_DEST_BEARING, ExifInterface.TAG_GPS_DEST_BEARING_REF,
        )

        /** Removes the GPS tags a camera app with location tagging on writes, so a photo never carries where it was taken. */
        fun stripLocation(file: File) {
            runCatching {
                val exif = ExifInterface(file.path)
                if (LOCATION_TAGS.none { exif.getAttribute(it) != null }) return
                LOCATION_TAGS.forEach { exif.setAttribute(it, null) }
                exif.saveAttributes()
            }
        }

        /** How far to turn a photo to show it upright, from its EXIF orientation (BitmapFactory ignores it). */
        fun degrees(orientation: Int): Int = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

        fun rotationOf(file: File): Int =
            runCatching { degrees(ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) }.getOrDefault(0)
        const val SUGGEST_EVERY_DAYS = 28L

        /** Body suggests new photos every 4 weeks, with no notification; and at the start, before any. */
        fun suggest(latest: LocalDate?, today: LocalDate): Boolean =
            latest == null || ChronoUnit.DAYS.between(latest, today) >= SUGGEST_EVERY_DAYS

        /** The dates that have a photo of [pose], newest first, for *Compare*. */
        fun datesFor(photos: List<ProgressPhotoEntity>, pose: PhotoPose): List<LocalDate> =
            photos.filter { it.pose == pose }.map { it.logDate }.distinct().sortedDescending()
    }
}
