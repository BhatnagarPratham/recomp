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
import app.recomp.domain.persona.Units
import app.recomp.domain.units.UnitMath
import app.recomp.domain.units.UnitStyle
import app.recomp.ui.components.Chart
import app.recomp.ui.components.ChartDots
import app.recomp.ui.components.ChartPoint
import app.recomp.ui.components.ChartSeries
import app.recomp.ui.components.ChoiceChips
import app.recomp.ui.components.LocalUnitStyle
import app.recomp.ui.onboarding.GoalText
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.math.roundToInt

/** PO-09: an exercise's progress over 12 weeks or everything, on M8's shared chart. */
@Composable
fun ExerciseChartCard(kgData: ExerciseChartData, range: ChartRange, today: LocalDate, onRange: (ChartRange) -> Unit, modifier: Modifier = Modifier) {
    val style = LocalUnitStyle.current
    val data = ExerciseChartText.inUnits(kgData, style.units)
    val first = (data.line + data.dots).minOfOrNull { it.date }
    val from = range.from(today, first)
    val colors = MaterialTheme.colorScheme
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ExerciseChartText.title(data.kind), style = MaterialTheme.typography.titleMedium)
            ExerciseChartText.caption(data.kind, style)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
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
                    description = ExerciseChartText.describe(data, from, style),
                    minPad = if (data.kind == ExerciseChartData.Kind.REPS) 1.0 else if (style.imperial) 5.0 else 2.5,
                    // Whole kg or lb on the axis (PRD §12.16).
                    label = { v -> GoalText.n(v.roundToInt().coerceAtLeast(0).toLong()) },
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

    fun caption(kind: ExerciseChartData.Kind, style: UnitStyle): String? = when (kind) {
        ExerciseChartData.Kind.E1RM -> "Line: best estimated 1RM each session. Circles: the heaviest set. In ${style.weight}."
        ExerciseChartData.Kind.REPS -> null
        ExerciseChartData.Kind.ASSISTANCE -> "The least assistance each session, in ${style.weight}. Lower is better."
    }

    /** The domain's chart (loads in kg) in the persona's unit; reps are left alone. */
    fun inUnits(data: ExerciseChartData, units: Units): ExerciseChartData {
        if (data.kind == ExerciseChartData.Kind.REPS || units == Units.METRIC) return data
        fun List<ChartValue>.lb() = map { it.copy(value = UnitMath.weightOf(it.value, units)) }
        return data.copy(line = data.line.lb(), dots = data.dots.lb())
    }

    /** Why there's no chart: too few sessions at all, or too few in the last 12 weeks. */
    fun notEnough(data: ExerciseChartData) =
        if (!data.drawable) "A chart appears after two sessions." else "Fewer than two sessions in the last 12 weeks. Choose Everything to see them all."

    fun range(r: ChartRange) = when (r) {
        ChartRange.WEEKS_12 -> "12 weeks"
        ChartRange.ALL -> "Everything"
    }

    /** What a screen reader hears instead of the chart: the first and latest values in view. */
    /** [data] is already in [style]'s unit ([inUnits]); values are read to 0.1. */
    fun describe(data: ExerciseChartData, from: LocalDate, style: UnitStyle): String {
        val shown = data.line.filter { !it.date.isBefore(from) }.ifEmpty { data.dots.filter { !it.date.isBefore(from) } }
        fun v(c: ChartValue) = if (data.kind == ExerciseChartData.Kind.REPS) "${c.value.roundToInt()} reps"
        else style.mark(BigDecimal(c.value).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()) + " ${style.weight}"
        val first = shown.firstOrNull() ?: return title(data.kind)
        val last = shown.last()
        return "${title(data.kind)}: ${shown.size} sessions, from ${v(first)} to ${v(last)}"
    }
}

