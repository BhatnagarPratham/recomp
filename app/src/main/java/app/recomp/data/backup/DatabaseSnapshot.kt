package app.recomp.data.backup

import android.database.Cursor
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** One `CREATE` statement from `sqlite_master` (TRD §6.5). */
@Serializable
data class SchemaItem(val type: String, val name: String, val table: String, val sql: String)

/** A table's rows, each a list of values in [columns] order. */
@Serializable
data class TableData(val columns: List<String>, val rows: List<List<JsonElement>>)

/** `database.json`: the schema that wrote the rows, and every user table's rows. */
@Serializable
data class DatabaseData(val schema: List<SchemaItem>, val tables: Map<String, TableData>)

/**
 * Reads and writes a whole database generically from `sqlite_master` (TRD §6.5), so a table added by a
 * later milestone is backed up with no code change. SQLite's and Room's own tables and FTS indexes are
 * left out; FTS is rebuilt from its content table.
 */
object DatabaseSnapshot {
    private val INTERNAL = setOf("android_metadata", "room_master_table", "sqlite_sequence")
    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val ALLOWED_SQL = mapOf(
        "table" to listOf("CREATE TABLE", "CREATE VIRTUAL TABLE"),
        "index" to listOf("CREATE INDEX", "CREATE UNIQUE INDEX"),
        "trigger" to listOf("CREATE TRIGGER"),
    )

    fun isIdentifier(name: String) = IDENTIFIER.matches(name)

    private fun isVirtual(item: SchemaItem) = item.type == "table" && item.sql.trimStart().startsWith("CREATE VIRTUAL TABLE", ignoreCase = true)

    /** Every object a backup carries: user tables, FTS tables, indexes and triggers, tables first. */
    fun schema(query: (String) -> Cursor): List<SchemaItem> {
        val all = query("SELECT type, name, tbl_name, sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY rowid").use { c ->
            buildList { while (c.moveToNext()) add(SchemaItem(c.getString(0), c.getString(1), c.getString(2), c.getString(3))) }
        }
        val virtual = all.filter(::isVirtual).map { it.name }
        fun internal(name: String) = name.startsWith("sqlite_") || name in INTERNAL || virtual.any { name.startsWith("${it}_") }
        return all.filter { it.type in ALLOWED_SQL && !internal(it.name) && !internal(it.table) }
            .sortedBy { listOf("table", "index", "trigger").indexOf(it.type) }
    }

    /** User tables holding rows: everything but FTS indexes. */
    fun dataTables(schema: List<SchemaItem>): List<String> = schema.filter { it.type == "table" && !isVirtual(it) }.map { it.name }

    fun virtualTables(schema: List<SchemaItem>): List<String> = schema.filter(::isVirtual).map { it.name }

    fun columns(query: (String) -> Cursor, table: String): List<String> = query("PRAGMA table_info(`$table`)").use { c ->
        val name = c.getColumnIndexOrThrow("name")
        buildList { while (c.moveToNext()) add(c.getString(name)) }
    }

    /** The whole database. Callers read it inside one transaction so it's consistent. */
    fun read(query: (String) -> Cursor): DatabaseData {
        val schema = schema(query)
        val tables = dataTables(schema).associateWith { table ->
            val columns = columns(query, table)
            val rows = query("SELECT ${columns.joinToString { "`$it`" }} FROM `$table` ORDER BY rowid").use { c ->
                buildList { while (c.moveToNext()) add(columns.indices.map { cell(c, it) }) }
            }
            TableData(columns, rows)
        }
        return DatabaseData(schema, tables)
    }

    /** A value with its SQLite storage class kept: integers stay integers, REALs round-trip exactly, BLOBs are base64. */
    private fun cell(c: Cursor, i: Int): JsonElement = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> JsonNull
        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(c.getLong(i))
        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i).let { if (it.isFinite()) JsonPrimitive(it) else JsonObject(mapOf(REAL to JsonPrimitive(it.toString()))) }
        Cursor.FIELD_TYPE_BLOB -> JsonObject(mapOf(BLOB to JsonPrimitive(Base64.getEncoder().encodeToString(c.getBlob(i)))))
        else -> JsonPrimitive(c.getString(i))
    }

    private const val REAL = "real"
    private const val BLOB = "base64"

    /** A cell as a value to bind: null, String, Long, Double or ByteArray. */
    fun value(e: JsonElement): Any? = when (e) {
        JsonNull -> null
        is JsonObject -> when {
            BLOB in e -> Base64.getDecoder().decode(e.getValue(BLOB).jsonPrimitive.content)
            REAL in e -> e.getValue(REAL).jsonPrimitive.content.toDouble()
            else -> throw BackupException.damaged("an unknown value in a table")
        }
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.content.any { it == '.' || it == 'e' || it == 'E' } -> e.content.toDouble()
            else -> e.content.toLongOrNull() ?: throw BackupException.damaged("a number that isn't one")
        }
        else -> throw BackupException.damaged("a list where a value should be")
    }

    /** Checks the schema only creates things, under plain names, before any of it runs. */
    fun validate(data: DatabaseData) {
        data.schema.forEach { item ->
            val prefixes = ALLOWED_SQL[item.type] ?: throw BackupException.damaged("a ${item.type} in its schema")
            if (prefixes.none { item.sql.trimStart().startsWith(it, ignoreCase = true) }) throw BackupException.damaged("an unexpected statement in its schema")
            if (!isIdentifier(item.name) || !isIdentifier(item.table)) throw BackupException.damaged("an unexpected name in its schema")
        }
        val tables = dataTables(data.schema).toSet()
        data.tables.forEach { (name, table) ->
            if (name !in tables) throw BackupException.damaged("rows for a table it doesn't define ($name)")
            if (table.columns.any { !isIdentifier(it) }) throw BackupException.damaged("an unexpected column name in $name")
            if (table.rows.any { it.size != table.columns.size }) throw BackupException.damaged("a short row in $name")
        }
    }
}
