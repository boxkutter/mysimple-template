package app.simpletemplate.shared.sync

import app.cash.sqldelight.db.QueryResult
import app.simpletemplate.shared.ApiClient
import app.simpletemplate.shared.ORG_HEADER
import app.simpletemplate.shared.data.LocalStore
import app.simpletemplate.shared.sha256Hex
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

// Wire types of /v1/files (platform contract §5.2).

@Serializable
data class UploadRequest(
    val sha256: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("original_name") val originalName: String? = null,
)

@Serializable
data class FileObjectDto(val id: Uuid, val status: String = "", @SerialName("rejected_reason") val rejectedReason: String? = null)

@Serializable
data class PartUrlDto(@SerialName("part_number") val partNumber: Int, val url: String)

@Serializable
data class UploadDto(@SerialName("upload_id") val uploadId: String, @SerialName("part_size") val partSize: Long, val parts: List<PartUrlDto>)

@Serializable
data class UploadPlanDto(@SerialName("file_object") val fileObject: FileObjectDto, val upload: UploadDto? = null)

@Serializable
data class PartDoneDto(@SerialName("part_number") val partNumber: Int, val etag: String)

@Serializable
data class CompleteRequest(val parts: List<PartDoneDto>)

/** Files on this phone, by the name their record keeps in its [FileColumns.fileName] (platform code). */
interface FileStorage {
    fun read(fileName: String): ByteArray?
    fun write(fileName: String, bytes: ByteArray)
    fun delete(fileNames: List<String>)
}

data class TransferReport(val uploaded: Int = 0, val downloaded: Int = 0, val problems: List<String> = emptyList())

/**
 * Moves files between this phone and an organisation (contract §5.2), for every entity of the
 * [registry] that declares [FileColumns]. The record syncs on its own and carries the file's
 * SHA-256; the bytes go here: uploaded part by part to pre-signed URLs with [storage], a client
 * that sends no platform credentials (they would break the URLs' signatures), and downloaded for
 * records the platform holds a file for that this phone has no copy of. The platform verifies
 * the hash, scans and encrypts; linking a record to its `file_object` is the entity's
 * `SyncPolicy` on the platform.
 */
class Attachments(
    private val store: LocalStore,
    private val api: ApiClient,
    private val storage: HttpClient,
    private val files: FileStorage,
    private val registry: SyncRegistry,
) {
    private val queries = store.db.syncQueries
    private val writer = RecordWriter(store.driver, registry)

    private data class Row(val id: String, val fileName: String?, val sha256: String?, val mime: String?, val name: String?, val fileObjectId: String?)

    suspend fun transfer(orgId: Uuid): TransferReport {
        var report = TransferReport()
        for (entity in registry.entities) {
            val f = entity.files ?: continue
            val rows = withContext(store.dispatcher) { rows(entity, f, orgId) } ?: continue
            for (r in rows) {
                val fileName = r.fileName ?: continue
                val sha = r.sha256 ?: continue
                if (withContext(store.dispatcher) { queries.isUploaded(sha, orgId.toString()).executeAsOne() } > 0) continue
                val bytes = files.read(fileName) ?: continue
                report = try {
                    val id = upload(orgId, bytes, sha, r.mime ?: "application/octet-stream", r.name)
                    withContext(store.dispatcher) { queries.markUploaded(sha, orgId.toString(), id.toString(), store.nowMillis()) }
                    report.copy(uploaded = report.uploaded + 1)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    report.copy(problems = report.problems + "Upload not finished: ${e.message ?: e::class.simpleName}")
                }
            }
            for (r in rows) {
                if (r.fileName != null) continue
                val fileId = r.fileObjectId ?: continue
                report = try {
                    val bytes = download(orgId, fileId)
                    // The record's hash was taken when the file was made: a file that differs is not this record's.
                    check(r.sha256 == null || sha256Hex(bytes) == r.sha256) { "the file does not match its record" }
                    val name = "$fileId${extension(r.mime, r.name)}"
                    files.write(name, bytes)
                    withContext(store.dispatcher) {
                        store.db.transaction {
                            // The file name is this phone's own (local-only): nothing to send.
                            store.driver.execute(null, "UPDATE ${entity.table} SET ${f.fileName} = ? WHERE id = ?", 2) {
                                bindString(0, name); bindString(1, r.id)
                            }
                            r.sha256?.let { queries.markUploaded(it, orgId.toString(), fileId, store.nowMillis()) }
                        }
                    }
                    report.copy(downloaded = report.downloaded + 1)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    report.copy(problems = report.problems + "Download not finished: ${e.message ?: e::class.simpleName}")
                }
            }
        }
        return report
    }

    /**
     * Uploads [bytes] to the organisation and returns the `file_object` id. When the organisation
     * holds the same content already, nothing is sent (the platform deduplicates by hash).
     */
    suspend fun upload(orgId: Uuid, bytes: ByteArray, sha256: String, mime: String, name: String?): Uuid {
        val plan: UploadPlanDto = api.http.post("v1/files/uploads") {
            header(ORG_HEADER, orgId.toString())
            contentType(ContentType.Application.Json)
            setBody(UploadRequest(sha256, bytes.size.toLong(), mime, name))
        }.body()
        val upload = plan.upload ?: return plan.fileObject.id
        val done = upload.parts.sortedBy { it.partNumber }.map { part ->
            val from = ((part.partNumber - 1) * upload.partSize).toInt()
            val to = minOf(bytes.size, (part.partNumber * upload.partSize).toInt())
            val response = storage.put(part.url) { setBody(bytes.copyOfRange(from, to)) }
            check(response.status.isSuccess()) { "storage answered ${response.status.value}" }
            PartDoneDto(part.partNumber, response.headers[HttpHeaders.ETag] ?: error("no ETag from storage"))
        }
        val result: FileObjectDto = api.http.post("v1/files/uploads/${plan.fileObject.id}/complete") {
            header(ORG_HEADER, orgId.toString())
            contentType(ContentType.Application.Json)
            setBody(CompleteRequest(done))
        }.body()
        check(result.status != "REJECTED") { result.rejectedReason ?: "the platform refused the file" }
        return result.id
    }

    /** The plaintext of an AVAILABLE file of the organisation. */
    suspend fun download(orgId: Uuid, fileObjectId: String): ByteArray {
        val response = api.http.get("v1/files/$fileObjectId/content") { header(ORG_HEADER, orgId.toString()) }
        check(response.status.isSuccess()) { "the platform answered ${response.status.value}" }
        return response.readRawBytes()
    }

    /** The organisation's rows of [entity] with their file columns; null when its table lacks them. */
    private fun rows(entity: SyncEntity, f: FileColumns, orgId: Uuid): List<Row>? {
        val cols = writer.columnsOf(entity.table) ?: return null
        val wanted = listOf(f.fileName, f.sha256, f.mimeType, f.fileObjectId) + listOfNotNull(f.originalName)
        if (!cols.keys.containsAll(wanted) || "owner_org_id" !in cols) return null
        val live = if ("deleted_at" in cols) " AND deleted_at IS NULL" else ""
        val sql = "SELECT id, ${f.fileName}, ${f.sha256}, ${f.mimeType}, ${f.originalName ?: "NULL"}, ${f.fileObjectId} " +
            "FROM ${entity.table} WHERE owner_org_id = ?$live"
        return store.driver.executeQuery(null, sql, { c ->
            val out = mutableListOf<Row>()
            while (c.next().value) out += Row(c.getString(0)!!, c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5))
            QueryResult.Value(out.toList())
        }, 1) { bindString(0, orgId.toString()) }.value
    }

    private fun extension(mime: String?, name: String?): String {
        name?.substringAfterLast('.', "")?.takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) }?.let { return ".$it" }
        return when (mime) {
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "audio/mp4", "audio/m4a" -> ".m4a"
            "video/mp4" -> ".mp4"
            "application/pdf" -> ".pdf"
            else -> ""
        }
    }
}
