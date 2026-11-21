package app.recomp.ui.health

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.recomp.data.health.BackgroundRead
import app.recomp.data.health.HealthAvailability
import app.recomp.data.health.HealthFeature
import app.recomp.data.repo.HealthRepository
import app.recomp.data.repo.HealthStatus
import app.recomp.healthconnect.HealthConnectSource
import app.recomp.healthconnect.HealthSyncScheduler
import app.recomp.ui.onboarding.SwitchRow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class HealthConnectViewModel @Inject constructor(private val health: HealthRepository, private val scheduler: HealthSyncScheduler) : ViewModel() {
    private val _status = MutableStateFlow<HealthStatus?>(null)
    val status: StateFlow<HealthStatus?> = _status

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    /** Why the last request changed nothing, shown under the list until the next one. */
    private val _note = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = _note

    /** Health Connect's own screens change permissions behind the app's back, so this runs on every return. */
    fun refresh() = viewModelScope.launch { load() }

    private suspend fun load() {
        _status.value = try {
            health.status()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _note.value = "Couldn't reach Health Connect (${e.message ?: e.javaClass.simpleName})."
            _status.value
        }
    }

    /** Health Connect answered a permission request for [feature]: on only if it granted everything the feature needs. */
    fun onPermissions(feature: HealthFeature, granted: Set<String>) {
        if (granted.containsAll(HealthConnectSource.permissions(feature))) turnOn(feature) else {
            _note.value = HealthText.DENIED
            refresh()
        }
    }

    fun turnOn(feature: HealthFeature) = viewModelScope.launch {
        _note.value = null
        health.setFeature(feature, true)
        syncNow()
    }

    fun turnOff(feature: HealthFeature) = viewModelScope.launch {
        _note.value = null
        health.setFeature(feature, false)
        scheduler.update()
        load()
    }

    fun onBackgroundAnswered(granted: Set<String>) = viewModelScope.launch {
        _note.value = if (HealthConnectSource.BACKGROUND_PERMISSION in granted) null else HealthText.DENIED
        scheduler.update()
        load()
    }

    fun sync() = viewModelScope.launch { syncNow() }

    private suspend fun syncNow() {
        _syncing.value = true
        try {
            health.sync()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _note.value = "Couldn't sync (${e.message ?: e.javaClass.simpleName})."
        } finally {
            _syncing.value = false
        }
        scheduler.update()
        load()
    }
}

/**
 * Settings → Health Connect (PRD §12.9): what Recomp can read and what each needs. Each feature is off until you
 * turn it on, which asks Health Connect for that feature's permission only. Everything works with all of it off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthConnectScreen(onBack: () -> Unit, viewModel: HealthConnectViewModel = hiltViewModel()) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val note by viewModel.note.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var asking by rememberSaveable { mutableStateOf<String?>(null) }
    val permissions = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        val feature = asking?.let { name -> HealthFeature.entries.firstOrNull { it.name == name } }
        asking = null
        if (feature != null) viewModel.onPermissions(feature, granted)
    }
    val background = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { viewModel.onBackgroundAnswered(it) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Scaffold(topBar = { TopAppBar(title = { Text("Health Connect") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { padding ->
        val s = status ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (s.availability) {
                HealthAvailability.UNAVAILABLE -> {
                    Text(HealthText.UNAVAILABLE)
                    return@Column
                }
                HealthAvailability.NEEDS_UPDATE -> {
                    Text(HealthText.NEEDS_UPDATE)
                    return@Column
                }
                HealthAvailability.AVAILABLE -> Unit
            }
            Text(
                "Recomp reads from Health Connect only what you turn on here, and what it reads stays on this phone. It writes there only if you turn on sharing below.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HealthFeature.entries.forEach { feature ->
                val active = feature in s.active
                SwitchRow(HealthText.title(feature), HealthText.needs(feature), active) { on ->
                    when {
                        !on -> viewModel.turnOff(feature)
                        feature in s.granted -> viewModel.turnOn(feature)
                        else -> {
                            asking = feature.name
                            permissions.launch(HealthConnectSource.permissions(feature))
                        }
                    }
                }
                if (feature in s.on && !active) Text(HealthText.NOT_ALLOWED, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                HorizontalDivider()
            }

            Text(HealthText.background(s.background))
            if (s.background == BackgroundRead.NOT_GRANTED && s.active.isNotEmpty()) {
                OutlinedButton(onClick = { background.launch(setOf(HealthConnectSource.BACKGROUND_PERMISSION)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Allow background sync")
                }
            }
            note?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }

            // M16 (DA-04): writing back, off until you turn it on.
            HorizontalDivider()
            HealthShareSection()
            HorizontalDivider()

            Text(
                HealthText.synced(s.sync.lastSyncAt) + (s.sync.error?.let { " Some of it couldn't be read ($it)." } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = viewModel::sync, enabled = !syncing && s.active.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(if (syncing) "Syncing…" else "Sync now")
            }
            OutlinedButton(
                onClick = { runCatching { context.startActivity(HealthConnectClient.getHealthConnectManageDataIntent(context)) } },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(bottom = 16.dp),
            ) { Text("Open Health Connect") }
        }
    }
}
