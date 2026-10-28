package app.simpletemplate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.simpletemplate.shared.AppContainer
import app.simpletemplate.shared.normaliseServerAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The platform's address, with "Change" in builds where it is editable (debug builds: a
 * development server whose LAN address changes). The new address is kept only when the platform
 * answers there; the sign-in server is then taken from the platform.
 */
@Composable
fun ServerAddressRow(container: AppContainer, onChanged: () -> Unit = {}) {
    val baseUrl by container.server.baseUrl.collectAsState()
    var editing by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Server", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(baseUrl, style = MaterialTheme.typography.bodyLarge)
        }
        if (container.server.editable) {
            TextButton(onClick = { editing = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Change") }
        }
    }
    if (editing) {
        ServerAddressDialog(container, baseUrl, onDismiss = { editing = false }) {
            editing = false
            onChanged()
        }
    }
}

@Composable
private fun ServerAddressDialog(container: AppContainer, current: String, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(current) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val valid = normaliseServerAddress(text) != null

    fun save() {
        if (!valid || busy) return
        busy = true
        error = null
        scope.launch {
            try {
                container.changeServer(text)
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "No platform answered at that address. Check it, and that this phone is on the same network."
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Server address") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The platform's API, for example 192.168.1.10:8081. The sign-in page comes from the platform.")
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    label = { Text("Address") },
                    singleLine = true,
                    enabled = !busy,
                    isError = error != null || (text.isNotBlank() && !valid),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::save, enabled = valid && !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (busy) "Checking…" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
        },
    )
}
