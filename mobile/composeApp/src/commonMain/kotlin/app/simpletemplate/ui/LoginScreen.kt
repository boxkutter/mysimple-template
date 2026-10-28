package app.simpletemplate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.simpletemplate.shared.ApiException
import app.simpletemplate.shared.auth.AuthRepository
import app.simpletemplate.shared.auth.OidcException
import app.simpletemplate.ui.auth.SignInCancelled
import app.simpletemplate.ui.auth.rememberOidcBrowser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Sign-in to the My Simple ISO platform (contract §2): the email decides which organisation's
 * sign-in page opens; the password is typed there, in the system browser, never in the app.
 * [onSignedIn] says whether the account belongs to an organisation yet.
 */
@Composable
fun LoginScreen(auth: AuthRepository, onSignedIn: (hasOrganisation: Boolean) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val browser = rememberOidcBrowser()
    var email by remember { mutableStateOf(auth.account?.email ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    // What went wrong underneath, for support (shown small under the message).
    var detail by remember { mutableStateOf<String?>(null) }
    val canSubmit = email.contains('@') && !busy

    fun submit() {
        if (!canSubmit) return
        busy = true
        error = null
        note = null
        detail = null
        scope.launch {
            try {
                val result = auth.signIn(email, browser)
                if (result.workspaces.isEmpty()) {
                    note = if (auth.local != null) {
                        "You are signed in. Your account does not belong to an organisation yet: on the Status page you can make one from the information on this phone."
                    } else {
                        "You are signed in, but your account does not belong to an organisation yet. Ask your organisation to invite you, or start without an account and create one later."
                    }
                }
                onSignedIn(result.workspaces.isNotEmpty())
            } catch (e: CancellationException) {
                throw e
            } catch (e: SignInCancelled) {
                error = null
            } catch (e: OidcException) {
                error = e.message
            } catch (e: ApiException) {
                error = when (e.status) {
                    429 -> "Too many attempts. Wait a minute and try again."
                    422 -> "Enter a valid email address."
                    else -> "The platform could not sign you in (${e.status})."
                }
            } catch (e: Exception) {
                error = e.message?.takeIf { "browser" in it } ?: "Can't reach the platform. Check your connection and try again."
                detail = generateSequence(e as Throwable) { it.cause }.last().let { "${it::class.simpleName}: ${it.message}" }
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(APP_NAME, style = MaterialTheme.typography.headlineMedium)
        Text(
            "Sign in to your organisation on My Simple ISO. You enter your password on the sign-in page, not in this app.",
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Work email") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        // Announced by screen readers as soon as it appears
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        note?.let { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Button(onClick = ::submit, enabled = canSubmit, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            if (busy) {
                CircularProgressIndicator(Modifier.padding(2.dp).semantics { contentDescription = "Signing in" })
            } else {
                Text("Continue to sign-in")
            }
        }
    }
}
