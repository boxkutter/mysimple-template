package app.simpletemplate.shared.auth

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import io.ktor.utils.io.errors.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

internal val testOidcSettings = OidcSettings(
    issuer = "https://auth.test",
    clientId = "client-1",
    redirectUri = "eu.mysimple.simpletemplate:/oidc/callback",
    postLogoutRedirectUri = "eu.mysimple.simpletemplate:/oidc/logout",
)

/**
 * A fake platform and Zitadel: discover, OIDC discovery, the token endpoint (code and refresh
 * grants, numbered tokens), /v1/me (which accepts only the latest access token) and POST /v1/orgs.
 */
internal class FakePlatform {
    var issued = 0
    var refreshStatus: HttpStatusCode = HttpStatusCode.OK
    var offline = false
    val requests = mutableListOf<String>()
    var lastNonce = ""
    var challenge = ""
    val userId = Uuid.generateV7()
    val orgA = Uuid.generateV7()
    val orgB = Uuid.generateV7()
    /** The account's organisations, by id and name. */
    val memberships = mutableListOf(orgA to "Acme Ltd", orgB to "Second Ltd")
    /** Ids POST /v1/orgs refuses as taken. */
    val takenIds = mutableSetOf<String>()

    val engine = MockEngine { request -> handle(request) }

    @OptIn(ExperimentalEncodingApi::class)
    private fun idToken(nonce: String) = "h." +
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode("""{"sub":"u","nonce":"$nonce"}""".encodeToByteArray()) + ".s"

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData) = run {
        val path = request.url.encodedPath
        requests += "${request.method.value} ${request.url.host}$path ${request.headers[HttpHeaders.Authorization] ?: "-"} ${request.headers["X-Org-Id"] ?: "-"}"
        if (offline) throw IOException("offline")
        when (path) {
            "/v1/auth/discover" -> json("""{"issuer":"https://auth.test","organisation_id":"zorg-1"}""")
            "/.well-known/openid-configuration" -> json(
                """{"issuer":"https://auth.test","authorization_endpoint":"https://auth.test/oauth/v2/authorize",""" +
                    """"token_endpoint":"https://auth.test/oauth/v2/token","end_session_endpoint":"https://auth.test/oidc/v1/end_session"}""",
            )
            "/oauth/v2/token" -> {
                val form = parseQueryString(request.body.toByteArray().decodeToString())
                when (form["grant_type"]) {
                    "authorization_code" ->
                        if (form["code"] != "good-code" || pkceChallenge(form["code_verifier"] ?: "") != challenge) {
                            respond("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest, jsonHeaders)
                        } else tokens()
                    "refresh_token" ->
                        if (refreshStatus == HttpStatusCode.OK) tokens()
                        else respond("""{"error":"invalid_grant"}""", refreshStatus, jsonHeaders)
                    else -> respondError(HttpStatusCode.BadRequest)
                }
            }
            "/v1/me" ->
                if (request.headers[HttpHeaders.Authorization] == "Bearer access-$issued") json(me())
                else respond(
                    """{"title":"unauthorized","detail":"Invalid or expired access token"}""", HttpStatusCode.Unauthorized,
                    headersOf(HttpHeaders.ContentType to listOf("application/problem+json"), HttpHeaders.WWWAuthenticate to listOf("Bearer")),
                )
            "/v1/orgs" -> {
                val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                val id = body["id"]?.jsonPrimitive?.content ?: Uuid.generateV7().toString()
                val name = body["display_name"]!!.jsonPrimitive.content
                if (id in takenIds || memberships.any { it.first.toString() == id }) {
                    respond("""{"title":"conflict","detail":"An organisation with this id already exists"}""", HttpStatusCode.Conflict, jsonHeaders)
                } else {
                    memberships += Uuid.parse(id) to name
                    respond("""{"id":"$id","display_name":"$name","timezone":"Europe/London"}""", HttpStatusCode.Created, jsonHeaders)
                }
            }
            else -> respondError(HttpStatusCode.NotFound)
        }
    }

    private fun me(): String {
        val orgs = memberships.joinToString(",") { (id, name) ->
            """{"organisation":{"id":"$id","display_name":"$name","timezone":"Europe/London"},"kind":"MEMBER","roles":["OWNER"]}"""
        }
        return """{"user":{"id":"$userId","email":"ada@example.com","name":"Ada"},"memberships":[$orgs]}"""
    }

    private fun MockRequestHandleScope.tokens() = run {
        issued++
        json("""{"access_token":"access-$issued","refresh_token":"refresh-$issued","id_token":"${idToken(lastNonce)}","expires_in":900}""")
    }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun MockRequestHandleScope.json(body: String) = respond(body, HttpStatusCode.OK, jsonHeaders)
}

/** Plays the system browser: checks the authorize request and comes back as Zitadel would. */
internal class FakeBrowser(
    private val platform: FakePlatform,
    var answer: (String, String) -> String = { redirect, state -> "$redirect?code=good-code&state=$state" },
) : OidcBrowser {
    val opened = mutableListOf<String>()
    override suspend fun authorize(url: String): String {
        opened += url
        val params = Url(url).parameters
        platform.lastNonce = params["nonce"]!!
        platform.challenge = params["code_challenge"]!!
        return answer(params["redirect_uri"]!!, params["state"]!!)
    }
}
