package app.instantrecorder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.instantrecorder.tasks.Repeat
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** "Repeat" with one round toggle per weekday (they fit any phone width) and quick presets. */
@Composable
fun RepeatDaysPicker(days: Int, onChange: (Int) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Repeat, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (days == Repeat.NONE) "Doesn't repeat" else "Repeats: ${Repeat.label(days)}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            DayOfWeek.entries.forEach { day ->
                val selected = Repeat.has(days, day)
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        .toggleable(value = selected, role = Role.Checkbox) { onChange(days xor Repeat.bit(day)) }
                        .semantics { contentDescription = day.getDisplayName(TextStyle.FULL, Locale.getDefault()) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        Row {
            TextButton(onClick = { onChange(Repeat.EVERY_DAY) }) { Text("Every day") }
            TextButton(onClick = { onChange(Repeat.WEEKDAYS) }) { Text("Weekdays") }
            if (days != Repeat.NONE) TextButton(onClick = { onChange(Repeat.NONE) }) { Text("None") }
        }
    }
}

/** Small "↻ Mon, Thu" label for task rows and cards. */
@Composable
fun RepeatLabel(days: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Filled.Repeat, contentDescription = "Repeats", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(Repeat.label(days), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
