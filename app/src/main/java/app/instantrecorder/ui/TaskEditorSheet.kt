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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.instantrecorder.data.Category
import app.instantrecorder.data.TaskItem
import app.instantrecorder.tasks.Due

/**
 * Edits a saved task: title, due date, category and notes. Save sits in the header so the keyboard can't
 * cover it. Edits live in saveable state so they survive folding/unfolding; dismissing discards them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorSheet(
    task: TaskItem,
    categories: List<Category>,
    canOpenRecording: Boolean,
    onDismiss: () -> Unit,
    onSave: (title: String, notes: String?, due: Due?, categoryId: Long?) -> Unit,
    onDelete: () -> Unit,
    onOpenRecording: () -> Unit,
) {
    var title by rememberSaveable(task.id) { mutableStateOf(task.title) }
    var notes by rememberSaveable(task.id) { mutableStateOf(task.notes.orEmpty()) }
    var dueAt by rememberSaveable(task.id) { mutableStateOf(task.dueAt) }
    var dueHasTime by rememberSaveable(task.id) { mutableStateOf(task.dueHasTime) }
    var categoryId by rememberSaveable(task.id) { mutableStateOf(task.categoryId) }
    var pickingDue by remember { mutableStateOf(false) }
    val due = dueAt?.let { Due(it, dueHasTime) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Edit task", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    onClick = { onSave(title.trim(), notes.trim().ifEmpty { null }, due, categoryId) },
                    enabled = title.isNotBlank(),
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Save") }
            }
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Task") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = { pickingDue = true },
                    label = { Text(due?.label() ?: "Add date") },
                    leadingIcon = { Icon(Icons.Filled.Schedule, null) },
                )
                if (due != null) TextButton(onClick = { dueAt = null }) { Text("Remove date") }
            }
            if (categories.isNotEmpty()) CategoryPicker(categories, categoryId, onSelect = { categoryId = it })
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            if (canOpenRecording) {
                TextButton(onClick = onOpenRecording) {
                    Icon(Icons.Filled.Mic, null, Modifier.size(18.dp))
                    Text("Open recording", Modifier.padding(start = 8.dp))
                }
            }
            TextButton(
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Delete task") }
        }
    }
    if (pickingDue) {
        DuePickerDialog(
            initial = due,
            onDismiss = { pickingDue = false },
            onConfirm = {
                dueAt = it?.epochMillis
                dueHasTime = it?.hasTime ?: false
                pickingDue = false
            },
        )
    }
}
