package app.recomp.ui.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.recomp.data.repo.HistoryRepository
import app.recomp.domain.model.LoadType
import app.recomp.domain.model.SetType
import app.recomp.domain.training.EditedExercise
import app.recomp.domain.training.EditedSession
import app.recomp.domain.training.EditedSet
import app.recomp.domain.training.Loads
import app.recomp.domain.training.SessionEdit
import app.recomp.domain.training.Supersets
import app.recomp.ui.navigation.SessionEditRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A set row being edited. [key] is stable while the screen is open; [id] is null for an added set. */
data class EditSetUi(val key: Long, val id: Long?, val type: SetType, val load: String, val reps: String, val rir: Int?)

data class EditExerciseUi(
    val sessionExerciseId: Long,
    val name: String,
    val loadType: LoadType,
    val supersetGroup: Int?,
    val superset: Pair<Int, Int>? = null,
    val sets: List<EditSetUi>,
)

data class SessionEditUi(
    val loaded: Boolean = false,
    /** The workout is gone or isn't one that can be edited here (an active one is edited in the workout screen). */
    val missing: Boolean = false,
    val title: String = "",
    val date: LocalDate = LocalDate.MIN,
    val start: LocalTime = LocalTime.MIDNIGHT,
    val end: LocalTime = LocalTime.MIDNIGHT,
    val notes: String = "",
    val exercises: List<EditExerciseUi> = emptyList(),
    val error: String? = null,
    val dirty: Boolean = false,
    val saving: Boolean = false,
)

sealed interface EditDone {
    data object Saved : EditDone
    data object Deleted : EditDone
}

/**
 * Editing a past workout (WL-10, PRD §12.6). The screen works on a draft: nothing is written until
 * *Save*, which is one transaction ([HistoryRepository.saveEdit]).
 */
@HiltViewModel
class SessionEditViewModel @Inject constructor(savedState: SavedStateHandle, private val history: HistoryRepository) : ViewModel() {
    private val sessionId = savedState.toRoute<SessionEditRoute>().sessionId
    private val zone: ZoneId = ZoneId.systemDefault()
    private var nextKey = 0L
    private var originalStart = 0L
    private var originalEnd = 0L
    private var dayStart: LocalTime = LocalTime.MIDNIGHT
    private var timesTouched = false

    private val _ui = MutableStateFlow(SessionEditUi())
    val ui: StateFlow<SessionEditUi> = _ui

    private val _done = MutableStateFlow<EditDone?>(null)
    val done: StateFlow<EditDone?> = _done

    init {
        viewModelScope.launch {
            val editable = history.editable(sessionId)
            if (editable == null) {
                _ui.value = SessionEditUi(loaded = true, missing = true)
                return@launch
            }
            dayStart = history.dayStart()
            originalStart = editable.session.startedAt
            originalEnd = editable.session.endedAt ?: originalStart
            fun time(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().truncatedTo(ChronoUnit.MINUTES)
            _ui.value = SessionEditUi(
                loaded = true,
                title = editable.routineName ?: "Workout",
                date = editable.session.logDate,
                start = time(originalStart),
                end = time(originalEnd),
                notes = editable.session.notes.orEmpty(),
                exercises = editable.exercises.map { row ->
                    EditExerciseUi(
                        row.entry.id, row.exercise.name, row.exercise.loadType, row.entry.supersetGroup,
                        sets = row.sets.map { EditSetUi(nextKey++, it.id, it.setType, it.loadG?.let(Loads::formatKg).orEmpty(), "${it.reps ?: 0}", it.rir) },
                    )
                }.grouped(),
            )
        }
    }

    /** Superset places, as they'll be once any removed exercise is gone. */
    private fun List<EditExerciseUi>.grouped(): List<EditExerciseUi> {
        val groups = Supersets.normalize(map { it.supersetGroup })
        return mapIndexed { i, ex -> ex.copy(superset = Supersets.position(groups, i)) }
    }

    private fun change(block: (SessionEditUi) -> SessionEditUi) = _ui.update { block(it).copy(dirty = true, error = null) }

    private fun changeSets(sessionExerciseId: Long, block: (List<EditSetUi>) -> List<EditSetUi>) = change { ui ->
        ui.copy(exercises = ui.exercises.map { if (it.sessionExerciseId == sessionExerciseId) it.copy(sets = block(it.sets)) else it })
    }

    private fun changeSet(sessionExerciseId: Long, key: Long, block: (EditSetUi) -> EditSetUi) =
        changeSets(sessionExerciseId) { sets -> sets.map { if (it.key == key) block(it) else it } }

    fun setDate(date: LocalDate) {
        timesTouched = true
        change { it.copy(date = date) }
    }

    fun setStart(time: LocalTime) {
        timesTouched = true
        change { it.copy(start = time) }
    }

    fun setEnd(time: LocalTime) {
        timesTouched = true
        change { it.copy(end = time) }
    }

    fun setNotes(notes: String) = change { it.copy(notes = notes) }
    fun setLoad(exercise: Long, key: Long, text: String) = changeSet(exercise, key) { it.copy(load = text) }
    fun setReps(exercise: Long, key: Long, text: String) = changeSet(exercise, key) { it.copy(reps = text.filter(Char::isDigit).take(3)) }
    fun setType(exercise: Long, key: Long, type: SetType) = changeSet(exercise, key) { it.copy(type = type) }

    /** RIR chips as one button: none → 0 → 1 → 2 → 3+ → none. */
    fun cycleRir(exercise: Long, key: Long) = changeSet(exercise, key) { it.copy(rir = when (it.rir) { null -> 0; 3 -> null; else -> it.rir + 1 }) }

    fun deleteSet(exercise: Long, key: Long) = changeSets(exercise) { sets -> sets.filter { it.key != key } }

    /** A new set starts as a copy of the one above it, the usual case for a set you forgot to tick. */
    fun addSet(exercise: Long) = changeSets(exercise) { sets ->
        val last = sets.lastOrNull()
        sets + EditSetUi(nextKey++, null, SetType.WORKING, last?.load.orEmpty(), last?.reps.orEmpty(), null)
    }

    fun removeExercise(exercise: Long) = change { ui -> ui.copy(exercises = ui.exercises.filter { it.sessionExerciseId != exercise }.grouped()) }

    fun save() {
        val ui = _ui.value
        if (ui.saving || !ui.loaded || ui.missing) return
        val exercises = ui.exercises.map { ex ->
            EditedExercise(
                ex.sessionExerciseId,
                ex.sets.mapIndexed { i, set ->
                    val where = "${ex.name}, set ${i + 1}"
                    val reps = set.reps.toIntOrNull() ?: return fail("$where: enter the reps.")
                    val load = if (Loads.logsLoad(ex.loadType)) Loads.parseKg(set.load) ?: return fail("$where: enter a weight.") else null
                    EditedSet(set.id, set.type, load, reps, set.rir).also { edited ->
                        SessionEdit.setProblem(edited, ex.loadType)?.let { return fail("$where: $it") }
                    }
                },
            )
        }
        val (startedAt, endedAt) = if (timesTouched) SessionEdit.times(ui.date, ui.start, ui.end, zone, dayStart) else originalStart to originalEnd
        _ui.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val problem = history.saveEdit(sessionId, EditedSession(startedAt, endedAt, ui.notes, exercises))
            if (problem == null) _done.value = EditDone.Saved else _ui.update { it.copy(saving = false, error = problem) }
        }
    }

    private fun fail(message: String) {
        _ui.update { it.copy(error = message) }
    }

    fun delete() = viewModelScope.launch {
        history.deleteSession(sessionId)
        _done.value = EditDone.Deleted
    }
}
