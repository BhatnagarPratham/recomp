package app.recomp.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A date picker that opens in typing mode, which is quicker for a date of birth. Material's dates are UTC midnights. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateDialog(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        initialDisplayMode = DisplayMode.Input,
        yearRange = 1900..2100,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state) }
}

/** "Midnight", "4 AM" (PR-03). */
fun formatDayStart(time: LocalTime): String =
    if (time == LocalTime.MIDNIGHT) "Midnight" else time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

/** A time of day, typed (quicker than a dial with one hand). Follows the phone's 12- or 24-hour setting. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initial.hour, initial.minute, android.text.format.DateFormat.is24HourFormat(LocalContext.current))
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimeInput(state) },
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * When something was measured: the day and time, each changeable. Never later than now, since a
 * measurement can be typed in afterwards but not ahead.
 */
@Composable
fun WhenRow(at: Long, label: (Long) -> String, onChange: (Long) -> Unit, modifier: Modifier = Modifier) {
    var picking by remember { mutableStateOf<String?>(null) }
    val zone = ZoneId.systemDefault()
    val local = Instant.ofEpochMilli(at).atZone(zone).toLocalDateTime()
    fun set(dt: LocalDateTime) = onChange(minOf(dt.atZone(zone).toInstant().toEpochMilli(), System.currentTimeMillis()))
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label(at), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = { picking = "day" }) { Text("Day") }
        TextButton(onClick = { picking = "time" }) { Text("Time") }
    }
    when (picking) {
        "day" -> DateDialog(local.toLocalDate(), onDismiss = { picking = null }) { d -> picking = null; set(d.atTime(local.toLocalTime())) }
        "time" -> TimeDialog(local.toLocalTime(), onDismiss = { picking = null }) { t -> picking = null; set(local.toLocalDate().atTime(t)) }
    }
}
