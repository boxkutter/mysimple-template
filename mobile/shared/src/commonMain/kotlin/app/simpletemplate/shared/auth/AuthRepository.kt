package app.simpletemplate.shared.auth

import app.simpletemplate.shared.ApiClient
import kotlinx.coroutines.flow.StateFlow

/**
 * The local workspace (no account), sign-in and sign-out. Signing out keeps local data (it is
 * scoped to the organisation) and returns to the local workspace if there is one.
 */
class AuthRepository(private val api: ApiClient, private val sessions: SessionHolder) {
    val session: StateFlow<Session?> = sessions.session

    /** Starts using the app without an account: the user and their organisation, on this phone. */
    fun startLocal(name: String, organisation: String, timezone: String): Session =
        localSession(name, organisation, timezone).also { sessions.setLocal(it) }

    /** Throws [app.simpletemplate.shared.ApiException] when the server refuses, IO errors when offline. */
    suspend fun signIn(email: String, password: String, deviceName: String?): Session {
        val session = api.login(email.trim(), password, deviceName).toSession()
        sessions.set(session)
        api.sessionChanged()
        return session
    }

    /** Ends the session on the server if reachable, and on this device in any case. */
    suspend fun signOut() {
        val session = sessions.current ?: return
        if (session.isLocal) return
        runCatching { api.logout(session.refreshToken) }
        sessions.clear()
        sessions.local?.let { sessions.set(it) }
        api.sessionChanged()
    }
}
