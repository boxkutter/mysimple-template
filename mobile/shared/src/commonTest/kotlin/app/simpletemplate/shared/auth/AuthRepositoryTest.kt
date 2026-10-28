package app.simpletemplate.shared.auth

import app.simpletemplate.shared.ApiClient
import app.simpletemplate.shared.ApiException
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthRepositoryTest {
    private val platform = FakePlatform()
    private val store = InMemorySessionStore()
    private val sessions = SessionHolder(store)
    private val oidc = OidcClient(HttpClient(platform.engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }, testOidcSettings)
    private val api = ApiClient("https://api.test", platform.engine, sessions, oidc)
    private val auth = AuthRepository(api, sessions, oidc)
    private val browser = FakeBrowser(platform)

    @Test
    fun signInUsesPkceInTheBrowserAndOpensTheFirstOrganisation() = runTest {
        val result = auth.signIn(" ada@example.com ", browser)

        val url = Url(browser.opened.single())
        assertEquals("/oauth/v2/authorize", url.encodedPath)
        assertEquals("S256", url.parameters["code_challenge_method"])
        assertEquals("client-1", url.parameters["client_id"])
        assertEquals("eu.mysimple.simpletemplate:/oidc/callback", url.parameters["redirect_uri"])
        assertEquals("ada@example.com", url.parameters["login_hint"])
        assertTrue("urn:zitadel:iam:org:id:zorg-1" in url.parameters["scope"]!!)
        assertTrue("offline_access" in url.parameters["scope"]!!)
        assertEquals(2, result.workspaces.size)
        val session = sessions.current!!
        assertEquals(platform.orgA, session.organisationId)
        assertEquals("Acme Ltd", session.organisationName)
        assertEquals("access-1", session.accessToken)
        assertTrue(session.idToken.isNotEmpty())
        assertEquals(session, store.loadAccount())
        // Later requests carry the token and name the open organisation (contract §2).
        assertEquals("Ada", api.me().user.name)
        assertEquals("GET api.test/v1/me Bearer access-1 ${platform.orgA}", platform.requests.last())
        // Discover is sent without a token.
        assertTrue(platform.requests.first().startsWith("POST api.test/v1/auth/discover - "))
    }

    @Test
    fun aCallbackForAnotherAttemptIsRefused() = runTest {
        browser.answer = { redirect, _ -> "$redirect?code=good-code&state=forged" }
        val error = assertFailsWith<OidcException> { auth.signIn("ada@example.com", browser) }
        assertEquals("invalid_state", error.error)
        assertNull(sessions.current)
    }

    @Test
    fun anErrorFromTheSignInPageIsReported() = runTest {
        browser.answer = { redirect, state -> "$redirect?error=access_denied&error_description=Denied&state=$state" }
        val error = assertFailsWith<OidcException> { auth.signIn("ada@example.com", browser) }
        assertEquals("access_denied", error.error)
    }

    @Test
    fun switchingWorkspacesKeepsTheAccount() = runTest {
        val local = auth.startLocal("Ada", "Ada's own", "Europe/London")
        val result = auth.signIn("ada@example.com", browser)

        auth.open(result.workspaces[1])
        assertEquals(platform.orgB, sessions.current?.organisationId)
        auth.openLocal()
        assertEquals(local, sessions.current)
        assertEquals("access-1", auth.account?.accessToken) // still signed in
        auth.open(result.workspaces[0])
        assertEquals(platform.orgA, sessions.current?.organisationId)
    }

    @Test
    fun anAccountWithoutAnOrganisationKeepsTheLocalWorkspaceOpen() = runTest {
        val local = auth.startLocal("Ada", "", "Europe/London")
        platform.memberships.clear()
        val result = auth.signIn("ada@example.com", browser)
        assertTrue(result.workspaces.isEmpty())
        assertEquals(local, sessions.current)
        assertNotNull(auth.account)
    }

    @Test
    fun theAppStartsWithoutAnAccountAndSendsNoToken() = runTest {
        val local = auth.startLocal(" Ada Lovelace ", "", "Europe/London")
        assertEquals(true, local.isLocal)
        assertEquals("Ada Lovelace", local.organisationName) // working on her own
        runCatching { api.me() }
        assertEquals("GET api.test/v1/me - -", platform.requests.last())
    }

    @Test
    fun expiredAccessTokenIsRefreshedAtZitadelAndTheRotatedTokensAreKept() = runTest {
        auth.signIn("ada@example.com", browser)
        platform.issued++ // the platform now expects a newer access token than the app has

        assertEquals("Ada", api.me().user.name)

        assertEquals("refresh-3", sessions.current?.refreshToken)
        assertEquals("refresh-3", store.loadAccount()?.refreshToken)
        assertTrue(platform.requests.any { it.startsWith("POST auth.test/oauth/v2/token") })
    }

    @Test
    fun aRefusedRefreshEndsTheAccountAndReturnsToTheLocalWorkspace() = runTest {
        val local = auth.startLocal("Ada", "", "Europe/London")
        auth.signIn("ada@example.com", browser)
        platform.issued++
        platform.refreshStatus = HttpStatusCode.BadRequest

        assertFailsWith<ApiException> { api.me() }

        assertEquals(local, sessions.current)
        assertNull(store.loadAccount())
    }

    @Test
    fun beingOfflineKeepsTheSession() = runTest {
        auth.signIn("ada@example.com", browser)
        val session = sessions.current
        platform.offline = true
        assertFailsWith<IOException> { api.me() }
        assertEquals(session, sessions.current)
    }

    @Test
    fun signOutForgetsTheAccountAndReturnsToTheLocalWorkspace() = runTest {
        val local = auth.startLocal("Ada", "", "Europe/London")
        auth.signIn("ada@example.com", browser)
        auth.signOut()
        assertEquals(local, sessions.current)
        assertNull(store.loadAccount())
        // Signing out of the local workspace itself does nothing
        auth.signOut()
        assertEquals(local, sessions.current)
    }

    @Test
    fun theSessionSurvivesARestart() = runTest {
        auth.signIn("ada@example.com", browser)
        assertEquals(sessions.current, SessionHolder(store).current)
    }

    @Test
    fun aWorkspaceAcceptedOnTheWebAppearsAfterARefresh() = runTest {
        auth.signIn("ada@example.com", browser)
        val joined = kotlin.uuid.Uuid.generateV7()
        platform.memberships += joined to "Third plc"

        val workspaces = auth.refreshWorkspaces()

        assertEquals(3, workspaces.size)
        assertEquals(3, auth.account?.workspaces?.size)
        assertEquals(platform.orgA, sessions.current?.organisationId, "the open workspace stays open")
    }

    @Test
    fun aWipedWorkspaceLeavesTheSwitcherAndTheNextOneOpens() = runTest {
        auth.signIn("ada@example.com", browser)

        auth.workspaceRemoved(platform.orgA)

        assertEquals(platform.orgB, sessions.current?.organisationId)
        assertEquals(listOf(platform.orgB), auth.account?.workspaces?.map { it.organisationId })
    }

    @Test
    fun aWipedWorkspaceReturnsToTheLocalWorkspaceWhenThereIsOne() = runTest {
        val local = auth.startLocal("Ada", "", "Europe/London")
        auth.signIn("ada@example.com", browser)
        auth.workspaceRemoved(platform.orgA)
        assertEquals(local, sessions.current)
        assertNotNull(auth.account, "still signed in")
    }

    @Test
    fun signingInAsAnotherAccountRemovesThePreviousAccountsWorkspacesAfterAWarning() = runTest {
        val data = app.simpletemplate.shared.data.testStore()
        val accounts = app.simpletemplate.shared.sync.PhoneAccounts(data)
        val auth = AuthRepository(api, sessions, oidc, phoneAccounts = { accounts })
        val local = auth.startLocal("Ada", "Ada's own", "Europe/London")
        // Bo's organisation workspace on this phone, with a change not sent yet
        val bosOrg = kotlin.uuid.Uuid.generateV7().toString()
        accounts.setOwner(DataOwner(kotlin.uuid.Uuid.generateV7().toString(), "Bo", "bo@example.com"))
        data.db.syncQueries.saveCursor(bosOrg, "c1", 1)
        data.db.syncQueries.enqueue(kotlin.uuid.Uuid.generateV7().toString(), "item", kotlin.uuid.Uuid.generateV7().toString(), "CREATE", "{}", 0, 1, bosOrg)
        data.db.syncQueries.enqueue(kotlin.uuid.Uuid.generateV7().toString(), "item", kotlin.uuid.Uuid.generateV7().toString(), "CREATE", "{}", 0, 1, local.organisationId.toString())

        // Before the sign-in page: Bo is named
        assertEquals("Bo", auth.unsentChangesOfAnother("ada@example.com")?.name)
        assertNull(auth.unsentChangesOfAnother("BO@example.com"))
        // After it: nothing signed in until Ada decides
        val refused = assertFailsWith<UnsentChangesException> { auth.signIn("ada@example.com", browser) }
        assertEquals("Bo's changes on this phone have not been sent. Sign in as Bo again and sync first, or discard them.", refused.message)
        assertEquals(local, sessions.current)
        assertEquals(1L, data.db.syncQueries.pendingCountFor(bosOrg).executeAsOne())

        auth.signIn("ada@example.com", browser, discardUnsent = true)

        assertEquals(platform.orgA, sessions.current?.organisationId)
        assertEquals(0L, data.db.syncQueries.pendingCountFor(bosOrg).executeAsOne())
        assertNull(data.db.syncQueries.cursor(bosOrg).executeAsOneOrNull())
        // The local workspace stays, with its own changes
        assertEquals(1L, data.db.syncQueries.pendingCountFor(local.organisationId.toString()).executeAsOne())
        assertEquals(platform.userId.toString(), accounts.owner()?.userId)
        assertEquals("ada@example.com", accounts.owner()?.email)
    }

    @Test
    fun withNothingUnsentAnotherAccountTakesThePhoneWithoutAsking() = runTest {
        val data = app.simpletemplate.shared.data.testStore()
        val accounts = app.simpletemplate.shared.sync.PhoneAccounts(data)
        val auth = AuthRepository(api, sessions, oidc, phoneAccounts = { accounts })
        auth.signIn("ada@example.com", browser)
        // Ada's workspace on the phone, everything sent
        data.db.syncQueries.saveCursor(platform.orgA.toString(), "c1", 1)
        auth.signOut()
        // Ada again: nothing leaves
        auth.signIn("ada@example.com", browser)
        assertEquals("c1", data.db.syncQueries.cursor(platform.orgA.toString()).executeAsOneOrNull()?.cursor_token)

        auth.signOut()
        platform.userId = kotlin.uuid.Uuid.generateV7()
        auth.signIn("ada@example.com", browser)

        // Another account (even in the same organisation): Ada's data left the phone
        assertNull(data.db.syncQueries.cursor(platform.orgA.toString()).executeAsOneOrNull())
        assertEquals(platform.userId.toString(), accounts.owner()?.userId)
    }
}
