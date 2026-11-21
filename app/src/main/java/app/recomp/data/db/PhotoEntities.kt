package app.recomp.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/** BM-04's poses. */
enum class PhotoPose { FRONT, SIDE, BACK }

/**
 * A progress photo (BM-04, TRD §4). The image is a file in app-private storage, [filePath] relative to
 * `files/` (e.g. `photos/2026-11-21-front-1700000000000.jpg`), so it never reaches the gallery or Android's
 * own backup. A row whose file has gone missing shows a placeholder.
 */
@Entity(tableName = "progress_photo", indices = [Index("log_date")])
data class ProgressPhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "log_date") val logDate: LocalDate,
    @ColumnInfo(name = "taken_at") val takenAt: Long,
    val pose: PhotoPose,
    @ColumnInfo(name = "file_path") val filePath: String,
)

@Dao
interface PhotoDao {
    @Query("SELECT * FROM progress_photo ORDER BY taken_at DESC, id DESC")
    fun observeAll(): Flow<List<ProgressPhotoEntity>>

    @Query("SELECT * FROM progress_photo ORDER BY taken_at DESC, id DESC")
    suspend fun all(): List<ProgressPhotoEntity>

    @Query("SELECT * FROM progress_photo WHERE id = :id")
    suspend fun get(id: Long): ProgressPhotoEntity?

    @Query("SELECT MAX(log_date) FROM progress_photo")
    fun observeLatestDate(): Flow<LocalDate?>

    @Insert
    suspend fun insert(photo: ProgressPhotoEntity): Long

    @Delete
    suspend fun delete(photo: ProgressPhotoEntity)
}
