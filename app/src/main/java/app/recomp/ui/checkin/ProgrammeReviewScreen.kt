package app.recomp.ui.checkin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.recomp.data.db.ProgrammeReviewEntity
import app.recomp.data.repo.AiRepository
import app.recomp.data.repo.ProgrammeReviewRepository
import app.recomp.data.repo.ProgrammeReviewView
import app.recomp.data.repo.ReviewSendResult
import app.recomp.data.repo.ReviewUndo
import app.recomp.data.repo.TargetPreview
import app.recomp.domain.ai.AiKind
import app.recomp.domain.ai.AiProviderId
import app.recomp.domain.coach.Change
import app.recomp.domain.coach.ProgrammeReport
import app.recomp.domain.coach.ReportSections
import app.recomp.ui.components.SectionTitle
import app.recomp.ui.food.AiText
import app.recomp.ui.navigation.ProgrammeReviewRoute
import app.recomp.ui.onboarding.GoalText
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the programme review is: choosing what's sent, reading it, waiting, or the reply. */
enum class ReviewStep { CHECKLIST, PREVIEW, SENDING, RESULT }

data class ProgrammeReviewUi(
    val week: LocalDate,
    val step: ReviewStep = ReviewStep.CHECKLIST,
    val sections: ReportSections = ReportSections(),
    /** The report as previewed: *Send* sends exactly this, text and refs. */
    val report: ProgrammeReport? = null,
    val view: ProgrammeReviewView? = null,
    val message: String? = null,
    /** A target suggestion's before and after, waiting for *Save*. */
    val target: Pair<Int, TargetPreview>? = null,
    val undo: ReviewUndo? = null,
    val busy: Boolean = false,
)

@HiltViewModel
class ProgrammeReviewViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val reviews: ProgrammeReviewRepository,
    private val ai: AiRepository,
) : ViewModel() {
    private val route = savedState.toRoute<ProgrammeReviewRoute>()
    private val _ui = MutableStateFlow(ProgrammeReviewUi(LocalDate.parse(route.weekStart)))
    val ui: StateFlow<ProgrammeReviewUi> = _ui

    val provider: StateFlow<AiProviderId> = ai.settings.map { it.provider }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiProviderId.DEFAULT)

    /** Whether the provider in use is sent the key: a server over plain http never is. */
    val sendsKey: StateFlow<Boolean> = ai.settings.map { it.needsKey }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val history: StateFlow<List<ProgrammeReviewEntity>> = reviews.observeHistory().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        if (route.reviewId != 0L) open(route.reviewId)
    }

    fun setSections(sections: ReportSections) = _ui.update { it.copy(sections = sections, report = null) }

    /** The full text, exactly as it would be sent. */
    fun preview() = viewModelScope.launch {
        val s = _ui.value.sections
        if (!s.any) return@launch _ui.update { it.copy(message = ProgrammeReviewText.NOTHING_TICKED) }
        val report = reviews.report(_ui.value.week, s)
        _ui.update { it.copy(step = ReviewStep.PREVIEW, report = report, message = null) }
    }

    fun back() = _ui.update { it.copy(step = ReviewStep.CHECKLIST, report = null, message = null) }

    /** Sends the report as previewed, even if the week has changed since. */
    fun send() {
        if (_ui.value.step == ReviewStep.SENDING) return
        val report = _ui.value.report ?: return
        _ui.update { it.copy(step = ReviewStep.SENDING, message = null) }
        viewModelScope.launch {
            ai.markNoticeSeen(AiKind.COACH)
            when (val r = reviews.send(_ui.value.week, report)) {
                is ReviewSendResult.Done -> {
                    _ui.update { it.copy(step = ReviewStep.RESULT, view = reviews.view(r.reviewId)) }
                }
                is ReviewSendResult.NotReady -> {
                    val message = AiText.notReady(ai.gate.first()) ?: "AI isn't ready right now."
                    _ui.update { it.copy(step = ReviewStep.PREVIEW, message = message) }
                }
                is ReviewSendResult.Failed -> _ui.update {
                    it.copy(
                        step = if (r.reviewId != null) ReviewStep.RESULT else ReviewStep.PREVIEW,
                        view = r.reviewId?.let { id -> reviews.view(id) },
                        message = ProgrammeReviewText.failed(r.status, provider.value),
                    )
                }
            }
        }
    }

    /** A past review from the history. */
    fun open(id: Long) = viewModelScope.launch {
        _ui.update { it.copy(step = ReviewStep.RESULT, view = reviews.view(id), message = null, undo = null) }
    }

    fun apply(index: Int) {
        // One answer at a time: a second tap in the same frame does nothing.
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                applyNow(index)
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun applyNow(index: Int) {
        val view = _ui.value.view ?: return
        val change = view.suggestions.getOrNull(index)?.change ?: return
        if (change is Change.Calories || change is Change.Protein) {
            // A target change shows its before and after first, as Settings does.
            reviews.previewTarget(change)?.let { p -> _ui.update { it.copy(target = index to p) } }
            return
        }
        commit(index, change)
    }

    fun confirmTarget() {
        val (index, _) = _ui.value.target ?: return
        if (_ui.value.busy) return
        val change = _ui.value.view?.suggestions?.getOrNull(index)?.change ?: return
        _ui.update { it.copy(target = null, busy = true) }
        viewModelScope.launch {
            try {
                commit(index, change)
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    fun dismissTarget() = _ui.update { it.copy(target = null) }

    private suspend fun commit(index: Int, change: Change) {
        val view = _ui.value.view ?: return
        val undo = reviews.apply(view.row.id, index)
        _ui.update { it.copy(undo = undo, view = reviews.view(view.row.id), message = ProgrammeReviewText.applied(change)) }
    }

    fun undo() {
        val undo = _ui.value.undo ?: return
        if (_ui.value.busy) return
        _ui.update { it.copy(undo = null, busy = true) }
        viewModelScope.launch {
            try {
                reviews.undo(undo)
                val id = _ui.value.view?.row?.id ?: return@launch
                _ui.update { it.copy(view = reviews.view(id), message = ProgrammeReviewText.UNDONE) }
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }
}

/**
 * The weekly programme review with AI (HM-06, PRD §12.7): the checklist, the full text before *Send*, the reply's
 * suggestions to apply one by one, and past reviews. Nothing is sent until *Send*, and nothing changes until *Apply*.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgrammeReviewScreen(onBack: () -> Unit, viewModel: ProgrammeReviewViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val provider by viewModel.provider.collectAsStateWithLifecycle()
    val sendsKey by viewModel.sendsKey.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Programme review") },
                navigationIcon = { TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ui.message?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
            when (ui.step) {
                ReviewStep.CHECKLIST -> Checklist(ui.sections, viewModel::setSections, viewModel::preview)
                ReviewStep.PREVIEW -> {
                    Text("This is everything that's sent:", style = MaterialTheme.typography.titleMedium)
                    Card(Modifier.fillMaxWidth()) {
                        Text(ui.report?.text.orEmpty(), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    Text(ProgrammeReviewText.sentTo(provider, sendsKey), color = muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = viewModel::back, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Change") }
                        Button(onClick = viewModel::send, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(ProgrammeReviewText.SEND) }
                    }
                }
                ReviewStep.SENDING -> {
                    Text(ProgrammeReviewText.SENDING)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                ReviewStep.RESULT -> ui.view?.let { Result(it, ui, viewModel) }
            }

            if (history.isNotEmpty()) {
                SectionTitle(ProgrammeReviewText.HISTORY)
                history.forEach { row ->
                    Text(
                        "Week of ${CheckinText.title(row.weekStart).removePrefix("Week of ")} · " + if (row.responseJson == null) "no reply" else "${appliedCount(row)} applied",
                        Modifier.fillMaxWidth().clickable(role = Role.Button) { viewModel.open(row.id) }.heightIn(min = 48.dp).padding(vertical = 12.dp),
                    )
                }
            }
        }
    }

    ui.target?.let { (_, p) ->
        AlertDialog(
            onDismissRequest = viewModel::dismissTarget,
            title = { Text("New target") },
            text = {
                Text(
                    "Calories ${p.before?.kcal?.let(GoalText::kcal) ?: "–"} → ${GoalText.kcal(p.after.kcal)}\n" +
                        "Protein ${p.before?.proteinG ?: "–"} g → ${p.after.proteinG} g\nSaved as a new target version from today.",
                )
            },
            confirmButton = { TextButton(onClick = viewModel::confirmTarget, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save") } },
            dismissButton = { TextButton(onClick = viewModel::dismissTarget, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
        )
    }
}

private fun appliedCount(row: ProgrammeReviewEntity): Int = row.appliedJson.trim('[', ']').split(',').count { it.isNotBlank() }

@Composable
private fun Checklist(sections: ReportSections, onChange: (ReportSections) -> Unit, onPreview: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text("Choose what's sent", style = MaterialTheme.typography.titleMedium)
    CheckRow(ProgrammeReviewText.GOAL, ProgrammeReviewText.GOAL_DETAIL, sections.goal) { onChange(sections.copy(goal = it)) }
    CheckRow(ProgrammeReviewText.FOOD, ProgrammeReviewText.FOOD_DETAIL, sections.food) { onChange(sections.copy(food = it)) }
    CheckRow(ProgrammeReviewText.TRAINING, ProgrammeReviewText.TRAINING_DETAIL, sections.training) { onChange(sections.copy(training = it)) }
    CheckRow(ProgrammeReviewText.BODY, ProgrammeReviewText.BODY_DETAIL, sections.body) { onChange(sections.copy(body = it)) }
    OutlinedTextField(
        value = sections.note,
        onValueChange = { onChange(sections.copy(note = it.take(500))) },
        label = { Text(ProgrammeReviewText.NOTE_LABEL) },
        placeholder = { Text("e.g. left knee hurts on hack squats") },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(ProgrammeReviewText.NEVER_SENT, style = MaterialTheme.typography.bodySmall, color = muted)
    Button(onClick = onPreview, enabled = sections.any, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(ProgrammeReviewText.PREVIEW) }
}

@Composable
private fun CheckRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { onChange(!checked) }.heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Result(view: ProgrammeReviewView, ui: ProgrammeReviewUi, viewModel: ProgrammeReviewViewModel) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    view.summary?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
    if (view.summary != null && view.suggestions.isEmpty()) Text(ProgrammeReviewText.NO_SUGGESTIONS, color = muted)
    ui.undo?.let { TextButton(onClick = viewModel::undo, modifier = Modifier.heightIn(min = 48.dp)) { Text("Undo") } }
    view.suggestions.forEachIndexed { index, s ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    ProgrammeReviewText.title(s, { view.names[it] ?: "an exercise" }, { view.routineNames[it] ?: "a routine" }),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (s.reason.isNotBlank()) Text(s.reason)
                ProgrammeReviewText.adviceOnly(s)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) }
                when {
                    index in view.applied -> Text(ProgrammeReviewText.APPLIED, color = MaterialTheme.colorScheme.primary)
                    s.change == null -> Unit
                    else -> Button(onClick = { viewModel.apply(index) }, enabled = !ui.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Apply") }
                }
            }
        }
    }
    SectionTitle("What was sent")
    Card(Modifier.fillMaxWidth()) {
        Text(view.row.sentText, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}
