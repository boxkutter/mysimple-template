package app.simpletemplate.android

import android.app.Application
import android.os.Build
import app.simpletemplate.shared.AppContainer
import app.simpletemplate.shared.auth.EncryptedSessionStore
import app.simpletemplate.shared.data.EncryptedDatabase

class SimpleTemplateApplication : Application() {
    /** Shared services for the whole process. The database opens lazily, off the main thread. */
    val container: AppContainer by lazy {
        AppContainer(
            baseUrl = BuildConfig.BASE_URL,
            sessionStore = EncryptedSessionStore(this),
            openStore = { EncryptedDatabase.open(this) },
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
        )
    }
}
