package app.recomp.domain.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** DA-03's CSV (RFC 4180) as spreadsheets read it. Invented values. */
class CsvTest {
    @Test
    fun `a field is quoted only when it has to be, with quotes doubled`() {
        assertEquals("Dal", Csv.field(Csv.text("Dal")))
        assertEquals("\"Dal, tadka\"", Csv.field(Csv.text("Dal, tadka")))
        assertEquals("\"6\"\" roti\"", Csv.field(Csv.text("6\" roti")))
        assertEquals("\"two\nlines\"", Csv.field(Csv.text("two\nlines")))
        assertEquals("\"cr\rhere\"", Csv.field(Csv.text("cr\rhere")))
        assertEquals("", Csv.field(Csv.text(null)))
        assertEquals("", Csv.field(Csv.Cell.Empty))
        assertEquals("पनीर टिक्का", Csv.field(Csv.text("पनीर टिक्का")))
    }

    @Test
    fun `text a spreadsheet would run as a formula stays text`() {
        assertEquals("'=HYPERLINK(\"x\")".let { "\"" + it.replace("\"", "\"\"") + "\"" }, Csv.field(Csv.text("=HYPERLINK(\"x\")")))
        assertEquals("'+91 snack", Csv.field(Csv.text("+91 snack")))
        assertEquals("'-shake", Csv.field(Csv.text("-shake")))
        assertEquals("'@home", Csv.field(Csv.text("@home")))
        assertEquals("\"'=1+2,3\"", Csv.field(Csv.text("=1+2,3")))
        // Numbers are never touched: a negative number stays a number.
        assertEquals("-12.5", Csv.field(Csv.number(-12.5)))
    }

    @Test
    fun `numbers use a dot, no trailing zeros and no exponent`() {
        assertEquals("80.25", Csv.field(Csv.number(80.25)))
        assertEquals("80.1", Csv.field(Csv.number(80.1)))
        assertEquals("2090", Csv.field(Csv.number(2090.0)))
        assertEquals("0.5", Csv.field(Csv.number(0.5)))
        assertEquals("0.33", Csv.field(Csv.number(1.0 / 3)))
        assertEquals("0.333", Csv.field(Csv.number(1.0 / 3, 3)))
        assertEquals("210", Csv.field(Csv.number(209.6, 0)))
        assertEquals("0", Csv.field(Csv.number(-0.001)))
        assertEquals("12000000", Csv.field(Csv.number(1.2e7)))
        assertEquals("", Csv.field(Csv.number(Double.NaN)))
        assertEquals("", Csv.field(Csv.number(null as Double?)))
        assertEquals("42", Csv.field(Csv.number(42L)))
        assertEquals("yes", Csv.field(Csv.yesNo(true)))
        assertEquals("", Csv.field(Csv.yesNo(null)))
    }

    @Test
    fun `a file has a BOM, the header and CRLF lines, and every row the header's width`() {
        val file = Csv.file(listOf("date", "food"), sequenceOf(listOf(Csv.text("2026-11-21"), Csv.text("Dal, tadka"))))
        assertEquals("﻿date,food\r\n2026-11-21,\"Dal, tadka\"\r\n", file)
        assertEquals("﻿date\r\n", Csv.file(listOf("date"), emptySequence()))
        assertTrue(runCatching { Csv.file(listOf("a", "b"), sequenceOf(listOf(Csv.text("x")))) }.isFailure)
    }
}
