package app.recomp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import app.recomp.data.ai.LocalNetwork
import app.recomp.domain.ai.AiModel
import app.recomp.domain.ai.AiProviderId
import app.recomp.domain.ai.AiUrl
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

/** Settings → AI (AI-01, AI-03, AI-05; PRD §7.12, §12.3). */
@HiltViewModel
class AiSettingsViewModel @Inject constructor(private val ai: AiRepository) : ViewModel() {
    val settings: StateFlow<AiSettings?> = ai.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val storedKeys: StateFlow<Set<AiProviderId>> = ai.storedKeys.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    val spend: StateFlow<AiSpend?> = ai.spend.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val gate: StateFlow<AiGateView?> = ai.gate.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setEnabled(on: Boolean) = viewModelScope.launch { ai.setEnabled(on) }
    fun setProvider(provider: AiProviderId) = viewModelScope.launch { ai.setProvider(provider) }
    fun setModel(model: AiModel) = viewModelScope.launch { ai.setModel(model) }
    fun setCap(usd: Double?) = viewModelScope.launch { ai.setMonthlyCap(usd) }
    suspend fun setCompatible(baseUrl: String, model: String, inputUsd: Double, outputUsd: Double) = ai.setCompatible(baseUrl, model, inputUsd, outputUsd)
    suspend fun saveKey(provider: AiProviderId, key: String) = ai.saveKey(key, provider)
    fun removeKey(provider: AiProviderId) = viewModelScope.launch { runCatching { ai.removeKey(provider) } }
    fun localNetworkAnswered() = ai.localNetworkAnswered()
}

private enum class AiEditing { PROVIDER, KEY, MODEL, SERVER, CAP }

/**
 * The AI rows of Settings: on or off, then (when on) the provider, its key and model, this month's spend and the
 * cap. The OpenAI-compatible provider has a server row instead of a model list: its address, model and prices.
 */
@Composable
fun AiSettingsSection(viewModel: AiSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val storedKeys by viewModel.storedKeys.collectAsStateWithLifecycle()
    val spend by viewModel.spend.collectAsStateWithLifecycle()
    val gate by viewModel.gate.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<AiEditing?>(null) }
    // Android's local-network permission, asked for when the server is on your own network.
    val localNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.localNetworkAnswered() }
    val s = settings ?: return
    val provider = s.provider
    val hasKey = provider in storedKeys

    SectionTitle("AI")
    SwitchRow(
        "Use AI",
        "Describe it estimates meals with the AI provider you choose, using your own API key. Everything works with AI off.",
        s.enabled,
        onChange = viewModel::setEnabled,
    )
    if (s.enabled) {
        SettingRow("Provider", provider.label) { editing = AiEditing.PROVIDER }
        if (provider == AiProviderId.COMPATIBLE) {
            SettingRow("Server and model", AiText.compatible(s)) { editing = AiEditing.SERVER }
        } else {
            SettingRow("Model", s.model?.label.orEmpty()) { editing = AiEditing.MODEL }
        }
        if (s.needsKey) {
            SettingRow("API key", if (hasKey) "Saved · Replace" else "Not set") { editing = AiEditing.KEY }
        } else {
            // A server reached over plain http is never sent a key.
            SettingRow("API key", "Not sent over http", onClick = null)
        }
        SettingRow("This month", spend?.let { "${AiText.usd(it.costUsd)} · ${it.requests} ${if (it.requests == 1) "request" else "requests"}" } ?: "…", onClick = null)
        SettingRow("Monthly cap", s.monthlyCapUsd?.let(AiText::usd) ?: "None") { editing = AiEditing.CAP }
        when (gate?.state) {
            AiState.OVER_CAP -> Text(AiText.notReady(gate).orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            AiState.NO_LOCAL_NETWORK -> {
                Text(AiText.LOCAL_NETWORK_REFUSED, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                TextButton(onClick = { localNetwork.launch(LocalNetwork.PERMISSION) }) { Text("Allow") }
            }
            else -> Unit
        }
        if (provider == AiProviderId.COMPATIBLE && s.configured && s.compatibleInputUsd == 0.0 && s.compatibleOutputUsd == 0.0) {
            Text(AiText.FREE_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            AiText.capNote(provider) + " " + AiText.privacy(s),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    when (editing) {
        null -> Unit
        AiEditing.PROVIDER -> ChoiceDialog(
            "Provider",
            AiProviderId.entries,
            provider,
            { it.label },
            onDismiss = { editing = null },
            note = "One provider is in use at a time. Each keeps its own key and model, so you can switch back.",
        ) {
            editing = null
            viewModel.setProvider(it)
        }
        AiEditing.KEY -> ApiKeyDialog(
            provider = provider,
            hasKey = hasKey,
            onDismiss = { editing = null },
            onSave = { key -> viewModel.saveKey(provider, key); editing = null },
            onRemove = { viewModel.removeKey(provider); editing = null },
        )
        AiEditing.MODEL -> ChoiceDialog("Model", AiModel.of(provider), s.model, AiText::model, onDismiss = { editing = null }) {
            editing = null
            viewModel.setModel(it)
        }
        AiEditing.SERVER -> CompatibleServerDialog(
            settings = s,
            onDismiss = { editing = null },
            onSave = { url, model, input, output ->
                viewModel.setCompatible(url.base, model, input, output)
                editing = null
                if (url.localNetwork) localNetwork.launch(LocalNetwork.PERMISSION)
            },
        )
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
 * The OpenAI-compatible provider's server (PRD §12.3): its base URL, the model's name as the server knows it, and
 * its prices per million tokens, which the app can't look up. The URL is checked against AI-05's rules as you
 * save: https anywhere, plain http only to this phone or your own network.
 */
@Composable
private fun CompatibleServerDialog(settings: AiSettings, onDismiss: () -> Unit, onSave: suspend (AiUrl, String, Double, Double) -> Unit) {
    fun price(value: Double) = if (value == 0.0) "" else if (value % 1.0 == 0.0) "${value.toLong()}" else "$value"
    var url by remember { mutableStateOf(settings.baseUrl) }
    var model by remember { mutableStateOf(settings.compatibleModel) }
    var input by remember { mutableStateOf(price(settings.compatibleInputUsd)) }
    var output by remember { mutableStateOf(price(settings.compatibleOutputUsd)) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val checked = AiUrl.ok(url)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("OpenAI-compatible server") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "OpenRouter (https://openrouter.ai/api/v1), or a server on your own network such as Ollama (http://192.168.1.20:11434/v1).",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    label = { Text("Base URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    isError = error != null,
                    supportingText = (error ?: checked?.takeIf { !it.sendsKey }?.let { "Plain http: your API key is never sent to this server." })?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Input price per million tokens") },
                    singleLine = true,
                    prefix = { Text("$") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = output,
                    onValueChange = { output = it },
                    label = { Text("Output price per million tokens") },
                    singleLine = true,
                    prefix = { Text("$") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Leave the prices empty for a free local server. " + AiText.FREE_NOTE, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                enabled = model.isNotBlank(),
                onClick = {
                    val inputUsd = if (input.isBlank()) 0.0 else parseNumber(input)?.takeIf { it in 0.0..10_000.0 }
                    val outputUsd = if (output.isBlank()) 0.0 else parseNumber(output)?.takeIf { it in 0.0..10_000.0 }
                    when (val check = AiUrl.check(url)) {
                        is AiUrl.Check.Bad -> error = AiText.urlProblem(check.problem)
                        is AiUrl.Check.Ok -> if (inputUsd == null || outputUsd == null) {
                            error = "A price is a number of dollars, or empty for free."
                        } else {
                            scope.launch {
                                try {
                                    onSave(check.url, model, inputUsd, outputUsd)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = "Couldn't save that. Try again."
                                }
                            }
                        }
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Pastes [provider]'s API key (TRD §6.2). The field is masked and never prefilled: a saved key is never shown
 * again, only replaced or removed.
 */
@Composable
fun ApiKeyDialog(provider: AiProviderId, hasKey: Boolean, onDismiss: () -> Unit, onSave: suspend (String) -> Unit, onRemove: (() -> Unit)?) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (hasKey) "Replace ${AiText.keyName(provider)}" else AiText.keyName(provider).replaceFirstChar { it.uppercase() }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(AiText.keyHelp(provider), style = MaterialTheme.typography.bodySmall)
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
