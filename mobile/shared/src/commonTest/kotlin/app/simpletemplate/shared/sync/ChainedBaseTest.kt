package app.simpletemplate.shared.sync

import app.cash.sqldelight.db.QueryResult
import app.simpletemplate.shared.ApiClient
import app.simpletemplate.shared.data.EntityType
import app.simpletemplate.shared.data.ItemRepository
import app.simpletemplate.shared.data.Tenant
import app.simpletemplate.shared.data.outboxOps
import app.simpletemplate.shared.data.testStore
import app.simpletemplate.shared.domain.Item
import app.simpletemplate.shared.domain.ItemStatus
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * A platform that keeps each record's version by the server's rule (the platform's sync engine):
 * a CREATE makes version 1; an UPDATE or DELETE whose base is not the current version is a
 * CONFLICT (EDIT_WINS for a delete); an accepted one raises the version by one.
 */
private class VersionedPlatform {
    val versions = mutableMapOf<String, Long>()
    val sent = mutableListOf<SyncOpDto>()
    private val json = Json { ignoreUnknownKeys = true }

    val engine = MockEngine { request ->
        if (request.url.encodedPath != "/v1/sync") return@MockEngine respondError(HttpStatusCode.NotFound)
        val body = json.decodeFromString<SyncRequest>(request.body.toByteArray().decodeToString())
        sent += body.ops
        val results = body.ops.joinToString(",") { result(it) }
        respond(
            """{"accepted":[],"results":[$results],"changes":[],"has_more":false,"wipe":[],"cursor":"c1"}""",
            HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    private fun result(op: SyncOpDto): String {
        val current = versions[op.entityId]
        val (status, version) = when {
            op.opType == "CREATE" && current == null -> "ACCEPTED" to 1L
            op.opType == "CREATE" -> "CONFLICT" to current!!
            current == null -> return """{"op_id":"${op.opId}","status":"REJECTED","reason":"Record not found"}"""
            op.baseVersion != current -> (if (op.opType == "DELETE") "EDIT_WINS" else "CONFLICT") to current
            else -> "ACCEPTED" to current + 1
        }
        if (status == "ACCEPTED") versions[op.entityId] = version
        val record = if (status == "ACCEPTED") "" else ""","server_record":{"id":"${op.entityId}"}"""
        return """{"op_id":"${op.opId}","status":"$status","server_version":$version$record}"""
    }
}

class ChainedBaseTest {
    private val org = Uuid.generateV7()
    private val tenant = Tenant(org)
    private val store = testStore()
    private val platform = VersionedPlatform()
    private val engine = SyncEngine(store, ApiClient("https://api.test", platform.engine), deviceName = "Pixel")
    private val items = ItemRepository(store, tenant)

    private fun localVersion(id: Uuid): Long = store.driver.executeQuery(
        null, "SELECT version FROM item WHERE id = ?", { c -> c.next(); QueryResult.Value(c.getLong(0)!!) }, 1,
    ) { bindString(0, id.toString()) }.value

    /** An item synced earlier, at [version] on the platform and on the phone. */
    private suspend fun synced(id: Uuid, version: Long) {
        items.save(Item(id, "Check fire exits"))
        engine.sync(org)
        platform.versions[id.toString()] = version
        store.driver.execute(null, "UPDATE item SET version = ? WHERE id = ?", 2) {
            bindLong(0, version); bindString(1, id.toString())
        }
    }

    @Test
    fun anItemCreatedAndEditedOfflineIsSentAsAChain() = runTest {
        val id = Uuid.generateV7()
        items.save(Item(id, "First"))
        items.save(Item(id, "Second"))
        items.save(Item(id, "Third", status = ItemStatus.DONE))

        assertEquals(0L, localVersion(id), "nothing has been accepted yet")
        assertEquals(listOf(0L, 1L, 2L), store.outboxOps().map { it.base_version })
        val report = engine.sync(org)

        assertEquals(3, report.accepted)
        assertEquals(0, report.conflicts)
        assertEquals(3L, platform.versions[id.toString()])
        assertEquals(3L, localVersion(id))
    }

    @Test
    fun twoEditsAndADeleteOfASyncedItemAreAllAccepted() = runTest {
        val id = Uuid.generateV7()
        synced(id, 4)
        items.save(Item(id, "B"))
        items.save(Item(id, "C"))
        items.delete(id)

        assertEquals(listOf(4L, 5L, 6L), store.outboxOps().map { it.base_version })
        val report = engine.sync(org)

        assertEquals(3, report.accepted)
        assertEquals(0, report.conflicts)
        assertEquals(7L, platform.versions[id.toString()])
    }

    @Test
    fun operationsQueuedInOneTransactionAreEachBasedOnTheOneBefore() = runTest {
        // As an app does for a change the platform takes in steps (a status path, one at a time).
        val id = Uuid.generateV7()
        synced(id, 2)
        store.db.transaction {
            for ((from, to) in listOf("OPEN" to "STEP_1", "STEP_1" to "STEP_2", "STEP_2" to "DONE")) {
                store.outbox.updated(
                    tenant, EntityType.ITEM, id, 2,
                    buildJsonObject { put("status", from) }, buildJsonObject { put("status", to) },
                )
            }
        }

        assertEquals(listOf(2L, 3L, 4L), store.outboxOps().map { it.base_version })
        val report = engine.sync(org)

        assertEquals(3, report.accepted)
        assertEquals(0, report.conflicts)
        assertEquals(5L, platform.versions[id.toString()])
    }

    @Test
    fun aChangeAfterASyncIsBasedOnTheAcceptedVersionAgain() = runTest {
        val id = Uuid.generateV7()
        items.save(Item(id, "First"))
        engine.sync(org)
        assertEquals(1L, localVersion(id))
        items.save(Item(id, "Second"))

        assertEquals(1L, store.outboxOps().single().base_version)
        assertEquals(1, engine.sync(org).accepted)
        assertEquals(2L, localVersion(id))
    }

    @Test
    fun aRefusedChangeIsLeftOutOfTheChain() = runTest {
        val id = Uuid.generateV7()
        synced(id, 4)
        items.save(Item(id, "B"))
        val refused = store.outboxOps().single()
        store.db.syncQueries.markRejected("No", refused.op_id)
        // Never sent: the next change is based on the record's version, not on the refused one.
        items.save(Item(id, "C"))

        assertEquals(4L, store.outboxOps().last().base_version)
        assertEquals(1, engine.sync(org).accepted)
    }
}
