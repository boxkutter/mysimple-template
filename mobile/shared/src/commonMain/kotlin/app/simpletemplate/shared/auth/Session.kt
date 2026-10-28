package app.simpletemplate.shared.auth

import app.simpletemplate.shared.data.Tenant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** An organisation the signed-in user can open as a workspace (platform contract §3.2). */
@Serializable
data class Workspace(
    val organisationId: Uuid,
    val name: String,
    val timezone: String,
    /** MEMBER, or EXTERNAL for a participant invited into someone else's organisation. */
    val kind: String = "MEMBER",
    val roles: List<String> = emptyList(),
)

/**
 * The workspace open on this device, kept across restarts so the app works offline. Either the
 * local workspace (no account: [isLocal]) or one organisation of the signed-in account, whose
 * tokens it carries. Only the server can end an account (sign-out, expiry, revocation).
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
    /** The latest ID token: proof of a recent sign-in for approvals (contract §2, `X-Id-Token`). */
    val idToken: String = "",
    /** Every organisation of the account, for the workspace switcher. */
    val workspaces: List<Workspace> = emptyList(),
    /** The Zitadel organisation the account signs in to (from discover), for re-authentication. */
    val zitadelOrgId: String = "",
) {
    val tenant: Tenant get() = Tenant(organisationId)

    /** The user's own workspace on this phone, with no account: nothing is sent to a server. */
    val isLocal: Boolean get() = accessToken.isEmpty()

    /** The same account, with [workspace] open. */
    fun openIn(workspace: Workspace) = copy(
        organisationId = workspace.organisationId,
        organisationName = workspace.name,
        organisationTimezone = workspace.timezone,
    )

    fun withTokens(access: String, refresh: String, id: String?) =
        copy(accessToken = access, refreshToken = refresh, idToken = id ?: idToken)
}

/** A new local workspace: the user and their organisation, on this phone only. */
fun localSession(name: String, organisation: String, timezone: String) = Session(
    userId = Uuid.generateV7(), email = "", name = name.trim(), organisationId = Uuid.generateV7(),
    organisationName = organisation.trim().ifEmpty { name.trim() }, organisationTimezone = timezone, accessToken = "", refreshToken = "",
)

/** Persists sessions securely (Android: encrypted with a Keystore key). */
interface SessionStore {
    /** The workspace open now. */
    fun load(): Session?
    fun save(session: Session)
    fun clear()

    /** The local workspace, kept apart so that signing out of an account returns to it. */
    fun loadLocal(): Session? = null
    fun saveLocal(session: Session) {}

    /** The local workspace is gone (it became an organisation's: WorkspaceAttach). */
    fun clearLocal() {}

    /** The signed-in account (tokens and workspaces), kept while the local workspace is open. */
    fun loadAccount(): Session? = null
    fun saveAccount(session: Session) {}
    fun clearAccount() {}
}

/** The current session, observable by the UI and used by the API client for its tokens. */
class SessionHolder(private val store: SessionStore) {
    private val state = MutableStateFlow(runCatching { store.load() }.getOrNull())
    val session: StateFlow<Session?> = state.asStateFlow()

    val current: Session? get() = state.value

    /** The signed-in account, whichever workspace is open. */
    val account: Session? get() = current?.takeUnless { it.isLocal } ?: runCatching { store.loadAccount() }.getOrNull()

    internal fun set(session: Session) {
        store.save(session)
        if (!session.isLocal) store.saveAccount(session)
        state.value = session
    }

    /** Keeps the account without opening one of its organisations (it has none yet). */
    internal fun saveAccountOnly(account: Session) {
        store.saveAccount(account)
    }

    /** Fresh tokens for the account; the open workspace keeps its organisation. */
    internal fun updateTokens(access: String, refresh: String, id: String?) {
        val account = account ?: return
        val updated = account.withTokens(access, refresh, id)
        store.saveAccount(updated)
        current?.takeUnless { it.isLocal }?.let { set(it.withTokens(access, refresh, id)) }
    }

    /** The account has ended: forget it and fall back to the local workspace (or nothing). */
    internal fun endAccount() {
        store.clearAccount()
        val local = local
        if (local != null) set(local) else clear()
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

    internal fun clearLocal() {
        store.clearLocal()
    }
}

/** For tests and previews. */
class InMemorySessionStore(private var session: Session? = null) : SessionStore {
    private var local: Session? = null
    private var account: Session? = session?.takeUnless { it.isLocal }
    override fun load() = session
    override fun save(session: Session) { this.session = session }
    override fun clear() { session = null }
    override fun loadLocal() = local
    override fun saveLocal(session: Session) { local = session }
    override fun clearLocal() { local = null }
    override fun loadAccount() = account
    override fun saveAccount(session: Session) { account = session }
    override fun clearAccount() { account = null }
}
