package app.recomp.ui.dev

import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * M0 spikes (TRD §10): each card answers one feasibility question on the real phone. Spike (b), the
 * lock-screen rest timer, became the real workout service in M1, and spike (a), Claude on Android, became
 * Describe it in M3c.
 */
@Composable
fun DevScreen(viewModel: DevViewModel = hiltViewModel()) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("M0 spikes", style = MaterialTheme.typography.headlineSmall)
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
