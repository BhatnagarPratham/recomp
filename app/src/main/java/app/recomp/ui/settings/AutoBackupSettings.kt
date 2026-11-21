package app.recomp.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.recomp.data.backup.AutoBackup
import app.recomp.data.backup.AutoBackupScheduler
import app.recomp.data.backup.BackupRun
import app.recomp.data.backup.SafBackupStorage
import app.recomp.data.prefs.AutoBackupState
import app.recomp.data.prefs.BackupTarget
import app.recomp.data.prefs.DevicePrefs
import app.recomp.domain.export.BackupRotation
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.recomp.ui.onboarding.SwitchRow

@HiltViewModel
class AutoBackupViewModel @Inject constructor(
    private val device: DevicePrefs,
    private val storage: SafBackupStorage,
    private val scheduler: AutoBackupScheduler,
    private val backup: AutoBackup,
) : ViewModel() {
    val state: StateFlow<AutoBackupState> = device.autoBackup.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutoBackupState())

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    /** A folder or a file was picked: its permission is kept, the old one given back, and a first backup made now. */
    fun choose(uri: Uri, folder: Boolean) = viewModelScope.launch {
        val old = device.autoBackup.first().target
        val kept = runCatching { storage.keep(uri) }.isSuccess
        if (!kept) {
            device.setBackupError("Recomp couldn't keep access to what you picked. Try another folder, or the Google Drive file.")
            return@launch
        }
        old?.let { if (it.uri != uri.toString()) storage.release(Uri.parse(it.uri)) }
        device.setBackupTarget(BackupTarget(uri.toString(), folder, storage.label(uri, folder)))
        runCatching { scheduler.reschedule() }
        backUpNow()
    }

    /** M17: progress photos in the automatic backup too; off by default. */
    fun setIncludePhotos(on: Boolean) = viewModelScope.launch { device.setBackupPhotos(on) }

    fun turnOff() = viewModelScope.launch {
        device.autoBackup.first().target?.let { storage.release(Uri.parse(it.uri)) }
        device.setBackupTarget(null)
        runCatching { scheduler.reschedule() }
    }

    fun backUpNow() = viewModelScope.launch {
        _running.value = true
        try {
            // Run here, so the result shows at once; a retryable failure is left to the weekly job's backoff.
            if (backup.run() is BackupRun.Failed) runCatching { scheduler.ensureScheduled() }
        } finally {
            _running.value = false
        }
    }
}

/**
 * Settings → Data → *Automatic backup* (DA-02, PRD §12.12): choose where once. A folder on the phone keeps the
 * newest 8; Google Drive gets one file that's overwritten each week.
 */
@Composable
fun AutoBackupSettings(viewModel: AutoBackupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf(false) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { viewModel.choose(it, folder = true) } }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { viewModel.choose(it, folder = false) } }

    val target = state.target
    SettingRow("Automatic backup", AutoBackupText.value(target)) { choosing = true }
    Text(
        AutoBackupText.status(state, running),
        style = MaterialTheme.typography.bodySmall,
        color = if (state.error != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (target != null) {
        SwitchRow("Photos in the automatic backup", AutoBackupText.PHOTOS, checked = state.includePhotos, onChange = viewModel::setIncludePhotos)
    }

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Automatic backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(AutoBackupText.EXPLAIN, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { choosing = false; folder.launch(null) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("A folder on this phone")
                    }
                    OutlinedButton(onClick = { choosing = false; file.launch(BackupRotation.ROLLING_FILE) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("One file, e.g. on Google Drive")
                    }
                    if (target != null) {
                        OutlinedButton(onClick = { choosing = false; viewModel.backUpNow() }, enabled = !running, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("Back up now")
                        }
                        TextButton(onClick = { choosing = false; viewModel.turnOff() }) { Text("Turn off") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosing = false }) { Text("Close") } },
        )
    }
}

/** The words of Settings' automatic backup row (PRD §12.12). */
object AutoBackupText {
    const val PHOTOS = "Progress photos and InBody screenshots go in each weekly backup too. Off, they stay only on this phone."

    const val EXPLAIN =
        "Every Sunday night, when the battery isn't low, Recomp saves a full backup. In a folder on this phone it keeps the newest " +
            "${BackupRotation.KEEP}. On Google Drive, pick one file in the save dialog and it's overwritten each week. Backups never hold an AI key."

    fun value(target: BackupTarget?): String = when {
        target == null -> "Off"
        target.folder -> "Weekly · ${target.label}"
        else -> "Weekly · ${target.label} (one file)"
    }

    fun status(state: AutoBackupState, running: Boolean): String = when {
        running -> "Backing up…"
        state.target == null -> "Off. Choose a folder or a file to back up to every week."
        state.error != null -> state.error + (state.lastSuccessAt?.let { " The last backup that worked: ${DataViewModel.dateTime(it)}." } ?: "")
        state.lastSuccessAt != null -> "Last backup: ${DataViewModel.dateTime(state.lastSuccessAt)}."
        else -> "No backup yet. The first one runs on Sunday night."
    }
}
