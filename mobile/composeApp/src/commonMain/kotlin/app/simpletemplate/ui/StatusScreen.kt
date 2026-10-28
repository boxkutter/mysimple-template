package app.simpletemplate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.simpletemplate.shared.AppContainer
import app.simpletemplate.shared.Repositories
import app.simpletemplate.shared.data.LockSettings
import app.simpletemplate.shared.data.UserSettings
import app.simpletemplate.shared.describe
import app.simpletemplate.shared.resolveHost
import app.simpletemplate.ui.lock.LocalAppLock
import app.simpletemplate.ui.lock.isDebugBuild
import app.simpletemplate.ui.lock.rememberDeviceLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/**
 * The account and workspaces, the workspace's sync, the app lock, and proof of the phone → proxy →
 * server path and the local database.
 * Add the app's own settings as further cards.
 */
@Composable
fun StatusScreen(container: AppContainer, repos: Repositories, onSignIn: () -> Unit) {
    val session = repos.session
    val baseUrl = container.baseUrl
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Not checked yet") }
    var details by remember { mutableStateOf("") }
    var database by remember { mutableStateOf("Opening local database…") }

    LaunchedEffect(Unit) {
        database = withContext(Dispatchers.IO) {
            runCatching { container.localStore.db.syncQueries.pendingCount().executeAsOne() }.fold(
                onSuccess = { "Local database ok · $it changes waiting to sync" },
                onFailure = { "Local database failed: ${it.describe()}" },
            )
        }
    }

    suspend fun check() {
        status = "Checking…"
        details = ""
        val host = baseUrl.substringAfter("://").substringBefore("/").substringBefore(":")
        val dns = resolveHost(host).fold(
            onSuccess = { "DNS $host → ${it.joinToString()}" },
            onFailure = { "DNS $host failed: ${it.describe()}" },
        )
        val started = TimeSource.Monotonic.markNow()
        status = try {
            val health = container.api.health()
            // An authenticated call proves the token (and its refresh) works, and picks up
            // organisations joined since (an invitation accepted on the web).
            val signedIn = if (container.auth.account != null) {
                container.auth.refreshWorkspaces()
                " · signed in as ${container.auth.account?.email}"
            } else ""
            "Platform ${health.status} · v${health.version} · ${health.environment}$signedIn"
        } catch (e: Exception) {
            details = e.describe()
            "Server unreachable"
        }
        details = listOf(dns, "after ${started.elapsedNow().inWholeMilliseconds} ms", details)
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    LaunchedEffect(Unit) { check() }

    AppScreen(title = "Status") { modifier ->
        Column(modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            WorkspacesCard(container, session, onSignIn)
            repos.sync?.let { SyncCard(it) }
            LockCards(repos.settings)
            SectionCard("Connection", Icons.Filled.Refresh, action = "Check" to { scope.launch { check() } }) {
                LabeledValue("Server", baseUrl)
                Text(status, style = MaterialTheme.typography.bodyLarge)
                if (details.isNotBlank()) Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SectionCard("This device", Icons.Filled.Lock) {
                Text(database, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** The app lock and, in development builds, test mode. */
@Composable
private fun LockCards(settings: UserSettings) {
    val scope = rememberCoroutineScope()
    val lock by settings.observeLockSettings().collectAsState(initial = LockSettings())
    val deviceLock = rememberDeviceLock()
    fun set(s: LockSettings) { scope.launch { settings.setLockSettings(s) } }
    val required = LocalAppLock.current?.required == true
    val on = lock.appLockOn || required
    SectionCard("App lock", Icons.Filled.Lock) {
        Row(
            Modifier.fillMaxWidth().toggleable(on, enabled = !required && (on || deviceLock.isDeviceSecure), role = Role.Switch) {
                set(lock.copy(appLockOn = it))
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Lock with this phone's fingerprint, face or PIN", style = MaterialTheme.typography.bodyLarge)
                Text(
                    when {
                        required -> "Always on: information that needs protecting is stored."
                        !deviceLock.isDeviceSecure -> "This phone has no screen lock yet."
                        else -> "Required once you store information that needs protecting."
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = on, onCheckedChange = null, enabled = !required)
        }
        if (on) {
            Text("Ask again after the app has been away for", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(5, 15, 30, 60).forEach { m -> FilterChip(selected = lock.appLockMinutes == m, onClick = { set(lock.copy(appLockMinutes = m)) }, label = { Text("$m min") }) }
            }
        }
    }
    if (isDebugBuild()) {
        // Development builds only: never in a store release
        val testMode by settings.observeTestMode().collectAsState(initial = false)
        SectionCard("Test mode (development build)", Icons.Filled.Settings) {
            Row(
                Modifier.fillMaxWidth().toggleable(testMode, role = Role.Switch) { on ->
                    // Turning it on needs the phone's lock once; turning it off never does
                    if (!on) scope.launch { settings.setTestMode(false) }
                    else if (!deviceLock.isDeviceSecure) scope.launch { settings.setTestMode(true) }
                    else deviceLock.authenticate("Turn on test mode", "The app lock will be off") { ok -> if (ok) scope.launch { settings.setTestMode(true) } }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("App lock off for testing", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "So the app can be driven on this phone during development. A strip at the top shows it is on.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = testMode, onCheckedChange = null)
            }
        }
    }
}
