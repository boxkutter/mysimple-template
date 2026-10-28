package app.simpletemplate.shared.sync

/**
 * Removes everything an organisation's workspace holds on this phone: the answer to a wipe
 * (contract §5: the membership was revoked). Derived from the schema, not a hand-kept list, so a
 * new table is covered: every row whose `owner_org_id` is the organisation, in any table (synced
 * or local-only), with the rows that refer to them by a declared foreign key, and the
 * organisation's queued changes, conflicts, cursor and file transfers. Returns the files the rows
 * named, for the platform code to delete. The caller runs it inside a database transaction.
 */
internal class WorkspacePurge(private val writer: RecordWriter) {
    fun purge(orgId: String): List<String> {
        require(ORG_ID.matches(orgId)) { "Not an organisation id" }
        val files = mutableListOf<String>()
        for (table in writer.tables()) {
            if (table in RecordWriter.BOOKKEEPING) continue
            val cols = writer.columnsOf(table) ?: continue
            if ("owner_org_id" !in cols) continue
            if ("id" in cols) {
                writer.ids("SELECT id FROM $table WHERE owner_org_id = ?", orgId).forEach { writer.removeTree(table, it, files, depth = 0) }
            } else {
                exec("DELETE FROM $table WHERE owner_org_id = '$orgId'")
            }
        }
        exec("DELETE FROM outbox_operation WHERE owner_org_id = '$orgId'")
        exec("DELETE FROM sync_conflict WHERE owner_org_id = '$orgId'")
        exec("DELETE FROM file_transfer WHERE owner_org_id = '$orgId'")
        exec("DELETE FROM sync_cursor WHERE scope = '$orgId'")
        return files
    }

    private fun exec(sql: String) {
        writer.execute(sql)
    }

    companion object {
        private val ORG_ID = Regex("^[0-9a-fA-F-]{36}$")
    }
}
