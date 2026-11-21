package app.recomp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.data.prefs.VolumeBand
import app.recomp.domain.training.MuscleVolume
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class VolumeBandViewModel @Inject constructor(private val prefs: SettingsPrefs) : ViewModel() {
    val band = prefs.volumeBand.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VolumeBand())

    fun save(min: Int, max: Int) = viewModelScope.launch { prefs.setVolumeBand(min, max) }
}

/** Settings → Training: the reference band on the Train tab's weekly sets card (PO-10). */
@Composable
fun VolumeBandSetting(viewModel: VolumeBandViewModel = hiltViewModel()) {
    val band by viewModel.band.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    SettingRow("Weekly sets band", "${band.min}–${band.max} sets") { editing = true }
    if (editing) {
        VolumeBandDialog(band, onDismiss = { editing = false }) { min, max ->
            editing = false
            viewModel.save(min, max)
        }
    }
}

@Composable
private fun VolumeBandDialog(band: VolumeBand, onDismiss: () -> Unit, onSave: (Int, Int) -> Unit) {
    var min by remember { mutableStateOf("${band.min}") }
    var max by remember { mutableStateOf("${band.max}") }
    val low = min.toIntOrNull()
    val high = max.toIntOrNull()
    val valid = low != null && high != null && MuscleVolume.validBand(low, high)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Weekly sets band") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("The range marked on the Train tab's weekly sets per muscle. It's a reference only: nothing warns you inside or outside it.")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = min, onValueChange = { min = it.filter(Char::isDigit).take(2) }, label = { Text("From") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = max, onValueChange = { max = it.filter(Char::isDigit).take(2) }, label = { Text("To") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                }
                if (!valid) Text("Enter a low number below a high one, up to ${MuscleVolume.BAND_LIMIT}.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(low!!, high!!) }, enabled = valid) { Text("Save") } },
        dismissButton = {
            TextButton(onClick = { onSave(MuscleVolume.DEFAULT_BAND_MIN, MuscleVolume.DEFAULT_BAND_MAX) }) { Text("Reset to ${MuscleVolume.DEFAULT_BAND_MIN}–${MuscleVolume.DEFAULT_BAND_MAX}") }
        },
    )
}
