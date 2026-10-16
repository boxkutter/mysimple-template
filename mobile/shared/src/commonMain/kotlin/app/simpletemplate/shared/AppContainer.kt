package app.simpletemplate.shared

import app.simpletemplate.shared.auth.AuthRepository
import app.simpletemplate.shared.auth.Session
import app.simpletemplate.shared.auth.SessionHolder
import app.simpletemplate.shared.auth.SessionStore
import app.simpletemplate.shared.data.AiDrafting
import app.simpletemplate.shared.data.ItemRepository
import app.simpletemplate.shared.data.LocalStore
import app.simpletemplate.shared.data.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/** Wires the shared services together; one per process. The platform supplies the storage. */
class AppContainer(
    val baseUrl: String,
    sessionStore: SessionStore,
    openStore: () -> LocalStore,
    val deviceName: String?,
) {
    val sessions = SessionHolder(sessionStore)
    val api = ApiClient(baseUrl, sessions = sessions)
    val auth = AuthRepository(api, sessions)

    /** Opened on first use; call off the main thread. */
    val localStore: LocalStore by lazy(openStore)

    /** Repositories for the signed-in organisation. Opens the database if needed. */
    suspend fun repositories(session: Session): Repositories = withContext(Dispatchers.IO) {
        Repositories(localStore, api, session)
    }
}

/** Everything the screens use, scoped to one user and organisation. Add the app's repositories here. */
class Repositories(store: LocalStore, api: ApiClient, val session: Session) {
    val settings = UserSettings(store, session.userId)
    val ai = AiDrafting(api, local = session.isLocal)

    // EXAMPLE feature: replace with the app's own repositories.
    val items = ItemRepository(store, session.tenant)
}
