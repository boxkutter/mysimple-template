package app.simpletemplate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * True while what is shown can be looked at but not changed (e.g. a record that has been signed
 * off). Building blocks follow it: section card actions and form saving are hidden.
 */
val LocalReadOnly = staticCompositionLocalOf { false }

/** Screen with a back arrow, a title and optional actions in the top bar. */
@Composable
fun AppScreen(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    val barColor = MaterialTheme.colorScheme.primary
    val onBarColor = MaterialTheme.colorScheme.onPrimary
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                // Navy bar: where you are is always clear at a glance
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = barColor,
                    titleContentColor = onBarColor,
                    navigationIconContentColor = onBarColor,
                    actionIconContentColor = onBarColor,
                ),
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    // The bar's colour for its icons and text buttons only (via LocalContentColor, not a
                    // text style), so menus opened from the bar keep their own readable colours
                    CompositionLocalProvider(LocalContentColor provides onBarColor) { actions() }
                },
            )
        },
        floatingActionButton = floatingActionButton,
    ) { padding -> content(Modifier.padding(padding)) }
}

/** Thrown by a form's save to stay open and show [message] (e.g. a change that does not fit). */
class SaveRefused(message: String) : Exception(message)

/**
 * Edit form: fields in a scrolling column, Save in the top bar, Delete at the bottom for
 * existing records. [onSave] and [onDelete] run in a coroutine, then [afterSave] / [afterDelete]
 * (by default: close the form).
 */
@Composable
fun FormScreen(
    title: String,
    canSave: Boolean,
    onSave: suspend () -> Unit,
    onClose: () -> Unit,
    deleteLabel: String? = null,
    onDelete: (suspend () -> Unit)? = null,
    afterSave: () -> Unit = onClose,
    afterDelete: () -> Unit = onClose,
    fields: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf<String?>(null) }
    val readOnly = LocalReadOnly.current

    fun run(action: suspend () -> Unit, then: () -> Unit) {
        busy = true
        refused = null
        scope.launch {
            try {
                action()
                then()
            } catch (e: SaveRefused) {
                refused = e.message
            } finally {
                busy = false
            }
        }
    }

    AppScreen(
        title = title,
        onBack = onClose,
        actions = {
            val onBar = LocalContentColor.current
            if (!readOnly) TextButton(
                onClick = { run(onSave, afterSave) }, enabled = canSave && !busy,
                colors = ButtonDefaults.textButtonColors(contentColor = onBar, disabledContentColor = onBar.copy(alpha = 0.5f)),
            ) { Text("Save", style = MaterialTheme.typography.labelLarge) }
        },
    ) { modifier ->
        Column(
            modifier = modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            refused?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (readOnly) Text("This can no longer be changed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            fields()
            if (onDelete != null && deleteLabel != null && !readOnly) {
                OutlinedButton(onClick = { confirmDelete = true }, enabled = !busy, modifier = Modifier.padding(top = 16.dp)) {
                    Text(deleteLabel, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmDelete && onDelete != null && deleteLabel != null) {
        ConfirmDeleteDialog(
            title = "$deleteLabel?",
            onConfirm = { confirmDelete = false; run(onDelete, afterDelete) },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
fun ConfirmDeleteDialog(
    title: String,
    text: String = "It is removed from this device and, with an account, from the server on the next sync.",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

enum class FieldKind { TEXT, NAME, EMAIL, PHONE, MULTILINE, NUMBER }

@Composable
fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    kind: FieldKind = FieldKind.TEXT,
    required: Boolean = false,
    supportingText: String? = null,
) {
    val keyboard = when (kind) {
        FieldKind.EMAIL -> KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next)
        FieldKind.PHONE -> KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next)
        FieldKind.NUMBER -> KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
        FieldKind.MULTILINE -> KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
        FieldKind.NAME -> KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next)
        FieldKind.TEXT -> KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next)
    }
    OutlinedTextField(
        value = value,
        onValueChange = { if (kind != FieldKind.NUMBER || it.all(Char::isDigit)) onValueChange(it.take(if (kind == FieldKind.NUMBER) 7 else Int.MAX_VALUE)) },
        label = { Text(if (required) "$label *" else label) },
        singleLine = kind != FieldKind.MULTILINE,
        minLines = if (kind == FieldKind.MULTILINE) 3 else 1,
        keyboardOptions = keyboard,
        supportingText = supportingText?.let { { Text(it) } },
        enabled = !LocalReadOnly.current,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Blank input is stored as null. */
fun String.orNull(): String? = trim().ifEmpty { null }
