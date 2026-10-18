package app.recomp.ui.checkin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.recomp.data.repo.CheckinRepository
import app.recomp.data.repo.CheckinSettingsException
import app.recomp.data.repo.CheckinView
import app.recomp.domain.checkin.CheckinProposal
import app.recomp.domain.checkin.CheckinStatus
import app.recomp.domain.nutrition.Goal
import app.recomp.ui.components.NumberEntryDialog
import app.recomp.ui.components.SectionTitle
import app.recomp.ui.components.Stepper
import app.recomp.ui.navigation.CheckinRoute
import app.recomp.ui.onboarding.GoalText
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The check-in screen's state: loading, gone (no such week), or the week. */
sealed interface CheckinUi {
    data object Loading : CheckinUi
    data object Missing : CheckinUi
    data class Shown(val view: CheckinView) : CheckinUi
}

@HiltViewModel
class CheckinViewModel @Inject constructor(savedState: SavedStateHandle, private val checkins: CheckinRepository) : ViewModel() {
    private val weekStart: LocalDate = LocalDate.parse(savedState.toRoute<CheckinRoute>().weekStart)

    private val _ui = MutableStateFlow<CheckinUi>(CheckinUi.Loading)
    val ui: StateFlow<CheckinUi> = _ui

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** An answer is saving: the proposal's buttons wait for it. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    init {
        reload()
    }

    /** Opening recomputes a pending week, so food logged since the Monday counts. */
    private fun reload() = viewModelScope.launch { load() }

    private suspend fun load(): CheckinView? = checkins.open(weekStart).also { _ui.value = it?.let { v -> CheckinUi.Shown(v) } ?: CheckinUi.Missing }

    /**
     * One answer at a time. A refusal says so only if the week is still open (a second tap finds it answered); a
     * failure before the commit leaves the target as it was; one after it says the target was saved.
     */
    private fun answer(block: suspend () -> Boolean) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            var answered = true
            try {
                answered = block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: CheckinSettingsException) {
                _error.value = CheckinText.settingsNotSaved(e.cause?.message ?: e.cause?.javaClass?.simpleName ?: "unknown")
            } catch (e: Exception) {
                _error.value = "Couldn't save: ${e.message ?: e.javaClass.simpleName}. Your target is unchanged."
            } finally {
                val view = load()
                if (!answered) _error.value = CheckinText.refused(view?.row)
                _busy.value = false
            }
        }
    }

    fun accept(useNewTarget: Boolean = false) = answer { checkins.accept(weekStart, useNewTarget) }

    fun adjust(kcal: Int) = answer { checkins.adjust(weekStart, kcal) }

    fun keep() = answer { checkins.keep(weekStart) }

    fun dismissError() {
        _error.value = null
    }
}

/**
 * The weekly check-in (PRD §12.1, J7): the goal's headline and verdict, the week's averages, the trend and waist,
 * the maintenance estimate, and a proposal only when the goal's flag fired. Targets never change on their own:
 * *Accept* and *Adjust* save a new version from today.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckinScreen(onBack: () -> Unit, viewModel: CheckinViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text((ui as? CheckinUi.Shown)?.view?.row?.weekStart?.let(CheckinText::title) ?: "Weekly check-in") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val state = ui) {
                CheckinUi.Loading -> Unit
                CheckinUi.Missing -> Text("This check-in isn't there any more, perhaps after an import.")
                is CheckinUi.Shown -> CheckinContent(state.view, busy, viewModel)
            }
            error?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::dismissError) { Text("OK") }
            }
        }
    }
}

@Composable
private fun CheckinContent(v: CheckinView, busy: Boolean, viewModel: CheckinViewModel) {
    // 1. The goal's headline and one verdict.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(CheckinText.headline(v), style = MaterialTheme.typography.headlineSmall)
        Text(
            CheckinText.verdict(v),
            style = MaterialTheme.typography.titleMedium,
            color = if (v.status == CheckinStatus.FLAGGED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // 2. The week's averages.
    SectionTitle("7-day averages")
    Text(CheckinText.averages(v.week), style = MaterialTheme.typography.bodyLarge)

    // 3. Trend weight and waist.
    SectionTitle("Trend weight")
    Text(CheckinText.trend(v), style = MaterialTheme.typography.bodyLarge)
    if (v.plan.goal == Goal.RECOMP || v.evaluation.waistChangeCm != null) Text(CheckinText.waist(v), color = MaterialTheme.colorScheme.onSurfaceVariant)

    // 4. The maintenance estimate.
    SectionTitle("Your maintenance estimate")
    Text(CheckinText.estimate(v), style = MaterialTheme.typography.bodyLarge)
    Text(CheckinText.estimateDetail(v), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    // 5. The proposal, or what became of it. The guardrails apply here as in Settings (PRD §4.9), and say so.
    v.notes.forEach { Text(GoalText.guardrail(it), color = MaterialTheme.colorScheme.secondary) }
    val proposal = v.proposal
    val range = v.adjustRange
    if (proposal != null && range != null) {
        ProposalCard(v, proposal.kcal, range, busy, viewModel)
    } else {
        CheckinText.decision(v.row, v.written?.kcal, v.decidedOn)?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        if (v.row.pending && v.row.flag != null && v.row.proposedKcal == null) Text(CheckinText.WITHIN_50, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** *Accept*, *Adjust* (−/+ 50 kcal, held to the floor and caps) and *Keep*; *Use the new target* with your own calories. */
@Composable
private fun ProposalCard(v: CheckinView, proposedKcal: Int, range: IntRange, busy: Boolean, viewModel: CheckinViewModel) {
    var adjusting by remember { mutableStateOf<Int?>(null) }
    var typing by remember { mutableStateOf(false) }
    // The default card, so the outlined buttons keep a visible border in both themes.
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(CheckinText.proposal(proposedKcal, v.current.kcal), style = MaterialTheme.typography.titleMedium)
            Text("Saves a new target from today, at the new maintenance estimate.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            v.kcalOverride?.let { Text(CheckinText.override(it), color = MaterialTheme.colorScheme.onSurfaceVariant) }

            val kcal = adjusting
            if (kcal == null) {
                Button(onClick = { viewModel.accept() }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Accept") }
                if (v.kcalOverride != null) {
                    OutlinedButton(onClick = { viewModel.accept(useNewTarget = true) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Use the new target")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { adjusting = proposedKcal }, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Adjust") }
                    OutlinedButton(onClick = viewModel::keep, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Keep") }
                }
            } else {
                Stepper(
                    label = "Calories",
                    value = GoalText.n(kcal),
                    onMinus = { adjusting = CheckinProposal.step(kcal, up = false, range) },
                    onPlus = { adjusting = CheckinProposal.step(kcal, up = true, range) },
                    onValueClick = { typing = true },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Between ${GoalText.n(range.first)} and ${GoalText.kcal(range.last)}: never below your floor or a 25% deficit, never above a 15% surplus.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (v.kcalOverride != null) Text("Saving replaces your own target of ${GoalText.kcal(v.kcalOverride)}.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { adjusting = null }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Cancel") }
                    Button(onClick = { viewModel.adjust(kcal) }, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Save ${GoalText.kcal(kcal)}") }
                }
            }
        }
    }
    if (typing) {
        NumberEntryDialog("Calories", (adjusting ?: proposedKcal).toString(), decimal = false, onDismiss = { typing = false }, suffix = "kcal") { text ->
            typing = false
            // On the 10 kcal grid, inside the floor and caps.
            text.trim().toIntOrNull()?.let { adjusting = (Math.round(it / 10.0).toInt() * 10).coerceIn(range) }
        }
    }
}
