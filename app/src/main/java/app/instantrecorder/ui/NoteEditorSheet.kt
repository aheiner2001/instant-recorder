package app.instantrecorder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.instantrecorder.data.Category
import app.instantrecorder.data.NoteItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorSheet(
    note: NoteItem,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onSaveText: (String) -> Unit,
    onSelectCategory: (Long?) -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onCalendar: () -> Unit,
) {
    var text by rememberSaveable(note.id) { mutableStateOf(note.text) }

    ModalBottomSheet(
        onDismissRequest = {
            if (text != note.text && text.isNotBlank()) {
                onSaveText(text)
            }
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header: Title, Pin toggle, Cancel, Save
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Edit note",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onTogglePin) {
                    Icon(
                        imageVector = if (note.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = if (note.pinned) "Unpin note" else "Pin note",
                        tint = if (note.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        onSaveText(text)
                        onDismiss()
                    },
                    enabled = text.isNotBlank(),
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    Text("Save")
                }
            }

            // Category picker chips to change or unassign category
            if (categories.isNotEmpty()) {
                CategoryPicker(
                    categories = categories,
                    selectedId = note.categoryId,
                    onSelect = onSelectCategory,
                )
            }

            // Text editing field with full note content
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Note content") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 12,
            )

            // Action buttons: Calendar & Share
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        if (text != note.text && text.isNotBlank()) onSaveText(text)
                        onCalendar()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Event, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Add to calendar", modifier = Modifier.padding(start = 8.dp))
                }
                OutlinedButton(
                    onClick = {
                        if (text != note.text && text.isNotBlank()) onSaveText(text)
                        onShare()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Share", modifier = Modifier.padding(start = 8.dp))
                }
            }

            // Explicit Delete button with error color
            TextButton(
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.align(Alignment.Start),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Delete note", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
