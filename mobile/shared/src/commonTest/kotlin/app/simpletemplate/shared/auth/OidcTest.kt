package app.simpletemplate.shared.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class OidcTest {
    private val settings = OidcSettings("https://auth.test", "c", "eu.mysimple.simpletemplate:/oidc/callback", "eu.mysimple.simpletemplate:/oidc/logout")
    private val discovery = OidcDiscovery("https://auth.test", "https://auth.test/oauth/v2/authorize", "https://auth.test/oauth/v2/token")

    @Test
    fun theChallengeIsRfc7636S256() {
        // RFC 7636 appendix B
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test
    fun everyAttemptHasItsOwnStateVerifierAndNonce() {
        val a = newSignIn(discovery, settings, "z", null)
        val b = newSignIn(discovery, settings, "z", null)
        assertNotEquals(a.state, b.state)
        assertNotEquals(a.codeVerifier, b.codeVerifier)
        assertEquals(64, a.codeVerifier.length) // 48 random bytes, base64url: within RFC 7636's 43-128
    }

    @Test
    fun reauthenticationAsksForAFreshSignIn() {
        val url = newSignIn(discovery, settings, "z", "ada@example.com", reauthenticate = true).url
        assertEquals(true, "prompt=login" in url && "max_age=300" in url)
    }

    @Test
    fun theCallbackMustComeBackToThisAppForThisAttempt() {
        val pending = newSignIn(discovery, settings, null, null)
        assertEquals("abc", authorizationCode("eu.mysimple.simpletemplate:/oidc/callback?code=abc&state=${pending.state}", pending, settings))
        assertFailsWith<OidcException> { authorizationCode("eu.mysimple.simpletemplate:/oidc/callback?code=abc&state=other", pending, settings) }
        assertFailsWith<OidcException> { authorizationCode("eu.mysimple.otherapp:/oidc/callback?code=abc&state=${pending.state}", pending, settings) }
    }
}
