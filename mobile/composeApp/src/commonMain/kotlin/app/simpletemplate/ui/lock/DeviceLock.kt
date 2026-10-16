package app.simpletemplate.ui.lock

import androidx.compose.runtime.Composable

/** The phone's own lock (fingerprint, face, PIN, pattern, password), used as the app lock. */
interface DeviceLock {
    /** The phone has a screen lock set. */
    val isDeviceSecure: Boolean

    /** Asks for the phone's biometric or device credential; [onResult] true when it succeeded. */
    fun authenticate(title: String, subtitle: String, onResult: (Boolean) -> Unit)

    /** Opens the phone's settings for setting a screen lock. */
    fun openSecuritySettings()
}

@Composable
expect fun rememberDeviceLock(): DeviceLock

/** A debug build (installed from the development machine, not a store release): test mode is offered. */
@Composable
expect fun isDebugBuild(): Boolean

/** Calls [onBackground] when the app leaves the screen and [onForeground] when it comes back. */
@Composable
expect fun AppVisibility(onBackground: () -> Unit, onForeground: () -> Unit)

/** Keeps the app's content out of the recent-apps preview while [hidden]. */
@Composable
expect fun HideInRecents(hidden: Boolean)

/** Keeps the screen on while this is shown (e.g. a screen used during fieldwork). */
@Composable
expect fun KeepScreenOn()

/** Swallows the back gesture while [enabled]. */
@Composable
expect fun BlockBack(enabled: Boolean)
