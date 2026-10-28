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
    /** The platform's address; editable in debug builds (see [ServerAddress]). */
    val server: ServerAddress,
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
        currentIssuer = { server.issuer.value },
    )
    val api = ApiClient(server.baseUrl.value, sessions = sessions, oidc = oidc, currentBaseUrl = { server.baseUrl.value })
    /** The API's address now. */
    val baseUrl: String get() = server.baseUrl.value
    val auth = AuthRepository(api, sessions, oidc, onIssuer = server::issuerFound)

    /**
     * Moves to the platform at [input] (debug builds): checks that it answers, then, when an
     * account is signed in, asks it which sign-in server it uses so refreshes go there.
     * The address is kept only when the platform answered.
     */
    suspend fun changeServer(input: String): HealthStatus {
        val url = normaliseServerAddress(input) ?: throw IllegalArgumentException("Enter an address such as 192.168.1.10:8081")
        val health = ApiClient(url, platformHttpEngine()).health()
        server.change(url)
        sessions.account?.takeUnless { it.isLocal }?.let { account ->
            runCatching { api.discover(account.email) }.getOrNull()?.let { server.issuerFound(it.issuer) }
        }
        return health
    }

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
