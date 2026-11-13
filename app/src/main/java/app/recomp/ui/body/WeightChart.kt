package app.recomp.ui.body

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.recomp.data.db.BodyWeightEntity
import app.recomp.data.repo.BodyTrend
import app.recomp.domain.trend.TrendCalculator
import app.recomp.ui.components.Chart
import app.recomp.ui.components.ChartDots
import app.recomp.ui.components.ChartPoint
import app.recomp.ui.components.ChartSeries
import java.time.LocalDate

/**
 * Weigh-ins as dots and the trend as a line (BM-01, TRD §5.2), over the last [days] days or everything.
 * The trend's weigh-ins are filled; other sources and later same-day readings are hollow.
 */
@Composable
fun WeightChart(trend: BodyTrend, today: LocalDate, days: Long?, modifier: Modifier = Modifier) {
    val from = days?.let { today.minusDays(it - 1) } ?: trend.weighIns.minOfOrNull { it.logDate } ?: today
    val shown = trend.weighIns.filter { !it.logDate.isBefore(from) }
    val line = trend.points.filter { !it.date.isBefore(from) }
    // The line starts at the window's edge, interpolated, so a long window doesn't begin mid-air.
    val edge = TrendCalculator.trendAt(trend.points, from)?.takeIf { line.firstOrNull()?.date != from }
    val colors = MaterialTheme.colorScheme
    val description = buildString {
        append("Weight chart, ${shown.size} ${if (shown.size == 1) "weigh-in" else "weigh-ins"}")
        trend.trendKg?.let { append(", trend ${BodyText.kg(it)}") }
    }
    fun points(rows: List<BodyWeightEntity>) = rows.map { ChartPoint(it.logDate, it.weightG / 1000.0) }
    Chart(
        series = listOf(
            ChartSeries(listOfNotNull(edge?.let { ChartPoint(from, it) }) + line.map { ChartPoint(it.date, it.trendKg) }, colors.primary, line = true, dots = ChartDots.NONE),
            ChartSeries(points(shown.filter { it.id in trend.countedIds }), colors.primary),
            ChartSeries(points(shown.filter { it.id !in trend.countedIds }), colors.onSurfaceVariant, dots = ChartDots.HOLLOW),
        ),
        from = from,
        to = today,
        description = description,
        modifier = modifier,
    )
}
