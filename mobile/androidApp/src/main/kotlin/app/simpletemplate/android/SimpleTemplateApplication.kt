package app.simpletemplate.android

import android.app.Application
import android.os.Build
import app.simpletemplate.shared.AppContainer
import app.simpletemplate.shared.PlatformNetwork
import app.simpletemplate.shared.ServerAddress
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
            // Debug builds: the user may point the app at a development server (Status →
            // Connection), whose LAN address changes; release builds keep the build's address.
            server = ServerAddress(
                store = ServerAddressPreferences(this),
                defaultBaseUrl = BuildConfig.BASE_URL,
                defaultIssuer = BuildConfig.OIDC_ISSUER,
                editable = BuildConfig.DEBUG,
            ),
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
