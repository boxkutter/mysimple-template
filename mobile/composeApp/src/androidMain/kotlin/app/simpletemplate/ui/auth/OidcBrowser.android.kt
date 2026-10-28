package app.simpletemplate.ui.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.simpletemplate.shared.auth.OidcBrowser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Opens the authorize URL in a Custom Tab (the system browser, RFC 8252; no Google Play services
 * and no embedded web view). A plain VIEW intent with the Custom Tabs session extra is enough
 * for any browser that supports them; others open it as a normal page.
 */
private class CustomTabBrowser(private val context: Context) : OidcBrowser {
    override suspend fun authorize(url: String): String {
        val deferred = OidcRedirects.expect()
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            putExtras(Bundle().apply { putBinder("android.support.customtabs.extra.SESSION", null) })
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            OidcRedirects.cancel()
            throw IllegalStateException("No browser is installed to sign in with")
        }
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            throw SignInCancelled()
        }
    }
}

@Composable
actual fun rememberOidcBrowser(): OidcBrowser {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Back in the app without a redirect (the tab was closed): the sign-in is cancelled. The
    // redirect itself also resumes the activity, so allow it a moment to arrive first.
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && OidcRedirects.isWaiting) {
                MainScope().launch { delay(1_500); if (OidcRedirects.isWaiting) OidcRedirects.cancel() }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return remember(context) { CustomTabBrowser(context) }
}
