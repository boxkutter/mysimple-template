package app.simpletemplate.shared.data

import app.simpletemplate.shared.db.SyncQueries
import app.simpletemplate.shared.sync.SyncEntity
import app.simpletemplate.shared.sync.SyncRegistry
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The entities this app syncs: one per synced table, named as on the platform (docs/platform.md,
 * "Sync"). Each must be registered on the platform before the server accepts it (contract §7);
 * the sync engine applies the platform's records to the local tables named here.
 */
internal enum class EntityType(val entity: SyncEntity) {
    // EXAMPLE feature: replace with the app's own entities. "item" is NOT a platform entity: the
    // server answers REJECTED to it. An app registers its own names on the platform (contract §7)
    // and reuses the platform's for organisations, contacts, sites and processes.
    ITEM(SyncEntity("item", platformColumns = setOf("id", "owner_org_id", "title", "notes", "status", "version", "created_at", "updated_at", "deleted_at"))),
    ;

    val wireName: String get() = entity.wireName
}

/** The app's entities, for the sync engine. */
fun appEntities(): SyncRegistry = SyncRegistry(EntityType.entries.map { it.entity })

/**
 * Queues sync operations. Callers enqueue inside the same transaction as the local write, so the
 * database and the outbox never disagree. Each operation belongs to the workspace ([Tenant]) it
 * was made in; only that organisation's sync sends it.
 */
internal class Outbox(
    private val queries: SyncQueries,
    private val clock: Clock,
    private val newId: () -> Uuid,
) {
    fun created(tenant: Tenant, type: EntityType, id: Uuid, fields: JsonObject) =
        enqueue(tenant, type, id, "CREATE", fields, baseVersion = 0)

    /** Queues only the fields that changed; nothing when [before] and [after] are equal. */
    fun updated(tenant: Tenant, type: EntityType, id: Uuid, baseVersion: Long, before: JsonObject, after: JsonObject) {
        val changed = after.filter { (key, value) -> before[key] != value }
        if (changed.isNotEmpty()) enqueue(tenant, type, id, "UPDATE", JsonObject(changed), baseVersion)
    }

    fun deleted(tenant: Tenant, type: EntityType, id: Uuid, baseVersion: Long) =
        enqueue(tenant, type, id, "DELETE", JsonObject(emptyMap()), baseVersion)

    private fun enqueue(tenant: Tenant, type: EntityType, id: Uuid, op: String, payload: JsonObject, baseVersion: Long) {
        queries.enqueue(
            op_id = newId().toString(),
            entity_type = type.wireName,
            entity_id = id.toString(),
            op_type = op,
            payload = payload.toString(),
            base_version = baseVersion,
            created_at = clock.now().toEpochMilliseconds(),
            owner_org_id = tenant.orgId.toString(),
        )
    }
}
