package app.recomp.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.recomp.data.backup.BackupException
import app.recomp.data.backup.BackupManager
import app.recomp.data.backup.BackupManifest
import app.recomp.data.backup.PreparedBackup
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where export and import stand (PRD §7.14). */
sealed interface DataState {
    data object Idle : DataState
    data class Busy(val what: String) : DataState
    data class Confirm(val backup: PreparedBackup, val safetyCopy: Boolean) : DataState
    data class Done(val title: String, val message: String) : DataState
}

@HiltViewModel
class DataViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backups: BackupManager,
) : ViewModel() {
    private val _state = MutableStateFlow<DataState>(DataState.Idle)
    val state: StateFlow<DataState> = _state

    /** When the safety copy was made, if there is one (PRD §7.14). */
    private val _safetyCopy = MutableStateFlow<Long?>(null)
    val safetyCopy: StateFlow<Long?> = _safetyCopy

    init {
        viewModelScope.launch { _safetyCopy.value = backups.safetyCopyDate() }
    }

    private fun run(busy: String, failedTitle: String, block: suspend () -> Unit) = viewModelScope.launch {
        _state.value = DataState.Busy(busy)
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupException) {
            _state.value = DataState.Done(failedTitle, e.message ?: "Something went wrong.")
        } catch (e: IOException) {
            _state.value = DataState.Done(failedTitle, "The file couldn't be read or written (${e.message ?: e.javaClass.simpleName}). Nothing on this phone changed.")
        } catch (e: Exception) {
            _state.value = DataState.Done(failedTitle, "Something went wrong: ${e.message ?: e.javaClass.simpleName}. If anything was replaced, Settings → Data can restore the copy from before.")
        }
    }

    /** A failed export leaves no half-written file behind with a backup's name. */
    fun export(uri: Uri) = run("Exporting…", "Not exported") {
        val resolver = context.contentResolver
        val manifest = try {
            withContext(Dispatchers.IO) {
                // "wt" overwrites a file you picked again; a provider without it gets the plain mode. A provider
                // such as Drive may go over the network, so this stays off the main thread.
                val out = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull() ?: resolver.openOutputStream(uri) ?: throw IOException("no file")
                backups.export(out)
            }
        } catch (e: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(resolver, uri) } }
            throw e
        }
        _state.value = DataState.Done("Exported", "Saved ${summary(manifest)}, and your settings.")
    }

    fun open(uri: Uri) = run("Checking the backup…", "Can't import this file") {
        val prepared = withContext(Dispatchers.IO) {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("no file")
            input.use { backups.prepare(it) }
        }
        _state.value = DataState.Confirm(prepared, safetyCopy = false)
    }

    fun openSafetyCopy() = run("Checking the copy…", "Can't restore the copy") {
        _state.value = DataState.Confirm(backups.prepareSafetyCopy(), safetyCopy = true)
    }

    fun confirm() {
        val confirm = _state.value as? DataState.Confirm ?: return
        run("Importing…", "Not imported") {
            try {
                backups.restore(confirm.backup)
            } catch (e: BackupException) {
                backups.discard(confirm.backup)
                throw e
            } finally {
                // A failure after the safety copy was written still leaves a new copy to restore.
                _safetyCopy.value = backups.safetyCopyDate()
            }
            _state.value = DataState.Done("Imported", "Your data is now the backup's: ${summary(confirm.backup.manifest)}.")
        }
    }

    fun dismiss() {
        (_state.value as? DataState.Confirm)?.let { backups.discard(it.backup) }
        _state.value = DataState.Idle
    }

    companion object {
        fun summary(m: BackupManifest): String {
            fun n(count: Int, one: String, many: String) = "$count ${if (count == 1) one else many}"
            return listOf(
                n(m.count("workout_session"), "workout", "workouts"),
                n(m.count("food_log"), "food entry", "food entries"),
                n(m.count("body_weight"), "weigh-in", "weigh-ins"),
                n(m.count("body_composition"), "scan", "scans"),
            ).joinToString(", ")
        }

        fun dateTime(epochMs: Long): String =
            Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
    }
}

/** Settings → Data (DA-01): export through the system's save dialog, import a backup, and undo the last import. */
@Composable
fun DataSettings(viewModel: DataViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val safetyCopy by viewModel.safetyCopy.collectAsStateWithLifecycle()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(viewModel::export) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::open) }

    SettingRow("Export all data", "Save a file") { save.launch(BackupManager.fileName(LocalDate.now())) }
    SettingRow("Import a backup", "Replace") { open.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
    safetyCopy?.let { at ->
        SettingRow("Restore the data from before the import", DataViewModel.dateTime(at), onClick = viewModel::openSafetyCopy)
    }
    Text(
        "A backup holds every workout, food entry, weigh-in, scan and setting. It never holds an AI key.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    when (val s = state) {
        DataState.Idle -> Unit
        is DataState.Busy -> AlertDialog(
            onDismissRequest = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator()
                    Text(s.what)
                }
            },
            confirmButton = {},
        )
        is DataState.Confirm -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text(if (s.safetyCopy) "Restore the copy from before the import?" else "Replace everything with this backup?") },
            text = {
                Text(
                    "${if (s.safetyCopy) "The copy" else "The backup"} from ${DataViewModel.dateTime(s.backup.manifest.exportedAt)} holds " +
                        "${DataViewModel.summary(s.backup.manifest)}, and settings.\n\n" +
                        "Everything on this phone is replaced by it. A copy of what's here now is kept, so you can restore it from Settings → Data.",
                )
            },
            confirmButton = { TextButton(onClick = viewModel::confirm) { Text("Replace") } },
            dismissButton = { TextButton(onClick = viewModel::dismiss) { Text("Cancel") } },
        )
        is DataState.Done -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text(s.title) },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = viewModel::dismiss) { Text("OK") } },
        )
    }
}
