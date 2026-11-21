package app.recomp.data.backup

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import app.recomp.data.db.AppDatabase
import app.recomp.data.body.ScanImages
import app.recomp.data.db.PhotoPaths
import app.recomp.data.db.readers
import app.recomp.data.prefs.SettingsPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** A backup that can't be used, with a message for the person importing it. */
class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    companion object {
        fun damaged(what: String, cause: Throwable? = null) = BackupException("This backup is damaged: it has $what.", cause)
    }
}

/** `manifest.json` (TRD §6.5). [schemaVersion] is the Room version that wrote the file. */
@Serializable
data class BackupManifest(
    val format: String,
    val formatVersion: Int,
    val schemaVersion: Int,
    val exportedAt: Long,
    val appVersion: String,
    val rows: Map<String, Int>,
    /** Progress photos carried as `photos/<name>` entries (M17, format 2). Zero in a backup without them. */
    val photos: Int = 0,
    /** InBody scan images (M15) carried as `scan_images/<name>` entries, with the photos. Zero in a backup without them. */
    val scanImages: Int = 0,
) {
    fun count(table: String): Int = rows[table] ?: 0
}

/** What [BackupManager.finishPendingRestore] found at launch. */
enum class PendingOutcome {
    /** The database had been replaced; its settings are now written too. */
    FINISHED,

    /** The import stopped before its database commit, so nothing changed. */
    NOT_APPLIED,

    /** The database had been replaced, but the settings still couldn't be written. */
    SETTINGS_FAILED,
}

/** An import whose settings are still to be written (TRD §6.5): its database token and the settings. */
@Serializable
internal data class PendingRestore(val token: Int, val settings: Map<String, SettingValue>, val exportedAt: Long, val photos: Boolean = false, val scanImages: Boolean = false)

/** One setting with its DataStore type, so it comes back as the same kind of key. */
@Serializable
data class SettingValue(val type: String, val value: JsonElement)

/**
 * A backup that has been read, checked and upgraded into a scratch database (steps 1 to 3 of TRD §6.5),
 * waiting for you to confirm. [discard] it if you don't.
 */
class PreparedBackup internal constructor(
    val manifest: BackupManifest,
    internal val settings: Preferences,
    internal val scratch: File,
    /** The backup's photos, staged in app-private storage until the import commits; null when it has none. */
    internal val photos: File? = null,
    /** The backup's scan images, staged the same way; null when it has none. */
    internal val scanImages: File? = null,
)

/**
 * Export and import of all data (DA-01, TRD §6.5): every Room table and every setting except the ones
 * that never leave the device. Restore replaces everything in one transaction, after a safety copy.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val prefs: SettingsPrefs,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The data as it was just before the last import (PRD §7.14). One copy; the next import replaces it. */
    val safetyCopyFile: File get() = File(context.filesDir, "backups/before-import.zip")

    private fun schemaVersion(): Int = db.openHelper.readableDatabase.version

    private fun appVersion(): String = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown"

    // Export

    /**
     * Writes the whole backup to [out] and closes it, whether or not the export succeeds. With [includePhotos]
     * (PRD §12.13, off by default) the progress photos go in too, which makes it format 2.
     */
    suspend fun export(out: OutputStream, now: Long = System.currentTimeMillis(), includePhotos: Boolean = false): BackupManifest = withContext(Dispatchers.IO) {
        out.use { exportTo(it, now, includePhotos) }
    }

    /** `files/photos/`: the progress photos (M17). Android's own backup never includes it. */
    private val photoDir: File get() = File(context.filesDir, PHOTO_DIR)

    /**
     * The photo files a backup carries: only those a `progress_photo` row points at, so a backup holds exactly
     * the photos the app shows (a file no row names is never exported), and plain names only.
     */
    private fun photoFiles(data: DatabaseData): List<File> {
        val table = data.tables[PHOTO_TABLE] ?: return emptyList()
        val column = table.columns.indexOf(FILE_PATH)
        val names = table.rows.mapNotNull { row -> row.getOrNull(column)?.jsonPrimitive?.contentOrNull?.let(PhotoPaths::name) }.toSet()
        return names.map { File(photoDir, it) }.filter { it.isFile }.sortedBy { it.name }
    }

    /** `files/scan_images/`: the InBody screenshots kept with scans (M15). Android's own backup never includes it. */
    private val scanImageDir: File get() = File(context.filesDir, SCAN_IMAGE_DIR)

    /** The scan images a backup carries: only those a scan's `image_path` names, with a name the store makes. */
    private fun scanImageFiles(data: DatabaseData): List<File> {
        val table = data.tables[SCAN_TABLE] ?: return emptyList()
        val column = table.columns.indexOf(IMAGE_PATH)
        if (column < 0) return emptyList()
        val names = table.rows.mapNotNull { row -> row.getOrNull(column)?.jsonPrimitive?.contentOrNull?.takeIf(ScanImages::valid) }.toSet()
        return names.map { File(scanImageDir, it) }.filter { it.isFile }.sortedBy { it.name }
    }

    private suspend fun exportTo(out: OutputStream, now: Long, includePhotos: Boolean): BackupManifest {
        val data = db.withTransaction { DatabaseSnapshot.read(db.openHelper.writableDatabase::query) }
        val settings = encodeSettings(prefs.exportable())
        val photos = if (includePhotos) photoFiles(data) else emptyList()
        // Scan images follow the same switch as the photos (PRD §12.13): both are pictures of you.
        val scanImages = if (includePhotos) scanImageFiles(data) else emptyList()
        val version = if (includePhotos) FORMAT_VERSION else FORMAT_VERSION_WITHOUT_MEDIA
        val manifest = BackupManifest(FORMAT, version, schemaVersion(), now, appVersion(), data.tables.mapValues { it.value.rows.size }, photos.size, scanImages.size)
        // Each entry is written straight into the zip, never held as one big string; photos stream file by file.
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            json.encodeToStream(manifest, zip)
            zip.putNextEntry(ZipEntry(DATABASE))
            json.encodeToStream(data, zip)
            zip.putNextEntry(ZipEntry(SETTINGS))
            json.encodeToStream(settings, zip)
            photos.forEach { file ->
                zip.putNextEntry(ZipEntry("$PHOTO_DIR/${file.name}"))
                file.inputStream().use { it.copyTo(zip) }
            }
            scanImages.forEach { file ->
                zip.putNextEntry(ZipEntry("$SCAN_IMAGE_DIR/${file.name}"))
                file.inputStream().use { it.copyTo(zip) }
            }
            zip.closeEntry()
        }
        return manifest
    }

    // Import

    /**
     * Reads and checks a backup and upgrades it into a scratch database with the app's own migrations, so
     * anything wrong with it shows before the phone's data is touched (TRD §6.5 steps 1 to 3).
     */
    suspend fun prepare(input: InputStream): PreparedBackup = withContext(Dispatchers.IO) {
        try {
            prepareChecked(input)
        } catch (e: OutOfMemoryError) {
            // Only a file far bigger than any real backup gets here; nothing on the phone has changed yet.
            throw BackupException(TOO_BIG)
        }
    }

    private suspend fun prepareChecked(input: InputStream): PreparedBackup {
        // A backup's photos are staged before anything is checked, and dropped again if the check fails.
        val staging = incomingPhotos.apply { deleteRecursively() }
        val scanStaging = incomingScanImages.apply { deleteRecursively() }
        val entries = try {
            input.use { readZip(it, photosTo = staging, scanImagesTo = scanStaging) }
        } catch (e: Throwable) {
            staging.deleteRecursively()
            scanStaging.deleteRecursively()
            throw e
        }
        try {
            return prepareRead(entries, staging, scanStaging)
        } catch (e: Throwable) {
            staging.deleteRecursively()
            scanStaging.deleteRecursively()
            throw e
        }
    }

    private suspend fun prepareRead(entries: Contents, staging: File, scanStaging: File): PreparedBackup {
        val manifest = entries.manifest?.takeIf { it.format == FORMAT } ?: throw BackupException(NOT_A_BACKUP)
        if (manifest.formatVersion > FORMAT_VERSION) throw BackupException("This backup's file format is newer than this version of Recomp understands. Update the app, then import it.")
        val current = schemaVersion()
        if (manifest.schemaVersion > current) throw BackupException("This backup was made by a newer version of Recomp. Update the app, then import it.")
        if (manifest.schemaVersion < 1) throw BackupException.damaged("no database version")
        val data = entries.database ?: throw BackupException.damaged("no database")
        DatabaseSnapshot.validate(data, manifest.rows)
        val settings = decodeSettings(entries.settings ?: emptyMap())
        prefs.problems(settings).takeIf { it.isNotEmpty() }?.let { throw BackupException.damaged("settings this version can't read (${it.joinToString()})") }

        val scratch = File(context.cacheDir, "restore/scratch.db")
        scratch.parentFile!!.mkdirs()
        deleteDatabaseFiles(scratch)
        try {
            build(scratch, data, manifest.schemaVersion)
            upgradeAndCheck(scratch)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            deleteDatabaseFiles(scratch)
            throw e as? BackupException ?: BackupException("This backup couldn't be read: ${e.message ?: e.javaClass.simpleName}", e)
        }
        if (entries.photos != manifest.photos || entries.scanImages != manifest.scanImages) {
            deleteDatabaseFiles(scratch)
            throw BackupException.damaged("${entries.photos} photos and ${entries.scanImages} scan images where it lists ${manifest.photos} and ${manifest.scanImages}")
        }
        return PreparedBackup(manifest, settings, scratch, staging.takeIf { manifest.photos > 0 }, scanStaging.takeIf { manifest.scanImages > 0 })
    }

    fun discard(backup: PreparedBackup) {
        deleteDatabaseFiles(backup.scratch)
        backup.photos?.deleteRecursively()
        backup.scanImages?.deleteRecursively()
    }

    /** Where an import's photos wait until its database commits. App-private, out of Android's backup. */
    internal val incomingPhotos: File get() = File(context.filesDir, "backups/incoming-photos")

    /** The same for scan images. */
    internal val incomingScanImages: File get() = File(context.filesDir, "backups/incoming-scan-images")

    /**
     * Replaces every table and setting with the backup's (TRD §6.5 steps 4 to 6): a safety copy first, then
     * one transaction for the database, then the settings.
     */
    suspend fun restore(backup: PreparedBackup, now: Long = System.currentTimeMillis()) = withContext(NonCancellable + Dispatchers.IO) {
        if (!backup.scratch.isFile) throw BackupException("This backup is no longer ready. Choose the file again.")
        if (db.workoutDao().active() != null) throw BackupException("Finish or discard the workout in progress first, then import.")

        writeSafetyCopy(now)
        // The settings can't share the database's transaction, so the commit stamps the database with a token and
        // a marker file holds that token and the settings. Until the settings are written, the next launch can
        // tell whether the commit happened and finish the job (finishPendingRestore).
        val token = Random.nextInt(1, Int.MAX_VALUE)
        val pending = PendingRestore(token, encodeSettings(backup.settings), backup.manifest.exportedAt, photos = backup.photos != null, scanImages = backup.scanImages != null)
        try {
            restoreDatabase(backup, token, pending)
        } catch (e: Throwable) {
            pendingFile.delete()
            throw e
        }
        finishPhotos(pending)
        finishScanImages(pending)
        finishSettings(pending)
        deleteDatabaseFiles(backup.scratch)
    }

    /**
     * After the database commit: a backup with photos replaces the current ones (the safety copy kept them); one
     * without leaves them in place, so a row whose file isn't there just shows a placeholder.
     */
    private fun finishPhotos(pending: PendingRestore) {
        val staged = incomingPhotos
        if (!pending.photos || !staged.isDirectory) return
        val old = File(context.filesDir, "$PHOTO_DIR.old").apply { deleteRecursively() }
        if (photoDir.exists() && !photoDir.renameTo(old)) throw BackupException("Your data was replaced, but the photos couldn't be. Restore the data from before the import in Settings → Data.")
        if (!staged.renameTo(photoDir)) {
            old.renameTo(photoDir)
            throw BackupException("Your data was replaced, but the photos couldn't be. Restore the data from before the import in Settings → Data.")
        }
        old.deleteRecursively()
    }

    /** The same swap for scan images: a backup with them replaces the current ones; one without leaves them. */
    private fun finishScanImages(pending: PendingRestore) {
        val staged = incomingScanImages
        if (!pending.scanImages || !staged.isDirectory) return
        val old = File(context.filesDir, "$SCAN_IMAGE_DIR.old").apply { deleteRecursively() }
        if (scanImageDir.exists() && !scanImageDir.renameTo(old)) throw BackupException("Your data was replaced, but the scan images couldn't be. Restore the data from before the import in Settings → Data.")
        if (!staged.renameTo(scanImageDir)) {
            old.renameTo(scanImageDir)
            throw BackupException("Your data was replaced, but the scan images couldn't be. Restore the data from before the import in Settings → Data.")
        }
        old.deleteRecursively()
    }

    /** Copies the scratch database over the live one in one transaction, stamped with [token] (TRD §6.5 step 5). */
    private suspend fun restoreDatabase(backup: PreparedBackup, token: Int, pending: PendingRestore) {
        SQLiteDatabase.openDatabase(backup.scratch.path, null, SQLiteDatabase.OPEN_READONLY).use { source ->
            db.withTransaction {
                val live = db.openHelper.writableDatabase
                // Rows go in table by table, so foreign keys are checked once, at commit.
                live.execSQL("PRAGMA defer_foreign_keys = ON")
                val schema = DatabaseSnapshot.schema(live::query)
                val sourceTables = DatabaseSnapshot.dataTables(DatabaseSnapshot.schema { source.rawQuery(it, null) }).toSet()
                val tables = DatabaseSnapshot.dataTables(schema)
                tables.forEach { live.execSQL("DELETE FROM `$it`") }
                tables.filter { it in sourceTables }.forEach { copy(source, live, it) }
                DatabaseSnapshot.virtualTables(schema).forEach { live.execSQL("INSERT INTO `$it`(`$it`) VALUES('rebuild')") }
                // Checked before the commit, so a broken link rolls back instead of failing the commit itself.
                brokenLinks(live::query)?.let { throw BackupException.damaged("entries that point at rows it doesn't have ($it)") }
                // The header's application_id commits (or rolls back) with the rows.
                live.execSQL("PRAGMA application_id = $token")
                writePending(pending)
            }
        }
    }

    /** Writes the backup's settings, then drops the marker. A failure keeps the marker for the next launch. */
    private suspend fun finishSettings(pending: PendingRestore) {
        try {
            prefs.replaceAll(decodeSettings(pending.settings))
        } catch (e: Exception) {
            throw BackupException(
                "Your data was replaced, but the settings couldn't be (${e.message ?: e.javaClass.simpleName}). " +
                    "Recomp tries again the next time it starts; if that fails too, restore the data from before the import in Settings → Data.",
                e,
            )
        }
        pendingFile.delete()
    }

    /**
     * Run at launch (TRD §6.5): if an import was interrupted, finishes it when its database commit happened (the
     * header carries its token) and drops it when it didn't. Null when there was nothing to do.
     */
    suspend fun finishPendingRestore(): PendingOutcome? = withContext(NonCancellable + Dispatchers.IO) {
        val pending = readPending() ?: return@withContext null
        val stamped = db.openHelper.writableDatabase.query("PRAGMA application_id").use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        if (stamped != pending.token) {
            pendingFile.delete()
            incomingPhotos.deleteRecursively()
            incomingScanImages.deleteRecursively()
            return@withContext PendingOutcome.NOT_APPLIED
        }
        try {
            runCatching { finishPhotos(pending) }
            runCatching { finishScanImages(pending) }
            finishSettings(pending)
            PendingOutcome.FINISHED
        } catch (e: BackupException) {
            PendingOutcome.SETTINGS_FAILED
        }
    }

    internal val pendingFile: File get() = File(context.filesDir, "backups/pending-settings.json")

    private fun writePending(pending: PendingRestore) {
        pendingFile.parentFile!!.mkdirs()
        FileOutputStream(pendingFile).use { out ->
            out.write(json.encodeToString(pending).toByteArray())
            out.fd.sync()
        }
    }

    internal fun readPending(): PendingRestore? =
        pendingFile.takeIf { it.isFile }?.let { runCatching { json.decodeFromString<PendingRestore>(it.readText()) }.getOrNull() ?: run { it.delete(); null } }

    /** Where the automatic backup (M16) builds its files before copying them out: app-private cache. */
    val autoBackupScratchDir: File get() = File(context.cacheDir, "backups")

    /** A new scratch file for one automatic run, never shared with another run (M17 review R3); the run deletes it. */
    fun newAutoBackupScratch(): File = File.createTempFile(SCRATCH_PREFIX, ".zip", autoBackupScratchDir.apply { mkdirs() })

    /** Deletes the scratch files runs that were killed left behind (with photos, if they were included). Returns how many. */
    fun clearAutoBackupScratch(): Int =
        autoBackupScratchDir.listFiles().orEmpty().count { it.isFile && it.name.startsWith(SCRATCH_PREFIX) && it.name.endsWith(".zip") && it.delete() }

    /** Whether an import is between its database commit and its photos and settings, so photos must be left alone. */
    fun importInProgress(): Boolean = pendingFile.isFile || incomingPhotos.exists() || incomingScanImages.exists()

    /**
     * Takes a deleted photo out of the safety copy too, file and row, so *Delete* holds ("This can't be undone")
     * and *Restore the data from before the import* doesn't bring it back (M17). Does nothing without a safety
     * copy or when the photo isn't in it.
     */
    suspend fun dropFromSafetyCopy(filePath: String) = withContext(NonCancellable + Dispatchers.IO) {
        val file = safetyCopyFile
        val name = PhotoPaths.name(filePath) ?: return@withContext
        if (!file.isFile) return@withContext
        val entry = "$PHOTO_DIR/$name"
        val part = File(file.parentFile, "${file.name}.part")
        val changed = ZipFile(file).use { zip ->
            val data = zip.getEntry(DATABASE)?.let { e -> zip.getInputStream(e).use { decode<DatabaseData>(it) } } ?: return@use false
            val table = data.tables[PHOTO_TABLE]
            val column = table?.columns?.indexOf(FILE_PATH) ?: -1
            val kept = table?.rows?.filterNot { it.getOrNull(column)?.jsonPrimitive?.contentOrNull == filePath }
            val hasFile = zip.getEntry(entry) != null
            if (!hasFile && kept?.size == table?.rows?.size) return@use false
            val newData = if (table != null && kept != null) data.copy(tables = data.tables + (PHOTO_TABLE to table.copy(rows = kept))) else data
            ZipOutputStream(part.outputStream()).use { out ->
                zip.entries().asSequence().forEach { e ->
                    if (e.name == entry) return@forEach
                    out.putNextEntry(ZipEntry(e.name))
                    when (e.name) {
                        MANIFEST -> {
                            val m = zip.getInputStream(e).use { decode<BackupManifest>(it) }
                            val rows = m.rows + (PHOTO_TABLE to (newData.tables[PHOTO_TABLE]?.rows?.size ?: 0))
                            json.encodeToStream(m.copy(rows = rows, photos = m.photos - if (hasFile) 1 else 0), out)
                        }
                        DATABASE -> json.encodeToStream(newData, out)
                        else -> zip.getInputStream(e).use { it.copyTo(out) }
                    }
                }
                out.closeEntry()
            }
            true
        }
        if (changed && !part.renameTo(file)) {
            part.delete()
            throw BackupException("Couldn't remove the photo from the safety copy.")
        }
    }

    /** When the safety copy was made, or null without one. */
    suspend fun safetyCopyDate(): Long? = withContext(Dispatchers.IO) {
        if (!safetyCopyFile.isFile) return@withContext null
        runCatching { safetyCopyFile.inputStream().use { readZip(it, only = setOf(MANIFEST)) }.manifest?.exportedAt }.getOrNull()
    }

    suspend fun prepareSafetyCopy(): PreparedBackup = prepare(safetyCopyFile.inputStream())

    private suspend fun writeSafetyCopy(now: Long) {
        val file = safetyCopyFile
        file.parentFile!!.mkdirs()
        val part = File(file.parentFile, "${file.name}.part")
        // The safety copy always keeps the photos, so *Restore the data from before the import* brings them back.
        export(part.outputStream(), now, includePhotos = true)
        if (!part.renameTo(file)) {
            part.delete()
            throw BackupException("Couldn't keep a copy of your current data, so nothing was replaced.")
        }
    }

    // Scratch database

    private fun build(file: File, data: DatabaseData, version: Int) {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.beginTransaction()
            try {
                data.schema.filter { it.type == "table" }.forEach { db.execSQL(it.sql) }
                data.tables.forEach { (table, rows) ->
                    val statement = db.compileStatement(insertSql(table, rows.columns))
                    rows.rows.forEach { row ->
                        statement.clearBindings()
                        row.forEachIndexed { i, cell ->
                            when (val v = DatabaseSnapshot.value(cell)) {
                                null -> statement.bindNull(i + 1)
                                is String -> statement.bindString(i + 1, v)
                                is Long -> statement.bindLong(i + 1, v)
                                is Double -> statement.bindDouble(i + 1, v)
                                is ByteArray -> statement.bindBlob(i + 1, v)
                            }
                        }
                        statement.executeInsert()
                    }
                    statement.close()
                }
                // Indexes after the rows, so a unique index also checks them; triggers last, so they don't fire.
                data.schema.filter { it.type != "table" }.forEach { db.execSQL(it.sql) }
                db.version = version
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /**
     * Room migrates an older backup with the real MIGRATIONS and validates every table, or refuses it. Then every
     * link between rows must hold, and every row must read back through Room's converters.
     */
    private suspend fun upgradeAndCheck(file: File) {
        val room = Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()
        try {
            val scratch = room.openHelper.writableDatabase
            brokenLinks(scratch::query)?.let { throw BackupException.damaged("entries that point at rows it doesn't have ($it)") }
            // A photo row may only name a file in files/photos, or Delete could remove any app file (M17).
            scratch.query("SELECT $FILE_PATH FROM $PHOTO_TABLE").use { c ->
                while (c.moveToNext()) if (!PhotoPaths.valid(c.getString(0) ?: "")) throw BackupException.damaged("a photo that points outside the photos folder")
            }
            // The same check for a scan's image: only a name the scan-image store makes, never a path (M15).
            scratch.query("SELECT $IMAGE_PATH FROM $SCAN_TABLE WHERE $IMAGE_PATH IS NOT NULL").use { c ->
                while (c.moveToNext()) if (!ScanImages.valid(c.getString(0) ?: "")) throw BackupException.damaged("a scan image that points outside its folder")
            }
            room.backupCheckDao().readers().forEach { (table, read) ->
                try {
                    read()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    throw BackupException.damaged("values this version can't read in $table", e)
                }
            }
        } finally {
            room.close()
        }
    }

    /** The first table with a row whose foreign key points nowhere, or null when every link holds. */
    private fun brokenLinks(query: (String) -> Cursor): String? = query("PRAGMA foreign_key_check").use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun copy(source: SQLiteDatabase, live: SupportSQLiteDatabase, table: String) {
        val sourceColumns = DatabaseSnapshot.columns({ source.rawQuery(it, null) }, table).toSet()
        val columns = DatabaseSnapshot.columns(live::query, table).filter { it in sourceColumns }
        val insert = live.compileStatement(insertSql(table, columns))
        source.rawQuery("SELECT ${columns.joinToString { "`$it`" }} FROM `$table` ORDER BY rowid", null).use { c ->
            while (c.moveToNext()) {
                insert.clearBindings()
                columns.indices.forEach { bind(insert, it + 1, c, it) }
                insert.executeInsert()
            }
        }
        insert.close()
    }

    private fun bind(s: SupportSQLiteStatement, index: Int, c: Cursor, column: Int) = when (c.getType(column)) {
        Cursor.FIELD_TYPE_NULL -> s.bindNull(index)
        Cursor.FIELD_TYPE_INTEGER -> s.bindLong(index, c.getLong(column))
        Cursor.FIELD_TYPE_FLOAT -> s.bindDouble(index, c.getDouble(column))
        Cursor.FIELD_TYPE_BLOB -> s.bindBlob(index, c.getBlob(column))
        else -> s.bindString(index, c.getString(column))
    }

    private fun insertSql(table: String, columns: List<String>) =
        "INSERT INTO `$table` (${columns.joinToString { "`$it`" }}) VALUES (${columns.joinToString { "?" }})"

    private fun deleteDatabaseFiles(file: File) {
        listOf("", "-journal", "-wal", "-shm").forEach { File(file.path + it).delete() }
    }

    // The zip and its JSON

    /** At most this much is read from a backup, across its entries (TRD §6.5). */
    internal var maxBytes: Long = MAX_BYTES

    /** A backup's entries, decoded straight from the zip with no copies as bytes or text. */
    /** Each photo may be at most this big, and all of them together at most [maxPhotoBytes]. */
    internal var maxPhotoFileBytes: Long = MAX_PHOTO_FILE_BYTES
    internal var maxPhotoBytes: Long = MAX_PHOTO_BYTES

    private class Contents(var manifest: BackupManifest? = null, var database: DatabaseData? = null, var settings: Map<String, SettingValue>? = null, var photos: Int = 0, var scanImages: Int = 0)

    /**
     * Reads [only] the named entries and skips the rest, stopping at [maxBytes] of JSON. With [photosTo], each
     * `photos/<name>` entry streams into that folder under its own cap and the photos' total cap; photos never
     * count toward the JSON's budget.
     */
    private fun readZip(input: InputStream, only: Set<String> = ENTRIES, photosTo: File? = null, scanImagesTo: File? = null): Contents = try {
        val contents = Contents()
        ZipInputStream(input).use { zip ->
            val budget = Budget(zip, maxBytes)
            var photoBytes = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (photosTo != null && name.startsWith("$PHOTO_DIR/")) {
                    val file = name.removePrefix("$PHOTO_DIR/")
                    if (!PHOTO_NAME.matches(file)) throw BackupException.damaged("a photo with a name it can't use")
                    photosTo.mkdirs()
                    val target = File(photosTo, file)
                    if (target.exists()) throw BackupException.damaged("the same photo twice")
                    val written = Budget(zip, maxPhotoFileBytes).let { capped -> target.outputStream().use { capped.copyTo(it) } }
                    photoBytes += written
                    if (photoBytes > maxPhotoBytes) throw BackupException(TOO_BIG)
                    contents.photos++
                    continue
                }
                if (scanImagesTo != null && name.startsWith("$SCAN_IMAGE_DIR/")) {
                    val file = name.removePrefix("$SCAN_IMAGE_DIR/")
                    if (!ScanImages.valid(file)) throw BackupException.damaged("a scan image with a name it can't use")
                    scanImagesTo.mkdirs()
                    val target = File(scanImagesTo, file)
                    if (target.exists()) throw BackupException.damaged("the same scan image twice")
                    // Under the same caps as the photos, and counted in their total.
                    val written = Budget(zip, maxPhotoFileBytes).let { capped -> target.outputStream().use { capped.copyTo(it) } }
                    photoBytes += written
                    if (photoBytes > maxPhotoBytes) throw BackupException(TOO_BIG)
                    contents.scanImages++
                    continue
                }
                when (name.takeIf { it in only }) {
                    MANIFEST -> contents.manifest = decode(budget)
                    DATABASE -> contents.database = decode(budget)
                    SETTINGS -> contents.settings = decode(budget)
                }
            }
        }
        contents
    } catch (e: ZipException) {
        throw BackupException(NOT_A_BACKUP, e)
    }

    /** Counts what's read across the zip's entries and refuses past [limit]. Never closes the zip. */
    private class Budget(private val zip: InputStream, private val limit: Long) : InputStream() {
        private var total = 0L

        override fun read(): Int = zip.read().also { if (it >= 0) count(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = zip.read(b, off, len).also { if (it > 0) count(it.toLong()) }

        private fun count(n: Long) {
            total += n
            if (total > limit) throw BackupException(TOO_BIG)
        }

        override fun close() = Unit
    }

    @OptIn(ExperimentalSerializationApi::class)
    private inline fun <reified T> decode(stream: InputStream): T = try {
        json.decodeFromStream<T>(stream)
    } catch (e: SerializationException) {
        throw BackupException.damaged("a file it can't read", e)
    } catch (e: IllegalArgumentException) {
        throw BackupException.damaged("a file it can't read", e)
    }

    companion object {
        const val FORMAT = "recomp-backup"
        /** The automatic backup's scratch files: `automatic-<random>.zip`, and the older fixed `automatic.zip`. */
        private const val SCRATCH_PREFIX = "automatic"
        /** Format 2 (M17) can carry `photos/<name>` entries; a backup without photos is still written as format 1. */
        const val FORMAT_VERSION = 2
        const val FORMAT_VERSION_WITHOUT_MEDIA = 1
        const val PHOTO_DIR = PhotoPaths.DIR
        private const val PHOTO_TABLE = "progress_photo"
        private const val FILE_PATH = "file_path"
        const val SCAN_IMAGE_DIR = ScanImages.DIR
        private const val SCAN_TABLE = "body_composition"
        private const val IMAGE_PATH = "image_path"
        internal val PHOTO_NAME = PhotoPaths.NAME
        /** A phone photo is a few MB; 25 MB allows for any camera. */
        private const val MAX_PHOTO_FILE_BYTES = 25L * 1024 * 1024
        /** Years of monthly front, side and back photos. */
        private const val MAX_PHOTO_BYTES = 2L * 1024 * 1024 * 1024
        const val MANIFEST = "manifest.json"
        const val DATABASE = "database.json"
        const val SETTINGS = "settings.json"
        private val ENTRIES = setOf(MANIFEST, DATABASE, SETTINGS)
        /** Several times any real backup (a year of daily use is about 1 MB of JSON), and small enough to decode on a phone. */
        private const val MAX_BYTES = 16L * 1024 * 1024
        const val TOO_BIG = "This file is too big to be a Recomp backup."
        const val NOT_A_BACKUP = "This file isn't a Recomp backup."

        /**
         * The name *Export all data* suggests: `recomp-export-2026-10-18.zip`. It's not an automatic backup's name, so
         * an export saved into the backup folder is never counted, or deleted, by that folder's rotation (#27 N1).
         */
        fun fileName(date: java.time.LocalDate) = "recomp-export-$date.zip"

        fun encodeSettings(prefs: Preferences): Map<String, SettingValue> = prefs.asMap().entries.associate { (key, value) ->
            key.name to when (value) {
                is String -> SettingValue("string", JsonPrimitive(value))
                is Boolean -> SettingValue("boolean", JsonPrimitive(value))
                is Int -> SettingValue("int", JsonPrimitive(value))
                is Long -> SettingValue("long", JsonPrimitive(value))
                is Float -> SettingValue("float", JsonPrimitive(value.toString()))
                is Double -> SettingValue("double", JsonPrimitive(value.toString()))
                is Set<*> -> SettingValue("string_set", JsonArray(value.map { JsonPrimitive(it as String) }))
                is ByteArray -> SettingValue("bytes", JsonPrimitive(Base64.getEncoder().encodeToString(value)))
                else -> error("Unknown setting type ${value::class}")
            }
        }

        fun decodeSettings(values: Map<String, SettingValue>): Preferences = mutablePreferencesOf().apply {
            values.forEach { (name, v) ->
                try {
                    val p = v.value
                    when (v.type) {
                        "string" -> this[stringPreferencesKey(name)] = p.jsonPrimitive.content
                        "boolean" -> this[booleanPreferencesKey(name)] = p.jsonPrimitive.content.toBooleanStrict()
                        "int" -> this[intPreferencesKey(name)] = p.jsonPrimitive.content.toInt()
                        "long" -> this[longPreferencesKey(name)] = p.jsonPrimitive.content.toLong()
                        "float" -> this[floatPreferencesKey(name)] = p.jsonPrimitive.content.toFloat()
                        "double" -> this[doublePreferencesKey(name)] = p.jsonPrimitive.content.toDouble()
                        "string_set" -> this[stringSetPreferencesKey(name)] = p.jsonArray.map { it.jsonPrimitive.content }.toSet()
                        "bytes" -> this[byteArrayPreferencesKey(name)] = Base64.getDecoder().decode(p.jsonPrimitive.content)
                        else -> throw BackupException.damaged("a setting of an unknown kind")
                    }
                } catch (e: IllegalArgumentException) {
                    throw BackupException.damaged("a setting it can't read ($name)", e)
                }
            }
        }
    }
}
