package app.simpletemplate.shared.data

import app.simpletemplate.shared.db.SyncQueries
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Entity names as used in sync operations; they match the server's table names. Add one per synced
 * table (docs/platform.md, "Sync").
 */
internal enum class EntityType(val wireName: String) {
    // EXAMPLE feature: replace with the app's own entities.
    ITEM("item"),
}

/**
 * Queues sync operations. Callers enqueue inside the same transaction as the local write, so the
 * database and the outbox never disagree.
 */
internal class Outbox(
    private val queries: SyncQueries,
    private val clock: Clock,
    private val newId: () -> Uuid,
) {
    fun created(type: EntityType, id: Uuid, fields: JsonObject) =
        enqueue(type, id, "CREATE", fields, baseVersion = 0)

    /** Queues only the fields that changed; nothing when [before] and [after] are equal. */
    fun updated(type: EntityType, id: Uuid, baseVersion: Long, before: JsonObject, after: JsonObject) {
        val changed = after.filter { (key, value) -> before[key] != value }
        if (changed.isNotEmpty()) enqueue(type, id, "UPDATE", JsonObject(changed), baseVersion)
    }

    fun deleted(type: EntityType, id: Uuid, baseVersion: Long) =
        enqueue(type, id, "DELETE", JsonObject(emptyMap()), baseVersion)

    private fun enqueue(type: EntityType, id: Uuid, op: String, payload: JsonObject, baseVersion: Long) {
        queries.enqueue(
            op_id = newId().toString(),
            entity_type = type.wireName,
            entity_id = id.toString(),
            op_type = op,
            payload = payload.toString(),
            base_version = baseVersion,
            created_at = clock.now().toEpochMilliseconds(),
        )
    }
}
