package app.simpletemplate.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

// Wire types of /v1/auth/discover, /v1/me and POST /v1/orgs (platform contract §2, §3).

@Serializable
data class DiscoverRequest(val email: String)

/** Which Zitadel organisation an email signs in to (a customer's verified domain, else Individuals). */
@Serializable
data class DiscoverResponse(
    val issuer: String,
    @SerialName("organisation_id") val organisationId: String,
)

@Serializable
data class UserDto(val id: Uuid, val email: String, val name: String)

@Serializable
data class OrganisationDto(
    val id: Uuid,
    @SerialName("display_name") val displayName: String,
    val timezone: String,
    val slug: String? = null,
    @SerialName("is_managed") val isManaged: Boolean = false,
)

/** One organisation the user may work in: MEMBER, or EXTERNAL (a participant invited in). */
@Serializable
data class MembershipDto(
    val organisation: OrganisationDto,
    val kind: String,
    val roles: List<String> = emptyList(),
)

@Serializable
data class MeResponse(
    val user: UserDto,
    val organisation: OrganisationDto? = null,
    val memberships: List<MembershipDto> = emptyList(),
)

/**
 * A new organisation with the caller as its Owner. [id] adopts the id this phone generated for
 * its local workspace ("adopt", docs/platform.md); the platform refuses an id already in use.
 */
@Serializable
data class CreateOrganisationRequest(
    val id: Uuid? = null,
    @SerialName("display_name") val displayName: String,
    val timezone: String,
)

/** RFC 9457 problem details, the server's error format. */
@Serializable
internal data class Problem(val title: String? = null, val detail: String? = null)

/**
 * The server answered with an error status. [detail] is the server's explanation, [title] its
 * machine-readable code (e.g. `reauthentication_required`).
 */
class ApiException(val status: Int, val detail: String, val title: String? = null) : Exception("HTTP $status: $detail")
