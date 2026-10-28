package app.simpletemplate.shared

import app.simpletemplate.shared.auth.OidcClient
import app.simpletemplate.shared.auth.SessionHolder
import app.simpletemplate.shared.auth.TokenRefusedException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.authProviders
import io.ktor.client.plugins.auth.providers.BearerAuthProvider
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

@Serializable
data class HealthStatus(
    val status: String,
    val version: String,
    val environment: String,
)

/** Header naming the organisation of a request (contract §2: the token never decides it). */
const val ORG_HEADER = "X-Org-Id"

/**
 * Client for the My Simple ISO platform API (docs/platform.md). The base URL is configuration
 * (build setting). With a [sessions] holder, requests carry the account's access token, refresh
 * it at the platform's Zitadel ([oidc]) when it expires, and name the open workspace's
 * organisation in `X-Org-Id`.
 *
 * Add the app's own endpoints as methods here (or as extensions next to their wire types, as
 * sync/SyncApi.kt does), with their wire types in a file next to this one (like AuthApi.kt).
 */
class ApiClient(
    baseUrl: String,
    engine: HttpClientEngine = platformHttpEngine(),
    private val sessions: SessionHolder? = null,
    private val oidc: OidcClient? = null,
) {
    internal val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** Names the open organisation unless the call named one itself (sync names its own). */
    private val organisationHeader = createClientPlugin("OrganisationHeader") {
        onRequest { request, _ ->
            val session = sessions?.current?.takeUnless { it.isLocal } ?: return@onRequest
            if (request.headers[ORG_HEADER] == null) request.headers.append(ORG_HEADER, session.organisationId.toString())
        }
    }

    internal val http = HttpClient(engine) {
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
                    loadTokens { sessions.account?.let { BearerTokens(it.accessToken, it.refreshToken) } }
                    refreshTokens { refreshSession() }
                    sendWithoutRequest { "/v1/auth/" !in it.url.buildString() }
                }
            }
            install(organisationHeader)
        }
        defaultRequest { url(baseUrl.trimEnd('/') + "/") }
    }

    suspend fun health(): HealthStatus = http.get("health").body()

    /** Which Zitadel organisation [email] signs in to. Rate-limited by the platform. */
    suspend fun discover(email: String): DiscoverResponse = http.post("v1/auth/discover") {
        contentType(ContentType.Application.Json)
        setBody(DiscoverRequest(email))
    }.body()

    /** The user and every organisation they may open. [accessToken] for the first call after sign-in. */
    suspend fun me(accessToken: String? = null): MeResponse = http.get("v1/me") {
        if (accessToken != null) header("Authorization", "Bearer $accessToken")
    }.body()

    /** Creates an organisation with the caller as its Owner (adopting a local id: [CreateOrganisationRequest.id]). */
    suspend fun createOrganisation(request: CreateOrganisationRequest): OrganisationDto = http.post("v1/orgs") {
        contentType(ContentType.Application.Json)
        setBody(request)
    }.body()

    /**
     * Asks the server for an AI draft (contract §6), for an organisation workspace. The server
     * decides whether AI is set up and allowed for the organisation, and which tasks exist; the
     * result is always a suggestion.
     */
    suspend fun draftWithAi(request: AiDraftRequest): AiDraftResponse =
        http.post("v1/ai/draft") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    /** Forget cached tokens after the session changed (sign-in, sign-out, refresh elsewhere). */
    fun sessionChanged() {
        http.authProviders.filterIsInstance<BearerAuthProvider>().forEach { it.clearToken() }
    }

    fun close() = http.close()

    /**
     * Swaps the refresh token for new tokens at Zitadel. Refused (expired, revoked): the account
     * ends and the device returns to the local workspace. Network errors leave the session alone,
     * so the app keeps working offline (contract §2).
     */
    private suspend fun refreshSession(): BearerTokens? {
        val holder = sessions ?: return null
        val client = oidc ?: return null
        val account = holder.account ?: return null
        return try {
            val tokens = client.refresh(account.refreshToken)
            val refresh = tokens.refreshToken ?: account.refreshToken
            holder.updateTokens(tokens.accessToken, refresh, tokens.idToken)
            BearerTokens(tokens.accessToken, refresh)
        } catch (e: TokenRefusedException) {
            holder.endAccount()
            null
        }
    }

    internal suspend fun HttpResponse.toApiException(): ApiException {
        val problem = runCatching { json.decodeFromString<Problem>(body<String>()) }.getOrNull()
        return ApiException(status.value, problem?.detail ?: problem?.title ?: status.description, problem?.title)
    }

    /** Requests of other components (sync, attachments) for one organisation. */
    internal fun HttpRequestBuilder.organisation(orgId: Uuid) {
        header(ORG_HEADER, orgId.toString())
    }
}

/** Platform HTTP engine (OkHttp on Android, Darwin on iOS later). No Play services. */
expect fun platformHttpEngine(): HttpClientEngine
