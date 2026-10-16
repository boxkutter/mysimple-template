package app.simpletemplate.shared.auth

import app.simpletemplate.shared.ApiClient
import app.simpletemplate.shared.ApiException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/** A fake auth server: issues numbered tokens and accepts only the latest access token. */
private class FakeServer {
    var issued = 0
    var refreshStatus: HttpStatusCode = HttpStatusCode.OK
    var offline = false
    val requests = mutableListOf<String>()
    private val userId = Uuid.generateV7()
    private val orgId = Uuid.generateV7()

    val engine = MockEngine { request -> handle(request) }

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData) = run {
        val path = request.url.encodedPath
        requests += "${request.method.value} $path ${request.headers[HttpHeaders.Authorization] ?: "-"}"
        if (offline) throw IOException("offline")
        when (path) {
            "/v1/auth/login" ->
                if ("wrong" in request.body.toByteArray().decodeToString()) problem(HttpStatusCode.Unauthorized, "Incorrect email or password")
                else tokens()
            "/v1/auth/refresh" -> if (refreshStatus == HttpStatusCode.OK) tokens() else problem(refreshStatus, "Session has ended")
            "/v1/auth/logout" -> respond("", HttpStatusCode.NoContent)
            "/v1/me" ->
                if (request.headers[HttpHeaders.Authorization] == "Bearer access-$issued") json(me())
                else problem(HttpStatusCode.Unauthorized, "Invalid or expired access token")
            else -> respondError(HttpStatusCode.NotFound)
        }
    }

    private fun me() = """"user":{"id":"$userId","email":"ada@example.com","name":"Ada"},""" +
        """"organisation":{"id":"$orgId","display_name":"Acme Ltd","timezone":"Europe/Stockholm"}"""

    private fun MockRequestHandleScope.tokens() = run {
        issued++
        json(me() + ""","access_token":"access-$issued","expires_in":900,"refresh_token":"refresh-$issued"""")
    }

    private fun MockRequestHandleScope.json(fields: String) =
        respond("{$fields}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.problem(status: HttpStatusCode, detail: String) = respond(
        """{"title":"unauthorized","status":${status.value},"detail":"$detail"}""", status,
        headersOf(
            HttpHeaders.ContentType to listOf("application/problem+json"),
            HttpHeaders.WWWAuthenticate to listOf("Bearer"),
        ),
    )
}

class AuthRepositoryTest {
    private val server = FakeServer()
    private val store = InMemorySessionStore()
    private val sessions = SessionHolder(store)
    private val api = ApiClient("https://example.test", server.engine, sessions)
    private val auth = AuthRepository(api, sessions)

    @Test
    fun signInStoresTheSessionAndAuthenticatesLaterRequests() = runTest {
        val session = auth.signIn(" ada@example.com ", "secret", "Pixel 9a")

        assertEquals("Acme Ltd", session.organisationName)
        assertEquals(session, store.load())
        assertEquals("Ada", api.me().user.name)
        assertEquals("GET /v1/me Bearer access-1", server.requests.last())
    }

    @Test
    fun theAppStartsWithoutAnAccountAndSendsNoToken() = runTest {
        val local = auth.startLocal(" Ada Lovelace ", "", "Europe/Stockholm")

        assertEquals(true, local.isLocal)
        assertEquals("Ada Lovelace", local.organisationName) // working on her own
        assertEquals(local, sessions.current)
        // Nothing to authenticate with: a request goes out without a bearer token
        runCatching { api.me() }
        assertEquals("GET /v1/me -", server.requests.last())
    }

    @Test
    fun signingOutOfAnAccountReturnsToTheLocalWorkspace() = runTest {
        val local = auth.startLocal("Ada", "Acme Ltd", "Europe/Stockholm")
        auth.signIn("ada@example.com", "secret", null)
        assertEquals(false, sessions.current?.isLocal)

        auth.signOut()

        assertEquals(local, sessions.current)
        // Signing out of the local workspace itself does nothing
        auth.signOut()
        assertEquals(local, sessions.current)
    }

    @Test
    fun wrongPasswordReportsTheServersReason() = runTest {
        val error = assertFailsWith<ApiException> { auth.signIn("ada@example.com", "wrong", null) }

        assertEquals(401, error.status)
        assertEquals("Incorrect email or password", error.detail)
        assertNull(sessions.current)
    }

    @Test
    fun expiredAccessTokenIsRefreshedAndTheRotatedTokensAreKept() = runTest {
        auth.signIn("ada@example.com", "secret", null)
        server.issued++ // the server now expects a newer access token than the app has

        assertEquals("Ada", api.me().user.name)

        assertEquals("refresh-3", sessions.current?.refreshToken)
        assertEquals("refresh-3", store.load()?.refreshToken)
        assertEquals("GET /v1/me Bearer access-3", server.requests.last())
    }

    @Test
    fun sessionEndedByTheServerSignsTheDeviceOut() = runTest {
        auth.signIn("ada@example.com", "secret", null)
        server.issued++
        server.refreshStatus = HttpStatusCode.Unauthorized

        assertFailsWith<ApiException> { api.me() }

        assertNull(sessions.current)
        assertNull(store.load())
    }

    @Test
    fun beingOfflineKeepsTheSession() = runTest {
        val session = auth.signIn("ada@example.com", "secret", null)
        server.offline = true

        assertFailsWith<IOException> { api.me() }

        assertEquals(session, sessions.current)
    }

    @Test
    fun signOutWorksOfflineAndForgetsTheSession() = runTest {
        auth.signIn("ada@example.com", "secret", null)
        server.offline = true

        auth.signOut()

        assertNull(sessions.current)
        assertNull(store.load())
    }

    @Test
    fun sessionSurvivesARestart() = runTest {
        val session = auth.signIn("ada@example.com", "secret", null)

        assertEquals(session, SessionHolder(store).current)
    }
}
