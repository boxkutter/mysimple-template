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
 *
 * Callers pass the record's local version; the operation is based on the version the platform
 * will hold when it arrives (see [predictedBase]), so several changes to one record made before a
 * sync reach the platform as a chain, not as conflicts with each other.
 */
internal class Outbox(
    private val queries: SyncQueries,
    private val clock: Clock,
    private val newId: () -> Uuid,
) {
    fun created(tenant: Tenant, type: EntityType, id: Uuid, fields: JsonObject) =
        enqueue(tenant, type, id, "CREATE", fields, recordVersion = 0)

    /** Queues only the fields that changed; nothing when [before] and [after] are equal. */
    fun updated(tenant: Tenant, type: EntityType, id: Uuid, recordVersion: Long, before: JsonObject, after: JsonObject) {
        val changed = after.filter { (key, value) -> before[key] != value }
        if (changed.isNotEmpty()) enqueue(tenant, type, id, "UPDATE", JsonObject(changed), recordVersion)
    }

    fun deleted(tenant: Tenant, type: EntityType, id: Uuid, recordVersion: Long) =
        enqueue(tenant, type, id, "DELETE", JsonObject(emptyMap()), recordVersion)

    /**
     * The version the platform will hold when an operation on this record reaches it. The local
     * version moves only when the platform accepts a change, and the platform compares an
     * operation's base strictly with its current version, so a second change made before a sync
     * must not be based on the local version: each accepted operation raises the platform's
     * version by one (a CREATE makes version 1), so the operation is based on the last unsent
     * one's base plus one; with nothing unsent, on the record's own version. The same rule as the
     * platform's web app (`predictedBase`).
     *
     * A refused operation (REJECTED, kept with its reason) is never sent, so it is left out: a
     * change made after the refusal is based on the last operation still to be sent. Operations
     * already queued behind a refused one keep their base, one too high, and come back as a
     * CONFLICT with the platform's record for the user to resolve (keep mine re-queues on the
     * platform's version).
     *
     * An app that sends one change as several operations (for example the steps of a status
     * change the platform only accepts one at a time) enqueues them one after another: each is
     * based on the one queued before it.
     */
    private fun predictedBase(type: EntityType, id: Uuid, recordVersion: Long): Long =
        queries.lastUnsentBase(type.wireName, id.toString()).executeAsOneOrNull()?.let { it + 1 } ?: recordVersion

    private fun enqueue(tenant: Tenant, type: EntityType, id: Uuid, op: String, payload: JsonObject, recordVersion: Long) {
        val baseVersion = if (op == "CREATE") 0 else predictedBase(type, id, recordVersion)
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
