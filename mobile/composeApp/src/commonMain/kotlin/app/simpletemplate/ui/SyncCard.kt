package app.simpletemplate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.simpletemplate.shared.db.Outbox_operation
import app.simpletemplate.shared.db.Sync_conflict
import app.simpletemplate.shared.sync.WorkspaceSync
import app.simpletemplate.shared.sync.parseRecord
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The organisation workspace's sync with My Simple ISO: what waits to be sent, when it last
 * synced, and what needs the user (changes the platform refused, conflicts to resolve).
 */
@Composable
fun SyncCard(sync: WorkspaceSync) {
    val scope = rememberCoroutineScope()
    val status by sync.status.collectAsState()
    val pending by remember(sync) { sync.pending() }.collectAsState(initial = 0L)
    // Bumped after a decision so the lists below are read again.
    var generation by remember { mutableIntStateOf(0) }
    val rejected by produceState(emptyList<Outbox_operation>(), generation, status.lastSyncedAt) { value = sync.rejected() }
    val conflicts by produceState(emptyList<Sync_conflict>(), generation, status.lastSyncedAt) { value = sync.conflicts() }

    SectionCard("Sync with My Simple ISO", Icons.Filled.Refresh) {
        LabeledValue("Waiting to be sent", if (pending == 0L) "Nothing" else "$pending change${if (pending == 1L) "" else "s"}")
        LabeledValue(
            "Last synced",
            when {
                status.running -> "Syncing…"
                status.lastSyncedAt != null -> status.lastSyncedAt!!
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                    .let { "${it.date} ${it.hour.toString().padStart(2, '0')}:${it.minute.toString().padStart(2, '0')}" }
                else -> "Not yet"
            },
        )
        status.problem?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        OutlinedButton(onClick = { scope.launch { sync.syncNow(); generation++ } }, enabled = !status.running, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Sync now")
        }

        if (rejected.isNotEmpty()) {
            Text("Not accepted by My Simple ISO", style = MaterialTheme.typography.labelLarge)
            rejected.forEach { op ->
                Column {
                    Text("${op.op_type.lowercase()} ${op.entity_type.replace('_', ' ')}: ${op.last_error}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { scope.launch { sync.discardRejected(op.op_id); generation++ } }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Discard this change")
                    }
                }
            }
        }
        if (conflicts.isNotEmpty()) {
            Text("Changed on both sides", style = MaterialTheme.typography.labelLarge)
            conflicts.forEach { c ->
                val theirs = parseRecord(c.server_record)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(c.entity_type.replace('_', ' '), style = MaterialTheme.typography.bodyMedium)
                    Text("On this phone: ${c.local_payload}", style = MaterialTheme.typography.bodySmall)
                    Text("In My Simple ISO: ${theirs?.filterKeys { it in c.local_payload } ?: "deleted"}", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { scope.launch { sync.keepMine(c); generation++ } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep mine") }
                        TextButton(onClick = { scope.launch { sync.useTheirs(c); generation++ } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Use theirs") }
                    }
                }
            }
        }
    }
}
