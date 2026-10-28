package app.simpletemplate.shared.sync

import app.cash.sqldelight.db.QueryResult
import app.simpletemplate.shared.data.LocalStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

/**
 * A scripted platform: each POST /v1/sync gets the next answer; requests are kept. Other paths
 * go to [other] (404 by default).
 */
internal class ScriptedPlatform {
    val requests = mutableListOf<Pair<HttpRequestData, SyncRequest>>()
    val wipeAcks = mutableListOf<Pair<HttpRequestData, List<String>>>()
    val answers = ArrayDeque<(SyncRequest) -> String>()
    var other: suspend MockRequestHandleScope.(HttpRequestData, String) -> io.ktor.client.request.HttpResponseData = { _, _ -> respondError(HttpStatusCode.NotFound) }
    private val json = Json { ignoreUnknownKeys = true }

    val engine = MockEngine { request -> handle(request) }

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData) = run {
        val body = request.body.toByteArray().decodeToString()
        when (request.url.encodedPath) {
            "/v1/sync" -> {
                val parsed = json.decodeFromString<SyncRequest>(body)
                requests += request to parsed
                respond(answers.removeFirst()(parsed), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            "/v1/sync/wipe-ack" -> {
                wipeAcks += request to json.decodeFromString<WipeAck>(body).organisationIds
                respond("", HttpStatusCode.NoContent)
            }
            else -> other(request, body)
        }
    }
}

internal fun change(type: String, id: Uuid, record: JsonObject?, op: String = "UPDATE") =
    """{"entity_type":"$type","entity_id":"$id","version":3,"op":"$op","deleted":${record == null},"record":${record ?: "null"}}"""

internal fun answer(results: String = "[]", changes: List<String> = emptyList(), more: Boolean = false, wipe: List<String> = emptyList(), cursor: String = "c1") =
    """{"accepted":[],"results":$results,"changes":[${changes.joinToString(",")}],"has_more":$more,"wipe":[${wipe.joinToString(",") { "\"$it\"" }}],"cursor":"$cursor"}"""

/** Queues an operation as a repository would (tests that need one without a repository). */
internal fun LocalStore.enqueue(orgId: Uuid, type: String, id: Uuid, payload: JsonObject, opType: String = "CREATE", baseVersion: Long = 0): String {
    val opId = Uuid.generateV7().toString()
    db.syncQueries.enqueue(opId, type, id.toString(), opType, payload.toString(), baseVersion, 1L, orgId.toString())
    return opId
}

internal fun LocalStore.count(table: String, where: String = "1 = 1"): Long =
    driver.executeQuery(null, "SELECT count(*) FROM $table WHERE $where", { c -> c.next(); QueryResult.Value(c.getLong(0)!!) }, 0).value

internal fun LocalStore.text(table: String, column: String, id: Uuid): String? =
    driver.executeQuery(null, "SELECT $column FROM $table WHERE id = ?", { c ->
        QueryResult.Value(if (c.next().value) c.getString(0) else null)
    }, 1) { bindString(0, id.toString()) }.value

/**
 * A second entity for the tests only, under an item (a declared foreign key), with a file: shows
 * that the engine is driven by the registry, not by the template's Items.
 */
internal fun LocalStore.createTestChildTable() = driver.execute(
    null,
    """CREATE TABLE item_photo (
        id TEXT NOT NULL PRIMARY KEY,
        owner_org_id TEXT NOT NULL,
        item_id TEXT NOT NULL REFERENCES item(id),
        file_name TEXT,
        sha256 TEXT,
        mime_type TEXT,
        original_name TEXT,
        file_object_id TEXT,
        version INTEGER NOT NULL DEFAULT 0,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        deleted_at INTEGER
    )""",
    0,
)

internal val testPhoto = SyncEntity("item_photo", files = FileColumns())

internal class MemoryFiles : FileStorage {
    val files = mutableMapOf<String, ByteArray>()
    override fun read(fileName: String) = files[fileName]
    override fun write(fileName: String, bytes: ByteArray) { files[fileName] = bytes }
    override fun delete(fileNames: List<String>) { fileNames.forEach(files::remove) }
}
