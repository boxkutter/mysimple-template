package app.simpletemplate.ui.items

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.simpletemplate.shared.data.ItemRepository
import app.simpletemplate.shared.domain.Item
import app.simpletemplate.shared.domain.ItemStatus
import app.simpletemplate.ui.AppScreen
import app.simpletemplate.ui.Badge
import app.simpletemplate.ui.EmptyState
import app.simpletemplate.ui.PrimaryActionButton
import app.simpletemplate.ui.theme.AppTheme
import kotlin.uuid.Uuid

// EXAMPLE feature: a list screen with one main action. Copy the shape for the app's own lists.

@Composable
fun ItemsScreen(repository: ItemRepository, onOpen: (Uuid) -> Unit, onNew: () -> Unit) {
    val list by remember(repository) { repository.observeItems() }.collectAsState(initial = null)
    AppScreen(
        title = "Items",
        floatingActionButton = { PrimaryActionButton("New item", Icons.Filled.Add, onNew) },
    ) { modifier ->
        val rows = list
        when {
            rows == null -> {}
            rows.isEmpty() -> EmptyState(
                "No items yet",
                "Items are an example feature of the template. Replace them with the app's own records.",
                modifier,
            )
            else -> LazyColumn(
                modifier.fillMaxSize(),
                // Room below the last row for the main action button
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows, key = { it.id.toString() }) { item -> ItemRow(item) { onOpen(item.id) } }
            }
        }
    }
}

@Composable
private fun ItemRow(item: Item, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                item.notes?.let {
                    Text(
                        it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Badge(item.status.label, if (item.status == ItemStatus.DONE) AppTheme.status.success else AppTheme.status.info)
        }
    }
}

val ItemStatus.label: String
    get() = when (this) {
        ItemStatus.OPEN -> "Open"
        ItemStatus.DONE -> "Done"
    }
