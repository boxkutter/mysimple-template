package app.simpletemplate.ui.auth

import androidx.compose.runtime.Composable
import app.simpletemplate.shared.auth.OidcBrowser
import kotlinx.coroutines.CompletableDeferred

/**
 * Where the platform's sign-in comes back: the activity hands the redirect URI here, and the
 * waiting [OidcBrowser] picks it up. One sign-in at a time.
 */
object OidcRedirects {
    private var waiting: CompletableDeferred<String>? = null

    internal fun expect(): CompletableDeferred<String> =
        CompletableDeferred<String>().also { waiting?.cancel(); waiting = it }

    /** The redirect arrived (from the activity's intent). */
    fun deliver(uri: String) {
        waiting?.complete(uri)
        waiting = null
    }

    /** The browser was closed without signing in. */
    fun cancel() {
        waiting?.cancel()
        waiting = null
    }

    val isWaiting: Boolean get() = waiting?.isActive == true
}

/** The person closed the browser without finishing the sign-in. */
class SignInCancelled : Exception("Sign-in was cancelled")

/** The system browser for sign-in (Custom Tabs on Android). */
@Composable
expect fun rememberOidcBrowser(): OidcBrowser
