package app.recomp.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.recomp.data.db.HistoryRow
import app.recomp.data.repo.ExtraEdit
import app.recomp.data.repo.HistoryRepository
import app.recomp.ui.components.ChoiceChips
import app.recomp.ui.components.ConfirmDialog
import app.recomp.ui.components.DateDialog
import app.recomp.ui.components.Labels
import app.recomp.ui.components.NumberEntryDialog
import app.recomp.ui.components.Stepper
import app.recomp.ui.components.formatDuration
import app.recomp.ui.workout.HistoryText
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryUi(val loaded: Boolean = false, val rows: List<HistoryRow> = emptyList(), val total: Int = 0)

/** An extra session open for editing. */
data class ExtraEditing(val sessionId: Long, val name: String, val style: String, val edit: ExtraEdit, val error: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(private val history: HistoryRepository) : ViewModel() {
    private val limit = MutableStateFlow(PAGE)

    val ui: StateFlow<HistoryUi> = combine(limit.flatMapLatest(history::observeHistory), history.observeCount()) { rows, total ->
        HistoryUi(loaded = true, rows = rows, total = total)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUi())

    private val _extra = MutableStateFlow<ExtraEditing?>(null)
    val extra: StateFlow<ExtraEditing?> = _extra

    fun showMore() = limit.update { it + PAGE }

    fun openExtra(row: HistoryRow) = viewModelScope.launch {
        val (session, detail) = history.extra(row.session.id) ?: return@launch
        _extra.value = ExtraEditing(
            session.id, row.routineName ?: Labels.kind(session.kind), detail.style,
            ExtraEdit(session.logDate, detail.durationMin, detail.intensity, session.notes),
        )
    }

    fun changeExtra(edit: ExtraEdit) = _extra.update { it?.copy(edit = edit, error = null) }

    fun closeExtra() {
        _extra.value = null
    }

    fun saveExtra() = viewModelScope.launch {
        val editing = _extra.value ?: return@launch
        val problem = history.saveExtra(editing.sessionId, editing.edit)
        _extra.value = if (problem == null) null else editing.copy(error = problem)
    }

    fun deleteExtra() = viewModelScope.launch {
        _extra.value?.let { history.deleteSession(it.sessionId) }
        _extra.value = null
    }

    private companion object {
        const val PAGE = 50
    }
}

/**
 * History (PRD §12.6): every workout and extra session, newest first. A workout opens its summary, which
 * leads to editing it (WL-10); an extra session opens a small sheet. [deleted] says a workout was just deleted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit, onOpenWorkout: (Long) -> Unit, deleted: Boolean = false, viewModel: HistoryViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val extra by viewModel.extra.collectAsStateWithLifecycle()
    val dates = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("History") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) },
    ) { padding ->
        if (!ui.loaded) return@Scaffold
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (deleted) {
                item(key = "deleted") { Text("Workout deleted. ${HistoryText.RECOMPUTED}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
            }
            if (ui.rows.isEmpty()) {
                item(key = "empty") { Text("No workouts yet. They appear here as you finish them.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(ui.rows, key = { it.session.id }) { row ->
                val isExtra = row.session.kind.isExtra
                Card(Modifier.fillMaxWidth().clickable(onClickLabel = if (isExtra) "Edit" else "Open") { if (isExtra) viewModel.openExtra(row) else onOpenWorkout(row.session.id) }) {
                    Column(Modifier.padding(16.dp).heightIn(min = 40.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(historyTitle(row), style = MaterialTheme.typography.titleMedium)
                        Text(row.session.logDate.format(dates) + " · " + historyDetail(row), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (ui.rows.size < ui.total) {
                item(key = "more") {
                    OutlinedButton(onClick = viewModel::showMore, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Show older") }
                }
            }
        }
    }

    extra?.let { editing ->
        ExtraEditSheet(editing, onChange = viewModel::changeExtra, onDismiss = viewModel::closeExtra, onSave = viewModel::saveExtra, onDelete = viewModel::deleteExtra)
    }
}

internal fun historyTitle(row: HistoryRow): String =
    row.routineName ?: if (row.session.kind.isExtra) Labels.kind(row.session.kind) else "Workout"

/** "52 min · 5 exercises · 15 sets", or for an extra session "60 min · Vinyasa". */
internal fun historyDetail(row: HistoryRow): String {
    if (row.session.kind.isExtra) {
        return listOfNotNull(row.durationMin?.let { "$it min" }, row.style?.takeIf { it.isNotBlank() && it != row.routineName }).joinToString(" · ").ifEmpty { Labels.kind(row.session.kind) }
    }
    val duration = formatDuration(((row.session.endedAt ?: row.session.startedAt) - row.session.startedAt).coerceAtLeast(0))
    return "$duration · ${plural(row.exercises, "exercise")} · ${plural(row.sets, "set")}"
}

private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExtraEditSheet(editing: ExtraEditing, onChange: (ExtraEdit) -> Unit, onDismiss: () -> Unit, onSave: () -> Unit, onDelete: () -> Unit) {
    val edit = editing.edit
    var picking by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    val dates = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(editing.name, style = MaterialTheme.typography.titleLarge)
            if (editing.style.isNotBlank() && editing.style != editing.name) Text(editing.style, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(edit.logDate.format(dates), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { picking = true }) { Text("Change date") }
            }
            Stepper(
                "Minutes",
                "${edit.durationMin}",
                onMinus = { onChange(edit.copy(durationMin = (edit.durationMin - 5).coerceAtLeast(5))) },
                onPlus = { onChange(edit.copy(durationMin = edit.durationMin + 5)) },
                onValueClick = { typing = true },
                minusLabel = "−5",
                plusLabel = "+5",
            )
            Text("Intensity (optional)", style = MaterialTheme.typography.labelLarge)
            ChoiceChips((1..5).toList(), edit.intensity, { "$it" }, { onChange(edit.copy(intensity = if (edit.intensity == it) null else it)) })
            OutlinedTextField(value = edit.notes.orEmpty(), onValueChange = { onChange(edit.copy(notes = it)) }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())
            editing.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Save") }
            TextButton(onClick = { confirming = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Delete session", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (picking) DateDialog(edit.logDate, onDismiss = { picking = false }) { date: LocalDate -> picking = false; onChange(edit.copy(logDate = date)) }
    if (typing) {
        NumberEntryDialog("Minutes", "${edit.durationMin}", decimal = false, onDismiss = { typing = false }) { text ->
            typing = false
            text.trim().toIntOrNull()?.takeIf { it in 1..600 }?.let { onChange(edit.copy(durationMin = it)) }
        }
    }
    if (confirming) {
        ConfirmDialog("Delete this session?", "It's removed from your history and this week's plan. This can't be undone.", "Delete", onDismiss = { confirming = false }) {
            confirming = false
            onDelete()
        }
    }
}
