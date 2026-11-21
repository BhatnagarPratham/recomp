package app.recomp.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.recomp.data.repo.BodyRepository
import app.recomp.data.repo.TrainingRepository
import app.recomp.domain.body.TapeSite
import app.recomp.domain.body.TapeSites
import app.recomp.ui.body.BodyText
import app.recomp.ui.onboarding.SwitchRow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class BodyExtrasViewModel @Inject constructor(private val body: BodyRepository, training: TrainingRepository) : ViewModel() {
    val tapeSites = body.tapeSites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val mobilityOn = body.observeMobilityOn(training.observeExtras().map { rows -> rows.map { it.kind } })
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setTapeSites(sites: Set<TapeSite>) = viewModelScope.launch { body.setTapeSites(sites) }

    fun setMobility(on: Boolean) = viewModelScope.launch { body.setMobilityMarkers(on) }
}

/** Settings → Body data → Tape measurements (BM-03): any of chest, arm, thigh and hip. Waist is always on. */
@Composable
fun TapeSitesSetting(viewModel: BodyExtrasViewModel = hiltViewModel()) {
    val sites by viewModel.tapeSites.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    SettingRow("Tape measurements", BodyText.tapeSites(sites)) { editing = true }
    if (editing) {
        var chosen by remember { mutableStateOf(sites) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Tape measurements") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Waist is always measured. These join it every 4 weeks.", style = MaterialTheme.typography.bodySmall)
                    TapeSites.OPTIONAL.forEach { site ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Checkbox) { chosen = if (site in chosen) chosen - site else chosen + site },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = site in chosen, onCheckedChange = null)
                            Text(BodyText.site(site), Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { editing = false; viewModel.setTapeSites(chosen) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}

/** Settings → Training → Mobility markers (YG-04): on by default when you have a yoga or mobility session. */
@Composable
fun MobilitySetting(viewModel: BodyExtrasViewModel = hiltViewModel()) {
    val on by viewModel.mobilityOn.collectAsStateWithLifecycle()
    SwitchRow("Mobility markers", "A monthly card on Body: sit-and-reach, a deep-squat hold and a toe touch.", checked = on, onChange = viewModel::setMobility)
}
