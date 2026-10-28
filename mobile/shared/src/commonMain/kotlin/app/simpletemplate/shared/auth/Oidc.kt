package app.simpletemplate.shared.auth

import app.simpletemplate.shared.sha256Hex
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Sign-in with the platform's Zitadel (platform contract §2): authorization code + PKCE (S256)
 * in the system browser, a public client (no secret), refresh tokens for offline grace.
 *
 * Everything here is plain HTTP and string work so it is shared and unit-tested; only opening the
 * browser and receiving the redirect is platform code ([OidcBrowser]).
 */
data class OidcSettings(
    val issuer: String,
    val clientId: String,
    val redirectUri: String,
    val postLogoutRedirectUri: String,
)

/** Cryptographically secure random bytes (the platform's generator). */
expect fun secureRandomBytes(size: Int): ByteArray

@OptIn(ExperimentalEncodingApi::class)
private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

@OptIn(ExperimentalEncodingApi::class)
internal fun randomToken(bytes: Int = 32): String = base64Url.encode(secureRandomBytes(bytes))

/** RFC 7636: the S256 challenge of a code verifier. */
@OptIn(ExperimentalEncodingApi::class)
fun pkceChallenge(verifier: String): String {
    val hex = sha256Hex(verifier.encodeToByteArray())
    val digest = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    return base64Url.encode(digest)
}

/** One sign-in attempt: what must survive until the browser comes back. */
data class PendingSignIn(val state: String, val codeVerifier: String, val nonce: String, val url: String)

@Serializable
data class OidcDiscovery(
    val issuer: String,
    @SerialName("authorization_endpoint") val authorizationEndpoint: String,
    @SerialName("token_endpoint") val tokenEndpoint: String,
    @SerialName("end_session_endpoint") val endSessionEndpoint: String? = null,
)

@Serializable
data class OidcTokens(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("id_token") val idToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 0,
)

/** The browser came back with an error, or with something that is not ours. */
class OidcException(val error: String, message: String) : Exception(message)

/** The token endpoint refused (as opposed to being unreachable): the refresh token is spent. */
class TokenRefusedException(message: String) : Exception(message)

/**
 * The scopes of contract §2. [zitadelOrgId] comes from `POST /v1/auth/discover` and sends the
 * person to the right Zitadel organisation's login page.
 */
fun signInScopes(zitadelOrgId: String?): String = buildList {
    addAll(listOf("openid", "profile", "email", "offline_access", "urn:zitadel:iam:user:resourceowner"))
    if (!zitadelOrgId.isNullOrBlank()) add("urn:zitadel:iam:org:id:$zitadelOrgId")
}.joinToString(" ")

/**
 * Builds the authorize URL for a new attempt. [reauthenticate] asks for a fresh sign-in (contract
 * §2: approvals need one within five minutes).
 */
fun newSignIn(
    discovery: OidcDiscovery,
    settings: OidcSettings,
    zitadelOrgId: String?,
    loginHint: String?,
    reauthenticate: Boolean = false,
): PendingSignIn {
    val state = randomToken()
    val verifier = randomToken(48)
    val nonce = randomToken()
    val url = URLBuilder(discovery.authorizationEndpoint).apply {
        parameters.append("client_id", settings.clientId)
        parameters.append("redirect_uri", settings.redirectUri)
        parameters.append("response_type", "code")
        parameters.append("scope", signInScopes(zitadelOrgId))
        parameters.append("state", state)
        parameters.append("nonce", nonce)
        parameters.append("code_challenge", pkceChallenge(verifier))
        parameters.append("code_challenge_method", "S256")
        if (!loginHint.isNullOrBlank()) parameters.append("login_hint", loginHint)
        if (reauthenticate) {
            parameters.append("prompt", "login")
            parameters.append("max_age", "300")
        }
    }.buildString()
    return PendingSignIn(state, verifier, nonce, url)
}

/**
 * The authorization code from the redirect. Refuses a redirect for another URI or another
 * attempt (state mismatch: a forged or stale callback).
 */
fun authorizationCode(callback: String, pending: PendingSignIn, settings: OidcSettings): String {
    val url = Url(callback)
    val expected = Url(settings.redirectUri)
    if (url.protocol.name != expected.protocol.name || url.encodedPath != expected.encodedPath) {
        throw OidcException("invalid_redirect", "The sign-in came back to an unexpected address")
    }
    val params = url.parameters
    params["error"]?.let { throw OidcException(it, params["error_description"] ?: "Sign-in was not completed") }
    if (params["state"] != pending.state) throw OidcException("invalid_state", "The sign-in answer does not match this attempt")
    return params["code"] ?: throw OidcException("invalid_request", "The sign-in answer has no code")
}

/** The claims of an ID token we use (unverified: the server verifies every token it receives). */
@OptIn(ExperimentalEncodingApi::class)
fun idTokenClaim(idToken: String, name: String): String? {
    val payload = idToken.split('.').getOrNull(1) ?: return null
    val json = runCatching { base64Url.decode(payload).decodeToString() }.getOrNull() ?: return null
    val element = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(json) }.getOrNull() ?: return null
    val value = (element as? kotlinx.serialization.json.JsonObject)?.get(name) ?: return null
    return (value as? kotlinx.serialization.json.JsonPrimitive)?.content
}

/** HTTP side of OIDC: discovery, code exchange, refresh. */
class OidcClient(
    private val http: HttpClient,
    val settings: OidcSettings,
    /** The issuer in use: the platform names it at sign-in (`POST /v1/auth/discover`). */
    private val currentIssuer: () -> String = { settings.issuer },
) {
    private var cached: Pair<String, OidcDiscovery>? = null

    suspend fun discovery(): OidcDiscovery {
        val issuer = currentIssuer()
        cached?.takeIf { it.first == issuer }?.let { return it.second }
        return http.get(issuer.trimEnd('/') + "/.well-known/openid-configuration")
            .body<OidcDiscovery>().also { cached = issuer to it }
    }

    suspend fun exchange(code: String, pending: PendingSignIn): OidcTokens = token(
        Parameters.build {
            append("grant_type", "authorization_code")
            append("code", code)
            append("redirect_uri", settings.redirectUri)
            append("client_id", settings.clientId)
            append("code_verifier", pending.codeVerifier)
        },
    )

    /**
     * New tokens for a refresh token. [TokenRefusedException] when Zitadel refuses (expired or
     * revoked: sign out); network failures propagate unchanged (keep working offline).
     */
    suspend fun refresh(refreshToken: String): OidcTokens = token(
        Parameters.build {
            append("grant_type", "refresh_token")
            append("refresh_token", refreshToken)
            append("client_id", settings.clientId)
        },
    )

    private suspend fun token(form: Parameters): OidcTokens {
        val response = http.submitForm(discovery().tokenEndpoint, form)
        if (!response.status.isSuccess()) {
            if (response.status.value in 400..499) throw TokenRefusedException("Sign-in refused (${response.status.value})")
            throw IllegalStateException("Sign-in service unavailable (${response.status.value})")
        }
        return response.body()
    }

    /** Where to send the browser to end the Zitadel session too (optional for the app). */
    suspend fun endSessionUrl(idToken: String?): String? {
        val endpoint = discovery().endSessionEndpoint ?: return null
        return URLBuilder(endpoint).apply {
            if (idToken != null) parameters.append("id_token_hint", idToken)
            parameters.append("client_id", settings.clientId)
            parameters.append("post_logout_redirect_uri", settings.postLogoutRedirectUri)
        }.buildString()
    }
}

/** Opens [url] in the system browser (Custom Tabs on Android) and returns the redirect URI it lands on. */
interface OidcBrowser {
    suspend fun authorize(url: String): String
}
