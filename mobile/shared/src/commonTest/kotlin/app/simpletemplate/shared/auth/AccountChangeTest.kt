package app.simpletemplate.shared.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountChangeTest {
    private val bo = DataOwner("bo-id", "Bo Nilsson", "bo@example.com")
    private val orgA = "org-a"
    private val orgB = "org-b"

    @Test
    fun theSameAccountKeepsEverything() {
        assertEquals(AccountChange.Same, accountChange(bo, "bo-id", setOf(orgA), mapOf(orgA to 3L, orgB to 1L)))
        // Nothing of anyone's on the phone
        assertEquals(AccountChange.Same, accountChange(bo, "ada-id", setOf(orgA), emptyMap()))
    }

    @Test
    fun anotherAccountRemovesEveryOrganisationWorkspace() {
        // Even the organisation both accounts belong to: its data on the phone is Bo's
        assertEquals(AccountChange.HandOver(bo, setOf(orgA, orgB)), accountChange(bo, "ada-id", setOf(orgA), mapOf(orgA to 0L, orgB to 0L)))
    }

    @Test
    fun unsentChangesAreADecisionFirst() {
        assertEquals(AccountChange.Unsent(bo, setOf(orgA, orgB), 4L), accountChange(bo, "ada-id", emptySet(), mapOf(orgA to 3L, orgB to 1L)))
    }

    @Test
    fun withNoOwnerRecordedTheAccountsOwnOrganisationsStay() {
        assertEquals(AccountChange.Same, accountChange(null, "ada-id", setOf(orgA), mapOf(orgA to 2L)))
        assertEquals(AccountChange.Unsent(null, setOf(orgB), 1L), accountChange(null, "ada-id", setOf(orgA), mapOf(orgA to 2L, orgB to 1L)))
    }

    @Test
    fun theWarningBeforeSignInNamesTheOtherAccountByEmail() {
        assertEquals(bo, unsentBeforeSignIn(bo, " ada@example.com ", mapOf(orgA to 1L)))
        assertNull(unsentBeforeSignIn(bo, "Bo@Example.com", mapOf(orgA to 1L)))
        assertNull(unsentBeforeSignIn(bo, "ada@example.com", mapOf(orgA to 0L)))
        assertNull(unsentBeforeSignIn(null, "ada@example.com", mapOf(orgA to 1L)))
        assertEquals(
            "Bo Nilsson's changes on this phone have not been sent. Sign in as Bo Nilsson again and sync first, or discard them.",
            unsentChangesMessage(bo),
        )
        assertEquals(
            "The previous account's changes on this phone have not been sent. Sign in as that account again and sync first, or discard them.",
            unsentChangesMessage(null),
        )
    }
}
