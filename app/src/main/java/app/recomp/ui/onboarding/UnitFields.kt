package app.recomp.ui.onboarding

import app.recomp.domain.persona.Units
import app.recomp.domain.units.UnitMath
import app.recomp.domain.units.UnitStyle
import app.recomp.ui.body.BodyText
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/** A prefilled field: the text it was shown as and the stored value behind it, which an unchanged field keeps (PR-05). */
data class Kept(val text: String, val value: Double)

/**
 * Height and body-weight fields in either unit (PR-05), for onboarding's About you and goal weight and for
 * Settings. Typed values are converted once, to cm and kg; nothing is stored in lb or inches.
 */
object UnitFields {
    /** Countries that weigh in lb: the US, Liberia and Myanmar (PRD §12.16). */
    private val IMPERIAL_COUNTRIES = setOf("US", "LR", "MM")

    /** Onboarding's first guess at the units, from the phone's region. */
    fun defaultUnits(locale: Locale = Locale.getDefault()): Units =
        if (locale.country.uppercase() in IMPERIAL_COUNTRIES) Units.IMPERIAL else Units.METRIC

    /** A body weight for a field: up to one decimal, trailing zeros dropped ("80", "177.2"). */
    fun weightText(kg: Double, style: UnitStyle): String = GoalText.weightNumber(kg, style)

    /** A typed body weight in kg (lb typed to 0.1 lb, then exact grams). Null when it isn't a number. */
    fun parseWeightKg(text: String, style: UnitStyle): Double? = BodyText.parseWeight(text, style)?.let { it / 1000.0 }

    /** A suggested goal weight's text: whole kg as suggested, or the nearest whole lb (PRD §12.16), which is then what's stored. */
    fun suggestedText(kg: Double, style: UnitStyle): String =
        if (style.imperial) Math.round(UnitMath.kgToLb(kg)).toString() else weightText(kg, style)

    /** Height for the fields: cm and "", or feet and inches ("5", "9"). */
    fun heightTexts(cm: Double, style: UnitStyle): Pair<String, String> =
        if (style.imperial) UnitMath.feetAndInches(cm).let { (ft, inches) -> ft.toString() to inches.toString() }
        else style.mark(BigDecimal(cm).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()) to ""

    /** Typed height in cm: cm in [first]; or feet in [first] and inches (blank is 0, under 12) in [second]. */
    fun parseHeightCm(first: String, second: String, style: UnitStyle): Double? {
        if (!style.imperial) return BodyText.parse(first)
        val feet = first.trim().toIntOrNull() ?: return null
        val inches = if (second.isBlank()) 0.0 else BodyText.parse(second) ?: return null
        return UnitMath.heightCm(feet, inches)
    }

    /** The key an unchanged height is recognised by. */
    fun heightKey(first: String, second: String, style: UnitStyle): String = if (style.imperial) "${first.trim()}|${second.trim()}" else first.trim()
}
