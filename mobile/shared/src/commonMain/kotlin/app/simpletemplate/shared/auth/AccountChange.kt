package app.simpletemplate.shared.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The account the organisation data on this phone belongs to: the last account that signed in.
 * Kept with the data (not with the session), so it outlives a sign-out.
 */
@Serializable
data class DataOwner(
    @SerialName("user_id") val userId: String,
    val name: String,
    val email: String,
)

/**
 * What signing in as an account means for the organisation data on this phone. Another account's
 * organisation workspaces leave the phone (the local workspace stays); the platform keeps that
 * account's records, which come back when it signs in again. Changes it has not sent yet would
 * be lost, so they are a decision for the person signing in ([Unsent]).
 */
sealed interface AccountChange {
    /** The same account (or nothing of another's on the phone): nothing leaves. */
    data object Same : AccountChange

    /** Another account's workspaces leave the phone; nothing of it is unsent. */
    data class HandOver(val previous: DataOwner?, val purge: Set<String>) : AccountChange

    /** As [HandOver], but [changes] of the previous account's changes have not been sent. */
    data class Unsent(val previous: DataOwner?, val purge: Set<String>, val changes: Long) : AccountChange
}

/**
 * Decides [AccountChange] for an account signing in. [phone]: the organisation workspaces on
 * this phone (organisation id) with the changes of each not accepted by the platform yet
 * (queued, refused or in conflict). [incomingOrgs]: the organisations the account may open.
 *
 * When the owner is not known (data from before this was recorded), the data of organisations
 * the account may open is taken as its own, and only the others leave.
 */
fun accountChange(owner: DataOwner?, incomingUserId: String, incomingOrgs: Set<String>, phone: Map<String, Long>): AccountChange {
    if (owner != null && owner.userId == incomingUserId) return AccountChange.Same
    val purge = if (owner != null) phone.keys else phone.keys - incomingOrgs
    if (purge.isEmpty()) return AccountChange.Same
    val unsent = purge.sumOf { phone[it] ?: 0L }
    return if (unsent > 0) AccountChange.Unsent(owner, purge, unsent) else AccountChange.HandOver(owner, purge)
}

/**
 * Before the sign-in page opens: the account whose unsent changes signing in with [email] would
 * discard, or null. Only an account known to differ by its email is named here; the decision
 * after sign-in ([accountChange], by user id) is the one that holds.
 */
fun unsentBeforeSignIn(owner: DataOwner?, email: String, phone: Map<String, Long>): DataOwner? {
    if (owner == null || owner.email.isBlank() || owner.email.equals(email.trim(), ignoreCase = true)) return null
    return owner.takeIf { phone.values.sum() > 0 }
}

/** The warning before signing in as someone else. */
fun unsentChangesMessage(previous: DataOwner?): String {
    val name = previous?.name?.takeIf { it.isNotBlank() }
    return if (name != null) {
        "$name's changes on this phone have not been sent. Sign in as $name again and sync first, or discard them."
    } else {
        "The previous account's changes on this phone have not been sent. Sign in as that account again and sync first, or discard them."
    }
}

/** Signing in would discard another account's unsent changes: the person decides first. */
class UnsentChangesException(val previous: DataOwner?, val changes: Long) : Exception(unsentChangesMessage(previous))
