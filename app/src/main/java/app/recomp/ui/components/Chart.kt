package app.recomp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.recomp.ui.body.BodyText
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A value on a day. */
data class ChartPoint(val date: LocalDate, val value: Double)

/** How a series' points are marked. A line can carry points that aren't marked, e.g. its start at the window's edge. */
enum class ChartDots { FILLED, HOLLOW, NONE }

/** One series: drawn as a line through its points, marked with dots, or both. */
data class ChartSeries(val points: List<ChartPoint>, val color: Color, val line: Boolean = false, val dots: ChartDots = ChartDots.FILLED)

/**
 * A small time chart on `Canvas` (TRD §10 M8): days from [from] to [to] across, the series' values up, with the range's
 * top and bottom and its first and last day as labels. Points outside the days are left out. Charts that share [from]
 * and [to] share their time axis, so they can be stacked.
 */
@Composable
fun Chart(
    series: List<ChartSeries>,
    from: LocalDate,
    to: LocalDate,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    /** The least spread of the value axis, so a flat series isn't stretched into noise. */
    minPad: Double = 0.5,
    label: (Double) -> String = BodyText::oneDecimal,
) {
    val shown = series.map { s -> s.copy(points = s.points.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }) }
    val values = shown.flatMap { s -> s.points.map { it.value } }
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)

    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        if (values.isEmpty()) return@Canvas
        val lo = values.min()
        val hi = values.max()
        val pad = maxOf((hi - lo) * 0.15, minPad)
        val yMin = lo - pad
        val yMax = hi + pad
        val span = maxOf(ChronoUnit.DAYS.between(from, to), 1).toDouble()
        val left = 44.dp.toPx()
        val bottom = size.height - 18.dp.toPx()
        val width = size.width - left - 8.dp.toPx()
        fun x(d: LocalDate) = left + (ChronoUnit.DAYS.between(from, d) / span * width).toFloat()
        fun y(v: Double) = (bottom - (v - yMin) / (yMax - yMin) * bottom).toFloat()

        drawText(measurer, label(yMax), Offset(0f, 0f), labelStyle)
        drawText(measurer, label(yMin), Offset(0f, bottom - 14.dp.toPx()), labelStyle)
        drawText(measurer, BodyText.shortDate(from), Offset(left, bottom + 2.dp.toPx()), labelStyle)
        val end = measurer.measure(BodyText.shortDate(to), labelStyle)
        drawText(end, topLeft = Offset(size.width - end.size.width, bottom + 2.dp.toPx()))
        drawLine(colors.outlineVariant, Offset(left, bottom), Offset(size.width, bottom), strokeWidth = 1.dp.toPx())

        // Lines under dots, so every dot stays visible.
        shown.filter { it.line && it.points.size > 1 }.forEach { s ->
            val path = Path()
            s.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.date), y(p.value)) else path.lineTo(x(p.date), y(p.value)) }
            drawPath(path, s.color, style = Stroke(width = 3.dp.toPx()))
        }
        val r = 4.dp.toPx()
        shown.forEach { s ->
            s.points.forEach { p ->
                val c = Offset(x(p.date), y(p.value))
                when (s.dots) {
                    ChartDots.FILLED -> drawCircle(s.color, r, c)
                    ChartDots.HOLLOW -> drawCircle(s.color, r, c, style = Stroke(width = 1.5.dp.toPx()))
                    ChartDots.NONE -> Unit
                }
            }
        }
    }
}
