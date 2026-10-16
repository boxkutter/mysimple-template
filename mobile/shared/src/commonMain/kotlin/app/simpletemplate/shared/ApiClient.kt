package app.simpletemplate.shared

import app.simpletemplate.shared.auth.SessionHolder
import app.simpletemplate.shared.auth.toSession
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.authProviders
import io.ktor.client.plugins.auth.providers.BearerAuthProvider
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class HealthStatus(
    val status: String,
    val version: String,
    val environment: String,
)

/**
 * Client for the platform API (the same server as the Mobile Auditor; docs/platform.md). The base
 * URL is configuration (build setting). With a [sessions] holder, requests carry the access token
 * and refresh it when it expires.
 *
 * Add the app's own endpoints as methods here, with their wire types in a file next to this one
 * (like AuthApi.kt and AiApi.kt).
 */
class ApiClient(
    baseUrl: String,
    engine: HttpClientEngine = platformHttpEngine(),
    private val sessions: SessionHolder? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val http = HttpClient(engine) {
        expectSuccess = true
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 30_000
        }
        HttpResponseValidator {
            handleResponseExceptionWithRequest { cause, _ ->
                if (cause is ResponseException) throw cause.response.toApiException()
            }
        }
        if (sessions != null) {
            install(Auth) {
                bearer {
                    loadTokens { sessions.current?.takeUnless { it.isLocal }?.let { BearerTokens(it.accessToken, it.refreshToken) } }
                    refreshTokens { refreshSession { markAsRefreshTokenRequest() } }
                    sendWithoutRequest { "/v1/auth/" !in it.url.buildString() }
                }
            }
        }
        defaultRequest { url(baseUrl.trimEnd('/') + "/") }
    }

    suspend fun health(): HealthStatus = http.get("health").body()

    suspend fun login(email: String, password: String, deviceName: String?): TokensResponse =
        http.post("v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email, password, deviceName))
        }.body()

    suspend fun logout(refreshToken: String) {
        http.post("v1/auth/logout") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(refreshToken))
        }
    }

    suspend fun me(): MeResponse = http.get("v1/me").body()

    /**
     * Asks the server for an AI draft (spec 14 of the platform). The server decides whether AI is
     * set up and allowed for the organisation, and which tasks exist; the result is always a
     * suggestion.
     */
    suspend fun draftWithAi(request: AiDraftRequest): AiDraftResponse =
        http.post("v1/ai/draft") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    /** Forget cached tokens after the session changed (sign-in, sign-out). */
    fun sessionChanged() {
        http.authProviders.filterIsInstance<BearerAuthProvider>().forEach { it.clearToken() }
    }

    fun close() = http.close()

    /**
     * Swaps the refresh token for new tokens (single use; the server rotates it). If the server
     * has ended the session, the device is signed out. Network errors leave the session alone,
     * so the app keeps working offline.
     */
    private suspend fun refreshSession(markAsRefresh: HttpRequestBuilder.() -> Unit): BearerTokens? {
        val holder = sessions ?: return null
        // The local workspace has no account: nothing to refresh, and never signed out by a server
        val session = holder.current?.takeUnless { it.isLocal } ?: return null
        return try {
            val tokens: TokensResponse = http.post("v1/auth/refresh") {
                contentType(ContentType.Application.Json)
                setBody(RefreshRequest(session.refreshToken))
                markAsRefresh()
            }.body()
            holder.set(tokens.toSession())
            BearerTokens(tokens.accessToken, tokens.refreshToken)
        } catch (e: ApiException) {
            if (e.status == HttpStatusCode.Unauthorized.value) holder.clear()
            null
        }
    }

    private suspend fun HttpResponse.toApiException(): ApiException {
        val problem = runCatching { json.decodeFromString<Problem>(body<String>()) }.getOrNull()
        return ApiException(status.value, problem?.detail ?: problem?.title ?: status.description)
    }
}

/** Platform HTTP engine (OkHttp on Android, Darwin on iOS later). No Play services. */
expect fun platformHttpEngine(): HttpClientEngine
