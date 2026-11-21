package app.recomp.domain.body

import app.recomp.domain.nutrition.NutritionCalculator
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** A scan as scan-informed targets see it (IB-05): its day, time, weight and fat mass in grams, and the protocol check. */
data class LeanScan(val date: LocalDate, val measuredAt: Long, val weightG: Int, val bodyFatG: Int, val onProtocol: Boolean) {
    /** Lean mass = scan weight − body fat mass (TRD §5.3). */
    val leanKg: Double get() = (weightG - bodyFatG) / 1000.0
}

/**
 * Scan-informed targets (IB-05, TRD §5.3, PRD §12.11). The scan that counts is the latest on-protocol one from the
 * last 60 days: BMR then uses Katch-McArdle on its lean mass. Targets don't change on their own: once a scan moves
 * BMR by 20 kcal or more from the target in force, Body offers a preview.
 */
object ScanTargets {
    const val MAX_AGE_DAYS = 60L
    const val BMR_PROMPT_KCAL = 20.0

    /** The scan that sets BMR on [today], or null: none on protocol within 60 days (one from the future doesn't count). */
    fun counting(scans: List<LeanScan>, today: LocalDate): LeanScan? = scans
        .filter { it.onProtocol && !it.date.isAfter(today) && ChronoUnit.DAYS.between(it.date, today) <= MAX_AGE_DAYS }
        .maxByOrNull { it.measuredAt }

    fun bmr(scan: LeanScan): Double = NutritionCalculator.katchMcArdle(scan.leanKg)

    /** Whether the scan's BMR is far enough from the target's to offer *Preview targets with your scan*. */
    fun worthPreview(targetBmrKcal: Double, scanBmrKcal: Double): Boolean = abs(scanBmrKcal - targetBmrKcal) >= BMR_PROMPT_KCAL
}
