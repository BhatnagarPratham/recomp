package app.recomp.domain.export

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * CSV for spreadsheets (DA-03, RFC 4180): comma-separated, CRLF line ends, and a field quoted when it holds a comma,
 * a quote or a line break, with quotes doubled. Files start with a UTF-8 byte order mark, so Excel reads names in any
 * script correctly.
 */
object Csv {
    const val BOM = "﻿"
    private const val EOL = "\r\n"

    /** A cell. Numbers and text are written differently: only text can be mistaken for a formula. */
    sealed interface Cell {
        data class Text(val value: String) : Cell
        data class Number(val value: String) : Cell
        data object Empty : Cell
    }

    fun text(value: String?): Cell = if (value == null) Cell.Empty else Cell.Text(value)

    /** A number with at most [decimals] places and no trailing zeros, always with a dot: "80.25", "2090", "0.5". */
    fun number(value: Double?, decimals: Int = 2): Cell =
        if (value == null || !value.isFinite()) Cell.Empty
        else Cell.Number(BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it })

    fun number(value: Long?): Cell = if (value == null) Cell.Empty else Cell.Number(value.toString())

    fun number(value: Int?): Cell = number(value?.toLong())

    fun yesNo(value: Boolean?): Cell = if (value == null) Cell.Empty else Cell.Text(if (value) "yes" else "no")

    /**
     * One field. Text that a spreadsheet would run as a formula (starting with `=`, `+`, `-`, `@`, a tab or a carriage
     * return) gets a leading apostrophe, so a food named "=HYPERLINK(…)" stays text (OWASP's CSV injection advice).
     */
    fun field(cell: Cell): String = when (cell) {
        Cell.Empty -> ""
        is Cell.Number -> cell.value
        is Cell.Text -> quote(if (cell.value.firstOrNull() in FORMULA_STARTS) "'" + cell.value else cell.value)
    }

    private val FORMULA_STARTS = setOf('=', '+', '-', '@', '\t', '\r')

    private fun quote(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    /** A whole file: the header, then one line per row. Every row must have the header's number of cells. */
    fun file(header: List<String>, rows: Sequence<List<Cell>>): String = buildString {
        append(BOM)
        append(header.joinToString(",") { quote(it) }).append(EOL)
        rows.forEach { row ->
            require(row.size == header.size) { "A row has ${row.size} cells; the header has ${header.size}" }
            append(row.joinToString(",", transform = ::field)).append(EOL)
        }
    }
}
