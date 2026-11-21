package app.recomp.domain.progression

import app.recomp.domain.model.LoadType
import app.recomp.domain.model.SetType
import app.recomp.domain.training.LoggedSet
import java.time.LocalDate

/** One completed session of an exercise, by log date. */
data class DatedExposure(val date: LocalDate, val sets: List<LoggedSet>)

/** A value on a day; kilograms for loads, plain counts for reps. */
data class ChartValue(val date: LocalDate, val value: Double)

/** What an exercise's chart plots. [line] is drawn as a line; [dots] as dots on the same axis. */
data class ExerciseChartData(val kind: Kind, val line: List<ChartValue>, val dots: List<ChartValue>) {
    enum class Kind {
        /** Line: best estimated 1RM per session. Dots: the top set's weight. Both in kg. */
        E1RM,
        /** Bodyweight: the best set's reps. */
        REPS,
        /** Assisted: the least assistance per session, in kg; lower is better. */
        ASSISTANCE,
    }

    /** At least two sessions to draw a trend. */
    val drawable: Boolean get() = maxOf(line.size, dots.size) >= 2
}

/** The range the chart shows (PO-09): the last 12 weeks, or everything. */
enum class ChartRange(val days: Long?) {
    WEEKS_12(84), ALL(null);

    fun from(today: LocalDate, first: LocalDate?): LocalDate =
        days?.let { today.minusDays(it) } ?: (first ?: today).coerceAtMost(today.minusDays(1))
}

/**
 * Per-exercise chart points (PO-09, PRD §12.6), one per session. Warm-ups never count.
 *
 * - Weighted and weighted-bodyweight lifts: the best estimated 1RM per session (Epley, sets of 12 reps or
 *   fewer with a load, as history and PRs use) as the line, and the top set's weight as dots.
 * - Bodyweight: the most reps in one set.
 * - Assisted: the least assistance, read from the sets' own loads (never a PR's value, TRD §5.1).
 */
object ExerciseChart {
    fun of(exposures: List<DatedExposure>, loadType: LoadType): ExerciseChartData {
        val sessions = exposures.map { e -> e.date to e.sets.filter { it.type != SetType.WARMUP } }.filter { it.second.isNotEmpty() }.sortedBy { it.first }
        return when (loadType) {
            LoadType.BODYWEIGHT -> ExerciseChartData(
                ExerciseChartData.Kind.REPS,
                line = sessions.map { (d, sets) -> ChartValue(d, sets.maxOf { it.reps }.toDouble()) },
                dots = emptyList(),
            )
            LoadType.ASSISTED -> ExerciseChartData(
                ExerciseChartData.Kind.ASSISTANCE,
                line = sessions.map { (d, sets) -> ChartValue(d, sets.minOf { it.loadG ?: 0 } / 1000.0) },
                dots = emptyList(),
            )
            else -> ExerciseChartData(
                ExerciseChartData.Kind.E1RM,
                line = sessions.mapNotNull { (d, sets) ->
                    sets.filter { (it.loadG ?: 0) > 0 && it.reps in 1..ProgressionEngine.E1RM_MAX_REPS }
                        .maxOfOrNull { ProgressionEngine.e1rmG(it.loadG!!, it.reps) }?.let { ChartValue(d, it / 1000.0) }
                },
                dots = sessions.map { (d, sets) -> ChartValue(d, sets.maxOf { it.loadG ?: 0 } / 1000.0) },
            )
        }
    }
}
