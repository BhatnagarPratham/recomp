package app.recomp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.recomp.data.prefs.AiSettings
import app.recomp.data.repo.AiGateView
import app.recomp.data.repo.AiRepository
import app.recomp.data.repo.AiSpend
import app.recomp.domain.ai.AiModel
import app.recomp.domain.ai.AiState
import app.recomp.ui.components.NumberEntryDialog
import app.recomp.ui.components.SectionTitle
import app.recomp.ui.food.AiText
import app.recomp.ui.food.parseNumber
import app.recomp.ui.onboarding.SwitchRow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Settings → AI (AI-01, AI-03, PRD §7.12). */
@HiltViewModel
class AiSettingsViewModel @Inject constructor(private val ai: AiRepository) : ViewModel() {
    val settings: StateFlow<AiSettings?> = ai.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val hasKey: StateFlow<Boolean> = ai.hasKey.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val spend: StateFlow<AiSpend?> = ai.spend.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val gate: StateFlow<AiGateView?> = ai.gate.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setEnabled(on: Boolean) = viewModelScope.launch { ai.setEnabled(on) }
    fun setModel(model: AiModel) = viewModelScope.launch { ai.setModel(model) }
    fun setCap(usd: Double?) = viewModelScope.launch { ai.setMonthlyCap(usd) }
    suspend fun saveKey(key: String) = ai.saveKey(key)
    fun removeKey() = viewModelScope.launch { runCatching { ai.removeKey() } }
}

private enum class AiEditing { KEY, MODEL, CAP }

/** The AI rows of Settings: on or off, then (when on) the key, model, this month's spend and the cap. */
@Composable
fun AiSettingsSection(viewModel: AiSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val hasKey by viewModel.hasKey.collectAsStateWithLifecycle()
    val spend by viewModel.spend.collectAsStateWithLifecycle()
    val gate by viewModel.gate.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<AiEditing?>(null) }
    val s = settings ?: return

    SectionTitle("AI")
    SwitchRow(
        "Use AI",
        "Describe it estimates meals with Claude, using your own API key. Everything works with AI off.",
        s.enabled,
        onChange = viewModel::setEnabled,
    )
    if (s.enabled) {
        SettingRow("API key", if (hasKey) "Saved · Replace" else "Not set") { editing = AiEditing.KEY }
        SettingRow("Model", s.model.label) { editing = AiEditing.MODEL }
        SettingRow("This month", spend?.let { "${AiText.usd(it.costUsd)} · ${it.requests} ${if (it.requests == 1) "request" else "requests"}" } ?: "…", onClick = null)
        SettingRow("Monthly cap", s.monthlyCapUsd?.let(AiText::usd) ?: "None") { editing = AiEditing.CAP }
        if (gate?.state == AiState.OVER_CAP) {
            Text(AiText.notReady(gate).orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
        Text(
            "The Console workspace's spend limit is the hard stop; the cap here is a second guard. " + AiText.PRIVACY,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    when (editing) {
        null -> Unit
        AiEditing.KEY -> ApiKeyDialog(
            hasKey = hasKey,
            onDismiss = { editing = null },
            onSave = { key -> viewModel.saveKey(key); editing = null },
            onRemove = { viewModel.removeKey(); editing = null },
        )
        AiEditing.MODEL -> ChoiceDialog("Model", AiModel.entries, s.model, AiText::model, onDismiss = { editing = null }) {
            editing = null
            viewModel.setModel(it)
        }
        AiEditing.CAP -> NumberEntryDialog(
            "Monthly cap (leave empty for none)",
            s.monthlyCapUsd?.let { if (it % 1.0 == 0.0) "${it.toLong()}" else "$it" }.orEmpty(),
            decimal = true,
            suffix = "$",
            onDismiss = { editing = null },
        ) { text ->
            editing = null
            if (text.isBlank()) viewModel.setCap(null) else parseNumber(text)?.takeIf { it in 0.0..10_000.0 }?.let(viewModel::setCap)
        }
    }
}

/**
 * Pastes the Claude API key (TRD §6.2). The field is masked and never prefilled: a saved key is never shown
 * again, only replaced or removed.
 */
@Composable
fun ApiKeyDialog(hasKey: Boolean, onDismiss: () -> Unit, onSave: suspend (String) -> Unit, onRemove: (() -> Unit)?) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (hasKey) "Replace API key" else "Claude API key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(AiText.KEY_HELP, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    label = { Text("API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (hasKey && onRemove != null) TextButton(onClick = onRemove) { Text("Remove saved key") }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    scope.launch {
                        try {
                            onSave(text)
                        } catch (e: IllegalArgumentException) {
                            error = "That doesn't look like an API key."
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // A Keystore or storage failure: say so, never crash, and never show what was typed.
                            error = "Couldn't store the key on this phone. Try again."
                        }
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
