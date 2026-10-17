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
import app.recomp.data.db.readers
import app.recomp.data.prefs.SettingsPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
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
) {
    fun count(table: String): Int = rows[table] ?: 0
}

/** One setting with its DataStore type, so it comes back as the same kind of key. */
@Serializable
data class SettingValue(val type: String, val value: JsonElement)

/**
 * A backup that has been read, checked and upgraded into a scratch database (steps 1 to 3 of TRD §6.5),
 * waiting for you to confirm. [discard] it if you don't.
 */
class PreparedBackup internal constructor(val manifest: BackupManifest, internal val settings: Preferences, internal val scratch: File)

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

    /** Writes the whole backup to [out] and closes it. */
    suspend fun export(out: OutputStream, now: Long = System.currentTimeMillis()): BackupManifest = withContext(Dispatchers.IO) {
        val data = db.withTransaction { DatabaseSnapshot.read(db.openHelper.writableDatabase::query) }
        val settings = encodeSettings(prefs.exportable())
        val manifest = BackupManifest(FORMAT, FORMAT_VERSION, schemaVersion(), now, appVersion(), data.tables.mapValues { it.value.rows.size })
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            entry(MANIFEST, json.encodeToString(manifest))
            entry(DATABASE, json.encodeToString(data))
            entry(SETTINGS, json.encodeToString(settings))
        }
        manifest
    }

    // Import

    /**
     * Reads and checks a backup and upgrades it into a scratch database with the app's own migrations, so
     * anything wrong with it shows before the phone's data is touched (TRD §6.5 steps 1 to 3).
     */
    suspend fun prepare(input: InputStream): PreparedBackup = withContext(Dispatchers.IO) {
        val entries = input.use(::unzip)
        val manifest = entries[MANIFEST]?.let { decode<BackupManifest>(it) }?.takeIf { it.format == FORMAT } ?: throw BackupException(NOT_A_BACKUP)
        if (manifest.formatVersion > FORMAT_VERSION) throw BackupException("This backup's file format is newer than this version of Recomp understands. Update the app, then import it.")
        val current = schemaVersion()
        if (manifest.schemaVersion > current) throw BackupException("This backup was made by a newer version of Recomp. Update the app, then import it.")
        if (manifest.schemaVersion < 1) throw BackupException.damaged("no database version")
        val data = decode<DatabaseData>(entries[DATABASE] ?: throw BackupException.damaged("no database"))
        DatabaseSnapshot.validate(data, manifest.rows)
        val settings = decodeSettings(entries[SETTINGS]?.let { decode<Map<String, SettingValue>>(it) } ?: emptyMap())
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
        PreparedBackup(manifest, settings, scratch)
    }

    fun discard(backup: PreparedBackup) = deleteDatabaseFiles(backup.scratch)

    /**
     * Replaces every table and setting with the backup's (TRD §6.5 steps 4 to 6): a safety copy first, then
     * one transaction for the database, then the settings.
     */
    suspend fun restore(backup: PreparedBackup, now: Long = System.currentTimeMillis()) = withContext(NonCancellable + Dispatchers.IO) {
        if (!backup.scratch.isFile) throw BackupException("This backup is no longer ready. Choose the file again.")
        if (db.workoutDao().active() != null) throw BackupException("Finish or discard the workout in progress first, then import.")

        writeSafetyCopy(now)
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
            }
        }
        try {
            prefs.replaceAll(backup.settings)
        } catch (e: Exception) {
            throw BackupException(
                "Your data was replaced, but the settings couldn't be (${e.message ?: e.javaClass.simpleName}). Restore the data from before the import in Settings → Data.",
                e,
            )
        }
        deleteDatabaseFiles(backup.scratch)
    }

    /** When the safety copy was made, or null without one. */
    suspend fun safetyCopyDate(): Long? = withContext(Dispatchers.IO) {
        if (!safetyCopyFile.isFile) return@withContext null
        runCatching { safetyCopyFile.inputStream().use { unzip(it, only = setOf(MANIFEST)) }[MANIFEST]?.let { decode<BackupManifest>(it).exportedAt } }.getOrNull()
    }

    suspend fun prepareSafetyCopy(): PreparedBackup = prepare(safetyCopyFile.inputStream())

    private suspend fun writeSafetyCopy(now: Long) {
        val file = safetyCopyFile
        file.parentFile!!.mkdirs()
        val part = File(file.parentFile, "${file.name}.part")
        export(part.outputStream(), now)
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

    /** The backup's entries ([only] those named, when given) as text. Others are skipped, never held in memory. */
    private fun unzip(input: InputStream, only: Set<String> = ENTRIES): Map<String, String> = try {
        val entries = mutableMapOf<String, String>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name in only) {
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw BackupException("This file is too big to be a Recomp backup.")
                        bytes.write(buffer, 0, n)
                    }
                    entries[entry.name] = bytes.toString(Charsets.UTF_8.name())
                }
            }
        }
        entries
    } catch (e: ZipException) {
        throw BackupException(NOT_A_BACKUP, e)
    }

    private inline fun <reified T> decode(text: String): T = try {
        json.decodeFromString<T>(text)
    } catch (e: SerializationException) {
        throw BackupException.damaged("a file it can't read", e)
    } catch (e: IllegalArgumentException) {
        throw BackupException.damaged("a file it can't read", e)
    }

    companion object {
        const val FORMAT = "recomp-backup"
        const val FORMAT_VERSION = 1
        const val MANIFEST = "manifest.json"
        const val DATABASE = "database.json"
        const val SETTINGS = "settings.json"
        private val ENTRIES = setOf(MANIFEST, DATABASE, SETTINGS)
        /** Far above any real backup (a year of use is a few MB), and small enough to read into memory. */
        private const val MAX_BYTES = 64L * 1024 * 1024
        const val NOT_A_BACKUP = "This file isn't a Recomp backup."

        /** `recomp-backup-2026-10-18.zip`. */
        fun fileName(date: java.time.LocalDate) = "recomp-backup-$date.zip"

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
