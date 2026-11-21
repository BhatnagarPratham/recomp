package app.recomp.ui.components

import androidx.compose.runtime.compositionLocalOf
import app.recomp.domain.persona.Units
import app.recomp.domain.units.UnitStyle
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * The units and decimal mark every screen formats with (PR-05). Provided at the root of the nav host from the
 * persona's `units`; text built outside composition takes a [UnitStyle] parameter from the same source.
 */
val LocalUnitStyle = compositionLocalOf { UnitStyle.METRIC }

/** [units] with the decimal mark of [locale] ("80,4" in German). */
fun unitStyle(units: Units, locale: Locale = Locale.getDefault()): UnitStyle =
    UnitStyle(units, DecimalFormatSymbols.getInstance(locale).decimalSeparator)
