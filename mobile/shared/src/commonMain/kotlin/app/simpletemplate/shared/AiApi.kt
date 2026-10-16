package app.simpletemplate.shared

import kotlinx.serialization.Serializable

// Wire types of POST /v1/ai/draft: an AI suggestion for one piece of text.

@Serializable
data class AiDraftRequest(
    /** What to write. The server keeps the list of tasks (docs/platform.md, "AI drafting"). */
    val task: String,
    /** The facts to write from (plain text), never private notes. */
    val context: String,
    /** The current text, to improve rather than replace, if any. */
    val current: String? = null,
)

@Serializable
data class AiDraftResponse(
    /** The suggestion, or null when AI is not available ([reason] says why). */
    val text: String? = null,
    val model: String? = null,
    val reason: String? = null,
)
