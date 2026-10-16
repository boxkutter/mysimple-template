package app.simpletemplate.shared.data

import app.simpletemplate.shared.AiDraftRequest
import app.simpletemplate.shared.AiDraftResponse
import app.simpletemplate.shared.ApiClient
import app.simpletemplate.shared.ApiException

/**
 * AI drafting through the platform's server: it needs an account and a connection, and the server
 * must have AI set up and allowed for the organisation. A draft is only a suggestion; the user
 * reviews and edits it. The tasks the server knows are listed in docs/platform.md.
 */
class AiDrafting(private val api: ApiClient, private val local: Boolean = false) {
    suspend fun draft(task: String, context: String, current: String? = null): AiDraftResponse =
        // Without an account nothing leaves the phone
        if (local) AiDraftResponse(reason = "AI drafting needs an account. Write it yourself, or sign in on the Status page.")
        else try {
            api.draftWithAi(AiDraftRequest(task, context, current?.takeIf { it.isNotBlank() }))
        } catch (e: ApiException) {
            AiDraftResponse(reason = if (e.status == 404) "AI drafting is not available on the server yet." else e.detail)
        } catch (e: Exception) {
            AiDraftResponse(reason = "AI drafting needs a connection to the server.")
        }
}
