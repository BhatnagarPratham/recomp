package app.recomp.ui.body

import app.recomp.domain.body.TapeSites
import app.recomp.domain.body.TapeSite
import app.recomp.domain.body.ProtocolIssue
import app.recomp.domain.body.ScanChange
import app.recomp.domain.body.ScanProtocol
import app.recomp.domain.body.ScanSlopes
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
import app.recomp.domain.units.UnitMath
import app.recomp.domain.units.UnitStyle
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs

/** Body's sentences (PRD §7.8, §7.9). Rates and protocol flags are information, never failure. */
object BodyText {
    fun oneDecimal(value: Double): String = "%.1f".format(Locale.getDefault(), value)

    /** "+0.4 kg", "−1.2 cm"; a value that rounds to 0.0 has no sign. */
    fun signed(value: Double, unit: String): String {
        val tenths = Math.round(value * 10)
        return (if (tenths > 0) "+" else if (tenths < 0) "−" else "") + "${oneDecimal(abs(tenths) / 10.0)} $unit"
    }

    /** True when [value] shows as 0.0. */
    fun roundsToZero(value: Double): Boolean = Math.round(value * 10) == 0L

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

    /** BM-03: "Chest", "Arm"… */
    fun site(s: TapeSite): String = when (s) {
        TapeSite.WAIST -> "Waist"
        TapeSite.CHEST -> "Chest"
        TapeSite.ARM -> "Arm"
        TapeSite.THIGH -> "Thigh"
        TapeSite.HIP -> "Hip"
    }

    /** Settings' summary: "Waist only", or "Waist, arm and hip". */
    fun tapeSites(chosen: Set<TapeSite>): String {
        val names = (listOf(TapeSite.WAIST) + TapeSites.OPTIONAL.filter { it in chosen }).map { site(it).lowercase() }
        if (names.size == 1) return "Waist only"
        val list = names.dropLast(1).joinToString(", ") + " and " + names.last()
        return list.replaceFirstChar { it.uppercase() }
    }

    fun date(d: LocalDate): String = d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

    fun shortDate(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))

    /** "Mon 13 Oct, 07:02". */
    fun dateTime(epochMs: Long): String {
        val at = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
        return at.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())) + ", " + time(at.toLocalTime())
    }

    /**
     * "+0.4 kg muscle · within noise; −0.8 pts body fat · within noise" (IB-04, TRD §5.6). A change in body fat is in
     * points of the percentage, not a percent of it.
     */
    /** Which scans the chart's slope is from: the window's ("in the last 12 weeks") or all of them. */
    fun slopeFrom(scans: Int, weeks: Long?): String =
        if (weeks == null) "from all $scans on-protocol scans" else "from $scans on-protocol scans in the last $weeks weeks"

    /** IB-05's offer: the scan's day and the two BMRs. */
    fun scanOffer(o: app.recomp.data.repo.ScanOffer): String =
        "Your scan of ${shortDate(o.date)} puts your resting calories at ${GoalText.kcal(o.scanBmrKcal)}; your targets use ${GoalText.kcal(o.targetBmrKcal)}. " +
            "Lean mass is more accurate than height and weight. Nothing changes until you save."

    /** The before and after of saving targets with the scan. */
    fun scanPreview(p: app.recomp.data.repo.TargetPreview): String = buildString {
        val before = p.before
        append("Resting calories ${before?.bmrKcal?.let(GoalText::kcal) ?: "–"} → ${GoalText.kcal(Math.round(p.after.bmrKcal).toInt())}\n")
        append("Calories ${before?.kcal?.let(GoalText::kcal) ?: "–"} → ${GoalText.kcal(p.after.kcal)}\n")
        append("Protein ${before?.proteinG ?: "–"} g → ${p.after.proteinG} g\n")
        append("Fat ${before?.fatG ?: "–"} g → ${p.after.fatG} g · carbs ${before?.carbsG ?: "–"} g → ${p.after.carbsG} g")
        if (p.maintenance != null) append("\nCalories still follow your check-in's maintenance estimate; the scan sets the floor.")
        if (!p.changes) append("\nYour targets already match.")
    }

    /** Typed numbers accept "80,4" as well as "80.4". */
    fun parse(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    // Body weights and lengths in either unit (PR-05). Every body weight, tape and height on screen goes through these.

    private fun one(value: Double, style: UnitStyle): String = style.mark("%.1f".format(Locale.ROOT, value))

    /** A body weight from grams, to 0.1 in kg or lb. */
    fun weight(grams: Int, style: UnitStyle): String = weight(grams / 1000.0, style)

    fun weight(kg: Double, style: UnitStyle): String = "${weightNumber(kg, style)} ${style.weight}"

    /** The number alone, for a field with the unit beside it. */
    fun weightNumber(kg: Double, style: UnitStyle): String = one(UnitMath.weightOf(kg, style.units), style)

    /** A change in kg: "+0.4 kg", "−0.9 lb"; one that shows as 0.0 has no sign. */
    fun signedWeight(kgDelta: Double, style: UnitStyle): String = signedIn(UnitMath.weightOf(kgDelta, style.units), style.weight, style)

    /** A length from cm: "81.5 cm", "32.25 in" (inches to the quarter, up to 2 decimals). */
    fun length(cm: Double, style: UnitStyle): String = "${lengthNumber(cm, style)} ${style.length}"

    fun lengthNumber(cm: Double, style: UnitStyle): String =
        if (style.imperial) style.mark(BigDecimal(UnitMath.cmToIn(cm)).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString())
        else one(cm, style)

    /** A change in cm: "−1.2 cm", "−0.4 in". */
    fun signedLength(cmDelta: Double, style: UnitStyle): String = signedIn(UnitMath.lengthOf(cmDelta, style.units), style.length, style)

    /** "175 cm", "5 ft 9 in". */
    fun height(cm: Double, style: UnitStyle): String =
        if (style.imperial) UnitMath.feetAndInches(cm).let { (ft, inches) -> "$ft ft $inches in" } else "${GoalText.d(cm)} cm"

    private fun signedIn(value: Double, unit: String, style: UnitStyle): String {
        val tenths = Math.round(value * 10)
        return (if (tenths > 0) "+" else if (tenths < 0) "−" else "") + "${one(abs(tenths) / 10.0, style)} $unit"
    }

    /** A typed body weight in the style's unit, in grams (lb typed to 0.1). Null when it isn't a number. */
    fun parseWeight(text: String, style: UnitStyle): Int? = parse(text)?.let { value ->
        if (style.imperial) UnitMath.lbToG(BigDecimal(value).setScale(1, RoundingMode.HALF_UP)) else Math.round(value * 1000).toInt()
    }

    /** A typed length in the style's unit, in cm. */
    fun parseLength(text: String, style: UnitStyle): Double? = parse(text)?.let { UnitMath.lengthToCm(it, style.units) }

    /** A body-weight stepper: 0.1 kg, or 0.2 lb on the lb grid so steps never drift. */
    fun weightStep(grams: Int, up: Boolean, style: UnitStyle): Int {
        if (!style.imperial) return grams + if (up) 100 else -100
        val lb = BigDecimal(grams).divide(UnitMath.G_PER_LB, 1, RoundingMode.HALF_UP)
        return UnitMath.lbToG(if (up) lb + STEP_LB else lb - STEP_LB)
    }

    /** A tape stepper: 0.5 cm, or ¼ in. Works in cm, so callers keep the exact value they show. */
    fun lengthStep(cm: Double, up: Boolean, style: UnitStyle): Double =
        if (style.imperial) UnitMath.inToCm(Math.round(UnitMath.cmToIn(cm) * 4) / 4.0 + if (up) 0.25 else -0.25) else cm + if (up) 0.5 else -0.5

    /** The weekly rate in the style's unit: "−0.4 kg this week (−0.5%)". */
    fun rate(r: WeeklyRate, style: UnitStyle): String = "${signedWeight(r.kgPerWeek, style)} this week (${signed(r.pctPerWeek, "%").replace(" %", "%")})"

    fun scanChange(c: ScanChange, style: UnitStyle): String =
        "${signedWeight(c.muscleKg, style)} muscle" + (if (c.muscleWithinNoise) " · within noise" else "") +
            "; ${signed(c.bodyFatPct, "pts")} body fat" + (if (c.bodyFatWithinNoise) " · within noise" else "")

    fun scanSlopes(s: ScanSlopes, style: UnitStyle): String =
        "Per 4 weeks: muscle ${signedWeight(s.muscleKg, style)}, fat mass ${signedWeight(s.fatKg, style)}, body fat ${signed(s.bodyFatPct, "pts")}"

    /** The scan table's caption, in the style's unit. */
    fun scanTableCaption(style: UnitStyle): String = "Weight, muscle and fat in ${style.weight}. Off-protocol scans are marked ◌."

    private val STEP_LB = BigDecimal("0.2")

    /** A number that may be negative, typed with a hyphen or a minus sign (YG-04's sit-and-reach). */
    fun parseSigned(text: String): Double? = parse(text.trim().replace('\u2212', '-'))

    /** "+3.5 cm", "−4 cm", "0 cm". */
    fun signedCm(value: Double): String = if (value == 0.0) "0 cm" else signed(value, "cm")

    /** "45 s", "1:05". */
    fun minutesSeconds(seconds: Int): String = if (seconds < 60) "$seconds s" else "%d:%02d".format(seconds / 60, seconds % 60)
}
