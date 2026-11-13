package app.recomp.ui.body

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.recomp.data.db.BodyCompositionEntity
import app.recomp.data.db.MeasurementEntity
import app.recomp.data.repo.BodyTrend
import app.recomp.data.repo.toScanPoint
import app.recomp.domain.body.ScanPoint
import app.recomp.domain.body.ScanTrends
import app.recomp.ui.components.Chart
import app.recomp.ui.components.ChartDots
import app.recomp.ui.components.ChartPoint
import app.recomp.ui.components.ChartSeries
import java.time.LocalDate

/** How many of the latest changes between on-protocol scans are labelled under the chart. */
private const val CHANGES_SHOWN = 4

/**
 * IB-04's body-composition chart: three small charts on one time axis, over the last [weeks] weeks or everything:
 * muscle and fat mass from scans, the trend weight, and the waist. On-protocol scans are filled and make the lines;
 * off-protocol ones are hollow and left out. Each change between on-protocol scans in the window is labelled with
 * §5.6's noise label, and from three of them the window's slope per 4 weeks shows.
 */
@Composable
fun BodyCompositionChart(
    scans: List<BodyCompositionEntity>,
    trend: BodyTrend,
    waist: List<MeasurementEntity>,
    today: LocalDate,
    weeks: Long?,
    modifier: Modifier = Modifier,
) {
    val points = scans.map { it.toScanPoint() }
    val from = weeks?.let { today.minusWeeks(it).plusDays(1) }
        ?: listOfNotNull(points.minOfOrNull { it.date }, trend.points.firstOrNull()?.date, waist.minOfOrNull { it.logDate }).minOrNull()
        ?: today
    val colors = MaterialTheme.colorScheme
    val muscle = colors.primary
    val fat = colors.tertiary
    val shown = ScanTrends.since(points, from)
    fun series(value: (ScanPoint) -> Double, color: Color) = listOf(
        ChartSeries(ScanTrends.onProtocol(shown).map { ChartPoint(it.date, value(it)) }, color, line = true),
        ChartSeries(shown.filter { !it.onProtocol }.map { ChartPoint(it.date, value(it)) }, color, dots = ChartDots.HOLLOW),
    )

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("● Muscle", color = muscle, style = MaterialTheme.typography.labelLarge)
            Text("● Fat mass", color = fat, style = MaterialTheme.typography.labelLarge)
        }
        Chart(
            series = series({ it.muscleKg }, muscle) + series({ it.fatKg }, fat),
            from = from,
            to = today,
            description = "Muscle and fat mass from ${shown.size} ${if (shown.size == 1) "scan" else "scans"}",
            height = 140.dp,
        )
        Text("Trend weight", style = MaterialTheme.typography.labelLarge)
        Chart(
            series = listOf(ChartSeries(trend.points.map { ChartPoint(it.date, it.trendKg) }, colors.secondary, line = true, dots = ChartDots.NONE)),
            from = from,
            to = today,
            description = "Trend weight" + (trend.trendKg?.let { ", ${BodyText.kg(it)}" } ?: ""),
            height = 100.dp,
        )
        if (waist.any { !it.logDate.isBefore(from) }) {
            Text("Waist", style = MaterialTheme.typography.labelLarge)
            Chart(
                series = listOf(ChartSeries(waist.map { ChartPoint(it.logDate, it.value) }, colors.secondary, line = true)),
                from = from,
                to = today,
                description = "Waist" + (waist.lastOrNull()?.let { ", ${BodyText.cm(it.value)}" } ?: ""),
                height = 100.dp,
            )
        }
        Text(
            "Filled dots are on-protocol scans and make the lines. Hollow ones were off protocol, so they're left out.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        ScanTrends.changes(shown).takeLast(CHANGES_SHOWN).asReversed().forEach { c ->
            Text("${BodyText.shortDate(c.from.date)} → ${BodyText.shortDate(c.to.date)}: ${BodyText.scanChange(c)}", style = MaterialTheme.typography.bodyMedium)
        }
        ScanTrends.slopes(shown)?.let {
            Text(BodyText.scanSlopes(it) + ", " + BodyText.slopeFrom(ScanTrends.onProtocol(shown).size, weeks), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
