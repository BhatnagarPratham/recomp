package app.recomp.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.recomp.data.prefs.BackupTarget
import app.recomp.data.prefs.DayBoundary
import app.recomp.data.prefs.DevicePrefs
import app.recomp.domain.export.BackupRotation
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** A file in the backup folder: its name and its address. */
data class StoredFile(val name: String, val uri: String)

/**
 * Where the automatic backup writes (DA-02), through the storage access framework, so tests can stand in for it.
 * [target] is a folder's tree address or a single file's address.
 */
interface BackupStorage {
    /** The files directly in the folder. */
    suspend fun files(target: BackupTarget): List<StoredFile>

    /**
     * Copies [source] to [name] in the folder, replacing a file of that name, or to the single file when [name] is
     * null. It streams, so a backup with photos is never held in memory.
     */
    suspend fun write(target: BackupTarget, name: String?, source: File)

    suspend fun delete(file: StoredFile)
}

/** How a run ended. [retry] is true when trying again later may work (a slow or offline Drive). */
sealed interface BackupRun {
    data object NoTarget : BackupRun
    data class Done(val name: String, val deleted: Int) : BackupRun
    data class Failed(val reason: String, val retry: Boolean) : BackupRun
}

/**
 * The automatic backup (DA-02, PRD §12.12): `BackupManager`'s full export, written to the folder you chose (keeping
 * the newest 8 of Recomp's backups there) or over the one file you chose for Google Drive. Settings shows the last
 * success, or why the latest try failed. It never throws.
 */
@Singleton
class AutoBackup @Inject constructor(
    private val backups: BackupManager,
    private val storage: BackupStorage,
    private val device: DevicePrefs,
    private val days: DayBoundary,
    private val clock: Clock,
) {
    suspend fun run(): BackupRun {
        val state = device.autoBackup.first()
        val target = state.target ?: return BackupRun.NoTarget
        val scratch = backups.autoBackupScratch
        return try {
            val now = clock.millis()
            // The whole backup is built first (in app-private cache, streamed, so photos never sit in memory), so a
            // failure while exporting never leaves a half-written file.
            scratch.parentFile?.mkdirs()
            backups.export(scratch.outputStream(), now, includePhotos = state.includePhotos)
            val result = if (target.folder) {
                val name = BackupRotation.fileName(days.of(now))
                storage.write(target, name, scratch)
                val old = BackupRotation.toDelete(storage.files(target), StoredFile::name)
                old.forEach { runCatching { storage.delete(it) } }
                BackupRun.Done(name, old.size)
            } else {
                storage.write(target, null, scratch)
                BackupRun.Done(target.label, 0)
            }
            device.setBackedUp(now)
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            failed(BackupRun.Failed(revoked(target), retry = false))
        } catch (e: FileNotFoundException) {
            failed(BackupRun.Failed(revoked(target), retry = false))
        } catch (e: IOException) {
            failed(BackupRun.Failed("Couldn't write the backup (${e.message ?: e.javaClass.simpleName}). It will try again.", retry = true))
        } catch (e: Exception) {
            failed(BackupRun.Failed("Couldn't back up: ${e.message ?: e.javaClass.simpleName}.", retry = false))
        } finally {
            scratch.delete()
        }
    }

    private suspend fun failed(run: BackupRun.Failed): BackupRun.Failed {
        runCatching { device.setBackupError(run.reason) }
        return run
    }

    companion object {
        /** The folder or file was deleted, or Recomp's access to it was taken back. */
        fun revoked(target: BackupTarget) =
            "Recomp can't reach ${if (target.folder) "the folder" else "the file"} “${target.label}” any more. Choose where to back up again in Settings → Data."
    }
}

/** The weekly run: Sunday night, when the battery isn't low; a slow or offline Drive is retried with backoff. */
@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val backup: AutoBackup,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (val run = backup.run()) {
        is BackupRun.Failed -> if (run.retry && runAttemptCount < MAX_TRIES) Result.retry() else Result.success()
        else -> Result.success()
    }

    companion object {
        const val NAME = "auto-backup"
        const val NOW = "auto-backup-now"
        const val MAX_TRIES = 4
    }
}

/** Keeps the weekly backup scheduled exactly while there's somewhere to write it. */
@Singleton
class AutoBackupScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val device: DevicePrefs,
    private val clock: Clock,
) {
    private val constraints get() = Constraints.Builder().setRequiresBatteryNotLow(true).build()

    /** At launch: scheduled if there's a target, and kept as it is when it already is. */
    suspend fun ensureScheduled() = schedule(ExistingPeriodicWorkPolicy.KEEP)

    /** After the target changes: rescheduled from now, so the next run is the coming Sunday night again. */
    suspend fun reschedule() = schedule(ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE)

    private suspend fun schedule(policy: ExistingPeriodicWorkPolicy) {
        val work = WorkManager.getInstance(context)
        if (device.autoBackup.first().target == null) {
            work.cancelUniqueWork(AutoBackupWorker.NAME)
            return
        }
        val now = LocalDateTime.now(clock)
        val delay = Duration.between(now, BackupRotation.nextRun(now))
        work.enqueueUniquePeriodicWork(
            AutoBackupWorker.NAME,
            policy,
            PeriodicWorkRequestBuilder<AutoBackupWorker>(7, TimeUnit.DAYS)
                .setInitialDelay(delay.toMinutes(), TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build(),
        )
    }

    /** *Back up now*: once, as soon as it can run. */
    fun runNow() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            AutoBackupWorker.NOW,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<AutoBackupWorker>().setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build(),
        )
    }
}

/** The storage access framework: a folder you picked once, or one file, through their saved permissions. */
@Singleton
class SafBackupStorage @Inject constructor(@ApplicationContext private val context: Context) : BackupStorage {
    private val resolver get() = context.contentResolver

    private fun folderDocument(target: BackupTarget): Uri {
        val tree = Uri.parse(target.uri)
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    }

    override suspend fun files(target: BackupTarget): List<StoredFile> = withContext(Dispatchers.IO) {
        val tree = Uri.parse(target.uri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        resolver.query(children, columns, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    add(StoredFile(c.getString(1), DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)).toString()))
                }
            }
        } ?: throw FileNotFoundException("The folder can't be read")
    }

    /**
     * In a folder, the backup is written to `<name>.part`, checked, and only then renamed over the day's file, so a
     * write that fails or is cut short never destroys the backup already there. A provider that can't rename gets
     * the file written in place. The single file (Google Drive) can't be renamed, so it's overwritten in place and
     * its size checked afterwards; a cut-short write is reported, and the next run writes it again.
     */
    override suspend fun write(target: BackupTarget, name: String?, source: File) = withContext(Dispatchers.IO) {
        val size = source.length()
        fun copyTo(uri: Uri) = open(uri).use { out -> source.inputStream().use { it.copyTo(out) } }
        if (name == null) {
            val uri = Uri.parse(target.uri)
            copyTo(uri)
            check(uri, size)
            return@withContext
        }
        val before = files(target)
        // A part left by an earlier run that didn't finish goes first.
        before.filter { it.name == BackupRotation.partName(name) }.forEach { runCatching { delete(it) } }
        val part = DocumentsContract.createDocument(resolver, folderDocument(target), "application/octet-stream", BackupRotation.partName(name))
            ?: throw IOException("The folder didn't take a new file")
        try {
            copyTo(part)
            check(part, size)
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, part) }
            throw e
        }
        val existing = before.firstOrNull { it.name == name }?.let { Uri.parse(it.uri) }
        val renamed = runCatching {
            existing?.let { DocumentsContract.deleteDocument(resolver, it) }
            DocumentsContract.renameDocument(resolver, part, name)
        }.getOrNull()
        if (renamed == null) {
            // No rename here: write the day's file in place, as before, and drop the part.
            runCatching { DocumentsContract.deleteDocument(resolver, part) }
            val uri = (if (existing != null && runCatching { resolver.openFileDescriptor(existing, "r")?.close() }.isSuccess) existing else null)
                ?: DocumentsContract.createDocument(resolver, folderDocument(target), "application/zip", name)
                ?: throw IOException("The folder didn't take a new file")
            copyTo(uri)
            check(uri, size)
        }
    }

    /** The provider's size for the file, where it reports one, must be what was written. */
    private fun check(uri: Uri, expected: Long) {
        val size = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
        }.getOrNull()
        if (size != null && size != expected) throw IOException("The backup was cut short ($size of $expected bytes written)")
    }

    override suspend fun delete(file: StoredFile) {
        withContext(Dispatchers.IO) { DocumentsContract.deleteDocument(resolver, Uri.parse(file.uri)) }
    }

    /** "wt" replaces the contents; a provider that doesn't take it gets the plain mode. */
    private fun open(uri: Uri): OutputStream =
        runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull() ?: resolver.openOutputStream(uri) ?: throw FileNotFoundException("The file can't be opened")

    /** Keeps access to what you picked across restarts; the address alone isn't enough. */
    fun keep(uri: Uri) = resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

    /** Gives access back when you choose somewhere else. */
    fun release(uri: Uri) = runCatching { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }

    /** What Settings calls it: the folder's or the file's name. */
    fun label(uri: Uri, folder: Boolean): String {
        val document = if (folder) DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)) else uri
        return runCatching {
            resolver.query(document, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: if (folder) "the folder" else "the file"
    }
}
