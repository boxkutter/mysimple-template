package app.simpletemplate.shared.sync

import kotlinx.serialization.json.JsonObject

/**
 * Who may change an entity from a device (platform contract §5.3, field ownership). The platform
 * decides (its `SyncPolicy` for the entity); declaring it here lets the app say so before sending
 * rather than collect a `REJECTED`.
 */
sealed interface Ownership {
    /** The device's own records (the assignee's work): created, changed and deleted on the phone. */
    data object Device : Ownership

    /** Planned by the organisation on the web; the device only receives them. */
    data object Organisation : Ownership

    /** The organisation owns the record; the device may only update [deviceFields]. */
    data class Mixed(val deviceFields: Set<String>) : Ownership
}

/**
 * Columns of a local table that hold a file kept on this phone (Attachments, contract §5.2).
 * [fileName] is this phone's own (where the file lies in [FileStorage]), never overwritten by a
 * pulled record; the others are synced columns.
 */
data class FileColumns(
    val fileName: String = "file_name",
    val sha256: String = "sha256",
    val mimeType: String = "mime_type",
    val originalName: String? = "original_name",
    val fileObjectId: String = "file_object_id",
)

/**
 * One entity this app syncs. [wireName] is the platform's name for it (global: every app shares
 * them, contract §5.1) and [table] the local table, whose columns are named as the platform's
 * (payload keys are column names): the engine writes pulled records into it generically.
 */
data class SyncEntity(
    val wireName: String,
    val table: String = wireName,
    val ownership: Ownership = Ownership.Device,
    /**
     * The platform's columns for the entity. When given, an operation with any other key is kept
     * back with a reason instead of being sent (since platform M8 the server rejects it).
     */
    val platformColumns: Set<String>? = null,
    /** Local columns whose value is this phone's own; a pulled record never overwrites them. */
    val localOnly: Set<String> = emptySet(),
    /** The record carries a file (Attachments). */
    val files: FileColumns? = null,
) {
    init {
        require(SAFE_NAME.matches(wireName) && SAFE_NAME.matches(table)) { "Not a table name: $wireName / $table" }
    }

    /** Columns a pulled record must not overwrite. */
    internal val keptLocally: Set<String> get() = localOnly + listOfNotNull(files?.fileName)
}

/** The entities an app syncs, by wire name and by local table. */
class SyncRegistry(val entities: List<SyncEntity>) {
    private val byWire = entities.associateBy { it.wireName }
    private val byTable = entities.associateBy { it.table }

    init {
        require(byWire.size == entities.size) { "An entity is registered twice" }
    }

    operator fun get(wireName: String): SyncEntity? = byWire[wireName]

    fun forTable(table: String): SyncEntity? = byTable[table]

    /**
     * Why the platform would refuse this operation, or null: an entity this app does not sync,
     * one the organisation owns, a field the device may not change, a key that is not a column.
     */
    fun problem(entityType: String, opType: String, payload: JsonObject): String? {
        val entity = byWire[entityType] ?: return "this app does not sync $entityType"
        val name = entityType.replace('_', ' ')
        val planned = "is planned by your organisation in My Simple ISO: change it there"
        when (val o = entity.ownership) {
            Ownership.Device -> Unit
            Ownership.Organisation -> return "$name $planned"
            is Ownership.Mixed -> {
                if (opType != "UPDATE") return "$name $planned"
                val others = payload.keys.filter { it !in o.deviceFields }.sorted()
                if (others.isNotEmpty()) return "${others.joinToString()} of the $name $planned"
            }
        }
        val columns = entity.platformColumns ?: return null
        val unknown = payload.keys.filter { it !in columns }.sorted()
        return if (unknown.isEmpty()) null else "unknown fields for $entityType: ${unknown.joinToString()}"
    }
}

internal val SAFE_NAME = Regex("^[a-z][a-z0-9_]{0,62}$")
