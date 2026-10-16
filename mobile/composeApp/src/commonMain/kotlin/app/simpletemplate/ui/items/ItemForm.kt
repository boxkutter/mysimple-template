package app.simpletemplate.ui.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import app.simpletemplate.shared.data.ItemRepository
import app.simpletemplate.shared.domain.Item
import app.simpletemplate.shared.domain.ItemStatus
import app.simpletemplate.ui.FieldKind
import app.simpletemplate.ui.FormField
import app.simpletemplate.ui.FormScreen
import app.simpletemplate.ui.SaveRefused
import app.simpletemplate.ui.lock.LocalAppLock
import app.simpletemplate.ui.lock.SCREEN_LOCK_NEEDED
import app.simpletemplate.ui.orNull
import kotlin.uuid.Uuid

// EXAMPLE feature: an edit form for a new or an existing record. Copy the shape for the app's own forms.

@Composable
fun ItemForm(items: ItemRepository, itemId: Uuid?, onClose: () -> Unit) {
    var loaded by remember { mutableStateOf(itemId == null) }
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(ItemStatus.OPEN) }
    LaunchedEffect(itemId) {
        if (itemId == null) return@LaunchedEffect
        items.item(itemId)?.let { title = it.title; notes = it.notes.orEmpty(); status = it.status }
        loaded = true
    }
    val lock = LocalAppLock.current

    FormScreen(
        title = if (itemId == null) "New item" else "Edit item",
        canSave = loaded && title.isNotBlank(),
        onSave = {
            // Stored information is kept behind the app lock, which needs the phone's screen lock
            if (lock?.canStoreSensitiveData == false) throw SaveRefused(SCREEN_LOCK_NEEDED)
            items.save(Item(itemId ?: Uuid.generateV7(), title.trim(), notes.orNull(), status))
        },
        onClose = onClose,
        deleteLabel = if (itemId != null) "Delete item" else null,
        onDelete = itemId?.let { id -> suspend { items.delete(id) } },
    ) {
        FormField("Title", title, { title = it }, required = true)
        FormField("Notes", notes, { notes = it }, FieldKind.MULTILINE)
        Text("Status", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ItemStatus.entries.forEach { s -> FilterChip(selected = status == s, onClick = { status = s }, label = { Text(s.label) }) }
        }
    }
}
