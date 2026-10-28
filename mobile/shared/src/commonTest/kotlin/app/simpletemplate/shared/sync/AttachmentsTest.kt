package app.simpletemplate.shared.sync

import app.simpletemplate.shared.ApiClient
import app.simpletemplate.shared.data.testStore
import app.simpletemplate.shared.sha256Hex
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class AttachmentsTest {
    private val store = testStore().also { it.createTestChildTable() }
    private val org = Uuid.generateV7()
    private val fileObject = Uuid.generateV7()
    private val bytes = ByteArray(300) { it.toByte() }
    private val files = MemoryFiles()
    private val requests = mutableListOf<String>()
    private val stored = mutableListOf<ByteArray>()

    private val platform = MockEngine { request ->
        requests += "${request.method.value} ${request.url.encodedPath} ${request.headers["X-Org-Id"]}"
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        when (request.url.encodedPath) {
            "/v1/files/uploads" -> respond(
                """{"file_object":{"id":"$fileObject","status":"PENDING"},"upload":{"upload_id":"u1","part_size":200,
                   "parts":[{"part_number":1,"url":"https://storage.test/p1"},{"part_number":2,"url":"https://storage.test/p2"}]}}""",
                HttpStatusCode.OK, json,
            )
            "/v1/files/uploads/$fileObject/complete" -> respond("""{"id":"$fileObject","status":"AVAILABLE"}""", HttpStatusCode.OK, json)
            "/v1/files/$fileObject/content" -> respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))
            else -> respondError(HttpStatusCode.NotFound)
        }
    }
    private val storage = HttpClient(MockEngine { request ->
        check(request.method == HttpMethod.Put)
        assertNull(request.headers[HttpHeaders.Authorization], "storage gets no platform credentials")
        assertNull(request.headers["X-Org-Id"])
        stored += request.body.toByteArray()
        respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "\"e${stored.size}\""))
    })
    private val registry = SyncRegistry(listOf(SyncEntity("item"), testPhoto))
    private val attachments = Attachments(store, ApiClient("https://api.test", platform), storage, files, registry)

    private fun photo(id: Uuid, fileName: String?, fileObjectId: Uuid?, sha256: String = sha256Hex(bytes)) =
        store.driver.execute(
            null,
            "INSERT INTO item_photo(id, owner_org_id, item_id, file_name, sha256, mime_type, original_name, file_object_id, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, 'image/jpeg', 'IMG_1.jpg', ?, 1, 1)",
            6,
        ) {
            bindString(0, id.toString()); bindString(1, org.toString()); bindString(2, Uuid.generateV7().toString())
            bindString(3, fileName); bindString(4, sha256); bindString(5, fileObjectId?.toString())
        }

    @Test
    fun aFileMadeOnThisPhoneIsUploadedInPartsOnce() = runTest {
        photo(Uuid.generateV7(), "a.jpg", null)
        files.write("a.jpg", bytes)

        val first = attachments.transfer(org)
        val second = attachments.transfer(org)

        assertEquals(1, first.uploaded)
        assertEquals(emptyList(), first.problems)
        assertContentEquals(bytes, stored[0] + stored[1])
        assertEquals(listOf("POST /v1/files/uploads $org", "POST /v1/files/uploads/$fileObject/complete $org"), requests)
        assertEquals(0, second.uploaded)
    }

    @Test
    fun aFileTheOrganisationHoldsIsDownloadedOnceAndNotSentBack() = runTest {
        val id = Uuid.generateV7()
        photo(id, null, fileObject)

        val report = attachments.transfer(org)

        assertEquals(1, report.downloaded)
        val name = "$fileObject.jpg"
        assertEquals(name, store.text("item_photo", "file_name", id))
        assertContentEquals(bytes, files.read(name))
        val again = attachments.transfer(org)
        assertEquals(0, again.uploaded, "a downloaded file is not sent back")
        assertEquals(0, again.downloaded)
        assertEquals(0L, store.db.syncQueries.pendingCount().executeAsOne(), "the local file name is never queued")
    }

    @Test
    fun aFileThatDoesNotMatchItsRecordIsNotKept() = runTest {
        val id = Uuid.generateV7()
        photo(id, null, fileObject, sha256 = "00".repeat(32))

        val report = attachments.transfer(org)

        assertEquals(0, report.downloaded)
        assertEquals(1, report.problems.size)
        assertNull(store.text("item_photo", "file_name", id))
        assertEquals(emptyMap(), files.files)
    }
}
