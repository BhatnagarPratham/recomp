package app.recomp.ui.body

import app.recomp.domain.body.ProtocolIssue
import app.recomp.domain.body.ScanProtocol
import app.recomp.domain.body.ScanSlot
import app.recomp.domain.body.SinceMeal
import app.recomp.domain.persona.WeightSource
import app.recomp.domain.trend.WeeklyRate
import app.recomp.domain.trend.WeightPace
import app.recomp.ui.onboarding.GoalText
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/** Body's sentences (PRD §7.8, §7.9). Rates and protocol flags are information, never failure. */
object BodyText {
    /** "80.4 kg" from grams, to 0.1 kg. */
    fun kg(grams: Int): String = "${oneDecimal(grams / 1000.0)} kg"

    fun kg(value: Double): String = "${oneDecimal(value)} kg"

    fun cm(value: Double): String = "${oneDecimal(value)} cm"

    fun oneDecimal(value: Double): String = "%.1f".format(Locale.getDefault(), value)

    fun signed(value: Double, unit: String): String = (if (value > 0) "+" else if (value < 0) "−" else "") + "${oneDecimal(abs(value))} $unit"

    /** The weigh-in sheet's chips; Settings uses the longer [app.recomp.ui.components.Labels.weightSource]. */
    fun source(s: WeightSource) = when (s) {
        WeightSource.HOME_SCALE -> "Home scale"
        WeightSource.GYM_SCALE -> "Gym scale"
        WeightSource.SCAN -> "Scan"
    }

    fun sourceLower(s: WeightSource) = when (s) {
        WeightSource.HOME_SCALE -> "home scale"
        WeightSource.GYM_SCALE -> "gym scale"
        WeightSource.SCAN -> "scans"
    }

    /** "−0.4 kg this week (−0.5%)". */
    fun rate(r: WeeklyRate): String = "${signed(r.kgPerWeek, "kg")} this week (${signed(r.pctPerWeek, "%").replace(" %", "%")})"

    /** "goal −0.5% a week", "goal +0.25 to +0.5% a week", "goal: steady". */
    fun pace(p: WeightPace): String = when {
        p.steady -> "goal: steady"
        p.minPctPerWeek == p.maxPctPerWeek -> "goal ${pct(p.minPctPerWeek)} a week"
        else -> "goal ${pct(p.minPctPerWeek)} to ${pct(p.maxPctPerWeek)} a week"
    }

    private fun pct(v: Double) = (if (v > 0) "+" else if (v < 0) "−" else "") + GoalText.d(abs(v)) + "%"

    const val RATE_PENDING = "The weekly rate appears once your trend spans a week."
    const val NO_TREND = "Your trend starts with your first weigh-in."
    const val WAIST_HOW = "Measure at your navel, standing relaxed, after breathing out."

    fun sinceMeal(s: SinceMeal) = when (s) {
        SinceMeal.UNDER_2H -> "Under 2 h"
        SinceMeal.TWO_TO_3H -> "2–3 h"
        SinceMeal.OVER_3H -> "3 h or more"
        SinceMeal.FASTED -> "Fasted overnight"
    }

    fun slot(s: ScanSlot): String = "${s.day.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${time(s.time)}"

    fun time(t: LocalTime): String = t.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

    /** IB-02's protocol, shown before each scan. */
    fun protocol(slot: ScanSlot?): String =
        (if (slot != null) "Usual slot ${slot(slot)} · " else "Your first scan sets your usual slot · ") +
            "at least 2 h after eating · before any workout that day"

    fun minutes(m: Int): String = if (m < 60) "$m min" else if (m % 60 == 0) "${m / 60} h" else "${m / 60} h ${m % 60} min"

    fun issue(i: ProtocolIssue, minsFromSlot: Int?): String = when (i) {
        ProtocolIssue.TIME -> "${minutes(minsFromSlot ?: 0)} from your usual time (${ScanProtocol.TIME_WINDOW_MIN} min allowed)"
        ProtocolIssue.MEAL -> "less than 2 h after eating"
        ProtocolIssue.WORKOUT -> "after a workout"
    }

    fun verdict(issues: Set<ProtocolIssue>, minsFromSlot: Int?): String =
        if (issues.isEmpty()) "On protocol" else "Off protocol: " + issues.sorted().joinToString("; ") { issue(it, minsFromSlot) }

    fun date(d: LocalDate): String = d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

    fun shortDate(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))

    /** "Mon 13 Oct, 07:02". */
    fun dateTime(epochMs: Long): String {
        val at = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
        return at.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())) + ", " + time(at.toLocalTime())
    }

    /** Typed numbers accept "80,4" as well as "80.4". */
    fun parse(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
}
