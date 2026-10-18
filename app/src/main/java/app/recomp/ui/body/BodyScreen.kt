package app.recomp.ui.body

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.recomp.data.db.BodyCompositionEntity
import app.recomp.data.db.BodyWeightEntity
import app.recomp.data.db.MeasurementEntity
import app.recomp.data.prefs.DayBoundary
import app.recomp.data.repo.BodyRepository
import app.recomp.data.repo.BodyTrend
import app.recomp.ui.components.ChoiceChips
import app.recomp.ui.components.SectionTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Logging and editing weigh-ins and waist, shared by Body and Today (PRD §7.8). */
class BodyEntries(private val body: BodyRepository) {
    suspend fun newWeighIn(): WeighInDraft {
        val source = body.primarySource()
        return WeighInDraft(null, body.prefillWeightG(source) ?: DEFAULT_WEIGHT_G, source, System.currentTimeMillis())
    }

    fun edit(row: BodyWeightEntity) = WeighInDraft(row.id, row.weightG, row.source, row.measuredAt)

    suspend fun save(d: WeighInDraft) {
        if (d.id == null) body.logWeighIn(d.weightG, d.source, d.at) else body.updateWeighIn(d.id, d.weightG, d.source, d.at)
    }

    suspend fun newWaist(): WaistDraft = WaistDraft(null, ((body.lastWaistCm() ?: DEFAULT_WAIST_CM) * 10).roundToInt(), System.currentTimeMillis())

    fun edit(row: MeasurementEntity) = WaistDraft(row.id, (row.value * 10).roundToInt(), row.measuredAt)

    suspend fun save(d: WaistDraft) {
        if (d.id == null) body.logWaist(d.tenthsCm / 10.0, d.at) else body.updateMeasurement(d.id, d.tenthsCm / 10.0, d.at)
    }

    private companion object {
        /** Only before any weigh-in or typed weight; the steppers and typing take it from there. */
        const val DEFAULT_WEIGHT_G = 70_000
        const val DEFAULT_WAIST_CM = 85.0
    }
}

@HiltViewModel
class BodyViewModel @Inject constructor(private val body: BodyRepository, days: DayBoundary) : ViewModel() {
    val entries = BodyEntries(body)
    val today: StateFlow<LocalDate> = days.observeToday().stateIn(viewModelScope, SharingStarted.Eagerly, LocalDate.now())
    val trend: StateFlow<BodyTrend?> = body.observeTrend().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val waist: StateFlow<List<MeasurementEntity>> = body.observeWaist().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val scans: StateFlow<List<BodyCompositionEntity>> = body.observeScans().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The chart's window in days; null is everything. */
    val range = MutableStateFlow<Long?>(28)

    fun save(d: WeighInDraft) = viewModelScope.launch { entries.save(d) }
    fun save(d: WaistDraft) = viewModelScope.launch { entries.save(d) }
    suspend fun deleteWeighIn(id: Long) = body.deleteWeighIn(id)
    suspend fun restore(row: BodyWeightEntity) = body.restoreWeighIn(row)
    suspend fun deleteWaist(id: Long) = body.deleteMeasurement(id)
    suspend fun restore(row: MeasurementEntity) = body.restoreMeasurement(row)
}

private val RANGES: List<Long?> = listOf(28L, 84L, null)

private fun rangeLabel(days: Long?) = when (days) {
    28L -> "4 weeks"
    84L -> "12 weeks"
    else -> "All"
}

/**
 * The Body tab (PRD §7.8, §7.9): the weight trend with its weekly rate against the goal's pace, every
 * weigh-in, the weekly waist, and the scan table (HM-02).
 */
@Composable
fun BodyScreen(onOpenScan: (Long?) -> Unit, viewModel: BodyViewModel = hiltViewModel()) {
    val trend by viewModel.trend.collectAsStateWithLifecycle()
    val waist by viewModel.waist.collectAsStateWithLifecycle()
    val scans by viewModel.scans.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    val range by viewModel.range.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var weighIn by remember { mutableStateOf<WeighInDraft?>(null) }
    var waistDraft by remember { mutableStateOf<WaistDraft?>(null) }
    var allWeighIns by remember { mutableStateOf(false) }

    fun undoable(message: String, undo: suspend () -> Unit) = scope.launch {
        if (snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) undo()
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("Body", style = MaterialTheme.typography.headlineMedium) }
            val t = trend ?: return@LazyColumn

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TrendSummary(t)
                        if (t.weighIns.isNotEmpty()) {
                            ChoiceChips(RANGES, range, ::rangeLabel, { viewModel.range.value = it })
                            WeightChart(t, today, range)
                            Text(
                                "Filled dots and the line are the first weigh-in each day from your ${BodyText.sourceLower(t.primary)}. Hollow dots are other weigh-ins, kept out of the trend.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = { scope.launch { weighIn = viewModel.entries.newWeighIn() } },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        ) { Text("Log weight") }
                    }
                }
            }

            if (t.weighIns.isNotEmpty()) {
                item { SectionTitle("Weigh-ins") }
                val newest = t.weighIns.asReversed()
                items(if (allWeighIns) newest else newest.take(RECENT), key = { "w${it.id}" }) { row ->
                    WeighInRow(row, counted = row.id in t.countedIds) {
                        if (row.scanId != null) onOpenScan(row.scanId) else weighIn = viewModel.entries.edit(row)
                    }
                }
                if (newest.size > RECENT && !allWeighIns) {
                    item { TextButton(onClick = { allWeighIns = true }) { Text("Show all ${newest.size}") } }
                }
            }

            item {
                Column {
                    SectionTitle("Waist")
                    WaistCard(waist, today, onLog = { scope.launch { waistDraft = viewModel.entries.newWaist() } }, onEdit = { waistDraft = viewModel.entries.edit(it) })
                }
            }

            item {
                Column {
                    SectionTitle("Scans")
                    ScanTable(scans, onOpen = { onOpenScan(it) }, onLog = { onOpenScan(null) })
                }
            }
        }
    }

    weighIn?.let { d ->
        WeighInSheet(
            d,
            onDismiss = { weighIn = null },
            onDelete = d.id?.let { id ->
                {
                    weighIn = null
                    scope.launch { viewModel.deleteWeighIn(id)?.let { row -> undoable("Deleted the weigh-in") { viewModel.restore(row) } } }
                }
            },
        ) { saved ->
            weighIn = null
            viewModel.save(saved)
        }
    }
    waistDraft?.let { d ->
        WaistSheet(
            d,
            onDismiss = { waistDraft = null },
            onDelete = d.id?.let { id ->
                {
                    waistDraft = null
                    scope.launch { viewModel.deleteWaist(id)?.let { row -> undoable("Deleted the waist measurement") { viewModel.restore(row) } } }
                }
            },
        ) { saved ->
            waistDraft = null
            viewModel.save(saved)
        }
    }
}

private const val RECENT = 10

/** Trend weight and the week's rate against the goal's pace; plain numbers, never failure styling. */
@Composable
fun TrendSummary(t: BodyTrend) {
    val kg = t.trendKg
    if (kg == null) {
        Text("Weight", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(BodyText.NO_TREND)
        if (t.weighIns.isNotEmpty()) {
            Text(
                "Your trend comes from your ${BodyText.sourceLower(t.primary)} (Settings → Body data), which has no weigh-ins yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    Text("Trend weight", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(BodyText.kg(kg), style = MaterialTheme.typography.displaySmall)
    val rate = t.rate
    val pace = t.pace?.let { " · ${BodyText.pace(it)}" }.orEmpty()
    Text(if (rate != null) BodyText.rate(rate) + pace else BodyText.RATE_PENDING, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun WeighInRow(row: BodyWeightEntity, counted: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Edit weigh-in", onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(BodyText.dateTime(row.measuredAt))
            Text(
                BodyText.source(row.source) + if (counted) "" else " · not in the trend",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(BodyText.kg(row.weightG), style = MaterialTheme.typography.titleMedium, fontWeight = if (counted) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** The weekly waist (BM-02): the latest, its change over 4 weeks, and the history. */
@Composable
private fun WaistCard(rows: List<MeasurementEntity>, today: LocalDate, onLog: () -> Unit, onEdit: (MeasurementEntity) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val latest = rows.lastOrNull()
            if (latest == null) {
                Text("Measure your waist once a week. It often keeps moving while the scale stalls.")
            } else {
                Text(BodyText.cm(latest.value), style = MaterialTheme.typography.headlineSmall)
                val monthAgo = rows.lastOrNull { !it.logDate.isAfter(latest.logDate.minusDays(28)) }
                Text(
                    (monthAgo?.let { "${BodyText.signed(latest.value - it.value, "cm")} in 4 weeks · " } ?: "") + "last ${BodyText.date(latest.logDate)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onLog, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (latest != null && latest.logDate.isAfter(today.minusDays(7))) "Log waist again" else "Log waist")
            }
            rows.asReversed().take(8).forEach { row ->
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Edit waist") { onEdit(row) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(BodyText.date(row.logDate), Modifier.weight(1f))
                    Text(BodyText.cm(row.value))
                }
            }
        }
    }
}

/** The scan table (HM-02), newest first, with off-protocol scans marked. Scrolls sideways at large font sizes. */
@Composable
private fun ScanTable(scans: List<BodyCompositionEntity>, onOpen: (Long) -> Unit, onLog: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (scans.isEmpty()) {
                Text(
                    "Optional. If you have access to an InBody or another body-composition (BIA) machine, log each scan here to follow muscle and fat separately.",
                )
            } else {
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    Row {
                        listOf("Date" to DATE_W, "Weight" to NUM_W, "Muscle" to NUM_W, "Fat" to NUM_W, "Fat %" to NUM_W, "Visceral" to NUM_W).forEach { (h, w) ->
                            Text(h, Modifier.width(w), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    scans.forEach { s ->
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Column(Modifier.clickable(role = Role.Button, onClickLabel = "Edit scan") { onOpen(s.id) }.heightIn(min = 48.dp)) {
                            Row {
                                Text(BodyText.shortDate(s.logDate), Modifier.width(DATE_W), style = MaterialTheme.typography.bodyMedium)
                                Cell(BodyText.oneDecimal(s.weightG / 1000.0))
                                Cell(BodyText.oneDecimal(s.skeletalMuscleG / 1000.0))
                                Cell(BodyText.oneDecimal(s.bodyFatG / 1000.0))
                                Cell(BodyText.oneDecimal(s.bodyFatPct))
                                Cell(BodyText.oneDecimal(s.visceralFatLevel))
                            }
                            if (!s.protocolOk) {
                                // As wide as the table, so it wraps instead of running off the card.
                                Text(
                                    "◌ " + BodyText.verdict(s.issues, s.minsFromSlot),
                                    Modifier.width(TABLE_W),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                    }
                }
                Text("Weight, muscle and fat in kg. Off-protocol scans are marked ◌.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onLog, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Log scan") }
        }
    }
}

@Composable
private fun Cell(text: String) = Text(text, Modifier.width(NUM_W), style = MaterialTheme.typography.bodyMedium)

/** Fits a phone at the default font size; at larger sizes the table scrolls sideways. */
private val DATE_W: Dp = 64.dp
private val NUM_W: Dp = 52.dp
private val TABLE_W: Dp = DATE_W + NUM_W * 5
