package app.recomp.ui.checkin

import android.os.SystemClock
import android.util.Log
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import app.recomp.data.repo.ReviewRepository
import app.recomp.data.repo.WeekReview
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One week of the review: its check-in (null for the week in progress, which has none yet) and its sections. [weeks]
 * is the range ‹ and › move through.
 */
data class ReviewUi(
    val week: LocalDate,
    val weeks: ClosedRange<LocalDate>? = null,
    val checkin: CheckinView? = null,
    val review: WeekReview? = null,
) {
    val loaded: Boolean get() = review != null
    val hasPrevious: Boolean get() = weeks != null && week > weeks.start
    val hasNext: Boolean get() = weeks != null && week < weeks.endInclusive
}

@HiltViewModel
class CheckinViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val checkins: CheckinRepository,
    private val reviews: ReviewRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(ReviewUi(LocalDate.parse(savedState.toRoute<CheckinRoute>().weekStart)))
    val ui: StateFlow<ReviewUi> = _ui

    private val week: LocalDate get() = _ui.value.week

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** An answer is saving: the proposal's buttons wait for it. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    init {
        viewModelScope.launch {
            _ui.update { it.copy(weeks = reviews.weeks()) }
            load()
        }
    }

    /**
     * Opening recomputes a pending check-in, so food logged since the Monday counts, then reads the week's sections.
     * The time it takes is logged: TRD §10 M8 asks that the review open well inside a second.
     */
    private suspend fun load(): CheckinView? {
        val target = week
        val started = SystemClock.elapsedRealtime()
        val checkin = checkins.open(target)
        val review = reviews.week(target)
        Log.i(TAG, "Weekly review loaded in ${SystemClock.elapsedRealtime() - started} ms")
        if (target == week) _ui.update { it.copy(checkin = checkin, review = review) }
        return checkin
    }

    /** ‹ and › move a week at a time, within the weeks there are. */
    fun show(previous: Boolean) {
        val ui = _ui.value
        if (if (previous) !ui.hasPrevious else !ui.hasNext) return
        _ui.update { ReviewUi(it.week.plusWeeks(if (previous) -1 else 1), it.weeks) }
        _error.value = null
        viewModelScope.launch { load() }
    }

    /**
     * One answer at a time. A refusal says so only if the week is still open (a second tap finds it answered); a
     * failure before the commit leaves the target as it was; one after it says the target was saved.
     */
    private fun answer(block: suspend (LocalDate) -> Boolean) {
        if (_busy.value) return
        _busy.value = true
        val target = week
        viewModelScope.launch {
            var answered = true
            try {
                answered = block(target)
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

    fun accept(useNewTarget: Boolean = false) = answer { checkins.accept(it, useNewTarget) }

    fun adjust(kcal: Int) = answer { checkins.adjust(it, kcal) }

    fun keep() = answer { checkins.keep(it) }

    fun dismissError() {
        _error.value = null
    }

    private companion object {
        const val TAG = "Recomp"
    }
}

/**
 * The weekly review (HM-03, HM-04, PRD §12.4), grown from M5's check-in (PRD §12.1, J7). One week at a time, with ‹ and
 * › between weeks and the current week so far. A finished week leads with its check-in: the goal's headline and verdict,
 * the maintenance estimate, and a proposal only when the goal's flag fired. Targets never change on their own:
 * *Accept* and *Adjust* save a new version from today. Then Food, Training, Body, the finish line and the calendar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckinScreen(onBack: () -> Unit, viewModel: CheckinViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val review = ui.review
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ReviewText.title(ui.week, review?.inProgress == true)) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    TextButton(onClick = { viewModel.show(previous = true) }, enabled = ui.hasPrevious, modifier = Modifier.semantics { contentDescription = "Previous week" }) {
                        Text("‹", style = MaterialTheme.typography.headlineSmall)
                    }
                    TextButton(onClick = { viewModel.show(previous = false) }, enabled = ui.hasNext, modifier = Modifier.semantics { contentDescription = "Next week" }) {
                        Text("›", style = MaterialTheme.typography.headlineSmall)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (review == null) return@Column
            val checkin = ui.checkin
            when {
                checkin != null -> CheckinContent(checkin, busy, viewModel)
                review.inProgress -> Text(ReviewText.SO_FAR, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::dismissError) { Text("OK") }
            }
            ReviewSections(review)
        }
    }
}

@Composable
private fun CheckinContent(v: CheckinView, busy: Boolean, viewModel: CheckinViewModel) {
    // The goal's headline and one verdict.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(CheckinText.headline(v), style = MaterialTheme.typography.headlineSmall)
        Text(
            CheckinText.verdict(v),
            style = MaterialTheme.typography.titleMedium,
            color = if (v.status == CheckinStatus.FLAGGED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (v.plan.goal == Goal.RECOMP || v.evaluation.waistChangeCm != null) Text(CheckinText.waist(v), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    // The maintenance estimate. The week's averages and trend are in the review's Food and Body sections below.
    SectionTitle("Your maintenance estimate")
    Text(CheckinText.estimate(v), style = MaterialTheme.typography.bodyLarge)
    Text(CheckinText.estimateDetail(v), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    // The proposal, or what became of it. The guardrails apply here as in Settings (PRD §4.9), and say so.
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
