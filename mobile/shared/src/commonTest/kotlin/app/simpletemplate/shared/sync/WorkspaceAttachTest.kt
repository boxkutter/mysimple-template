package app.simpletemplate.shared.sync

import app.simpletemplate.shared.ApiClient
import app.simpletemplate.shared.ApiException
import app.simpletemplate.shared.auth.AuthRepository
import app.simpletemplate.shared.auth.FakeBrowser
import app.simpletemplate.shared.auth.FakePlatform
import app.simpletemplate.shared.auth.InMemorySessionStore
import app.simpletemplate.shared.auth.OidcClient
import app.simpletemplate.shared.auth.SessionHolder
import app.simpletemplate.shared.auth.testOidcSettings
import app.simpletemplate.shared.data.ItemRepository
import app.simpletemplate.shared.data.LockSettings
import app.simpletemplate.shared.data.UserSettings
import app.simpletemplate.shared.data.fields
import app.simpletemplate.shared.data.outboxOps
import app.simpletemplate.shared.data.testStore
import app.simpletemplate.shared.domain.Item
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class WorkspaceAttachTest {
    private val platform = FakePlatform()
    private val sessionStore = InMemorySessionStore()
    private val sessions = SessionHolder(sessionStore)
    private val oidc = OidcClient(HttpClient(platform.engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }, testOidcSettings)
    private val api = ApiClient("https://api.test", platform.engine, sessions, oidc)
    private val auth = AuthRepository(api, sessions, oidc)
    private val store = testStore()
    private val attach = WorkspaceAttach(store, api, sessions, auth)

    /** The local workspace with an item made in it, then a sign-in (the account opens its first organisation). */
    private suspend fun localWorkspaceWithAnItem(): Pair<app.simpletemplate.shared.auth.Session, Item> {
        val local = auth.startLocal("Ada", "Ada's practice", "Europe/London")
        val item = Item(Uuid.generateV7(), "Check fire exits")
        ItemRepository(store, local.tenant).save(item)
        UserSettings(store, local.userId).setLockSettings(LockSettings(appLockOn = true, appLockMinutes = 30))
        // A local-only column naming the user, as an app's own table might have
        store.driver.execute(null, "CREATE TABLE note (id TEXT PRIMARY KEY, owner_org_id TEXT, author_user_id TEXT)", 0)
        store.driver.execute(null, "INSERT INTO note VALUES ('n1', '${local.organisationId}', '${local.userId}')", 0)
        auth.signIn("ada@example.com", FakeBrowser(platform))
        return local to item
    }

    @Test
    fun adoptingCreatesAnOrganisationWithTheLocalIdAndKeepsTheRecordsAndTheirChanges() = runTest {
        val (local, item) = localWorkspaceWithAnItem()
        val opsBefore = store.outboxOps().map { it.op_id }

        val report = attach.adopt()

        val created = platform.requests.single { it.startsWith("POST api.test/v1/orgs ") }
        assertTrue("Bearer access-1" in created)
        assertEquals(local.organisationId, report.organisation.organisationId)
        assertEquals("Ada's practice", report.organisation.name)
        assertEquals(1, report.changesToSend)
        // The records keep their organisation; the changes are now that organisation's to send
        assertEquals(item.title, ItemRepository(store, local.tenant).observeItems().first().single().title)
        assertEquals(opsBefore, store.outboxOps().map { it.op_id })
        assertEquals(1L, store.db.syncQueries.pendingCountFor(local.organisationId.toString()).executeAsOne())
        // The local user is the platform user now
        assertEquals(platform.userId.toString(), noteAuthor())
        assertEquals(LockSettings(true, 30), UserSettings(store, platform.userId).observeLockSettings().first())
        // The organisation workspace is open and the local workspace is gone
        val session = sessions.current!!
        assertEquals(local.organisationId, session.organisationId)
        assertTrue(!session.isLocal)
        assertNull(auth.local)
        assertTrue(auth.account!!.workspaces.any { it.organisationId == local.organisationId })
        assertEquals(listOf("user"), store.db.syncQueries.remaps().executeAsList().map { it.kind })
    }

    @Test
    fun remappingMovesTheRecordsAndTheirQueuedChangesIntoAnExistingOrganisation() = runTest {
        val (local, item) = localWorkspaceWithAnItem()
        val target = auth.account!!.workspaces.single { it.organisationId == platform.orgB }

        val report = attach.remap(target)

        assertEquals(platform.orgB, report.organisation.organisationId)
        assertEquals(2, report.records) // the item and the note
        assertEquals(emptyList(), ItemRepository(store, local.tenant).observeItems().first())
        assertEquals(listOf(item.id), ItemRepository(store, app.simpletemplate.shared.data.Tenant(target.organisationId)).observeItems().first().map { it.id })
        val op = store.outboxOps().single()
        assertEquals(platform.orgB.toString(), op.owner_org_id)
        assertEquals(platform.orgB.toString(), op.fields["owner_org_id"]?.jsonPrimitive?.content)
        assertEquals(0L, store.db.syncQueries.pendingCountFor(local.organisationId.toString()).executeAsOne())
        assertEquals(platform.orgB, sessions.current?.organisationId)
        assertNull(auth.local)
        assertEquals(platform.userId.toString(), noteAuthor())
        assertEquals(platform.orgB.toString(), noteOrg())
        val remaps = store.db.syncQueries.remaps().executeAsList().associate { it.kind to (it.local_id to it.platform_id) }
        assertEquals(local.organisationId.toString() to platform.orgB.toString(), remaps["organisation"])
        assertEquals(local.userId.toString() to platform.userId.toString(), remaps["user"])
    }

    @Test
    fun aLocalOrganisationRecordIsNotSentTheProfileIsThePlatforms() = runTest {
        val (local, _) = localWorkspaceWithAnItem()
        store.enqueue(local.organisationId, "organisation", local.organisationId, buildJsonObject { put("display_name", "Ada's practice") })

        attach.adopt()

        assertEquals(listOf("item"), store.outboxOps().map { it.entity_type })
    }

    @Test
    fun anAdoptionThatFinishedOnThePlatformButNotHereCarriesOn() = runTest {
        val (local, _) = localWorkspaceWithAnItem()
        platform.memberships += local.organisationId to "Ada's practice" // created by an earlier attempt

        val report = attach.adopt()

        assertEquals(local.organisationId, report.organisation.organisationId)
        assertEquals(local.organisationId, sessions.current?.organisationId)
    }

    @Test
    fun anIdTakenBySomeoneElseIsRefusedAndNothingChanges() = runTest {
        val (local, _) = localWorkspaceWithAnItem()
        platform.takenIds += local.organisationId.toString()
        val open = sessions.current

        assertFailsWith<ApiException> { attach.adopt() }

        assertEquals(local, auth.local)
        assertEquals(open, sessions.current)
        assertEquals(local.organisationId.toString(), store.outboxOps().single().owner_org_id)
    }

    private fun noteAuthor(): String? = store.driver.executeQuery(null, "SELECT author_user_id FROM note", { c ->
        app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0) else null)
    }, 0).value

    private fun noteOrg(): String? = store.driver.executeQuery(null, "SELECT owner_org_id FROM note", { c ->
        app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0) else null)
    }, 0).value
}
