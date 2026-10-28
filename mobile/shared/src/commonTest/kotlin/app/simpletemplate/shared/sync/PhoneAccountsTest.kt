package app.simpletemplate.shared.sync

import app.simpletemplate.shared.auth.DataOwner
import app.simpletemplate.shared.data.appEntities
import app.simpletemplate.shared.data.testStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class PhoneAccountsTest {
    private val store = testStore()
    private val registry = SyncRegistry(appEntities().entities + testPhoto)
    private val deleted = mutableListOf<String>()
    private val accounts = PhoneAccounts(store, registry) { deleted += it }
    private val writer = RecordWriter(store.driver, registry)
    private val org = Uuid.generateV7()
    private val local = Uuid.generateV7()

    init {
        store.createTestChildTable()
    }

    private fun item(owner: Uuid): Uuid {
        val id = Uuid.generateV7()
        writer.upsert("item", buildJsonObject {
            put("id", id.toString()); put("owner_org_id", owner.toString()); put("title", "Check fire exits")
            put("status", "OPEN"); put("version", 1); put("created_at", 1761300000000); put("updated_at", 1761300000000)
        })
        return id
    }

    private fun photo(owner: Uuid, itemId: Uuid, file: String) {
        val id = Uuid.generateV7()
        writer.upsert("item_photo", buildJsonObject {
            put("id", id.toString()); put("owner_org_id", owner.toString()); put("item_id", itemId.toString())
            put("version", 1); put("created_at", 1761300000000); put("updated_at", 1761300000000)
        })
        // The file's name on this phone is the phone's own (never synced)
        store.driver.execute(null, "UPDATE item_photo SET file_name = '$file' WHERE id = '$id'", 0)
    }

    @Test
    fun theOwnerIsRememberedAndClaimedOnlyOnce() = runTest {
        assertNull(accounts.owner())
        accounts.claim(DataOwner("bo", "Bo", "bo@example.com"))
        accounts.claim(DataOwner("ada", "Ada", "ada@example.com"))
        assertEquals("Bo", accounts.owner()?.name)
        accounts.setOwner(DataOwner("ada", "Ada", "ada@example.com"))
        assertEquals("Ada", accounts.owner()?.name)
    }

    @Test
    fun theWorkspacesOnThePhoneWithWhatIsNotSent() = runTest {
        item(org)
        item(local)
        store.enqueue(org, "item", Uuid.generateV7(), buildJsonObject { put("title", "Queued") })
        val refused = store.enqueue(org, "item", Uuid.generateV7(), buildJsonObject { put("title", "Refused") })
        store.db.syncQueries.markRejected("No", refused)
        store.enqueue(local, "item", Uuid.generateV7(), buildJsonObject { put("title", "Never sent") })
        val other = Uuid.generateV7().toString()
        store.db.syncQueries.saveCursor(other, "c1", 1)
        store.db.syncQueries.saveCursor("_device", Uuid.generateV7().toString(), 1)
        store.db.syncQueries.saveConflict(Uuid.generateV7().toString(), other, "item", Uuid.generateV7().toString(), "CONFLICT", "{}", null, 2, 1)

        // Refused and conflicting changes count: they are lost too if the workspace goes
        assertEquals(mapOf(org.toString() to 2L, other to 1L), accounts.workspaces(local))
    }

    @Test
    fun purgingRemovesTheWorkspacesRowsFilesQueueAndCursorButNotTheLocalOnes() = runTest {
        val theirs = item(org)
        photo(org, theirs, "photo-1.jpg")
        val mine = item(local)
        photo(local, mine, "mine.jpg")
        store.db.syncQueries.saveCursor(org.toString(), "c1", 1)
        store.enqueue(org, "item", theirs, buildJsonObject { put("title", "Changed") }, "UPDATE", 1)
        store.enqueue(local, "item", mine, buildJsonObject { put("title", "Mine") }, "UPDATE", 1)

        accounts.purge(setOf(org.toString()))

        assertEquals(listOf("photo-1.jpg"), deleted)
        assertEquals(1L, store.count("item"))
        assertEquals(1L, store.count("item_photo"))
        assertEquals(1L, store.count("outbox_operation", "owner_org_id = '$local'"))
        assertEquals(0L, store.count("outbox_operation", "owner_org_id = '$org'"))
        assertNull(store.db.syncQueries.cursor(org.toString()).executeAsOneOrNull())
        assertEquals(emptyMap(), accounts.workspaces(local))
    }
}
