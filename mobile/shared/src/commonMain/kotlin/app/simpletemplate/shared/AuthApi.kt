package app.simpletemplate.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

// Wire types of /v1/auth/* and /v1/me (the platform's identity module).

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    @SerialName("device_name") val deviceName: String? = null,
)

@Serializable
data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class UserDto(val id: Uuid, val email: String, val name: String)

@Serializable
data class OrganisationDto(
    val id: Uuid,
    @SerialName("display_name") val displayName: String,
    val timezone: String,
)

@Serializable
data class MeResponse(val user: UserDto, val organisation: OrganisationDto)

@Serializable
data class TokensResponse(
    val user: UserDto,
    val organisation: OrganisationDto,
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("refresh_token") val refreshToken: String,
)

/** RFC 9457 problem details, the server's error format. */
@Serializable
internal data class Problem(val title: String? = null, val detail: String? = null)

/** The server answered with an error status. [detail] is the server's explanation. */
class ApiException(val status: Int, val detail: String) : Exception("HTTP $status: $detail")
