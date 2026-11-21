package app.recomp.ui.checkin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import app.recomp.data.repo.AiRepository
import app.recomp.data.repo.ProgrammeReviewRepository
import app.recomp.domain.ai.AiState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Opens the weekly programme review for a week. The nav host provides it around the review. */
val LocalOpenProgrammeReview = staticCompositionLocalOf<(LocalDate) -> Unit> { {} }

/** Whether AI is on, so the review offers itself (PRD §12.7: with AI off the review shows the numbers only). */
@HiltViewModel
class AiOnViewModel @Inject constructor(ai: AiRepository) : ViewModel() {
    val on: StateFlow<Boolean> = ai.gate.map { it.state != AiState.OFF }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}

/** The end of each week's review: *Ask AI to review your programme*, with AI on. */
@Composable
fun ProgrammeReviewButton(weekStart: LocalDate, viewModel: AiOnViewModel = hiltViewModel()) {
    val on by viewModel.on.collectAsStateWithLifecycle()
    if (!on) return
    val open = LocalOpenProgrammeReview.current
    OutlinedButton(onClick = { open(weekStart) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(ProgrammeReviewText.ASK) }
}

/** The check-in card's mention of the review, with AI on. */
@Composable
fun ProgrammeReviewCardLine(viewModel: AiOnViewModel = hiltViewModel()) {
    val on by viewModel.on.collectAsStateWithLifecycle()
    if (on) Text(ProgrammeReviewText.CARD_LINE, style = MaterialTheme.typography.bodySmall)
}

@HiltViewModel
class DeloadCardViewModel @Inject constructor(private val reviews: ProgrammeReviewRepository) : ViewModel() {
    /** Weeks since the last deload while the card is offered, else null. */
    val due: StateFlow<Long?> = reviews.observeDeloadDue().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun accept() = viewModelScope.launch { reviews.acceptDeloadWeek() }

    fun later() = viewModelScope.launch { reviews.snoozeDeloadWeek() }
}

/**
 * Train's scheduled deload card (PRD §4.3, §12.7), with or without AI: offered every 6–8 weeks to intermediate
 * lifters and 4–6 to advanced ones, never to beginners. Accepting marks the next workouts to deload; nothing is
 * applied on its own.
 */
@Composable
fun ScheduledDeloadCard(viewModel: DeloadCardViewModel = hiltViewModel()) {
    val weeks by viewModel.due.collectAsStateWithLifecycle()
    val w = weeks ?: return
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ProgrammeReviewText.DELOAD_TITLE, style = MaterialTheme.typography.titleMedium)
            Text(ProgrammeReviewText.deloadLine(w), color = MaterialTheme.colorScheme.onSecondaryContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::later, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(ProgrammeReviewText.DELOAD_LATER) }
                Button(onClick = viewModel::accept, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(ProgrammeReviewText.DELOAD_ACCEPT) }
            }
        }
    }
}
