package app.simpletemplate.android

import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.content.Intent
import app.simpletemplate.ui.App
import app.simpletemplate.ui.auth.OidcRedirects

class MainActivity : ComponentActivity() {
    // Bumped when local network access is granted, so the server check runs again.
    private var networkGeneration by mutableStateOf(0)

    private val requestLocalNetwork =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) networkGeneration++
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The launch screen (navy with the mark) until the first frame is drawn
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Android 17+ blocks connections to LAN addresses without this runtime permission;
        // they fail as connect timeouts. The platform server may resolve to one.
        if (Build.VERSION.SDK_INT >= 37 &&
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            requestLocalNetwork.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
        deliverSignIn(intent)
        setContent { key(networkGeneration) { App((application as SimpleTemplateApplication).container) } }
    }

    // The sign-in redirect arrives while the app is running (singleTask).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deliverSignIn(intent)
    }

    private fun deliverSignIn(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == BuildConfig.OIDC_SCHEME) OidcRedirects.deliver(data.toString())
    }
}
