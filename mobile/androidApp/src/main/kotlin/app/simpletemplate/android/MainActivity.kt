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
import app.simpletemplate.ui.App

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
        setContent { key(networkGeneration) { App((application as SimpleTemplateApplication).container) } }
    }
}
