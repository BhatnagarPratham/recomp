package app.recomp.ui.reminders

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.recomp.data.prefs.SettingsPrefs
import app.recomp.domain.body.ScanSlot
import app.recomp.domain.food.MealSlot
import app.recomp.domain.food.MealSlots
import app.recomp.domain.nudge.MealClock
import app.recomp.domain.nudge.ReminderDay
import app.recomp.domain.nudge.ReminderKind
import app.recomp.domain.nudge.ReminderSchedule
import app.recomp.domain.nudge.Reminders
import app.recomp.domain.training.LogDates
import app.recomp.reminders.AndroidReminderNotifier
import app.recomp.reminders.ReminderText
import app.recomp.ui.body.BodyText
import app.recomp.ui.components.ChoiceChips
import app.recomp.ui.components.DayChips
import app.recomp.ui.components.TimeDialog
import app.recomp.ui.onboarding.SwitchRow
import app.recomp.ui.settings.SettingRow
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Opens Settings → Reminders. The nav host provides it, so `SettingsScreen` needs no new parameter. */
val LocalOpenReminders = staticCompositionLocalOf<() -> Unit> { {} }

data class RemindersUi(
    val loaded: Boolean = false,
    val reminders: Reminders = Reminders(),
    val slots: List<MealSlot> = MealSlots.DEFAULT,
    val scanSlot: ScanSlot? = null,
    val dayStart: LocalTime = LogDates.DEFAULT_DAY_START,
) {
    /** The most that could be due on one day; over [ReminderDay.MAX_PER_DAY], only the first two needed are sent. */
    val mostOnOneDay: Int get() = ReminderSchedule.mostOnOneDay(dayStart, ReminderSchedule.timed(reminders, scanSlot))
    val watched: MealSlot? get() = MealClock.watched(slots, reminders.lunchSlot, reminders.lunch.time)
    val on: Int get() = ReminderKind.entries.count(reminders::isOn)
}

@HiltViewModel
class RemindersViewModel @Inject constructor(private val prefs: SettingsPrefs) : ViewModel() {
    val ui: StateFlow<RemindersUi> = combine(prefs.reminders, prefs.mealSlots, prefs.scanSlot, prefs.dayStart) { reminders, slots, scanSlot, dayStart ->
        RemindersUi(true, reminders, slots, scanSlot, dayStart)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RemindersUi())

    private fun edit(transform: (Reminders) -> Reminders) = viewModelScope.launch { prefs.updateReminders(transform) }

    fun set(kind: ReminderKind, on: Boolean) = edit { it.with(kind, on) }
    fun set(kind: ReminderKind, time: LocalTime) = edit { it.with(kind, time) }
    fun toggleWorkoutDay(day: Int) = edit { r -> DayOfWeek.of(day).let { d -> r.copy(workoutDays = if (d in r.workoutDays) r.workoutDays - d else r.workoutDays + d) } }
    fun watch(slot: String) = edit { it.copy(lunchSlot = slot) }
}

/** The Settings row: "Reminders · 2 on". */
@Composable
fun RemindersSettingsRow(viewModel: RemindersViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val open = LocalOpenReminders.current
    SettingRow("Reminders", if (ui.on == 0) "All off" else "${ui.on} on", onClick = open)
}

/**
 * Settings → Reminders (RM-01, PRD §12.8). Each reminder is off until you turn it on; the first one asks for the
 * notification permission. At most two are sent a day, and the screen says so when more could be due.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersScreen(onBack: () -> Unit, viewModel: RemindersViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permitted by remember { mutableStateOf(AndroidReminderNotifier.permitted(context)) }
    // Coming back from the system's settings may have changed it.
    LifecycleResumeEffect(Unit) {
        permitted = AndroidReminderNotifier.permitted(context)
        onPauseOrDispose { }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = AndroidReminderNotifier.permitted(context) }
    var picking by remember { mutableStateOf<ReminderKind?>(null) }

    fun turn(kind: ReminderKind, on: Boolean) {
        viewModel.set(kind, on)
        if (on && !permitted) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Reminders") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { padding ->
        if (!ui.loaded) return@Scaffold
        val r = ui.reminders
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Each reminder is off until you turn it on. At most ${ReminderDay.MAX_PER_DAY} are sent a day: the first two that are still needed.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ui.mostOnOneDay > ReminderDay.MAX_PER_DAY) {
                Notice("Up to ${ui.mostOnOneDay} of these could be due on one day. Only the first ${ReminderDay.MAX_PER_DAY} still needed will be sent, in time order.")
            }
            if (r.anyOn && !permitted) {
                Notice("Notifications are off for Recomp, so nothing can be sent. Turn them on in the phone's settings for this app. Today's evening card still shows.")
            }

            ReminderRow(ReminderKind.WEIGH_IN, r, ReminderText.skippedWhen(ReminderKind.WEIGH_IN, null), ::turn) { picking = it }
            HorizontalDivider()

            ReminderRow(ReminderKind.LUNCH, r, ReminderText.skippedWhen(ReminderKind.LUNCH, ui.watched?.name), ::turn) { picking = it }
            if (r.lunch.on) {
                Text("Meal it watches", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(ui.slots.filter { !it.removed }, ui.watched, { it.name }, { viewModel.watch(it.key) })
            }
            HorizontalDivider()

            ReminderRow(ReminderKind.EVENING, r, ReminderText.skippedWhen(ReminderKind.EVENING, null), ::turn) { picking = it }
            Text(
                "Today shows the same card from this time, whether or not the reminder is on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()

            ReminderRow(ReminderKind.WORKOUT, r, ReminderText.skippedWhen(ReminderKind.WORKOUT, null), ::turn) { picking = it }
            if (r.workout.on) {
                DayChips(r.workoutDays.map { it.value }.toSet(), onToggle = viewModel::toggleWorkoutDay)
                Text(
                    if (r.workoutDays.isEmpty()) "Pick at least one day, or it never rings." else "These days are only for the reminder. Your routines still rotate in order.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (r.workoutDays.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()

            ReminderRow(ReminderKind.CHECKIN, r, ReminderText.skippedWhen(ReminderKind.CHECKIN, null), ::turn) { picking = it }
            HorizontalDivider()

            val slot = ui.scanSlot
            SwitchRow(
                ReminderText.label(ReminderKind.SCAN),
                if (slot == null) "Set your usual scan slot first (Settings → Body data), or log a scan."
                else ReminderSchedule.scanReminder(slot).let { (day, time) ->
                    "${day.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${BodyText.time(time)}. " + ReminderText.skippedWhen(ReminderKind.SCAN, null)
                },
                checked = r.scan && slot != null,
                enabled = slot != null,
                onChange = { turn(ReminderKind.SCAN, it) },
            )
        }
    }

    picking?.let { kind ->
        val current = ui.reminders.of(kind)?.time ?: return@let
        TimeDialog(current, onDismiss = { picking = null }) { time ->
            picking = null
            viewModel.set(kind, time)
        }
    }
}

@Composable
private fun ReminderRow(kind: ReminderKind, reminders: Reminders, note: String, onTurn: (ReminderKind, Boolean) -> Unit, onPickTime: (ReminderKind) -> Unit) {
    val reminder = reminders.of(kind) ?: return
    SwitchRow(ReminderText.label(kind), note, reminder.on) { onTurn(kind, it) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(BodyText.time(reminder.time), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = { onPickTime(kind) }) { Text("Change time") }
    }
}

@Composable
private fun Notice(text: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Text(text, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}
