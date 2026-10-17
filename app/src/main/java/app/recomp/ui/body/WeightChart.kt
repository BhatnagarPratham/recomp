package app.recomp.ui.body

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.recomp.data.repo.BodyTrend
import app.recomp.domain.trend.TrendCalculator
import java.time.LocalDate
import java.time.temporal.ChronoUnit

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
    val values = shown.map { it.weightG / 1000.0 } + line.map { it.trendKg } + listOfNotNull(edge)
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val description = buildString {
        append("Weight chart, ${shown.size} weigh-ins")
        trend.trendKg?.let { append(", trend ${BodyText.kg(it)}") }
    }

    Canvas(modifier.fillMaxWidth().height(180.dp).semantics { contentDescription = description }) {
        if (values.isEmpty()) return@Canvas
        val lo = values.min()
        val hi = values.max()
        val pad = maxOf((hi - lo) * 0.15, 0.5)
        val yMin = lo - pad
        val yMax = hi + pad
        val span = maxOf(ChronoUnit.DAYS.between(from, today), 1).toDouble()
        val left = 44.dp.toPx()
        val bottom = size.height - 18.dp.toPx()
        val width = size.width - left - 8.dp.toPx()
        fun x(d: LocalDate) = left + (ChronoUnit.DAYS.between(from, d) / span * width).toFloat()
        fun y(kg: Double) = (bottom - (kg - yMin) / (yMax - yMin) * bottom).toFloat()

        // Axis labels: the range's top and bottom, and its first and last day.
        drawText(measurer, BodyText.oneDecimal(yMax), Offset(0f, 0f), labelStyle)
        drawText(measurer, BodyText.oneDecimal(yMin), Offset(0f, bottom - 14.dp.toPx()), labelStyle)
        drawText(measurer, BodyText.shortDate(from), Offset(left, bottom + 2.dp.toPx()), labelStyle)
        val end = measurer.measure(BodyText.shortDate(today), labelStyle)
        drawText(end, topLeft = Offset(size.width - end.size.width, bottom + 2.dp.toPx()))
        drawLine(colors.outlineVariant, Offset(left, bottom), Offset(size.width, bottom), strokeWidth = 1.dp.toPx())

        val path = Path()
        val start = edge?.let { Offset(left, y(it)) }
        start?.let { path.moveTo(it.x, it.y) }
        line.forEachIndexed { i, p ->
            if (i == 0 && start == null) path.moveTo(x(p.date), y(p.trendKg)) else path.lineTo(x(p.date), y(p.trendKg))
        }
        if (line.isNotEmpty()) drawPath(path, colors.primary, style = Stroke(width = 3.dp.toPx()))

        val r = 4.dp.toPx()
        shown.forEach { w ->
            val c = Offset(x(w.logDate), y(w.weightG / 1000.0))
            if (w.id in trend.countedIds) drawCircle(colors.primary, r, c)
            else drawCircle(colors.onSurfaceVariant, r, c, style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}
