package app.simpletemplate.shared.auth

import app.simpletemplate.shared.ApiClient
import app.simpletemplate.shared.MeResponse
import kotlinx.coroutines.flow.StateFlow
import kotlin.uuid.Uuid

/**
 * The local workspace (no account), sign-in through the platform's Zitadel, the organisation
 * workspaces of the account, and sign-out (platform contract §2, §3.2). Signing out keeps local
 * data (it is scoped by organisation) and returns to the local workspace if there is one.
 */
class AuthRepository(
    private val api: ApiClient,
    private val sessions: SessionHolder,
    private val oidc: OidcClient,
    /** The sign-in server the platform named; it may differ from the build's (a moved dev server). */
    private val onIssuer: (String) -> Unit = {},
) {
    val session: StateFlow<Session?> = sessions.session

    /** The signed-in account, whichever workspace is open (null: none). */
    val account: Session? get() = sessions.account

    /** The local workspace, if one was set up on this phone. */
    val local: Session? get() = sessions.local

    /** Starts using the app without an account: the user and their organisation, on this phone. */
    fun startLocal(name: String, organisation: String, timezone: String): Session =
        localSession(name, organisation, timezone).also { sessions.setLocal(it) }

    /**
     * Signs in: discover the Zitadel organisation for [email], authorize in the system browser,
     * exchange the code, read the memberships. Opens the first organisation workspace; with no
     * membership the local workspace stays open and [SignInResult.workspaces] is empty (the
     * caller offers to make an organisation of the local workspace: WorkspaceAttach).
     */
    suspend fun signIn(email: String, browser: OidcBrowser): SignInResult {
        val hint = email.trim()
        val found = api.discover(hint)
        onIssuer(found.issuer)
        val zitadelOrg = found.organisationId
        val tokens = authorize(browser, zitadelOrg, hint, reauthenticate = false)
        val me = api.me(tokens.accessToken)
        val workspaces = me.workspaces()
        val base = Session(
            userId = me.user.id,
            email = me.user.email,
            name = me.user.name,
            organisationId = workspaces.firstOrNull()?.organisationId ?: me.user.id,
            organisationName = workspaces.firstOrNull()?.name ?: me.user.name,
            organisationTimezone = workspaces.firstOrNull()?.timezone ?: (local?.organisationTimezone ?: "UTC"),
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken ?: "",
            idToken = tokens.idToken ?: "",
            workspaces = workspaces,
            zitadelOrgId = zitadelOrg,
        )
        if (workspaces.isEmpty()) {
            sessions.saveAccountOnly(base)
        } else {
            sessions.set(base.openIn(workspaces.first()))
        }
        api.sessionChanged()
        return SignInResult(base, workspaces)
    }

    /**
     * A fresh sign-in for an approval (contract §2: within five minutes): Zitadel asks for the
     * password again and the new ID token is kept for `X-Id-Token`.
     */
    suspend fun reauthenticate(browser: OidcBrowser): String {
        val account = sessions.account ?: error("Not signed in")
        val tokens = authorize(browser, account.zitadelOrgId, account.email, reauthenticate = true)
        sessions.updateTokens(tokens.accessToken, tokens.refreshToken ?: account.refreshToken, tokens.idToken)
        api.sessionChanged()
        return tokens.idToken ?: ""
    }

    private suspend fun authorize(browser: OidcBrowser, zitadelOrg: String?, hint: String?, reauthenticate: Boolean): OidcTokens {
        val pending = newSignIn(oidc.discovery(), oidc.settings, zitadelOrg, hint, reauthenticate)
        val callback = browser.authorize(pending.url)
        val code = authorizationCode(callback, pending, oidc.settings)
        val tokens = oidc.exchange(code, pending)
        if (tokens.idToken != null && idTokenClaim(tokens.idToken, "nonce") != pending.nonce) {
            throw OidcException("invalid_nonce", "The sign-in answer does not match this attempt")
        }
        return tokens
    }

    /** Opens one of the account's organisations. */
    fun open(workspace: Workspace) {
        val account = sessions.account ?: return
        sessions.set(account.openIn(workspace))
    }

    /** Opens the local workspace; the account stays signed in. */
    fun openLocal() {
        sessions.local?.let { sessions.set(it) }
    }

    /**
     * Refreshes the account's workspaces from `GET /v1/me` (an invitation accepted on the web).
     * A workspace that is no longer listed stays open until its sync receives the platform's
     * wipe for it (contract §5), which then removes it ([workspaceRemoved]).
     */
    suspend fun refreshWorkspaces(): List<Workspace> {
        val me = api.me()
        val account = sessions.account ?: return emptyList()
        val workspaces = me.workspaces()
        val updated = account.copy(workspaces = workspaces, name = me.user.name, email = me.user.email)
        val current = sessions.current
        if (current != null && !current.isLocal) {
            val open = workspaces.firstOrNull { it.organisationId == current.organisationId }
            if (open != null) sessions.set(updated.openIn(open)) else sessions.saveAccountOnly(updated)
        } else {
            sessions.saveAccountOnly(updated)
        }
        return workspaces
    }

    /**
     * The platform took the organisation away from this device (a wipe: the membership ended).
     * Its data is gone already; the workspace leaves the switcher, and if it was open the local
     * workspace opens instead (or the account's next organisation, or the welcome screen).
     */
    fun workspaceRemoved(orgId: Uuid) {
        val account = sessions.account ?: return
        val remaining = account.workspaces.filterNot { it.organisationId == orgId }
        val updated = account.copy(workspaces = remaining)
        val current = sessions.current
        if (current == null || current.isLocal || current.organisationId != orgId) {
            sessions.saveAccountOnly(updated)
            if (current != null && !current.isLocal) sessions.set(current.copy(workspaces = remaining))
            return
        }
        val local = sessions.local
        when {
            local != null -> { sessions.saveAccountOnly(updated); sessions.set(local) }
            remaining.isNotEmpty() -> sessions.set(updated.openIn(remaining.first()))
            else -> { sessions.saveAccountOnly(updated); sessions.clear() }
        }
    }

    /** Ends the account on this device; local data stays. Back to the local workspace. */
    fun signOut() {
        if (sessions.account == null) return
        sessions.endAccount()
        api.sessionChanged()
    }
}

data class SignInResult(val account: Session, val workspaces: List<Workspace>)

internal fun MeResponse.workspaces(): List<Workspace> = memberships.map {
    Workspace(it.organisation.id, it.organisation.displayName, it.organisation.timezone, it.kind, it.roles)
}
