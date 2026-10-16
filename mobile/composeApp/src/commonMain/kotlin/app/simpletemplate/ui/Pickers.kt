package app.simpletemplate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

@Composable
fun ChoiceRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    radio: Boolean,
    badge: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (radio) RadioButton(selected = selected, onClick = onClick) else Checkbox(checked = selected, onCheckedChange = { onClick() })
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        badge?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
fun DateField(label: String, value: LocalDate?, onClear: (() -> Unit)? = null, onPick: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.weight(1f)) {
            Text("$label: ${value?.format() ?: "not set"}")
        }
        if (onClear != null && value != null) TextButton(onClick = onClear) { Text("Clear") }
    }
    if (!open) return
    // The picker works in UTC milliseconds.
    val state = rememberDatePickerState(initialSelectedDateMillis = value?.atStartOfDayIn(TimeZone.UTC)?.toEpochMilliseconds())
    DatePickerDialog(
        onDismissRequest = { open = false },
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date) }
                open = false
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
    ) { DatePicker(state) }
}

/** 24-hour time, typed in. */
@Composable
fun TimeField(label: String, value: LocalTime, modifier: Modifier = Modifier, onPick: (LocalTime) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier) { Text("$label ${value.hhmm()}") }
    if (!open) return
    val state = rememberTimePickerState(initialHour = value.hour, initialMinute = value.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(label) },
        text = { TimeInput(state) },
        confirmButton = {
            TextButton(onClick = { onPick(LocalTime(state.hour, state.minute)); open = false }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
    )
}

fun LocalTime.plusMinutes(minutes: Int): LocalTime =
    LocalTime.fromSecondOfDay((toSecondOfDay() + minutes * 60).coerceIn(0, 23 * 3600 + 59 * 60))

fun LocalTime.hhmm(): String = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

/**
 * IANA time zone picker with search. With [inheritLabel], "no override" (null) can be chosen,
 * e.g. "Same as head office (Europe/Stockholm)".
 */
@Composable
fun TimezoneField(label: String, value: String?, inheritLabel: String? = null, onChange: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text("$label: ${value?.zoneLabel() ?: inheritLabel ?: "not set"}")
    }
    if (!open) return
    var query by remember { mutableStateOf("") }
    val zones = remember { TimeZone.availableZoneIds.filter { '/' in it && !it.startsWith("Etc/") }.sorted() }
    val matches = zones.filter { query.isBlank() || it.contains(query.trim().replace(' ', '_'), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(label) },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("Search, e.g. Singapore") }, modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (inheritLabel != null && query.isBlank()) {
                        item { ChoiceRow(inheritLabel, selected = value == null, radio = true) { onChange(null); open = false } }
                    }
                    items(matches) { zone ->
                        ChoiceRow(zone.zoneLabel(), selected = zone == value, radio = true) { onChange(zone); open = false }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
    )
}

/** "Asia/Singapore" → "Asia / Singapore" */
fun String.zoneLabel(): String = replace("/", " / ").replace('_', ' ')

/** "16 Oct 2026": unambiguous in every locale. */
fun LocalDate.format(): String =
    "$day ${month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)} $year"
