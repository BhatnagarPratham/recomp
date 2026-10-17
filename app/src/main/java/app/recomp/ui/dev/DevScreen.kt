package app.recomp.ui.dev

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.recomp.workout.ActiveWorkoutService

/** M0 spikes (TRD §10): each card answers one feasibility question on the real phone. */
@Composable
fun DevScreen(viewModel: DevViewModel = hiltViewModel()) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("M0 spikes", style = MaterialTheme.typography.headlineSmall)
        AiSpikeCard(viewModel)
        RestTimerSpikeCard()
        HealthConnectSpikeCard(viewModel)
    }
}

@Composable
private fun SpikeCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun AiSpikeCard(viewModel: DevViewModel) {
    val state by viewModel.ai.collectAsStateWithLifecycle()
    var apiKey by remember { mutableStateOf("") }
    var meal by remember { mutableStateOf("2 phulka, 1 katori rajma, cucumber salad, 1 glass chaas") }

    SpikeCard("(a) Claude on Android", "Anthropic Java SDK + structured output. The key stays in memory only.") {
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(value = meal, onValueChange = { meal = it }, label = { Text("Meal") }, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = { viewModel.runAiSpike(apiKey, meal) },
            enabled = apiKey.isNotBlank() && meal.isNotBlank() && state !is AiSpikeState.Running,
        ) { Text(if (state is AiSpikeState.Running) "Estimating…" else "Estimate") }

        when (val s = state) {
            AiSpikeState.Idle, AiSpikeState.Running -> Unit
            is AiSpikeState.Failed -> Text(s.error, color = MaterialTheme.colorScheme.error)
            is AiSpikeState.Done -> {
                val r = s.result
                Text("${r.latencyMs} ms · ${r.inputTokens} in / ${r.outputTokens} out · stop: ${r.stopReason}")
                val items = r.estimate?.items.orEmpty()
                if (items.isEmpty()) Text("No structured items returned", color = MaterialTheme.colorScheme.error)
                items.forEach {
                    Text("• ${it.name}: ${it.quantity} ${it.unit} (${it.grams.toInt()} g) · ${it.kcal.toInt()} kcal · P ${it.proteinG} g · ${it.confidence}")
                }
                if (items.isNotEmpty()) {
                    Text("Total: ${items.sumOf { it.kcal }.toInt()} kcal · ${"%.1f".format(items.sumOf { it.proteinG })} g protein")
                }
                r.estimate?.clarifyingQuestion?.takeIf { it.isNotBlank() }?.let { Text("Q: $it") }
            }
        }
    }
}

@Composable
private fun RestTimerSpikeCard() {
    val context = LocalContext.current
    var pendingSeconds by remember { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf("") }
    val required = arrayOf(Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.POST_NOTIFICATIONS)

    fun allGranted() = required.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val seconds = pendingSeconds
        pendingSeconds = null
        if (allGranted() && seconds != null) {
            ActiveWorkoutService.startRest(context, seconds)
            message = "Started ${seconds}s rest. Lock the phone and watch the lock screen."
        } else {
            message = "Needs physical activity and notification permissions."
        }
    }

    fun start(seconds: Int) {
        if (allGranted()) {
            ActiveWorkoutService.startRest(context, seconds)
            message = "Started ${seconds}s rest. Lock the phone and watch the lock screen."
        } else {
            pendingSeconds = seconds
            launcher.launch(required)
        }
    }

    SpikeCard(
        "(b) Rest timer on the lock screen",
        "Foreground service of type \"health\". Start a rest, lock the phone: the countdown should show, then vibrate at zero.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { start(30) }) { Text("Rest 30 s") }
            Button(onClick = { start(90) }) { Text("Rest 90 s") }
            OutlinedButton(onClick = { ActiveWorkoutService.stop(context); message = "Stopped." }) { Text("End") }
        }
        if (message.isNotEmpty()) Text(message)
    }
}

@Composable
private fun HealthConnectSpikeCard(viewModel: DevViewModel) {
    val state by viewModel.hc.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        viewModel.refreshHealthConnect()
    }
    LaunchedEffect(Unit) { viewModel.refreshHealthConnect() }

    SpikeCard(
        "(c, e) Health Connect",
        "Do Google Fit steps reach Health Connect? Which body records does the InBody app write?",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { launcher.launch(viewModel.healthConnect.permissions) }) { Text("Grant access") }
            OutlinedButton(onClick = { viewModel.refreshHealthConnect() }) { Text("Read") }
        }
        when (val s = state) {
            HcSpikeState.Unknown, HcSpikeState.Loading -> Text("Checking…")
            is HcSpikeState.Unavailable -> Text(s.reason, color = MaterialTheme.colorScheme.error)
            is HcSpikeState.NeedsPermission -> Text("Granted ${s.granted} of ${s.total} permissions. Tap Grant access.")
            is HcSpikeState.Failed -> Text(s.error, color = MaterialTheme.colorScheme.error)
            is HcSpikeState.Ready -> {
                val r = s.report
                Text("Steps today: ${r.stepsToday} (from ${r.stepsTodayOrigins.ifEmpty { listOf("nobody") }.joinToString()})")
                Text("Steps, last 7 days, by writer:")
                if (r.steps7dByOrigin.isEmpty()) Text("  none")
                r.steps7dByOrigin.forEach { (origin, count) -> Text("  $origin: $count") }
                Text("Body records, last 180 days: ${r.bodyRecords.size}")
                r.bodyRecords.take(30).forEach { Text("  ${it.time.toString().take(10)} ${it.type} ${it.value} · ${it.origin}") }
            }
        }
    }
}
