package app.simpletemplate.android

import android.app.Application
import android.os.Build
import app.simpletemplate.shared.AppContainer
import app.simpletemplate.shared.PlatformNetwork
import app.simpletemplate.shared.auth.EncryptedSessionStore
import app.simpletemplate.shared.auth.OidcSettings
import app.simpletemplate.shared.data.DirectoryFileStorage
import app.simpletemplate.shared.data.EncryptedDatabase
import java.io.File

class SimpleTemplateApplication : Application() {
    /** Shared services for the whole process. The database opens lazily, off the main thread. */
    val container: AppContainer by lazy {
        PlatformNetwork.resolveLocalhostToLoopback = BuildConfig.LOOPBACK_LOCALHOST
        AppContainer(
            baseUrl = BuildConfig.BASE_URL,
            oidcSettings = OidcSettings(
                issuer = BuildConfig.OIDC_ISSUER,
                clientId = BuildConfig.OIDC_CLIENT_ID,
                redirectUri = "${BuildConfig.OIDC_SCHEME}:/oidc/callback",
                postLogoutRedirectUri = "${BuildConfig.OIDC_SCHEME}:/oidc/logout",
            ),
            sessionStore = EncryptedSessionStore(this),
            openStore = { EncryptedDatabase.open(this) },
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
            appVersion = BuildConfig.VERSION_NAME,
            // Files of synced records (Attachments), in the app's private storage
            files = DirectoryFileStorage(File(filesDir, "attachments")),
        )
    }
}
