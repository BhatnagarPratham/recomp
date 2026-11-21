package app.recomp.ui.exercises

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.recomp.domain.progression.ChartRange
import app.recomp.domain.progression.ChartValue
import app.recomp.domain.progression.ExerciseChartData
import app.recomp.domain.training.Loads
import app.recomp.ui.components.Chart
import app.recomp.ui.components.ChartDots
import app.recomp.ui.components.ChartPoint
import app.recomp.ui.components.ChartSeries
import app.recomp.ui.components.ChoiceChips
import java.time.LocalDate
import kotlin.math.roundToInt

/** PO-09: an exercise's progress over 12 weeks or everything, on M8's shared chart. */
@Composable
fun ExerciseChartCard(data: ExerciseChartData, range: ChartRange, today: LocalDate, onRange: (ChartRange) -> Unit, modifier: Modifier = Modifier) {
    val first = (data.line + data.dots).minOfOrNull { it.date }
    val from = range.from(today, first)
    val colors = MaterialTheme.colorScheme
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ExerciseChartText.title(data.kind), style = MaterialTheme.typography.titleMedium)
            ExerciseChartText.caption(data.kind)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
            ChoiceChips(ChartRange.entries, range, ExerciseChartText::range, onRange)
            if (!data.drawableFrom(from)) {
                Text(ExerciseChartText.notEnough(data), color = colors.onSurfaceVariant)
            } else {
                val series = buildList {
                    if (data.dots.isNotEmpty()) add(ChartSeries(data.dots.points(), colors.onSurfaceVariant, line = false, dots = ChartDots.HOLLOW))
                    add(ChartSeries(data.line.points(), colors.primary, line = true, dots = if (data.dots.isEmpty()) ChartDots.FILLED else ChartDots.NONE))
                }
                Chart(
                    series = series,
                    from = from,
                    to = today,
                    description = ExerciseChartText.describe(data, from),
                    minPad = if (data.kind == ExerciseChartData.Kind.REPS) 1.0 else 2.5,
                    label = { v -> if (data.kind == ExerciseChartData.Kind.REPS) "${v.roundToInt()}" else Loads.formatKg((v * 1000).roundToInt().coerceAtLeast(0) / 500 * 500) },
                )
            }
        }
    }
}

private fun List<ChartValue>.points() = map { ChartPoint(it.date, it.value) }

/** Words for the exercise chart, kept apart so they're tested. */
object ExerciseChartText {
    fun title(kind: ExerciseChartData.Kind) = when (kind) {
        ExerciseChartData.Kind.E1RM -> "Estimated 1RM and top set"
        ExerciseChartData.Kind.REPS -> "Best set's reps"
        ExerciseChartData.Kind.ASSISTANCE -> "Assistance"
    }

    fun caption(kind: ExerciseChartData.Kind): String? = when (kind) {
        ExerciseChartData.Kind.E1RM -> "Line: best estimated 1RM each session. Circles: the heaviest set. In kg."
        ExerciseChartData.Kind.REPS -> null
        ExerciseChartData.Kind.ASSISTANCE -> "The least assistance each session, in kg. Lower is better."
    }

    /** Why there's no chart: too few sessions at all, or too few in the last 12 weeks. */
    fun notEnough(data: ExerciseChartData) =
        if (!data.drawable) "A chart appears after two sessions." else "Fewer than two sessions in the last 12 weeks. Choose Everything to see them all."

    fun range(r: ChartRange) = when (r) {
        ChartRange.WEEKS_12 -> "12 weeks"
        ChartRange.ALL -> "Everything"
    }

    /** What a screen reader hears instead of the chart: the first and latest values in view. */
    fun describe(data: ExerciseChartData, from: LocalDate): String {
        val shown = data.line.filter { !it.date.isBefore(from) }.ifEmpty { data.dots.filter { !it.date.isBefore(from) } }
        val unit = if (data.kind == ExerciseChartData.Kind.REPS) " reps" else " kg"
        fun v(c: ChartValue) = if (data.kind == ExerciseChartData.Kind.REPS) "${c.value.roundToInt()}$unit" else "${Loads.formatKg((c.value * 1000).roundToInt())}$unit"
        val first = shown.firstOrNull() ?: return title(data.kind)
        val last = shown.last()
        return "${title(data.kind)}: ${shown.size} sessions, from ${v(first)} to ${v(last)}"
    }
}

