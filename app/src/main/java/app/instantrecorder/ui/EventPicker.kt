package app.instantrecorder.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.instantrecorder.tasks.Calendars
import app.instantrecorder.tasks.EventLink
import app.instantrecorder.tasks.EventRef
import app.instantrecorder.tasks.EventTiming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Pick an upcoming calendar event and how long before it to be reminded. Asks for calendar access if needed.
 * [initialQuery] pre-fills the search (e.g. what was said: "board meeting").
 */
@Composable
fun EventPickerDialog(
    initialQuery: String,
    initialOffsetMinutes: Int,
    onDismiss: () -> Unit,
    onPick: (EventLink) -> Unit,
) {
    val context = LocalContext.current
    var permitted by remember { mutableStateOf(Calendars.hasPermission(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }
    var events by remember { mutableStateOf<List<EventRef>?>(null) }
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var offset by rememberSaveable { mutableIntStateOf(initialOffsetMinutes) }
    var picked by remember { mutableStateOf<EventRef?>(null) }

    LaunchedEffect(permitted) {
        if (permitted) {
            events = withContext(Dispatchers.IO) { Calendars.upcoming(context).filter { it.begin > System.currentTimeMillis() } }
        }
    }
    val shown = events.orEmpty().let { all ->
        if (query.isBlank()) all else all.filter { EventTiming.matchScore(query, it.title) > 0 }.sortedByDescending { EventTiming.matchScore(query, it.title) }
    }.take(60)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remind me before an event") },
        text = {
            if (!permitted) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Instant Recorder needs to read your calendar to find the event. It only reads it, on this phone.")
                    Button(onClick = { permission.launch(Manifest.permission.READ_CALENDAR) }) { Text("Allow calendar access") }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(EventTiming.PRESETS) { minutes ->
                            FilterChip(selected = offset == minutes, onClick = { offset = minutes }, label = { Text(EventTiming.describe(minutes)) })
                        }
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        placeholder = { Text("Search events") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    when {
                        events == null -> Text("Loading your calendar…")
                        shown.isEmpty() -> Text("No upcoming events match.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> LazyColumn(Modifier.heightIn(max = 320.dp)) {
                            items(shown, key = { "${it.eventId}-${it.begin}" }) { e ->
                                val selected = picked == e
                                Row(
                                    Modifier.fillMaxWidth().clickable { picked = e }.padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    CategoryDot(e.calendarColor)
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            e.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            fontWeight = if (selected) FontWeight.Bold else null,
                                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(whenText(e), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { picked?.let { onPick(EventLink(it, offset)) } }, enabled = picked != null) { Text("Set reminder") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val DATE_TIME = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
private val DATE = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

fun whenText(e: EventRef): String =
    if (e.allDay) Instant.ofEpochMilli(e.begin).atZone(ZoneOffset.UTC).toLocalDate().format(DATE)
    else Instant.ofEpochMilli(e.begin).atZone(ZoneId.systemDefault()).format(DATE_TIME)
