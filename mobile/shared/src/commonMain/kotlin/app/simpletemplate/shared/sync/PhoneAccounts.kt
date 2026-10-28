package app.simpletemplate.shared.sync

import app.simpletemplate.shared.auth.DataOwner
import app.simpletemplate.shared.data.LocalStore
import app.simpletemplate.shared.data.appEntities
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/**
 * Which account the organisation data on this phone belongs to, what of it is unsent, and its
 * removal when another account signs in. The local workspace is never touched: it belongs to the
 * phone, not to an account. Like [WorkspacePurge], derived from the schema: every table with an
 * `owner_org_id` counts, so an app's new tables are covered.
 *
 * Personal data: the owner's user id, name and email are kept on the phone (in the encrypted
 * database, as a `sync_cursor` row) for as long as their organisation data is; they are replaced
 * when another account signs in.
 */
class PhoneAccounts(
    private val store: LocalStore,
    registry: SyncRegistry = appEntities(),
    /** Deletes files of removed workspaces from this phone's storage. */
    private val deleteFiles: (List<String>) -> Unit = {},
) {
    private val queries = store.db.syncQueries
    private val writer = RecordWriter(store.driver, registry)
    private val json = Json { ignoreUnknownKeys = true }

    /** The account the organisation data belongs to, or null when not recorded yet. */
    suspend fun owner(): DataOwner? = withContext(store.dispatcher) {
        queries.cursor(OWNER_SCOPE).executeAsOneOrNull()?.let {
            runCatching { json.decodeFromString<DataOwner>(it.cursor_token) }.getOrNull()
        }
    }

    suspend fun setOwner(owner: DataOwner) = withContext(store.dispatcher) {
        queries.saveCursor(OWNER_SCOPE, json.encodeToString(DataOwner.serializer(), owner), store.nowMillis())
    }

    /** Records [owner] if no owner is recorded yet (an account signed in before this was kept). */
    suspend fun claim(owner: DataOwner) {
        if (owner() == null) setOwner(owner)
    }

    /**
     * The organisation workspaces on this phone (not [localOrg]), each with its changes the
     * platform has not accepted: queued, refused, or in conflict.
     */
    suspend fun workspaces(localOrg: Uuid?): Map<String, Long> = withContext(store.dispatcher) {
        val orgs = buildSet {
            for (table in writer.tables()) {
                if (table in RecordWriter.BOOKKEEPING) continue
                if ("owner_org_id" !in writer.columnsOf(table).orEmpty()) continue
                addAll(writer.ids("SELECT DISTINCT owner_org_id FROM $table WHERE owner_org_id IS NOT NULL"))
            }
            addAll(writer.ids("SELECT DISTINCT owner_org_id FROM outbox_operation"))
            addAll(writer.ids("SELECT DISTINCT owner_org_id FROM sync_conflict"))
            addAll(writer.ids("SELECT DISTINCT owner_org_id FROM file_transfer"))
            addAll(writer.ids("SELECT scope FROM sync_cursor").filter { UUID_TEXT.matches(it) })
        }.filter { UUID_TEXT.matches(it) }.toSet() - setOfNotNull(localOrg?.toString())
        orgs.associateWith { org ->
            queries.opsFor(org).executeAsList().size.toLong() + queries.conflictsFor(org).executeAsList().size.toLong()
        }
    }

    /** Removes the organisations' workspaces from this phone: rows, files, queue, cursors. */
    suspend fun purge(organisations: Set<String>) {
        if (organisations.isEmpty()) return
        val purge = WorkspacePurge(writer)
        val files = withContext(store.dispatcher) {
            store.db.transactionWithResult { organisations.flatMap { purge.purge(it) } }
        }
        if (files.isNotEmpty()) deleteFiles(files)
    }

    companion object {
        private const val OWNER_SCOPE = "_data_owner"
        private val UUID_TEXT = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
