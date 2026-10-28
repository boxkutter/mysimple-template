package app.simpletemplate.shared.sync

import app.simpletemplate.shared.ApiClient
import app.simpletemplate.shared.data.ItemRepository
import app.simpletemplate.shared.data.Tenant
import app.simpletemplate.shared.data.appEntities
import app.simpletemplate.shared.data.testStore
import app.simpletemplate.shared.domain.Item
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class SyncEngineTest {
    private val store = testStore()
    private val platform = ScriptedPlatform()
    private val api = ApiClient("https://api.test", platform.engine)
    private val registry = SyncRegistry(appEntities().entities + testPhoto)
    private val deleted = mutableListOf<String>()
    private val engine = SyncEngine(store, api, deviceName = "Pixel", appVersion = "0.2.0", registry = registry) { deleted += it }
    private val org = Uuid.generateV7()
    private val localWorkspace = Uuid.generateV7()
    private val items = ItemRepository(store, Tenant(org))

    init {
        store.createTestChildTable()
    }

    private fun item(id: Uuid, title: String, version: Int = 3) = buildJsonObject {
        put("id", id.toString()); put("owner_org_id", org.toString()); put("title", title); put("notes", null as String?)
        put("status", "OPEN"); put("version", version); put("created_at", 1761300000000); put("updated_at", 1761300000000)
        // Server-owned columns this app does not keep are ignored.
        put("created_by", Uuid.generateV7().toString())
    }

    private fun photo(id: Uuid, itemId: Uuid) = buildJsonObject {
        put("id", id.toString()); put("owner_org_id", org.toString()); put("item_id", itemId.toString())
        put("sha256", "ab".repeat(32)); put("mime_type", "image/jpeg"); put("file_name", "another-phone.jpg")
        put("version", 1); put("created_at", 1761300000000); put("updated_at", 1761300000000)
    }

    @Test
    fun onlyTheWorkspacesOwnOperationsAreSentWithTheDeviceAndOrganisation() = runTest {
        items.save(Item(Uuid.generateV7(), "Check fire exits"))
        ItemRepository(store, Tenant(localWorkspace)).save(Item(Uuid.generateV7(), "Never sent"))
        val mine = store.db.syncQueries.pendingFor(org.toString(), 10).executeAsList().single().op_id
        platform.answers += { answer("""[{"op_id":"$mine","status":"ACCEPTED","server_version":1}]""") }

        val report = engine.sync(org)

        val (request, body) = platform.requests.single()
        assertEquals(listOf(mine), body.ops.map { it.opId })
        assertEquals("item", body.ops.single().entityType)
        assertEquals(org.toString(), request.headers["X-Org-Id"])
        assertEquals(engine.deviceId().toString(), request.headers["X-Device-Id"])
        assertEquals("Pixel", body.deviceName)
        assertEquals("0.2.0", body.appVersion)
        assertEquals(1, report.accepted)
        assertEquals(0L, engine.pendingCount(org))
        assertEquals(1L, engine.pendingCount(localWorkspace), "the local workspace's change stays on the phone")
        assertEquals(1L, items.observeItems().first().size.toLong())
        assertEquals("1", store.driver.executeQuery(null, "SELECT version FROM item", { c -> c.next(); app.cash.sqldelight.db.QueryResult.Value(c.getLong(0).toString()) }, 0).value)
    }

    @Test
    fun theDeviceIdIsStable() {
        assertEquals(engine.deviceId(), engine.deviceId())
        assertEquals(engine.deviceId(), SyncEngine(store, api).deviceId())
    }

    @Test
    fun recordsAreAppliedPagedAndTheCursorIsKept() = runTest {
        val a = Uuid.generateV7()
        val b = Uuid.generateV7()
        platform.answers += { answer(changes = listOf(change("item", a, item(a, "First"))), more = true, cursor = "c1") }
        platform.answers += { req ->
            assertEquals("c1", req.cursor)
            answer(changes = listOf(change("item", b, item(b, "Second")), change("risk", Uuid.generateV7(), buildJsonObject { put("id", "x") })), cursor = "c2")
        }

        val report = engine.sync(org)

        assertEquals(2, platform.requests.size)
        assertEquals("First", store.text("item", "title", a))
        assertEquals("Second", store.text("item", "title", b))
        assertEquals(2, report.received)
        assertEquals(1, report.skipped, "an entity this app does not register is skipped")
        assertEquals("c2", store.db.syncQueries.cursor(org.toString()).executeAsOne().cursor_token)
        assertEquals(listOf("First", "Second"), items.observeItems().first().map { it.title }.sorted())
    }

    @Test
    fun aPulledRecordNeverOverwritesThisPhonesFileName() = runTest {
        val i = Uuid.generateV7()
        val p = Uuid.generateV7()
        platform.answers += { answer(changes = listOf(change("item", i, item(i, "Item")), change("item_photo", p, photo(p, i)))) }
        engine.sync(org)
        assertNull(store.text("item_photo", "file_name", p))
        assertEquals("ab".repeat(32), store.text("item_photo", "sha256", p))
    }

    @Test
    fun aTombstoneRemovesTheRecordAndWhatHangsOnIt() = runTest {
        val i = Uuid.generateV7()
        val p = Uuid.generateV7()
        platform.answers += { answer(changes = listOf(change("item", i, item(i, "Kept")), change("item_photo", p, photo(p, i)))) }
        engine.sync(org)
        store.driver.execute(null, "UPDATE item_photo SET file_name = 'photo.jpg'", 0)
        store.enqueue(org, "item_photo", p, buildJsonObject { put("original_name", "x.jpg") }, "UPDATE")
        val queued = store.db.syncQueries.pendingFor(org.toString(), 10).executeAsList().single().op_id

        // Taken away: the parent's tombstone first, then the child's (already gone here)
        platform.answers += { req ->
            assertEquals(listOf(queued), req.ops.map { it.opId })
            answer("""[{"op_id":"$queued","status":"RETRY_LATER"}]""", changes = listOf(change("item", i, null, op = "RESTRICT"), change("item_photo", p, null)), cursor = "c2")
        }
        val report = engine.sync(org)

        assertNull(store.text("item", "title", i))
        assertEquals(0L, store.count("item_photo"))
        assertEquals(0L, engine.pendingCount(org), "the queued change went with its record")
        assertEquals(listOf("photo.jpg"), deleted)
        assertEquals(2, report.removed)
    }

    @Test
    fun aRefusedOperationIsKeptWithItsReasonAndNotResent() = runTest {
        val refused = store.enqueue(org, "item", Uuid.generateV7(), buildJsonObject { put("title", "x") })
        val later = store.enqueue(org, "item", Uuid.generateV7(), buildJsonObject { put("title", "y") })
        val transition = store.enqueue(org, "item", Uuid.generateV7(), buildJsonObject { put("status", "DONE") }, "UPDATE")
        platform.answers += {
            answer(
                """[{"op_id":"$refused","status":"REJECTED","reason":"Unknown entity type: item"},""" +
                    """{"op_id":"$later","status":"RETRY_LATER","reason":"parent"},""" +
                    """{"op_id":"$transition","status":"INVALID_TRANSITION","reason":"OPEN to DONE is not allowed"}]""",
            )
        }
        val report = engine.sync(org)
        assertEquals(2, report.rejected)
        assertEquals(1, report.retryLater)
        assertEquals(setOf("Unknown entity type: item", "OPEN to DONE is not allowed"), engine.rejected(org).map { it.last_error }.toSet())
        platform.answers += { req -> assertEquals(listOf(later), req.ops.map { it.opId }); answer() }
        engine.sync(org)
    }

    @Test
    fun discardingARefusedChangeBringsThePlatformsRecordBack() = runTest {
        val a = Uuid.generateV7()
        platform.answers += { answer(changes = listOf(change("item", a, item(a, "Theirs"))), cursor = "c1") }
        engine.sync(org)
        store.driver.execute(null, "UPDATE item SET title = 'Mine' WHERE id = ?", 1) { bindString(0, a.toString()) }
        val refused = store.enqueue(org, "item", a, buildJsonObject { put("title", "Mine") }, "UPDATE")
        platform.answers += { answer("""[{"op_id":"$refused","status":"REJECTED","reason":"Not yours"}]""", cursor = "c2") }
        engine.sync(org)

        engine.discardRejected(refused, org)
        platform.answers += { req ->
            assertNull(req.cursor, "the feed starts again")
            answer(changes = listOf(change("item", a, item(a, "Theirs"))), cursor = "c3")
        }
        engine.sync(org)

        assertEquals(emptyList(), engine.rejected(org))
        assertEquals("Theirs", store.text("item", "title", a))
    }

    @Test
    fun aConflictIsKeptForTheUserToResolve() = runTest {
        val id = Uuid.generateV7()
        platform.answers += { answer(changes = listOf(change("item", id, item(id, "Original")))) }
        engine.sync(org)
        val op = store.enqueue(org, "item", id, buildJsonObject { put("notes", "mine") }, "UPDATE", baseVersion = 3)
        platform.answers += {
            answer("""[{"op_id":"$op","status":"CONFLICT","server_version":4,"server_record":${item(id, "Theirs", version = 4)}}]""", cursor = "c2")
        }
        val report = engine.sync(org)
        assertEquals(1, report.conflicts)
        val conflict = engine.conflicts(org).single()
        assertEquals("CONFLICT", conflict.status)
        assertTrue("mine" in conflict.local_payload)

        engine.keepMine(conflict)
        val resent = store.db.syncQueries.pendingFor(org.toString(), 10).executeAsList().single()
        assertEquals("UPDATE", resent.op_type)
        assertEquals(4, resent.base_version, "sent again on top of the platform's version")
        assertEquals(emptyList(), engine.conflicts(org))
    }

    @Test
    fun takingThePlatformsVersionReplacesThePhones() = runTest {
        val id = Uuid.generateV7()
        val op = store.enqueue(org, "item", id, buildJsonObject { put("title", "Mine") })
        platform.answers += { answer("""[{"op_id":"$op","status":"EDIT_WINS","server_version":5,"server_record":${item(id, "Theirs", 5)}}]""") }
        engine.sync(org)
        assertEquals("Theirs", store.text("item", "title", id), "a delete that lost to an edit brings the record back")
        store.driver.execute(null, "UPDATE item SET title = 'Changed again' WHERE id = ?", 1) { bindString(0, id.toString()) }

        engine.useTheirs(engine.conflicts(org).single())

        assertEquals("Theirs", store.text("item", "title", id))
        assertEquals(emptyList(), engine.conflicts(org))
    }

    @Test
    fun aLocalChangeWaitingToBeSentIsNotOverwrittenByAPull() = runTest {
        val f = Uuid.generateV7()
        platform.answers += { answer(changes = listOf(change("item", f, item(f, "Platform")))) }
        engine.sync(org)
        val op = store.enqueue(org, "item", f, buildJsonObject { put("title", "Phone") }, "UPDATE")
        platform.answers += {
            answer("""[{"op_id":"$op","status":"RETRY_LATER"}]""", changes = listOf(change("item", f, item(f, "Older"))))
        }
        engine.sync(org)
        assertEquals("Platform", store.text("item", "title", f)) // not replaced while the phone's change waits
    }

    @Test
    fun whatThePlatformWouldRefuseIsNeverSent() = runTest {
        val registry = SyncRegistry(
            listOf(
                SyncEntity("item", platformColumns = setOf("id", "title", "status")),
                SyncEntity("item_photo", ownership = Ownership.Organisation),
                SyncEntity("item_note", ownership = Ownership.Mixed(setOf("status"))),
            ),
        )
        val engine = SyncEngine(store, api, registry = registry)
        val unknown = store.enqueue(org, "item", Uuid.generateV7(), buildJsonObject { put("title", "x"); put("seq", 3) })
        val notSynced = store.enqueue(org, "local_draft", Uuid.generateV7(), buildJsonObject { put("text", "q") })
        val owned = store.enqueue(org, "item_photo", Uuid.generateV7(), buildJsonObject { put("sha256", "a") })
        val field = store.enqueue(org, "item_note", Uuid.generateV7(), buildJsonObject { put("title", "t") }, "UPDATE")
        val allowed = store.enqueue(org, "item_note", Uuid.generateV7(), buildJsonObject { put("status", "DONE") }, "UPDATE")
        platform.answers += { req -> assertEquals(listOf(allowed), req.ops.map { it.opId }); answer("""[{"op_id":"$allowed","status":"ACCEPTED"}]""") }

        engine.sync(org)

        val reasons = engine.rejected(org).associate { it.op_id to it.last_error.orEmpty() }
        assertTrue("unknown fields for item: seq" in reasons.getValue(unknown))
        assertTrue("does not sync local_draft" in reasons.getValue(notSynced))
        assertTrue("item photo is planned by your organisation" in reasons.getValue(owned))
        assertTrue("title of the item note is planned by your organisation" in reasons.getValue(field))
    }

    @Test
    fun aWipeRemovesTheWorkspaceAndIsAcknowledged() = runTest {
        val a = Uuid.generateV7()
        val p = Uuid.generateV7()
        platform.answers += { answer(changes = listOf(change("item", a, item(a, "To go")), change("item_photo", p, photo(p, a)))) }
        engine.sync(org)
        store.driver.execute(null, "UPDATE item_photo SET file_name = 'photo.jpg'", 0)
        items.save(Item(Uuid.generateV7(), "Not sent yet"))
        val kept = Uuid.generateV7()
        ItemRepository(store, Tenant(localWorkspace)).save(Item(kept, "The local workspace's"))
        platform.answers += { answer(wipe = listOf(org.toString())) }

        val report = engine.sync(org)

        assertEquals(listOf(org.toString()), report.wiped)
        assertEquals(0L, store.count("item", "owner_org_id = '$org'"))
        assertEquals(0L, store.count("item_photo"))
        assertEquals(listOf("photo.jpg"), deleted)
        assertEquals(0L, engine.pendingCount(org))
        assertNull(store.db.syncQueries.cursor(org.toString()).executeAsOneOrNull())
        val (ack, ids) = platform.wipeAcks.single()
        assertEquals(listOf(org.toString()), ids)
        assertEquals(org.toString(), ack.headers["X-Org-Id"])
        assertEquals(engine.deviceId().toString(), ack.headers["X-Device-Id"])
        assertEquals("The local workspace's", store.text("item", "title", kept), "other workspaces are untouched")
        assertEquals(1L, engine.pendingCount(localWorkspace))
    }

    @Test
    fun afterASchemaChangeTheFeedStartsAgain() = runTest {
        store.db.syncQueries.saveCursor(org.toString(), "old", 1)
        store.db.syncQueries.saveCursor("_schema", "0", 1)
        platform.answers += { req -> assertNull(req.cursor); answer() }
        engine.sync(org)
        assertEquals(1, platform.requests.size)
    }
}
