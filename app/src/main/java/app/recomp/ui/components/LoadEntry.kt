package app.recomp.ui.components

import app.recomp.domain.units.LoadUnits
import app.recomp.domain.units.UnitStyle

/**
 * A typed load field (PR-05, PRD §12.16 "Nothing you didn't change is rewritten"). A load shows rounded in lb (a
 * kg-era 42,500 g reads "93.7"), so reading the text back would rewrite it as 42,502 g. When the text is what was
 * shown, the stored grams are kept; anything else is parsed in the style's unit.
 */
object LoadEntry {
    /** What the field shows for [grams]: "93.7", "42,5", or blank for none. */
    fun shown(grams: Int?, style: UnitStyle): String = grams?.let { LoadUnits.format(it, style) }.orEmpty()

    /** Grams for [text]: [storedG] when the text is unchanged from [shown] of it, else the parsed value; null if invalid. */
    fun parse(text: String, storedG: Int?, style: UnitStyle): Int? =
        if (storedG != null && text.trim() == shown(storedG, style)) storedG else LoadUnits.parse(text, style.units)
}
