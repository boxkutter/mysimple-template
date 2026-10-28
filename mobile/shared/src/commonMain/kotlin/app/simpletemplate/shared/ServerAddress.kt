package app.simpletemplate.shared

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the platform's address is kept on the phone (the platform supplies it). */
interface ServerAddressStore {
    fun load(): SavedServerAddress?
    fun save(address: SavedServerAddress)
}

data class SavedServerAddress(val baseUrl: String, val issuer: String?)

/**
 * The platform's address: the API's base URL and its sign-in server (the OIDC issuer).
 *
 * Release builds use the build's address. In builds where it is [editable] (debug builds: a
 * development server whose LAN address changes), the auditor can change the API's address in the
 * app; the issuer is not typed but taken from the platform, which names it in
 * `POST /v1/auth/discover` at every sign-in ([issuerFound]).
 */
class ServerAddress(
    private val store: ServerAddressStore?,
    defaultBaseUrl: String,
    private val defaultIssuer: String,
    val editable: Boolean,
) {
    private val saved = if (editable) store?.load() else null
    private val _baseUrl = MutableStateFlow(saved?.baseUrl ?: defaultBaseUrl)
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()
    private val _issuer = MutableStateFlow(saved?.issuer ?: defaultIssuer)
    val issuer: StateFlow<String> = _issuer.asStateFlow()

    /** Uses [input] as the API's address from the next call; the issuer is found again at sign-in. */
    fun change(input: String): String {
        check(editable) { "The server address is fixed in this build" }
        val url = normaliseServerAddress(input) ?: throw IllegalArgumentException("Not a server address: $input")
        if (url != _baseUrl.value) {
            _baseUrl.value = url
            store?.save(SavedServerAddress(url, _issuer.value))
        }
        return url
    }

    /** The issuer the platform named (sign-in, or a check after the address changed). */
    fun issuerFound(issuer: String) {
        val clean = issuer.trim().trimEnd('/')
        if (clean.isEmpty() || clean == _issuer.value) return
        _issuer.value = clean
        if (editable) store?.save(SavedServerAddress(_baseUrl.value, clean))
    }
}

/**
 * `192.168.1.10:8081` → `http://192.168.1.10:8081`; keeps an explicit `http://` or `https://`,
 * drops a trailing slash. Null when it is not an address (no host, a space, another scheme).
 */
fun normaliseServerAddress(input: String): String? {
    val text = input.trim()
    if (text.isEmpty() || text.any { it.isWhitespace() }) return null
    val scheme = if ("://" in text) text.substringBefore("://").lowercase() else "http"
    if (scheme != "http" && scheme != "https") return null
    val rest = text.substringAfter("://").trimEnd('/')
    val host = rest.substringBefore('/').substringBefore(':')
    if (host.isEmpty()) return null
    return "$scheme://$rest"
}
