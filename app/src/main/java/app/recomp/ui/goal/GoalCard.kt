package app.recomp.ui.goal

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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.recomp.data.prefs.DayBoundary
import app.recomp.data.repo.CheckinRepository
import app.recomp.data.repo.GoalRepository
import app.recomp.domain.persona.GoalStatus
import app.recomp.domain.persona.MaintenanceBreak
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Opens the goal flow (Settings → Goal) for *Choose a new goal*. The nav host provides it around Today and the
 * check-in, like `LocalOpenCheckin`, so neither screen takes a new parameter.
 */
val LocalOpenGoal = staticCompositionLocalOf<() -> Unit> { {} }

@HiltViewModel
class GoalCardViewModel @Inject constructor(private val goals: GoalRepository, checkins: CheckinRepository, days: DayBoundary) : ViewModel() {
    val status: StateFlow<GoalStatus> = goals.status.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoalStatus.None)

    /** The latest check-in's week: the break is suggested there, not in an older week's screen. */
    val latestWeek: StateFlow<LocalDate?> = checkins.observeLatest().map { it?.weekStart }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    init {
        // At launch and on each new log day: a break whose end date has come resumes, and the finish line is checked.
        viewModelScope.launch { days.observeToday().collectLatest { runCatching { goals.refresh() } } }
    }

    /** One answer at a time. A failed save leaves the goal and targets as they were and says so. */
    private fun answer(onChanged: () -> Unit, block: suspend () -> Boolean) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
                _error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "Couldn't save: ${e.message ?: e.javaClass.simpleName}. Check your targets in Settings."
            } finally {
                _busy.value = false
                onChanged()
            }
        }
    }

    fun startBreak(weeks: Int, onChanged: () -> Unit) = answer(onChanged) { goals.startBreak(weeks) }

    fun endEarly(onChanged: () -> Unit) = answer(onChanged) { goals.endBreakEarly() }

    fun notNow(onChanged: () -> Unit) = answer(onChanged) { goals.snoozeBreak() }

    fun keepGoing(onChanged: () -> Unit) = answer(onChanged) { goals.keepGoing() }
}

/**
 * The goal's own card (GL-06, PRD §12.15), on Today and in the check-in: Goal reached with its three choices,
 * the maintenance break's line with *End early* (Today), and the break suggestion after 12 weeks in a deficit
 * (the latest check-in). Nothing shows otherwise.
 *
 * @param checkinWeek the week whose check-in this is in; null on Today.
 * @param onChanged an answer changed the targets, so the check-in reloads.
 */
@Composable
fun GoalStatusCard(checkinWeek: LocalDate? = null, onChanged: () -> Unit = {}, viewModel: GoalCardViewModel = hiltViewModel()) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val latest by viewModel.latestWeek.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val openGoal = LocalOpenGoal.current
    val inCheckin = checkinWeek != null
    if (inCheckin && checkinWeek != latest) return

    when (val s = status) {
        GoalStatus.None -> Unit
        is GoalStatus.Reached -> GoalCardFrame("Goal reached", GoalCardText.reached(s.plan), error) {
            Text(GoalCardText.next(s), color = MaterialTheme.colorScheme.onSecondaryContainer)
            if (s.offerMaintenance) {
                Button(onClick = { viewModel.startBreak(MaintenanceBreak.REACHED_WEEKS, onChanged) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(GoalCardText.START_MAINTENANCE)
                }
            }
            if (s.offerMaintenance) {
                OutlinedButton(onClick = openGoal, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(GoalCardText.NEW_GOAL) }
            } else {
                Button(onClick = openGoal, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(GoalCardText.NEW_GOAL) }
            }
            // While the 2 weeks run there's nothing to keep going with yet; the choice comes back when they end.
            if (s.maintenanceUntil == null) {
                TextButton(onClick = { viewModel.keepGoing(onChanged) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(GoalCardText.KEEP_GOING) }
                Text(GoalCardText.KEEP_GOING_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        is GoalStatus.OnBreak -> if (!inCheckin) {
            GoalCardFrame(null, GoalCardText.onBreak(s), error) {
                Button(onClick = { viewModel.endEarly(onChanged) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(GoalCardText.END_EARLY) }
            }
        }
        is GoalStatus.BreakDue -> if (inCheckin) {
            GoalCardFrame("Maintenance break", GoalCardText.breakTitle(s), error) {
                Text(GoalCardText.breakBody(s), color = MaterialTheme.colorScheme.onSecondaryContainer)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MaintenanceBreak.WEEKS.forEach { weeks ->
                        Button(onClick = { viewModel.startBreak(weeks, onChanged) }, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(GoalCardText.weeks(weeks)) }
                    }
                }
                TextButton(onClick = { viewModel.notNow(onChanged) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(GoalCardText.NOT_NOW) }
                Text(GoalCardText.NOT_NOW_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

@Composable
private fun GoalCardFrame(label: String?, title: String, error: String?, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (label != null) Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }
}
