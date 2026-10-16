package app.simpletemplate.shared.data

import app.simpletemplate.shared.domain.Item
import app.simpletemplate.shared.domain.ItemStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

// EXAMPLE feature tests: the pattern to copy for the app's own repositories.
class ItemRepositoryTest {
    private val clock = FixedClock()
    private val store = testStore(clock)
    private val tenant = Tenant(Uuid.generateV7())
    private val items = ItemRepository(store, tenant)

    @Test
    fun savingANewItemStoresItAndQueuesACreate() = runTest {
        val item = Item(Uuid.generateV7(), "Check fire exits", notes = "Ground floor")

        items.save(item)

        assertEquals(listOf(item), items.observeItems().first())
        val op = store.outboxOps().single()
        assertEquals("item", op.entity_type)
        assertEquals("CREATE", op.op_type)
        assertEquals(0, op.base_version)
        assertEquals(tenant.orgId.toString(), op.fields["owner_org_id"]?.jsonPrimitive?.content)
        assertEquals("Check fire exits", op.fields["title"]?.jsonPrimitive?.content)
    }

    @Test
    fun anUpdateQueuesOnlyTheChangedFields() = runTest {
        val item = Item(Uuid.generateV7(), "Check fire exits")
        items.save(item)
        clock.now += 5.minutes

        items.save(item.copy(status = ItemStatus.DONE))

        val update = store.outboxOps().last()
        assertEquals("UPDATE", update.op_type)
        assertEquals(setOf("status"), update.fields.keys)
        // Nothing changed: nothing queued
        items.save(item.copy(status = ItemStatus.DONE))
        assertEquals(2, store.outboxOps().size)
    }

    @Test
    fun deletingHidesTheItemAndQueuesADelete() = runTest {
        val item = Item(Uuid.generateV7(), "Check fire exits")
        items.save(item)

        items.delete(item.id)

        assertEquals(emptyList(), items.observeItems().first())
        assertNull(items.item(item.id))
        assertEquals("DELETE", store.outboxOps().last().op_type)
    }

    @Test
    fun itemsAreScopedToTheirOrganisation() = runTest {
        items.save(Item(Uuid.generateV7(), "Ours"))
        val other = ItemRepository(store, Tenant(Uuid.generateV7()))

        assertEquals(emptyList(), other.observeItems().first())
    }
}
