package app.simpletemplate.shared.auth

import app.simpletemplate.shared.TokensResponse
import app.simpletemplate.shared.data.Tenant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * The signed-in user on this device. Kept across restarts so the app works offline; only the
 * server can end it (sign-out, expiry, revocation).
 */
@Serializable
data class Session(
    val userId: Uuid,
    val email: String,
    val name: String,
    val organisationId: Uuid,
    val organisationName: String,
    val organisationTimezone: String,
    val accessToken: String,
    val refreshToken: String,
) {
    val tenant: Tenant get() = Tenant(organisationId)

    /** The user's own workspace on this phone, with no account: nothing is sent to a server. */
    val isLocal: Boolean get() = accessToken.isEmpty()
}

/** A new local workspace: the user and their organisation, on this phone only. */
fun localSession(name: String, organisation: String, timezone: String) = Session(
    userId = Uuid.generateV7(), email = "", name = name.trim(), organisationId = Uuid.generateV7(),
    organisationName = organisation.trim().ifEmpty { name.trim() }, organisationTimezone = timezone, accessToken = "", refreshToken = "",
)

internal fun TokensResponse.toSession() = Session(
    userId = user.id,
    email = user.email,
    name = user.name,
    organisationId = organisation.id,
    organisationName = organisation.displayName,
    organisationTimezone = organisation.timezone,
    accessToken = accessToken,
    refreshToken = refreshToken,
)

/** Persists the session securely (Android: encrypted with a Keystore key). */
interface SessionStore {
    fun load(): Session?
    fun save(session: Session)
    fun clear()

    /** The local workspace, kept apart so that signing out of an account returns to it. */
    fun loadLocal(): Session? = null
    fun saveLocal(session: Session) {}
}

/** The current session, observable by the UI and used by the API client for its tokens. */
class SessionHolder(private val store: SessionStore) {
    private val state = MutableStateFlow(runCatching { store.load() }.getOrNull())
    val session: StateFlow<Session?> = state.asStateFlow()

    val current: Session? get() = state.value

    internal fun set(session: Session) {
        store.save(session)
        state.value = session
    }

    internal fun clear() {
        store.clear()
        state.value = null
    }

    /** The local workspace, if one was set up on this phone. */
    internal val local: Session? get() = runCatching { store.loadLocal() }.getOrNull()

    internal fun setLocal(session: Session) {
        store.saveLocal(session)
        set(session)
    }
}

/** For tests and previews. */
class InMemorySessionStore(private var session: Session? = null) : SessionStore {
    private var local: Session? = null
    override fun load() = session
    override fun save(session: Session) { this.session = session }
    override fun clear() { session = null }
    override fun loadLocal() = local
    override fun saveLocal(session: Session) { local = session }
}
