package app.simpletemplate.ui.lock

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
actual fun rememberDeviceLock(): DeviceLock {
    val context = LocalContext.current
    return remember(context) {
        object : DeviceLock {
            override val isDeviceSecure: Boolean
                get() = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

            override fun authenticate(title: String, subtitle: String, onResult: (Boolean) -> Unit) {
                val builder = BiometricPrompt.Builder(context).setTitle(title).setSubtitle(subtitle)
                if (Build.VERSION.SDK_INT >= 30) {
                    builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                } else {
                    @Suppress("DEPRECATION") builder.setDeviceCredentialAllowed(true)
                }
                runCatching {
                    builder.build().authenticate(CancellationSignal(), context.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
                    })
                }.onFailure { onResult(false) }
            }

            override fun openSecuritySettings() {
                val intent = Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }.onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
        }
    }
}

@Composable
actual fun isDebugBuild(): Boolean {
    val context = LocalContext.current
    return remember(context) { (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 }
}

@Composable
actual fun AppVisibility(onBackground: () -> Unit, onForeground: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val background = rememberUpdatedState(onBackground)
    val foreground = rememberUpdatedState(onForeground)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> background.value()
                Lifecycle.Event.ON_START -> foreground.value()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

@Composable
actual fun HideInRecents(hidden: Boolean) {
    val activity = LocalContext.current.activity() ?: return
    DisposableEffect(activity, hidden) {
        // Android 13+: no snapshot of the app in Recents while the lock is in use
        if (Build.VERSION.SDK_INT >= 33) activity.setRecentsScreenshotEnabled(!hidden)
        onDispose { if (Build.VERSION.SDK_INT >= 33) activity.setRecentsScreenshotEnabled(true) }
    }
}

@Composable
actual fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
actual fun BlockBack(enabled: Boolean) {
    BackHandler(enabled = enabled) {}
}
