package app.simpletemplate.shared

import app.simpletemplate.shared.auth.AuthRepository
import app.simpletemplate.shared.auth.OidcClient
import app.simpletemplate.shared.auth.OidcSettings
import app.simpletemplate.shared.auth.Session
import app.simpletemplate.shared.auth.SessionHolder
import app.simpletemplate.shared.auth.SessionStore
import app.simpletemplate.shared.data.AiDrafting
import app.simpletemplate.shared.data.ItemRepository
import app.simpletemplate.shared.data.LocalStore
import app.simpletemplate.shared.data.UserSettings
import app.simpletemplate.shared.data.appEntities
import app.simpletemplate.shared.sync.Attachments
import app.simpletemplate.shared.sync.FileStorage
import app.simpletemplate.shared.sync.SyncEngine
import app.simpletemplate.shared.sync.WorkspaceAttach
import app.simpletemplate.shared.sync.WorkspaceSync
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Wires the shared services together; one per process. The platform supplies the storage. */
class AppContainer(
    val baseUrl: String,
    val oidcSettings: OidcSettings,
    sessionStore: SessionStore,
    openStore: () -> LocalStore,
    val deviceName: String?,
    val appVersion: String? = null,
    /** Files on this phone, for entities that carry one (Attachments); null when the app has none. */
    val files: FileStorage? = null,
) {
    val sessions = SessionHolder(sessionStore)

    /** Talks to the platform's Zitadel: no bearer token, no organisation header. */
    val oidc = OidcClient(
        HttpClient(platformHttpEngine()) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } },
        oidcSettings,
    )
    val api = ApiClient(baseUrl, sessions = sessions, oidc = oidc)
    val auth = AuthRepository(api, sessions, oidc)

    /** Uploads to pre-signed storage URLs: no platform credentials or headers. */
    private val storageHttp by lazy { HttpClient(platformHttpEngine()) }

    /** Opened on first use; call off the main thread. */
    val localStore: LocalStore by lazy(openStore)

    /** Makes the local workspace an organisation's (adopt or remap). Opens the database if needed. */
    val attach: WorkspaceAttach by lazy { WorkspaceAttach(localStore, api, sessions, auth) }

    /** Repositories for the open workspace. Opens the database if needed. */
    suspend fun repositories(session: Session): Repositories = withContext(Dispatchers.IO) {
        val store = localStore
        val sync = if (session.isLocal) null else {
            val registry = appEntities()
            WorkspaceSync(
                store, session.organisationId,
                SyncEngine(store, api, deviceName, appVersion, registry) { files?.delete(it) },
                files?.let { Attachments(store, api, storageHttp, it, registry) },
                onRemoved = auth::workspaceRemoved,
            )
        }
        Repositories(store, api, session, sync)
    }
}

/** Everything the screens use, scoped to one user and workspace. Add the app's repositories here. */
class Repositories(store: LocalStore, api: ApiClient, val session: Session, val sync: WorkspaceSync? = null) {
    val settings = UserSettings(store, session.userId)
    val ai = AiDrafting(api, local = session.isLocal)

    // EXAMPLE feature: replace with the app's own repositories.
    val items = ItemRepository(store, session.tenant)
}
