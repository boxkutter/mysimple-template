package app.simpletemplate.shared.sync

import app.simpletemplate.shared.ApiClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

// Wire types of POST /v1/sync and /v1/sync/wipe-ack (platform contract §5).

@Serializable
data class SyncOpDto(
    @SerialName("op_id") val opId: String,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: String,
    @SerialName("op_type") val opType: String,
    val payload: JsonObject,
    @SerialName("base_version") val baseVersion: Long,
)

@Serializable
data class SyncRequest(
    val cursor: String? = null,
    val ops: List<SyncOpDto> = emptyList(),
    @SerialName("pull_limit") val pullLimit: Int = 200,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
)

@Serializable
data class OpResultDto(
    @SerialName("op_id") val opId: String,
    val status: String,
    @SerialName("server_version") val serverVersion: Long? = null,
    @SerialName("server_record") val serverRecord: JsonObject? = null,
    val reason: String? = null,
)

@Serializable
data class ChangeDto(
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: String,
    val version: Long,
    val op: String,
    val deleted: Boolean,
    val record: JsonObject? = null,
)

@Serializable
data class SyncResponse(
    val accepted: List<String> = emptyList(),
    val results: List<OpResultDto> = emptyList(),
    val changes: List<ChangeDto> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    val wipe: List<String> = emptyList(),
    val cursor: String,
)

@Serializable
data class WipeAck(@SerialName("organisation_ids") val organisationIds: List<String>)

/** Names this installation on every sync call (contract §5). */
const val DEVICE_HEADER = "X-Device-Id"

suspend fun ApiClient.sync(orgId: Uuid, deviceId: Uuid, request: SyncRequest): SyncResponse =
    http.post("v1/sync") {
        organisation(orgId)
        header(DEVICE_HEADER, deviceId.toString())
        contentType(ContentType.Application.Json)
        setBody(request)
        timeout { requestTimeoutMillis = 120_000 }
    }.body()

suspend fun ApiClient.wipeAck(orgId: Uuid, deviceId: Uuid, organisationIds: List<String>) {
    http.post("v1/sync/wipe-ack") {
        organisation(orgId)
        header(DEVICE_HEADER, deviceId.toString())
        contentType(ContentType.Application.Json)
        setBody(WipeAck(organisationIds))
    }
}
