package app.simpletemplate.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.simpletemplate.shared.AppContainer
import app.simpletemplate.shared.Repositories
import app.simpletemplate.ui.lock.AppLockGate
import app.simpletemplate.ui.theme.AppTheme
import kotlinx.coroutines.flow.map

/**
 * First start: the welcome screen (no account needed). Then the app, scoped to the user's
 * organisation (the local workspace or an account), behind the app lock.
 */
@Composable
fun App(container: AppContainer) {
    val session by container.auth.session.collectAsState()
    AppTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val current = session
            if (current == null) {
                Box(Modifier.safeDrawingPadding()) { WelcomeScreen(container.auth) }
            } else {
                // A different user or organisation gets fresh repositories and navigation.
                key(current.userId, current.organisationId) {
                    val repositories by produceState<Repositories?>(null) { value = container.repositories(current) }
                    when (val repos = repositories) {
                        null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                        else -> {
                            // When the app lock becomes mandatory. EXAMPLE: once any item is stored. Choose the
                            // app's own rule, e.g. once data about clients or other people is stored.
                            val lockRequired = remember(repos) { repos.items.observeItems().map { it.isNotEmpty() } }
                            AppLockGate(repos, lockRequired) { AppShell(container, repos) }
                        }
                    }
                }
            }
        }
    }
}
