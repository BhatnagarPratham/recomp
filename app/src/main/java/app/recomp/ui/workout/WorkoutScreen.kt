package app.recomp.ui.workout

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.recomp.domain.model.Equipment
import app.recomp.domain.model.LoadType
import app.recomp.domain.model.SetType
import app.recomp.domain.training.Loads
import app.recomp.ui.components.ConfirmDialog
import app.recomp.ui.components.ExercisePickerSheet
import app.recomp.ui.components.GymTouch
import app.recomp.ui.components.Labels
import app.recomp.ui.components.MenuItem
import app.recomp.ui.components.NumberEntryDialog
import app.recomp.ui.components.OverflowMenu
import app.recomp.ui.components.TextEntryDialog
import app.recomp.ui.components.formatRest
import app.recomp.workout.ActiveWorkoutService
import app.recomp.workout.RestState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface Dialog {
    data object Finish : Dialog
    data object Discard : Dialog
    data object SessionNotes : Dialog
    data class ExerciseNotes(val exercise: ExerciseUi) : Dialog
    data class Remove(val exercise: ExerciseUi) : Dialog
    data class Weight(val setId: Long, val thenTick: Boolean) : Dialog
    data class Reps(val setId: Long) : Dialog
    /** Exercise picker: add when [swapFor] is null, else swap that session exercise ([forStall]: and the routine). */
    data class Pick(val swapFor: ExerciseUi?, val forStall: Boolean = false) : Dialog
}

/**
 * The active workout (PRD §7.4 "Workout screen"): exercise cards scroll above, the selected set sits in
 * the bottom dock with gym-sized steppers, RIR and a full-width ✓.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutScreen(
    onBack: () -> Unit,
    onOpenExercise: (Long) -> Unit,
    onOpenHistory: (Long) -> Unit,
    onFinished: (Long) -> Unit,
    onDiscarded: () -> Unit,
    viewModel: WorkoutViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var leaving by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // The service runs while a session is active (TRD §6.1); it stops itself otherwise.
    LaunchedEffect(ui.loaded, ui.active) { if (ui.loaded && ui.active) ActiveWorkoutService.sync(context) }
    // Ended elsewhere, e.g. finished from the resume prompt: nothing left to log here.
    LaunchedEffect(ui.loaded, ui.active) { if (ui.loaded && !ui.active && !leaving) onBack() }

    // WL-07: keep the screen on while this screen is showing, if you asked for it.
    DisposableEffect(ui.keepScreenOn) {
        val window = activity?.window
        if (ui.keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Bring the selected set's exercise into view when ticking moves on to the next exercise.
    val selectedIndex = ui.exercises.indexOfFirst { ex -> ex.sets.any { it.id == ui.selectedSetId } }
    LaunchedEffect(selectedIndex) { if (selectedIndex >= 0) listState.animateScrollToItem(selectedIndex) }

    // Without the service (permissions refused) the screen vibrates when rest ends.
    val rest = ui.rest
    LaunchedEffect(rest?.endsAt) {
        if (rest == null) return@LaunchedEffect
        val wait = rest.endsAt - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        if (!ActiveWorkoutService.isRunning) {
            if (wait > 0) ActiveWorkoutService.vibrate(context)
            viewModel.restEnded(rest.endsAt)
        }
    }

    fun tick(setId: Long) {
        val result = viewModel.tick(setId)
        if (result is TickResult.NeedsWeight) dialog = Dialog.Weight(result.setId, thenTick = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(ui.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Elapsed(ui.startedAt)
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    Button(onClick = { dialog = Dialog.Finish }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Finish") }
                    OverflowMenu("Workout options") { close ->
                        MenuItem("Add exercise") { close(); dialog = Dialog.Pick(null) }
                        MenuItem(if (ui.notes == null) "Add workout notes" else "Edit workout notes") { close(); dialog = Dialog.SessionNotes }
                        MenuItem(if (ui.keepScreenOn) "Keep screen on: on" else "Keep screen on: off") { close(); viewModel.setKeepScreenOn(!ui.keepScreenOn) }
                        MenuItem("Discard workout") { close(); dialog = Dialog.Discard }
                    }
                },
            )
        },
        bottomBar = {
            SetDock(
                ui = ui,
                onStepLoad = viewModel::stepLoad,
                onStepReps = viewModel::stepReps,
                onTypeLoad = { dialog = Dialog.Weight(it, thenTick = false) },
                onTypeReps = { dialog = Dialog.Reps(it) },
                onRir = viewModel::toggleRir,
                onTick = ::tick,
                onSkipRest = viewModel::skipRest,
                onAddRest = viewModel::addRest,
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(ui.exercises, key = { it.id }) { exercise ->
                ExerciseCard(
                    exercise = exercise,
                    selectedSetId = ui.selectedSetId,
                    isFirst = exercise == ui.exercises.first(),
                    isLast = exercise == ui.exercises.last(),
                    onSelect = viewModel::select,
                    onTick = ::tick,
                    onSetType = viewModel::setType,
                    onDeleteSet = viewModel::deleteSet,
                    onAddSet = { viewModel.addSet(exercise.id) },
                    onSwap = { dialog = Dialog.Pick(exercise) },
                    onMove = { up -> viewModel.move(exercise.id, up) },
                    onNotes = { dialog = Dialog.ExerciseNotes(exercise) },
                    onSettings = { onOpenExercise(exercise.exerciseId) },
                    onHistory = { onOpenHistory(exercise.exerciseId) },
                    onStallDeload = { viewModel.deloadToday(exercise.id) },
                    onStallSwap = { dialog = Dialog.Pick(exercise, forStall = true) },
                    onStallHold = { viewModel.holdStall(exercise.id) },
                    onRemove = { if (exercise.sets.any { it.done }) dialog = Dialog.Remove(exercise) else viewModel.remove(exercise.id) },
                )
            }
            item {
                OutlinedButton(onClick = { dialog = Dialog.Pick(null) }, modifier = Modifier.fillMaxWidth().heightIn(min = GymTouch)) {
                    Text("+ Add exercise")
                }
            }
            ui.notes?.let { notes -> item { Text("Notes: $notes", style = MaterialTheme.typography.bodyMedium) } }
        }
    }

    when (val d = dialog) {
        null -> Unit
        Dialog.Finish -> {
            val (title, text, confirm) = when {
                ui.doneCount == 0 -> Triple("Nothing logged yet", "Discard this workout?", "Discard")
                ui.untickedCount > 0 -> Triple("Finish workout?", "${ui.untickedCount} unticked sets will be dropped.", "Finish")
                else -> Triple("Finish workout?", "Every set is logged.", "Finish")
            }
            ConfirmDialog(title, text, confirm, onDismiss = { dialog = null }) {
                dialog = null
                leaving = true
                scope.launch {
                    if (viewModel.finish()) onFinished(viewModel.sessionId) else { viewModel.discard(); onDiscarded() }
                }
            }
        }
        Dialog.Discard -> ConfirmDialog("Discard workout?", "Nothing from this workout will be kept.", "Discard", onDismiss = { dialog = null }) {
            dialog = null
            leaving = true
            scope.launch { viewModel.discard(); onDiscarded() }
        }
        Dialog.SessionNotes -> TextEntryDialog("Workout notes", ui.notes.orEmpty(), onDismiss = { dialog = null }, singleLine = false) {
            dialog = null
            viewModel.setSessionNotes(it)
        }
        is Dialog.ExerciseNotes -> TextEntryDialog("Notes: ${d.exercise.name}", d.exercise.notes.orEmpty(), onDismiss = { dialog = null }, singleLine = false) {
            dialog = null
            viewModel.setExerciseNotes(d.exercise.id, it)
        }
        is Dialog.Remove -> ConfirmDialog(
            "Remove ${d.exercise.name}?",
            "Its logged sets are removed from this workout. The routine doesn't change.",
            "Remove",
            onDismiss = { dialog = null },
        ) {
            dialog = null
            viewModel.remove(d.exercise.id)
        }
        is Dialog.Weight -> {
            val current = ui.exercises.flatMap { it.sets }.firstOrNull { it.id == d.setId }
            NumberEntryDialog(
                "Weight" + if (ui.selected?.first?.equipment == Equipment.DUMBBELL) " per dumbbell" else "",
                current?.load?.let(Loads::formatKg).orEmpty(),
                decimal = true,
                suffix = "kg",
                onDismiss = { dialog = null },
            ) { text ->
                dialog = null
                val grams = Loads.parseKg(text) ?: return@NumberEntryDialog
                viewModel.enterLoad(d.setId, grams)
                if (d.thenTick) viewModel.tick(d.setId, loadOverride = grams)
            }
        }
        is Dialog.Reps -> {
            val current = ui.exercises.flatMap { it.sets }.firstOrNull { it.id == d.setId }
            NumberEntryDialog("Reps", current?.reps?.toString().orEmpty(), decimal = false, onDismiss = { dialog = null }) { text ->
                dialog = null
                text.trim().toIntOrNull()?.takeIf { it >= 0 }?.let { viewModel.enterReps(d.setId, it) }
            }
        }
        is Dialog.Pick -> ExercisePickerSheet(
            title = d.swapFor?.let { "Swap ${it.name}" } ?: "Add exercise",
            library = library,
            onDismiss = { dialog = null },
            onPick = { ex ->
                dialog = null
                when {
                    d.swapFor == null -> viewModel.addExercise(ex)
                    d.forStall -> viewModel.swapForStall(d.swapFor, ex)
                    else -> viewModel.swap(d.swapFor.id, ex)
                }
            },
            onCreate = { new ->
                dialog = null
                viewModel.createExercise(new, swapFor = d.swapFor?.id)
            },
        )
    }
}

@Composable
private fun Elapsed(startedAt: Long) {
    val now by produceState(System.currentTimeMillis(), startedAt) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val seconds = ((now - startedAt) / 1000).coerceAtLeast(0)
    Text(
        if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun loadText(ex: ExerciseUi, load: Int?): String = when (ex.loadType) {
    LoadType.BODYWEIGHT -> "BW"
    LoadType.BODYWEIGHT_PLUS -> if (load == null || load == 0) "BW" else "BW+${Loads.formatKg(load)}"
    else -> load?.let { Loads.formatKg(it) } ?: "—"
}

@Composable
private fun ExerciseCard(
    exercise: ExerciseUi,
    selectedSetId: Long?,
    isFirst: Boolean,
    isLast: Boolean,
    onSelect: (Long) -> Unit,
    onTick: (Long) -> Unit,
    onSetType: (Long, SetType) -> Unit,
    onDeleteSet: (Long) -> Unit,
    onAddSet: () -> Unit,
    onSwap: () -> Unit,
    onMove: (Boolean) -> Unit,
    onNotes: () -> Unit,
    onSettings: () -> Unit,
    onHistory: () -> Unit,
    onStallDeload: () -> Unit,
    onStallSwap: () -> Unit,
    onStallHold: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(top = 4.dp)) {
                    Text(exercise.name, style = MaterialTheme.typography.titleMedium)
                    val details = buildList {
                        add("${exercise.repMin}–${exercise.repMax} reps")
                        add("rest ${formatRest(exercise.restSec)}")
                        if (exercise.equipment == Equipment.DUMBBELL && exercise.logsLoad) add("kg per dumbbell")
                        if (exercise.unilateral) add("reps per side")
                    }
                    Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    exercise.setupNotes?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic) }
                    Text(
                        exercise.last?.let { "Last: $it" } ?: "First time: pick a weight you could lift ${exercise.repMax} times with about 2 reps left",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    exercise.targetText?.let {
                        Text("Today: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                }
                OverflowMenu("More options for ${exercise.name}") { close ->
                    MenuItem("Swap exercise", enabled = exercise.canSwap) { close(); onSwap() }
                    MenuItem("Move up", enabled = !isFirst) { close(); onMove(true) }
                    MenuItem("Move down", enabled = !isLast) { close(); onMove(false) }
                    MenuItem(if (exercise.notes == null) "Add notes" else "Edit notes") { close(); onNotes() }
                    MenuItem("History") { close(); onHistory() }
                    MenuItem("Exercise settings") { close(); onSettings() }
                    MenuItem("Remove from workout") { close(); onRemove() }
                }
            }
            if (exercise.showStall) StallCard(onStallDeload, onStallSwap, onStallHold)
            Spacer(Modifier.height(4.dp))
            exercise.sets.forEach { set ->
                SetRow(exercise, set, selected = set.id == selectedSetId, onSelect = { onSelect(set.id) }, onTick = { onTick(set.id) },
                    onType = { onSetType(set.id, it) }, onDelete = { onDeleteSet(set.id) })
            }
            Row {
                TextButton(onClick = onAddSet, modifier = Modifier.heightIn(min = 48.dp)) { Text("+ Add set") }
            }
            exercise.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 12.dp, bottom = 4.dp)) }
        }
    }
}

@Composable
private fun SetRow(
    exercise: ExerciseUi,
    set: SetUi,
    selected: Boolean,
    onSelect: () -> Unit,
    onTick: () -> Unit,
    onType: (SetType) -> Unit,
    onDelete: () -> Unit,
) {
    val ghostColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val background = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface.copy(alpha = 0f)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).background(background, MaterialTheme.shapes.small).clickable(onClick = onSelect)
            .semantics { stateDescription = if (set.done) "logged" else if (selected) "selected" else "not logged" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        var menu by remember { mutableStateOf(false) }
        Box {
            TextButton(
                onClick = { menu = true },
                modifier = Modifier.size(48.dp).semantics { contentDescription = "Set ${set.label}, ${Labels.setType(set.type)}. Change type" },
                contentPadding = PaddingValues(0.dp),
            ) { Text(set.label, fontWeight = FontWeight.Bold) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                SetType.entries.forEach { type -> MenuItem(Labels.setType(type)) { menu = false; onType(type) } }
                MenuItem("Delete set") { menu = false; onDelete() }
            }
        }
        Text(
            loadText(exercise, set.load),
            modifier = Modifier.widthIn(min = 72.dp),
            style = MaterialTheme.typography.titleMedium,
            color = if (set.loadIsGhost && !set.done) ghostColor else MaterialTheme.colorScheme.onSurface,
        )
        Text("×", modifier = Modifier.padding(horizontal = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            set.reps?.toString() ?: "—",
            modifier = Modifier.widthIn(min = 32.dp),
            style = MaterialTheme.typography.titleMedium,
            color = if (set.repsIsGhost && !set.done) ghostColor else MaterialTheme.colorScheme.onSurface,
        )
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            set.rir?.let { Text("RIR ${if (it >= 3) "3+" else it}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            set.feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
        }
        val tickDescription = if (set.done) "Undo set ${set.label}" else "Log set ${set.label}"
        if (set.done) {
            Button(onClick = onTick, modifier = Modifier.size(48.dp).semantics { contentDescription = tickDescription }, contentPadding = PaddingValues(0.dp)) { Text("✓") }
        } else {
            OutlinedButton(onClick = onTick, modifier = Modifier.size(48.dp).semantics { contentDescription = tickDescription }, contentPadding = PaddingValues(0.dp)) { Text("✓") }
        }
        Spacer(Modifier.width(8.dp))
    }
}

/** The bottom dock: rest banner, then the selected set's steppers, RIR and ✓ (WL-02 to WL-05). */
@Composable
private fun SetDock(
    ui: WorkoutUi,
    onStepLoad: (Long, Boolean) -> Unit,
    onStepReps: (Long, Boolean) -> Unit,
    onTypeLoad: (Long) -> Unit,
    onTypeReps: (Long) -> Unit,
    onRir: (Long, Int) -> Unit,
    onTick: (Long) -> Unit,
    onSkipRest: () -> Unit,
    onAddRest: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ui.rest?.let { RestBanner(it, onSkipRest, onAddRest) }
            val (ex, set) = ui.selected ?: run {
                Text(
                    if (ui.exercises.isEmpty()) "Add an exercise to start" else "Every set is logged. Finish when you're ready.",
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            val position = ex.sets.indexOf(set) + 1
            Text(
                "${ex.name} · set $position of ${ex.sets.size}" + if (set.type != SetType.WORKING) " · ${Labels.setType(set.type).lowercase()}" else "",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (ex.logsLoad) {
                val step = Loads.formatKg(ex.incrementG)
                DockStepper(
                    label = if (ex.loadType == LoadType.BODYWEIGHT_PLUS) "+ kg" else "kg",
                    value = set.load?.let(Loads::formatKg) ?: if (ex.loadType == LoadType.BODYWEIGHT_PLUS) "0" else "—",
                    ghost = set.loadIsGhost && !set.done,
                    minus = "−$step",
                    plus = "+$step",
                    description = "weight",
                    onMinus = { onStepLoad(set.id, false) },
                    onPlus = { onStepLoad(set.id, true) },
                    onValue = { onTypeLoad(set.id) },
                )
            }
            DockStepper(
                label = "reps",
                value = set.reps?.toString() ?: "—",
                ghost = set.repsIsGhost && !set.done,
                minus = "−1",
                plus = "+1",
                description = "reps",
                onMinus = { onStepReps(set.id, false) },
                onPlus = { onStepReps(set.id, true) },
                onValue = { onTypeReps(set.id) },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("RIR", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                (0..3).forEach { rir ->
                    val on = set.rir == rir || (rir == 3 && (set.rir ?: 0) >= 3)
                    val label = if (rir == 3) "3+" else "$rir"
                    Surface(
                        onClick = { onRir(set.id, rir) },
                        shape = MaterialTheme.shapes.small,
                        color = if (on) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (on) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(width = 44.dp, height = GymTouch).semantics {
                            contentDescription = "$label reps in reserve"
                            stateDescription = if (on) "selected" else "not selected"
                        },
                    ) { Box(contentAlignment = Alignment.Center) { Text(label, fontWeight = FontWeight.SemiBold) } }
                }
                Button(
                    onClick = { onTick(set.id) },
                    modifier = Modifier.weight(1f).height(GymTouch).semantics { contentDescription = if (set.done) "Undo this set" else "Log this set" },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) { Text(if (set.done) "✓ Logged" else "✓", style = MaterialTheme.typography.titleLarge) }
            }
        }
    }
}

@Composable
private fun DockStepper(
    label: String,
    value: String,
    ghost: Boolean,
    minus: String,
    plus: String,
    description: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onValue: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(
            onClick = onMinus,
            modifier = Modifier.size(width = 72.dp, height = GymTouch).semantics { contentDescription = "$description $minus" },
            contentPadding = PaddingValues(0.dp),
        ) { Text(minus, style = MaterialTheme.typography.titleMedium) }
        Surface(
            onClick = onValue,
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.weight(1f).height(GymTouch).semantics { contentDescription = "$description $value, tap to type" },
        ) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    value,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (ghost) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface,
                )
                Text(" $label", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FilledTonalButton(
            onClick = onPlus,
            modifier = Modifier.size(width = 72.dp, height = GymTouch).semantics { contentDescription = "$description $plus" },
            contentPadding = PaddingValues(0.dp),
        ) { Text(plus, style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun RestBanner(rest: RestState, onSkip: () -> Unit, onAdd: () -> Unit) {
    val now by produceState(System.currentTimeMillis(), rest.endsAt) {
        while (true) {
            value = System.currentTimeMillis()
            delay(250)
        }
    }
    val remaining = rest.remainingMs(now)
    val seconds = ((remaining + 999) / 1000).toInt()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                if (remaining > 0) "Rest ${formatRest(seconds)}" else "Rest over",
                style = MaterialTheme.typography.titleLarge,
                color = if (remaining > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
            )
            if (rest.totalSec > 0) {
                LinearProgressIndicator(
                    progress = { (remaining / (rest.totalSec * 1000f)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
        OutlinedButton(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) { Text("+30 s") }
        OutlinedButton(onClick = onSkip, modifier = Modifier.heightIn(min = 48.dp)) { Text("Skip") }
    }
}

/** PO-05: offered, never auto-applied. Hidden once the exercise's first set is ticked. */
@Composable
private fun StallCard(onDeload: () -> Unit, onSwap: () -> Unit, onHold: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(end = 12.dp, top = 8.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No gain lately", style = MaterialTheme.typography.titleSmall)
            Text("Your estimated 1RM hasn't moved for a few sessions. Pick what to do; nothing changes until you do.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDeload, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(4.dp)) { Text("Deload 10%") }
                OutlinedButton(onClick = onSwap, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(4.dp)) { Text("Swap") }
                OutlinedButton(onClick = onHold, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(4.dp)) { Text("Hold") }
            }
        }
    }
}
