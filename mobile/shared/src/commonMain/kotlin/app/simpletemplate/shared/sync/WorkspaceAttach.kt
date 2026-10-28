package app.simpletemplate.shared.sync

import app.simpletemplate.shared.ApiClient
import app.simpletemplate.shared.ApiException
import app.simpletemplate.shared.CreateOrganisationRequest
import app.simpletemplate.shared.auth.AuthRepository
import app.simpletemplate.shared.auth.Session
import app.simpletemplate.shared.auth.SessionHolder
import app.simpletemplate.shared.auth.Workspace
import app.simpletemplate.shared.data.LocalStore
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** What an attach did, for the confirmation on screen. */
data class AttachReport(val organisation: Workspace, val records: Int, val changesToSend: Int)

/**
 * Makes the local workspace (no account, made on this phone) an organisation's, so its records
 * sync from then on (platform plan §7: the phone generated both the user and the organisation id
 * before any account existed). Two ways, both an explicit choice of the signed-in user:
 *
 * - **adopt** ([adopt]): the account creates a new organisation on the platform that takes the
 *   local workspace's own id (`POST /v1/orgs {id}`), so the records keep their `owner_org_id`;
 * - **remap** ([remap]): the records move into an organisation the account already belongs to;
 *   `owner_org_id` (and `managed_by_org_id`, `organisation_id` naming it) are rewritten.
 *
 * In both, the local user becomes the platform user (every `user_id` / `*_user_id` column and the
 * user's settings on this phone), in one database transaction that also rewrites the queued
 * operations' payloads and owner, and records the mapping in `id_remap`. The local workspace then
 * ends: its records and queued changes are the organisation's, and its sync sends them.
 *
 * Personal data: everything in the local workspace goes to the organisation. The screen says so
 * before the user confirms.
 */
class WorkspaceAttach(
    private val store: LocalStore,
    private val api: ApiClient,
    private val sessions: SessionHolder,
    private val auth: AuthRepository,
) {
    private val writer = RecordWriter(store.driver, SyncRegistry(emptyList()))
    private val json = Json { ignoreUnknownKeys = true }

    /** Whether there is a local workspace and an account to attach it with. */
    val available: Boolean get() = sessions.local != null && sessions.account != null

    /** A new organisation named after the local workspace, taking its id. */
    suspend fun adopt(): AttachReport {
        val local = sessions.local ?: error("There is no local workspace on this phone")
        sessions.account ?: error("Not signed in")
        try {
            api.createOrganisation(CreateOrganisationRequest(local.organisationId, local.organisationName, local.organisationTimezone))
        } catch (e: ApiException) {
            // Created by an earlier attempt that did not finish on this phone: carry on with it.
            val ours = e.status == 409 && auth.refreshWorkspaces().any { it.organisationId == local.organisationId }
            if (!ours) throw e
        }
        return finish(local, local.organisationId)
    }

    /** Into [into], an organisation of the account. */
    suspend fun remap(into: Workspace): AttachReport {
        val local = sessions.local ?: error("There is no local workspace on this phone")
        val account = sessions.account ?: error("Not signed in")
        require(account.workspaces.any { it.organisationId == into.organisationId }) { "Not an organisation of this account" }
        return finish(local, into.organisationId)
    }

    private suspend fun finish(local: Session, orgId: kotlin.uuid.Uuid): AttachReport {
        val account = sessions.account ?: error("Not signed in")
        val (records, ops) = withContext(store.dispatcher) {
            store.db.transactionWithResult {
                rewrite(local.organisationId.toString(), orgId.toString(), local.userId.toString(), account.userId.toString())
            }
        }
        val workspaces = auth.refreshWorkspaces()
        val workspace = workspaces.firstOrNull { it.organisationId == orgId }
            ?: Workspace(orgId, local.organisationName, local.organisationTimezone)
        sessions.clearLocal()
        auth.open(workspace)
        return AttachReport(workspace, records, ops)
    }

    /** Rewrites the local ids to the platform's; returns the records and the queued operations it touched. */
    private fun rewrite(fromOrg: String, toOrg: String, fromUser: String, toUser: String): Pair<Int, Int> {
        var records = 0
        for (table in writer.tables()) {
            if (table in RecordWriter.BOOKKEEPING) continue
            val cols = writer.columnsOf(table)?.keys ?: continue
            if ("owner_org_id" in cols) records += count(table, "owner_org_id", fromOrg)
            for (col in cols) {
                if (col in ORG_COLUMNS && fromOrg != toOrg) update(table, col, fromOrg, toOrg)
                if (col.isUserColumn() && fromUser != toUser) update(table, col, fromUser, toUser)
            }
        }
        // Personal settings follow the user; the platform user's own settings on this phone win.
        writer.execute("UPDATE OR IGNORE user_setting SET user_id = '$toUser' WHERE user_id = '$fromUser'")
        writer.execute("DELETE FROM user_setting WHERE user_id = '$fromUser'")

        val queries = store.db.syncQueries
        // The platform creates the organisation record itself: a local CREATE of it is not sent.
        queries.dropOpsFor("organisation", fromOrg)
        val ops = queries.opsFor(fromOrg).executeAsList()
        for (op in ops) {
            val payload = json.parseToJsonElement(op.payload).jsonObject
            val rewritten = JsonObject(payload.mapValues { (key, value) ->
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                when {
                    text == fromOrg && key in ORG_COLUMNS -> JsonPrimitive(toOrg)
                    text == fromUser && key.isUserColumn() -> JsonPrimitive(toUser)
                    else -> value
                }
            })
            queries.setOpOrganisation(toOrg, rewritten.toString(), op.op_id)
        }
        val now = store.nowMillis()
        if (fromOrg != toOrg) queries.saveRemap("organisation", fromOrg, toOrg, now)
        if (fromUser != toUser) queries.saveRemap("user", fromUser, toUser, now)
        return records to ops.size
    }

    private fun update(table: String, column: String, from: String, to: String) {
        writer.execute("UPDATE $table SET $column = '$to' WHERE $column = '$from'")
    }

    private fun count(table: String, column: String, value: String): Int =
        writer.ids("SELECT count(*) FROM $table WHERE $column = ?", value).firstOrNull()?.toIntOrNull() ?: 0

    private fun String.isUserColumn() = this == "user_id" || endsWith("_user_id")

    private companion object {
        val ORG_COLUMNS = setOf("owner_org_id", "managed_by_org_id", "organisation_id")
    }
}
