package app.recomp.ui.body

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.recomp.data.repo.PersonaRepository
import app.recomp.data.repo.ScanOffer
import app.recomp.data.repo.TargetPreview
import app.recomp.ui.onboarding.GoalText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ScanTargetsViewModel @Inject constructor(private val personas: PersonaRepository) : ViewModel() {
    val offer: StateFlow<ScanOffer?> = personas.scanOffer.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The before and after, while the dialog is open. */
    val preview = MutableStateFlow<TargetPreview?>(null)

    fun open() = viewModelScope.launch { preview.value = personas.preview() }

    fun close() {
        preview.value = null
    }

    /** Saves the targets with the scan, as Settings would: a new target version from today. */
    fun save() = viewModelScope.launch {
        personas.save()
        preview.value = null
    }

    fun dismiss(offer: ScanOffer) = viewModelScope.launch { personas.dismissScanOffer(offer.measuredAt) }
}

/**
 * Body's *Preview targets with your scan* (IB-05, PRD §12.11): a recent on-protocol scan puts BMR 20 kcal or more
 * from the targets in force. Nothing changes until *Save*; *Not now* hides it until a newer scan.
 */
@Composable
fun ScanTargetsCard(viewModel: ScanTargetsViewModel = hiltViewModel()) {
    val offer by viewModel.offer.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val o = offer ?: return
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Targets from your scan", style = MaterialTheme.typography.titleMedium)
            Text(BodyText.scanOffer(o), color = MaterialTheme.colorScheme.onSecondaryContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.dismiss(o) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Not now") }
                Button(onClick = viewModel::open, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Preview targets") }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    preview?.let { p ->
        AlertDialog(
            onDismissRequest = viewModel::close,
            title = { Text("Targets with your scan") },
            text = { Text(BodyText.scanPreview(p)) },
            confirmButton = { TextButton(onClick = viewModel::save, enabled = p.changes, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save") } },
            dismissButton = { TextButton(onClick = viewModel::close, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
        )
    }
}
