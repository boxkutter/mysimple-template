package app.simpletemplate.shared.domain

import kotlin.uuid.Uuid

// EXAMPLE feature: a simple record the user creates, edits and deletes. It shows every layer of the
// pattern (table, repository with outbox, domain type, list and form screens, tests). Replace it
// with the app's own records, e.g. a risk assessment, then delete it.

enum class ItemStatus { OPEN, DONE }

data class Item(
    val id: Uuid,
    val title: String,
    val notes: String? = null,
    val status: ItemStatus = ItemStatus.OPEN,
)
