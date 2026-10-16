package app.simpletemplate.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.simpletemplate.shared.Repositories
import app.simpletemplate.shared.data.LockSettings
import app.simpletemplate.ui.APP_NAME
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Screens that the app lock does not interrupt when the user comes back (e.g. a screen used all day
 * during fieldwork). Such a screen calls [LockExemptScreen].
 */
object LockExemptPresence {
    var screens by mutableIntStateOf(0)
        internal set
    val active: Boolean get() = screens > 0
}

/**
 * The app lock as the rest of the app sees it: e.g. storing sensitive data needs a screen lock.
 * [required]: the app requires the lock now (it cannot be turned off).
 */
class AppLockAccess(val deviceLock: DeviceLock, private val testMode: Boolean = false, val required: Boolean = false) {
    /** Sensitive data may be stored: the phone has a screen lock (the app lock then becomes mandatory), or test mode is on. */
    val canStoreSensitiveData: Boolean get() = testMode || deviceLock.isDeviceSecure
}

val LocalAppLock = staticCompositionLocalOf<AppLockAccess?> { null }

const val SCREEN_LOCK_NEEDED =
    "Set a screen lock on this phone first (fingerprint, face or PIN): this information is kept behind the app lock."

/**
 * The app lock: the phone's own fingerprint, face or PIN. In use when the user turned it on, and
 * always while [required] says so (e.g. once data about other people is stored). It asks when the
 * app starts and when it comes back after the chosen time away. It lies over the app (which keeps
 * its place), so nothing shows or can be touched while locked.
 */
@Composable
fun AppLockGate(repos: Repositories, required: Flow<Boolean>, content: @Composable () -> Unit) {
    val deviceLock = rememberDeviceLock()
    val settings by remember(repos) { repos.settings.observeLockSettings() }.collectAsState(initial = null)
    val forced by remember(required) { required }.collectAsState(initial = null)
    val s = settings
    val mustLock = forced
    var locked by remember { mutableStateOf(true) }
    var awaySince by remember { mutableStateOf<Instant?>(null) }
    var awayFromExempt by remember { mutableStateOf(false) }
    // Test mode (debug builds only, Status page): the app lock is off so the app can be driven for testing
    val debug = isDebugBuild()
    val testMode by remember(repos) { repos.settings.observeTestMode() }.collectAsState(initial = false)
    val bypass = debug && testMode
    val inUse = s != null && mustLock != null && (s.appLockOn || mustLock) && !bypass
    // Re-checked when the lock comes into use and when the user comes back (e.g. from the phone's settings)
    var checks by remember { mutableIntStateOf(0) }
    val secure = remember(checks, inUse) { deviceLock.isDeviceSecure }

    AppVisibility(
        onBackground = { awaySince = Clock.System.now(); awayFromExempt = LockExemptPresence.active },
        onForeground = {
            checks++
            val since = awaySince
            val minutes = (s ?: LockSettings()).appLockMinutes
            if (since != null && !awayFromExempt && Clock.System.now() - since >= minutes.minutes) locked = true
            awaySince = null
        },
    )
    // Turning the lock on (or storing the first sensitive record) does not lock the user out there and
    // then: it asks from the next start or time away. On a fresh start it asks at once.
    var wasInUse by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(inUse, s != null && mustLock != null) {
        if (s == null || mustLock == null) return@LaunchedEffect
        if (wasInUse == false && inUse) locked = false
        wasInUse = inUse
        // Once required, the lock stays on, also if the data is deleted later
        if (mustLock && !s.appLockOn) repos.settings.setLockSettings(s.copy(appLockOn = true))
    }

    HideInRecents(inUse)
    CompositionLocalProvider(LocalAppLock provides AppLockAccess(deviceLock, bypass, mustLock == true)) {
        Box(Modifier.fillMaxSize()) {
            content()
            if (bypass) Text(
                "Test mode: app lock off",
                style = MaterialTheme.typography.labelMedium, color = Color.White, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color(0xFFB45309)).statusBarsPadding(),
            )
            when {
                s == null || mustLock == null -> Cover {}
                inUse && !secure -> Cover {
                    LockMessage(
                        "Set a screen lock",
                        "Your information is kept behind the app lock, which uses this phone's own lock (fingerprint, face or PIN). " +
                            "This phone has no screen lock yet.",
                        "Open security settings",
                    ) { deviceLock.openSecuritySettings() }
                }
                inUse && locked -> Cover {
                    fun unlock() = deviceLock.authenticate("Unlock $APP_NAME", "Your information is protected") { ok -> if (ok) locked = false }
                    LaunchedEffect(Unit) { unlock() }
                    LockMessage("Locked", "Unlock with your fingerprint, face or PIN.", "Unlock") { unlock() }
                }
            }
        }
    }
}

/** Marks a screen the app lock does not interrupt after time away (see [LockExemptPresence]). */
@Composable
fun LockExemptScreen(content: @Composable () -> Unit) {
    DisposableEffect(Unit) {
        LockExemptPresence.screens++
        onDispose { LockExemptPresence.screens-- }
    }
    content()
}

/** An opaque layer over the app that takes every touch and the back gesture. */
@Composable
private fun Cover(content: @Composable () -> Unit) {
    BlockBack(true)
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun LockMessage(title: String, text: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier.safeDrawingPadding().padding(32.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Icons.Filled.Lock, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Button(onClick = onAction, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(action, style = MaterialTheme.typography.titleMedium)
        }
    }
}
