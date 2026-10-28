package app.simpletemplate.shared.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.simpletemplate.shared.db.Item as ItemRow
import app.simpletemplate.shared.domain.Item
import app.simpletemplate.shared.domain.ItemStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

/**
 * EXAMPLE feature: the organisation's items. Works offline: every write goes to the local database
 * and queues a sync operation in the same transaction. Copy this shape for the app's own records.
 */
class ItemRepository(private val store: LocalStore, private val tenant: Tenant) {
    private val db = store.db
    private val outbox = store.outbox

    fun observeItems(): Flow<List<Item>> =
        db.itemQueries.forOwner(tenant.orgId.toString()).asFlow()
            .mapToList(store.dispatcher).map { rows -> rows.map { it.toItem() } }

    suspend fun item(id: Uuid): Item? = withContext(store.dispatcher) {
        db.itemQueries.byId(id.toString()).executeAsOneOrNull()?.takeIf { it.deleted_at == null }?.toItem()
    }

    /** Creates the item, or updates it if it already exists. */
    suspend fun save(item: Item) = withContext(store.dispatcher) {
        db.transaction {
            val now = store.nowMillis()
            val existing = db.itemQueries.byId(item.id.toString()).executeAsOneOrNull()
            if (existing == null) {
                db.itemQueries.insert(
                    item.id.toString(), tenant.orgId.toString(), item.title, item.notes, item.status.name, now, now,
                )
                outbox.created(tenant, EntityType.ITEM, item.id, item.fields(tenant))
            } else {
                db.itemQueries.update(item.title, item.notes, item.status.name, now, item.id.toString())
                outbox.updated(tenant, EntityType.ITEM, item.id, existing.version, existing.toItem().fields(tenant), item.fields(tenant))
            }
        }
    }

    /** Soft delete: the row stays (deleted_at) so the deletion can be synced. */
    suspend fun delete(id: Uuid) = withContext(store.dispatcher) {
        db.transaction {
            val existing = db.itemQueries.byId(id.toString()).executeAsOneOrNull() ?: return@transaction
            db.itemQueries.softDelete(store.nowMillis(), id.toString())
            outbox.deleted(tenant, EntityType.ITEM, id, existing.version)
        }
    }
}

private fun ItemRow.toItem() = Item(
    id = Uuid.parse(id),
    title = title,
    notes = notes,
    status = runCatching { ItemStatus.valueOf(status) }.getOrDefault(ItemStatus.OPEN),
)

/** The synced fields, named as on the server. */
private fun Item.fields(tenant: Tenant): JsonObject = buildJsonObject {
    put("owner_org_id", tenant.orgId.toString())
    put("title", title)
    put("notes", notes)
    put("status", status.name)
}
