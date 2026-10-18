package app.recomp.ui.checkin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.recomp.data.db.WeeklyCheckinEntity
import app.recomp.data.prefs.DayBoundary
import app.recomp.data.repo.CheckinRepository
import app.recomp.ui.settings.SettingRow
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Opens a week's check-in. The nav host provides it around Today and Settings, so neither screen needs a new
 * parameter, and later milestones (M8, M11) change the card here without touching `TodayScreen.kt`.
 */
val LocalOpenCheckin = staticCompositionLocalOf<(LocalDate) -> Unit> { {} }

@HiltViewModel
class CheckinCardViewModel @Inject constructor(private val checkins: CheckinRepository, days: DayBoundary) : ViewModel() {
    /** The latest week's check-in until it's been opened, or the next one is ready. */
    val card: StateFlow<WeeklyCheckinEntity?> = checkins.observeCard().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // The first launch after a week ends computes it (and any missed before it), and so does a Monday that
        // starts while Today is open.
        viewModelScope.launch { days.observeToday().collectLatest { checkins.catchUp(it) } }
    }
}

@HiltViewModel
class LatestCheckinViewModel @Inject constructor(checkins: CheckinRepository) : ViewModel() {
    val latest: StateFlow<WeeklyCheckinEntity?> = checkins.observeLatest().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/**
 * Settings → Targets: the latest check-in, so a proposal you didn't answer can be found again once Today's card
 * has gone. Nothing shows before the first check-in.
 */
@Composable
fun LatestCheckinRow(viewModel: LatestCheckinViewModel = hiltViewModel()) {
    val row by viewModel.latest.collectAsStateWithLifecycle()
    val open = LocalOpenCheckin.current
    val week = row ?: return
    SettingRow("Weekly check-in", CheckinText.cardLine(week)) { open(week.weekStart) }
}

/** Today's *Weekly check-in* card (PRD §12.1). Nothing shows when there's no check-in waiting. */
@Composable
fun WeeklyCheckinCard(viewModel: CheckinCardViewModel = hiltViewModel()) {
    val row by viewModel.card.collectAsStateWithLifecycle()
    val open = LocalOpenCheckin.current
    val week = row ?: return
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Weekly check-in", style = MaterialTheme.typography.titleMedium)
            Text(CheckinText.cardLine(week), color = MaterialTheme.colorScheme.onSecondaryContainer)
            Button(onClick = { open(week.weekStart) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Open check-in") }
        }
    }
}
