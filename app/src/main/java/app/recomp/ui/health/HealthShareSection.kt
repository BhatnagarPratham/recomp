package app.recomp.ui.health

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.recomp.data.health.HealthWriter
import app.recomp.data.prefs.DevicePrefs
import app.recomp.data.prefs.HealthShareState
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.data.repo.HealthWriteRepository
import app.recomp.data.repo.ShareResult
import app.recomp.healthconnect.HealthConnectWriter
import app.recomp.ui.onboarding.SwitchRow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class HealthShareViewModel @Inject constructor(
    prefs: SettingsPrefs,
    device: DevicePrefs,
    private val writes: HealthWriteRepository,
    private val writer: HealthWriter,
) : ViewModel() {
    val on: StateFlow<Boolean> = prefs.healthShare.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val status: StateFlow<HealthShareState> = device.healthShare.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HealthShareState())

    private val _note = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = _note

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** Whether turning it on needs Health Connect's permission screen first. */
    suspend fun needsPermission(): Boolean = !runCatching { writer.canWrite() }.getOrDefault(false)

    fun onPermissions(granted: Set<String>) {
        if (granted.containsAll(HealthConnectWriter.PERMISSIONS)) turn(true) else _note.value = HealthText.DENIED
    }

    fun turn(on: Boolean) = viewModelScope.launch {
        _note.value = null
        writes.setShare(on)
        if (on) run { writes.sync() }
    }

    /** *Resync everything*: Recomp's records in Health Connect are replaced by what's in the app now, all of it. */
    fun resync() = viewModelScope.launch { run { writes.sync(everything = true) } }

    private suspend fun run(block: suspend () -> ShareResult) {
        _busy.value = true
        try {
            when (val r = block()) {
                is ShareResult.Failed -> _note.value = "Couldn't write to Health Connect (${r.reason})."
                ShareResult.NotAllowed -> _note.value = HealthWriteRepository.NOT_ALLOWED
                else -> Unit
            }
        } finally {
            _busy.value = false
        }
    }
}

/**
 * Settings → Health Connect → *Share with other apps* (DA-04, PRD §12.12): off by default. On, Recomp writes your
 * workouts, one nutrition record per meal and the weigh-ins you log, and says that apps you let read them may upload
 * them to their own cloud.
 */
@Composable
fun HealthShareSection(viewModel: HealthShareViewModel = hiltViewModel()) {
    val on by viewModel.on.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val note by viewModel.note.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val permissions = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { viewModel.onPermissions(it) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    SwitchRow(HealthText.SHARE_TITLE, HealthText.SHARE_NOTE, on) { want ->
        if (!want) viewModel.turn(false) else scope.launch {
            if (viewModel.needsPermission()) permissions.launch(HealthConnectWriter.PERMISSIONS) else viewModel.turn(true)
        }
    }
    if (on) {
        Text(
            HealthText.shared(status.lastWriteAt) + (status.error?.let { " The last try didn't work: $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = if (status.error != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = viewModel::resync, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (busy) "Writing…" else "Resync everything")
        }
        Text(HealthText.RESYNC_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    note?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
}
