package app.recomp.domain.food.indb

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/** One sheet as a header row plus data rows, read by column name. */
class Table(val header: List<String>, val rows: List<List<String?>>) {
    private val index = header.withIndex().associate { (i, name) -> name.trim() to i }

    fun has(column: String): Boolean = column in index

    fun require(vararg columns: String) {
        val missing = columns.filterNot(::has)
        require(missing.isEmpty()) { "Missing columns $missing; found $header" }
    }

    operator fun get(row: List<String?>, column: String): String? =
        index[column]?.let(row::getOrNull)?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        fun of(sheet: List<List<String?>>): Table {
            require(sheet.isNotEmpty()) { "Empty sheet" }
            return Table(sheet.first().map { it.orEmpty() }, sheet.drop(1))
        }
    }
}

/**
 * A minimal reader for .xlsx workbooks (a zip of XML parts), with only the JDK, for the build-time INDB
 * converter. Reads cell values as text: shared and inline strings, numbers as written. DTDs are refused,
 * because the file comes from the network (its SHA-256 is pinned too).
 */
object Xlsx {
    private const val MAX_PART_BYTES = 64L * 1024 * 1024
    private const val MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    /** Every sheet by name, in workbook order. Each row lists cell values by column; null is empty. */
    fun read(input: InputStream): Map<String, List<List<String?>>> {
        val parts = unzip(input)
        val shared = parts["xl/sharedStrings.xml"]?.let(::sharedStrings).orEmpty()
        val targets = relationships(parts.getValue("xl/_rels/workbook.xml.rels"))
        return sheets(parts.getValue("xl/workbook.xml")).associate { (name, relId) ->
            val target = targets.getValue(relId).removePrefix("/").let { if (it.startsWith("xl/")) it else "xl/$it" }
            name to cells(parts.getValue(target), shared)
        }
    }

    fun table(input: InputStream, sheet: String? = null): Table {
        val sheets = read(input)
        return Table.of(if (sheet == null) sheets.values.first() else sheets.getValue(sheet))
    }

    private fun unzip(input: InputStream): Map<String, ByteArray> {
        val parts = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || !entry.name.endsWith(".xml") && !entry.name.endsWith(".rels")) continue
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = zip.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    require(out.size() <= MAX_PART_BYTES) { "${entry.name} is too large" }
                }
                parts[entry.name] = out.toByteArray()
            }
        }
        return parts
    }

    private fun parse(bytes: ByteArray, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        factory.newSAXParser().parse(ByteArrayInputStream(bytes), handler)
    }

    private fun sharedStrings(bytes: ByteArray): List<String> {
        val strings = mutableListOf<String>()
        parse(bytes, object : DefaultHandler() {
            private val text = StringBuilder()
            private var inText = false
            private var phonetic = 0

            override fun startElement(uri: String, local: String, q: String, attrs: Attributes) {
                when (local) {
                    "si" -> text.setLength(0)
                    "rPh" -> phonetic++
                    "t" -> inText = phonetic == 0
                }
            }

            override fun endElement(uri: String, local: String, q: String) {
                when (local) {
                    "si" -> strings += text.toString()
                    "rPh" -> phonetic--
                    "t" -> inText = false
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inText) text.appendRange(ch, start, start + length)
            }
        })
        return strings
    }

    private fun sheets(bytes: ByteArray): List<Pair<String, String>> {
        val sheets = mutableListOf<Pair<String, String>>()
        parse(bytes, object : DefaultHandler() {
            override fun startElement(uri: String, local: String, q: String, attrs: Attributes) {
                if (local == "sheet" && uri == MAIN) sheets += attrs.getValue("name") to attrs.getValue(REL, "id")
            }
        })
        return sheets
    }

    private fun relationships(bytes: ByteArray): Map<String, String> {
        val targets = mutableMapOf<String, String>()
        parse(bytes, object : DefaultHandler() {
            override fun startElement(uri: String, local: String, q: String, attrs: Attributes) {
                if (local == "Relationship") targets[attrs.getValue("Id")] = attrs.getValue("Target")
            }
        })
        return targets
    }

    private fun cells(bytes: ByteArray, shared: List<String>): List<List<String?>> {
        val rows = mutableListOf<List<String?>>()
        parse(bytes, object : DefaultHandler() {
            private var row = mutableMapOf<Int, String>()
            private var column = 0
            private var type: String? = null
            private val value = StringBuilder()
            private var inValue = false

            override fun startElement(uri: String, local: String, q: String, attrs: Attributes) {
                when (local) {
                    "row" -> row = mutableMapOf()
                    "c" -> {
                        column = attrs.getValue("r")?.let(::columnIndex) ?: (row.keys.maxOrNull()?.plus(1) ?: 0)
                        type = attrs.getValue("t")
                        value.setLength(0)
                    }
                    "v", "t" -> inValue = true
                }
            }

            override fun endElement(uri: String, local: String, q: String) {
                when (local) {
                    "v", "t" -> inValue = false
                    "c" -> {
                        val raw = value.toString()
                        val text = if (type == "s") raw.trim().toIntOrNull()?.let(shared::getOrNull) else raw
                        if (!text.isNullOrEmpty()) row[column] = text
                    }
                    "row" -> if (row.isNotEmpty()) rows += (0..row.keys.max()).map { row[it] }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inValue) value.appendRange(ch, start, start + length)
            }
        })
        return rows
    }

    /** "C12" → 2. */
    internal fun columnIndex(ref: String): Int = ref.takeWhile(Char::isLetter).fold(0) { n, ch -> n * 26 + (ch.uppercaseChar() - 'A' + 1) } - 1
}
