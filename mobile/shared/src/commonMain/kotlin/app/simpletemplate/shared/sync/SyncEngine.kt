package app.simpletemplate.shared.sync

import app.simpletemplate.shared.ApiClient
import app.simpletemplate.shared.data.LocalStore
import app.simpletemplate.shared.data.appEntities
import app.simpletemplate.shared.db.Outbox_operation
import app.simpletemplate.shared.db.SimpleTemplateDatabase
import app.simpletemplate.shared.db.Sync_conflict
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.uuid.Uuid

/** What one sync did, for the Status page and tests. */
data class SyncReport(
    val sent: Int = 0,
    val accepted: Int = 0,
    val rejected: Int = 0,
    val conflicts: Int = 0,
    val retryLater: Int = 0,
    val received: Int = 0,
    val removed: Int = 0,
    /** Entities in the feed this app keeps no table for (not registered): skipped. */
    val skipped: Int = 0,
    val wiped: List<String> = emptyList(),
    /** Files the organisation holds (`file_object` ids) that arrived in the feed. */
    val filesSeen: Set<String> = emptySet(),
)

/**
 * Two-way sync of one organisation's workspace with the platform (contract §5): sends the
 * workspace's queued operations with `X-Org-Id` and `X-Device-Id`, records each result, applies
 * the platform's changes to the local tables of the app's [registry], keeps the cursor, and acts
 * on a wipe. Pages until the platform has nothing more (a page can be short or empty while
 * `has_more` is set: the feed is scoped to the user's assignments, contract §5.3).
 *
 * The local workspace never syncs (contract §1).
 */
class SyncEngine(
    private val store: LocalStore,
    private val api: ApiClient,
    private val deviceName: String? = null,
    private val appVersion: String? = null,
    private val registry: SyncRegistry = appEntities(),
    /** Deletes files of removed records and of a wiped workspace from this phone's storage. */
    private val deleteFiles: (List<String>) -> Unit = {},
) {
    private val queries = store.db.syncQueries
    private val writer = RecordWriter(store.driver, registry)
    private val purge = WorkspacePurge(writer)
    private val json = Json { ignoreUnknownKeys = true }

    /** This installation's id, sent as `X-Device-Id`; stable until the app is reinstalled. */
    fun deviceId(): Uuid {
        queries.cursor(DEVICE_SCOPE).executeAsOneOrNull()?.let { return Uuid.parse(it.cursor_token) }
        val id = store.newId()
        queries.saveCursor(DEVICE_SCOPE, id.toString(), store.nowMillis())
        return id
    }

    fun pendingCount(orgId: Uuid): Long = queries.pendingCountFor(orgId.toString()).executeAsOne()

    fun rejected(orgId: Uuid): List<Outbox_operation> = queries.rejectedFor(orgId.toString()).executeAsList()

    fun conflicts(orgId: Uuid): List<Sync_conflict> = queries.conflictsFor(orgId.toString()).executeAsList()

    /**
     * Forget a change the platform refused (the user read the reason). The phone still shows the
     * refused value, so the workspace's feed starts again: the next sync brings every record back
     * as the platform holds it.
     */
    fun discardRejected(opId: String, orgId: Uuid) = store.db.transaction {
        queries.discardRejected(opId)
        queries.deleteCursor(orgId.toString())
    }

    /**
     * The user keeps their version: it is sent again as an update on top of the platform's
     * version (contract §5: the user resolves and the app submits a new UPDATE).
     */
    fun keepMine(conflict: Sync_conflict) = store.db.transaction {
        queries.enqueue(
            store.newId().toString(), conflict.entity_type, conflict.entity_id, "UPDATE",
            conflict.local_payload, conflict.server_version ?: 0, store.nowMillis(), conflict.owner_org_id,
        )
        queries.removeConflict(conflict.op_id)
    }

    /** The user takes the platform's version: it replaces the phone's. */
    fun useTheirs(conflict: Sync_conflict) = store.db.transaction {
        parseRecord(conflict.server_record)?.let { writer.upsert(conflict.entity_type, it) }
        queries.removeConflict(conflict.op_id)
    }

    /**
     * After an app update changed the local schema, records pulled earlier lack the new columns
     * (they were written with defaults): start the feed again so every record comes in whole.
     */
    private fun pullAgainAfterSchemaChange() {
        val current = SimpleTemplateDatabase.Schema.version.toString()
        val seen = queries.cursor(SCHEMA_SCOPE).executeAsOneOrNull()?.cursor_token
        if (seen == current) return
        store.db.transaction {
            queries.allCursors().executeAsList()
                .filter { !it.scope.startsWith("_") }
                .forEach { queries.deleteCursor(it.scope) }
            queries.saveCursor(SCHEMA_SCOPE, current, store.nowMillis())
        }
    }

    suspend fun sync(orgId: Uuid, maxRounds: Int = 50): SyncReport = withContext(store.dispatcher) {
        pullAgainAfterSchemaChange()
        val device = deviceId()
        var report = SyncReport()
        repeat(maxRounds) {
            val ops = queries.pendingFor(orgId.toString(), BATCH).executeAsList().filter { op ->
                // Never send what the platform would refuse for its entity, owner or keys: say so here.
                val problem = registry.problem(op.entity_type, op.op_type, json.parseToJsonElement(op.payload).jsonObject)
                if (problem != null) queries.markRejected("Not sent: $problem", op.op_id)
                problem == null
            }
            val cursor = queries.cursor(orgId.toString()).executeAsOneOrNull()?.cursor_token
            val response = api.sync(
                orgId, device,
                SyncRequest(cursor, ops.map { it.toDto() }, PULL_LIMIT, deviceName, appVersion),
            )
            report = apply(orgId, ops, response, report)
            if (response.wipe.isNotEmpty()) {
                report = wipe(orgId, device, response.wipe, report)
                if (orgId.toString() in response.wipe) return@withContext report
            }
            val more = queries.pendingCountFor(orgId.toString()).executeAsOne() > 0 &&
                response.results.any { it.status == "ACCEPTED" }
            if (!response.hasMore && !more) return@withContext report
        }
        report
    }

    private fun apply(orgId: Uuid, ops: List<Outbox_operation>, response: SyncResponse, before: SyncReport): SyncReport {
        var report = before.copy(sent = before.sent + ops.size)
        val byId = ops.associateBy { it.op_id }
        val removedFiles = mutableListOf<String>()
        store.db.transaction {
            for (result in response.results) {
                val op = byId[result.opId] ?: continue
                report = when (result.status) {
                    "ACCEPTED" -> {
                        queries.dropOp(op.op_id)
                        result.serverVersion?.let { writer.setVersion(op.entity_type, op.entity_id, it) }
                        report.copy(accepted = report.accepted + 1)
                    }
                    "CONFLICT", "EDIT_WINS" -> {
                        queries.saveConflict(
                            op.op_id, orgId.toString(), op.entity_type, op.entity_id, result.status,
                            op.payload, result.serverRecord?.toString(), result.serverVersion, store.nowMillis(),
                        )
                        queries.dropOp(op.op_id)
                        // A delete that lost to an edit: the record comes back as the platform has it.
                        if (result.status == "EDIT_WINS") result.serverRecord?.let { writer.upsert(op.entity_type, it) }
                        report.copy(conflicts = report.conflicts + 1)
                    }
                    "RETRY_LATER" -> { // a parent is not on the platform yet: kept, sent again
                        queries.recordFailure(result.reason ?: "Retry later", listOf(op.op_id))
                        report.copy(retryLater = report.retryLater + 1)
                    }
                    else -> { // REJECTED, INVALID_TRANSITION, DUPLICATE_ID: kept with the reason, never resent
                        queries.markRejected(result.reason ?: result.status, op.op_id)
                        if (result.status == "INVALID_TRANSITION") result.serverRecord?.let { writer.upsert(op.entity_type, it) }
                        report.copy(rejected = report.rejected + 1)
                    }
                }
            }
            val waiting = queries.pendingFor(orgId.toString(), Long.MAX_VALUE).executeAsList()
                .map { it.entity_type to it.entity_id }.toSet()
            for (change in response.changes) {
                if (change.entityType == FILE_OBJECT && registry[FILE_OBJECT] == null) {
                    if (!change.deleted) report = report.copy(filesSeen = report.filesSeen + change.entityId)
                    continue
                }
                if (registry[change.entityType] == null) {
                    report = report.copy(skipped = report.skipped + 1)
                    continue
                }
                if (change.deleted || change.record == null) {
                    removedFiles += writer.remove(change.entityType, change.entityId)
                    report = report.copy(removed = report.removed + 1)
                    continue
                }
                // A change still waiting on this phone wins locally until the platform has it: its
                // push carries the base version, and the platform reports a conflict if needed.
                if ((change.entityType to change.entityId) in waiting) continue
                if (writer.upsert(change.entityType, change.record)) report = report.copy(received = report.received + 1)
            }
            queries.saveCursor(orgId.toString(), response.cursor, store.nowMillis())
        }
        if (removedFiles.isNotEmpty()) deleteFiles(removedFiles)
        return report
    }

    private suspend fun wipe(orgId: Uuid, device: Uuid, organisations: List<String>, before: SyncReport): SyncReport {
        val files = mutableListOf<String>()
        store.db.transaction { organisations.forEach { files += purge.purge(it) } }
        if (files.isNotEmpty()) deleteFiles(files)
        api.wipeAck(orgId, device, organisations)
        return before.copy(wiped = before.wiped + organisations)
    }

    private fun Outbox_operation.toDto() = SyncOpDto(
        opId = op_id,
        entityType = entity_type,
        entityId = entity_id,
        opType = op_type,
        payload = json.parseToJsonElement(payload).jsonObject,
        baseVersion = base_version,
    )

    companion object {
        private const val DEVICE_SCOPE = "_device"
        private const val SCHEMA_SCOPE = "_schema"
        private const val BATCH = 200L
        private const val PULL_LIMIT = 500
        private const val FILE_OBJECT = "file_object"
    }
}

/** The platform's record of a conflict, for the resolution screen. */
fun parseRecord(json: String?): JsonObject? = json?.let { Json.parseToJsonElement(it).jsonObject }
