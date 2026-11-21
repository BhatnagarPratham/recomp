package app.recomp.ui.nudge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.recomp.data.prefs.DayBoundary
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.data.repo.EveningNudge
import app.recomp.data.repo.LoggedSuggestion
import app.recomp.data.repo.TodaySnapshotRepository
import app.recomp.domain.nudge.ReminderSchedule
import app.recomp.domain.nudge.Reminders
import app.recomp.domain.nudge.Suggestion
import app.recomp.reminders.ReminderText
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Today's evening card: tonight's nudge, and the suggestion just logged from it (for *Undo*). */
data class EveningCardUi(val nudge: EveningNudge = EveningNudge.NONE, val logged: LoggedSuggestion? = null, val message: String? = null) {
    val visible: Boolean get() = nudge.lines.isNotEmpty() || logged != null || message != null
}

/** When Today's evening card shows (PRD §12.8): from the evening nudge's time until the log day ends. */
object EveningWindow {
    fun open(now: Instant, logDate: LocalDate, dayStart: LocalTime, zone: ZoneId, reminders: Reminders): Boolean {
        val from = ReminderSchedule.forDay(logDate, dayStart, zone, ReminderSchedule.timed(Reminders(evening = reminders.evening.copy(on = true)), null))
        return from.firstOrNull()?.let { !now.isBefore(it.at) } ?: false
    }
}

@HiltViewModel
class EveningCardViewModel @Inject constructor(
    private val snapshots: TodaySnapshotRepository,
    prefs: SettingsPrefs,
    days: DayBoundary,
) : ViewModel() {
    private val logged = MutableStateFlow<LoggedSuggestion?>(null)
    private val message = MutableStateFlow<String?>(null)

    /** Whether the nudge time has passed, checked again each minute so the card appears while Today is open. */
    private val open: Flow<Boolean> = combine(prefs.reminders, prefs.dayStart, minutes()) { reminders, dayStart, now ->
        EveningWindow.open(Instant.ofEpochMilli(now), days.of(now), dayStart, ZoneId.systemDefault(), reminders)
    }.distinctUntilChanged()

    /** The same nudge the notification carries, whether or not the reminder is on. */
    val ui: StateFlow<EveningCardUi> = combine(snapshots.shared, open, logged, message) { snapshot, open, logged, message ->
        EveningCardUi(if (open) snapshots.evening(snapshot) else EveningNudge.NONE, logged?.takeIf { it.entryId > 0 }, message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EveningCardUi())

    private fun minutes(): Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }

    /** One tap logs it into the meal for the time of day (NU-23). When it can't, the card says why (review N7). */
    fun log(suggestion: Suggestion) = viewModelScope.launch { logNow(suggestion) }

    /** The card's message, if any: why a tap didn't log. */
    val notice: StateFlow<String?> = message

    internal suspend fun logNow(suggestion: Suggestion, now: Long = System.currentTimeMillis()) {
        val done = runCatching { snapshots.log(suggestion, now) }.getOrNull()
        logged.value = done
        message.value = if (done == null) NOT_LOGGED else null
    }

    fun undo() = viewModelScope.launch {
        logged.value?.let { runCatching { snapshots.undo(it) } }
        logged.value = null
    }

    companion object {
        const val NOT_LOGGED = "Couldn't log that: the food isn't available any more. Log something from the Food tab."
    }
}

/**
 * Today's evening card (GL-08, NU-23): what's left tonight and, for protein, 2–3 of your own foods with a portion
 * that fits. Tapping one logs it, with *Undo*. Nothing shows before the nudge time or when nothing is short.
 */
@Composable
fun EveningNudgeCard(viewModel: EveningCardViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    if (!ui.visible) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tonight", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ui.nudge.lines.forEach { Text(ReminderText.line(it), style = MaterialTheme.typography.titleLarge) }
            ui.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            ui.logged?.let { logged ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(ReminderText.logged(logged.suggestion, logged.slot), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { viewModel.undo() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Undo") }
                }
            }
            if (ui.nudge.suggestions.isNotEmpty()) {
                Text("From what you usually eat. Tap one to log it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ui.nudge.suggestions.forEach { s ->
                    OutlinedButton(onClick = { viewModel.log(s) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(ReminderText.suggestion(s))
                    }
                }
            }
        }
    }
}
