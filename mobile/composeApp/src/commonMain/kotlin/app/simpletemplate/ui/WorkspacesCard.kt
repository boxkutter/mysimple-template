package app.simpletemplate.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.simpletemplate.shared.ApiException
import app.simpletemplate.shared.AppContainer
import app.simpletemplate.shared.auth.Session
import app.simpletemplate.shared.auth.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Who is signed in and which workspace is open (platform contract §3.2): the local workspace on
 * this phone, and each organisation of the account. Switching keeps both. With both an account
 * and a local workspace, the local workspace can be made an organisation's (WorkspaceAttach).
 */
@Composable
fun WorkspacesCard(container: AppContainer, session: Session, onSignIn: () -> Unit) {
    val auth = container.auth
    val account = auth.account
    val local = auth.local
    SectionCard(if (account == null) "You" else "Account and workspaces", Icons.Filled.Person) {
        LabeledValue("Name", session.name)
        if (account == null) {
            LabeledValue("Organisation", session.organisationName)
            Text(
                "No account: your information is on this phone only. An account adds backup, several devices, your organisation's records and AI drafting.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onSignIn, modifier = Modifier.heightIn(min = 48.dp)) { Text("Sign in to an account") }
            return@SectionCard
        }
        LabeledValue("Account", account.email)
        Text("Workspace", style = MaterialTheme.typography.labelLarge)
        Column(Modifier.selectableGroup()) {
            if (local != null) {
                WorkspaceRow("${local.organisationName} (this phone only)", selected = session.isLocal) { auth.openLocal() }
            }
            account.workspaces.forEach { w ->
                val label = if (w.kind == "EXTERNAL") "${w.name} (guest)" else w.name
                WorkspaceRow(label, selected = !session.isLocal && session.organisationId == w.organisationId) { auth.open(w) }
            }
        }
        if (account.workspaces.isEmpty()) {
            Text(
                "Your account does not belong to an organisation yet. Ask your organisation to invite you, or create one below.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (local != null) AttachLocalWorkspace(container, local, account.workspaces.filter { it.kind == "MEMBER" })
        OutlinedButton(onClick = { auth.signOut() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Sign out") }
    }
}

/** What the user is about to confirm: a new organisation (adopt) or an existing one (remap). */
private sealed interface AttachChoice {
    data object NewOrganisation : AttachChoice
    data class Into(val workspace: Workspace) : AttachChoice
}

/**
 * Brings the local workspace's information into an organisation, so it syncs. Everything in it
 * goes to the organisation, which the confirmation says before anything is sent.
 */
@Composable
private fun AttachLocalWorkspace(container: AppContainer, local: Session, organisations: List<Workspace>) {
    val scope = rememberCoroutineScope()
    var choice by remember { mutableStateOf<AttachChoice?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Text("Information on this phone", style = MaterialTheme.typography.labelLarge)
    Text(
        "Move what you made in ${local.organisationName} into an organisation, so it is backed up and shared with your organisation.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = { choice = AttachChoice.NewOrganisation }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("Create the organisation ${local.organisationName}")
    }
    organisations.forEach { w ->
        OutlinedButton(onClick = { choice = AttachChoice.Into(w) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Move it into ${w.name}")
        }
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

    val pending = choice ?: return
    val target = when (pending) {
        AttachChoice.NewOrganisation -> "a new organisation, ${local.organisationName}, with you as its owner"
        is AttachChoice.Into -> pending.workspace.name
    }
    AlertDialog(
        onDismissRequest = { choice = null },
        title = { Text("Move this phone's information?") },
        text = {
            Text(
                "Everything in ${local.organisationName} on this phone, including any information about other people, " +
                    "will belong to $target and be sent to My Simple ISO. The phone-only workspace then ends. This cannot be undone on the phone.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                choice = null
                busy = true
                message = null
                scope.launch {
                    try {
                        when (pending) {
                            AttachChoice.NewOrganisation -> container.attach.adopt()
                            is AttachChoice.Into -> container.attach.remap(pending.workspace)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ApiException) {
                        message = "My Simple ISO did not accept it: ${e.detail}"
                    } catch (e: Exception) {
                        message = "Not moved: can't reach My Simple ISO. Nothing has changed; try again later."
                    } finally {
                        busy = false
                    }
                }
            }) { Text("Move it") }
        },
        dismissButton = { TextButton(onClick = { choice = null }) { Text("Cancel") } },
    )
}

@Composable
private fun WorkspaceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
