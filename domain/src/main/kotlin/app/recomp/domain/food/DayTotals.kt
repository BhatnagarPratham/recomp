package app.recomp.domain.food

/**
 * A day's or a meal's totals (NU-16). Carbs and fat sum only the entries that know them; [unsplitKcal]
 * is the calories from entries that don't (quick adds), which the macro split can't place.
 */
data class DayTotals(
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val fiberG: Double,
    val unsplitKcal: Double,
    val entries: Int,
) {
    companion object {
        val EMPTY = DayTotals(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0)

        fun of(entries: List<Macros>): DayTotals = DayTotals(
            kcal = entries.sumOf { it.kcal },
            proteinG = entries.sumOf { it.proteinG },
            carbsG = entries.sumOf { it.carbsG ?: 0.0 },
            fatG = entries.sumOf { it.fatG ?: 0.0 },
            fiberG = entries.sumOf { it.fiberG ?: 0.0 },
            unsplitKcal = entries.filter { it.unsplit }.sumOf { it.kcal },
            entries = entries.size,
        )
    }
}

/**
 * What's left of a day's targets (NU-16). Negative means over, which the UI shows as a plain number,
 * never as a failure. [proteinLeftG] is null while the protein ramp is measuring (no target yet).
 */
data class Remaining(val kcalLeft: Int, val proteinLeftG: Int?) {
    companion object {
        fun of(totals: DayTotals, kcalTarget: Int, proteinTargetG: Int?): Remaining =
            Remaining(kcalTarget - totals.kcal.whole(), proteinTargetG?.let { it - totals.proteinG.whole() })
    }
}
