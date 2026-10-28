package app.simpletemplate.shared.sync

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Writes platform records into the local tables. Every synced entity's local table uses the
 * platform's column names (contract §5: payload keys are column names), so one writer serves
 * them all: the record's keys that are columns of the local table are written, the rest
 * (server-owned columns this app does not keep) are ignored, and the entity's local-only columns
 * are left as they are.
 *
 * Writing here never queues an operation: these are the platform's records arriving.
 */
internal class RecordWriter(private val driver: SqlDriver, private val registry: SyncRegistry) {
    internal data class Column(val name: String, val type: String, val notNull: Boolean)

    private val columns = mutableMapOf<String, Map<String, Column>>()

    /** The local table's columns, or null when there is no such table. */
    internal fun columnsOf(table: String): Map<String, Column>? {
        columns[table]?.let { return it.ifEmpty { null } }
        if (!SAFE_NAME.matches(table)) return null
        val found = driver.executeQuery(null, "PRAGMA table_info($table)", { cursor ->
            val out = mutableMapOf<String, Column>()
            while (cursor.next().value) {
                val name = cursor.getString(1) ?: continue
                out[name] = Column(name, (cursor.getString(2) ?: "").uppercase(), cursor.getLong(3) == 1L)
            }
            QueryResult.Value(out.toMap())
        }, 0).value
        columns[table] = found
        return found.ifEmpty { null }
    }

    /** Inserts or updates the record (by id). Returns false when this app keeps no table for it. */
    fun upsert(entityType: String, record: JsonObject): Boolean {
        val entity = registry[entityType] ?: return false
        val cols = columnsOf(entity.table) ?: return false
        val kept = entity.keptLocally
        // A null the platform holds for a column this app requires becomes a neutral value rather
        // than blocking the whole sync.
        val values = record.filterKeys { it in cols && it !in kept && SAFE_NAME.matches(it) }.mapValues { (name, value) ->
            val column = cols.getValue(name)
            if (value is JsonNull && column.notNull) {
                if (column.type.startsWith("INT") || column.type.startsWith("REAL")) JsonPrimitive(0) else JsonPrimitive("")
            } else value
        }
        if ("id" !in values) return false
        val names = values.keys.toList()
        val placeholders = names.joinToString(", ") { "?" }
        val updates = names.filter { it != "id" }.joinToString(", ") { "$it = excluded.$it" }
        val sql = buildString {
            append("INSERT INTO ${entity.table} (${names.joinToString(", ")}) VALUES ($placeholders)")
            append(" ON CONFLICT(id) DO ")
            append(if (updates.isEmpty()) "NOTHING" else "UPDATE SET $updates")
        }
        driver.execute(null, sql, names.size) {
            names.forEachIndexed { i, name -> bind(i, cols.getValue(name), values.getValue(name)) }
        }
        return true
    }

    /**
     * The record is gone, or no longer the user's (a tombstone, contract §5). Rows that refer to
     * it by a declared foreign key (`REFERENCES` in the .sq file) go too, whatever order the
     * platform's tombstones come in, local-only rows included, with their queued changes. Returns
     * the files the removed rows named, for the platform code to delete.
     */
    fun remove(entityType: String, id: String): List<String> {
        val entity = registry[entityType] ?: return emptyList()
        columnsOf(entity.table) ?: return emptyList()
        val files = mutableListOf<String>()
        removeTree(entity.table, id, files, depth = 0)
        return files
    }

    internal fun removeTree(table: String, id: String, files: MutableList<String>, depth: Int) {
        check(depth < 16) { "Records nested too deep under $table" }
        for ((child, column) in dependants(table)) {
            val childCols = columnsOf(child) ?: continue
            if ("id" in childCols) {
                ids("SELECT id FROM $child WHERE $column = ?", id).forEach { removeTree(child, it, files, depth + 1) }
            } else {
                driver.execute(null, "DELETE FROM $child WHERE $column = ?", 1) { bindString(0, id) }
            }
        }
        val entity = registry.forTable(table)
        entity?.files?.let { f ->
            if (f.fileName in columnsOf(table).orEmpty()) {
                files += ids("SELECT ${f.fileName} FROM $table WHERE id = ? AND ${f.fileName} IS NOT NULL", id)
            }
        }
        driver.execute(null, "DELETE FROM outbox_operation WHERE entity_type = ? AND entity_id = ?", 2) {
            bindString(0, entity?.wireName ?: table); bindString(1, id)
        }
        driver.execute(null, "DELETE FROM $table WHERE id = ?", 1) { bindString(0, id) }
    }

    private var dependantsOf: Map<String, List<Pair<String, String>>>? = null

    /** Tables (and their column) that point at [table]'s ids by a declared foreign key. */
    internal fun dependants(table: String): List<Pair<String, String>> {
        val all = dependantsOf ?: run {
            val out = mutableMapOf<String, MutableSet<Pair<String, String>>>()
            for (child in tables()) {
                if (child in BOOKKEEPING) continue
                driver.executeQuery(null, "PRAGMA foreign_key_list($child)", { c ->
                    while (c.next().value) {
                        val parent = c.getString(2) ?: continue
                        val from = c.getString(3) ?: continue
                        if (parent != child) out.getOrPut(parent) { mutableSetOf() } += child to from
                    }
                    QueryResult.Value(Unit)
                }, 0)
            }
            out.mapValues { it.value.toList() }.also { dependantsOf = it }
        }
        return all[table].orEmpty()
    }

    /** The database's own tables (not SQLite's), by name. */
    internal fun tables(): List<String> =
        ids("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").filter { SAFE_NAME.matches(it) }

    internal fun ids(sql: String, vararg args: String): List<String> =
        driver.executeQuery(null, sql, { c ->
            val out = mutableListOf<String>()
            while (c.next().value) c.getString(0)?.let(out::add)
            QueryResult.Value(out.toList())
        }, args.size) { args.forEachIndexed { i, a -> bindString(i, a) } }.value

    internal fun execute(sql: String) {
        driver.execute(null, sql, 0)
    }

    /** The local version after the platform accepted a change (its `server_version`). */
    fun setVersion(entityType: String, id: String, version: Long) {
        val entity = registry[entityType] ?: return
        val cols = columnsOf(entity.table) ?: return
        if ("version" !in cols) return
        driver.execute(null, "UPDATE ${entity.table} SET version = ? WHERE id = ?", 2) {
            bindLong(0, version)
            bindString(1, id)
        }
    }

    private fun SqlPreparedStatement.bind(index: Int, column: Column, value: JsonElement) {
        when {
            value is JsonNull -> bindString(index, null)
            value is JsonArray || value is JsonObject -> bindString(index, value.toString())
            value is JsonPrimitive && column.type.startsWith("INT") -> bindLong(index, value.asLong())
            value is JsonPrimitive && (column.type.startsWith("REAL") || column.type.startsWith("DOUB")) ->
                bindDouble(index, value.doubleOrNull)
            value is JsonPrimitive && value.isString -> bindString(index, value.content)
            value is JsonPrimitive && value.booleanOrNull != null -> bindLong(index, if (value.booleanOrNull == true) 1 else 0)
            value is JsonPrimitive -> bindString(index, value.content)
        }
    }

    private fun JsonPrimitive.asLong(): Long? = booleanOrNull?.let { if (it) 1L else 0L }
        ?: longOrNull
        ?: doubleOrNull?.toLong()
        ?: content.toLongOrNull()

    companion object {
        /** The sync engine's own tables and per-user settings: never an entity's dependants. */
        internal val BOOKKEEPING = setOf("outbox_operation", "sync_cursor", "sync_conflict", "id_remap", "file_transfer", "user_setting")
    }
}
