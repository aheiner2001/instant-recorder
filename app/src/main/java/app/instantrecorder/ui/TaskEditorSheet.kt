package app.instantrecorder.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.instantrecorder.data.Category
import app.instantrecorder.data.TaskItem
import app.instantrecorder.tasks.Due
import app.instantrecorder.tasks.EventLink
import app.instantrecorder.tasks.EventTiming
import app.instantrecorder.tasks.eventLink
import app.instantrecorder.tasks.Repeat
import java.time.Instant
import java.time.ZonedDateTime
import app.instantrecorder.tasks.TaskExtractor
import app.instantrecorder.tasks.TaskIntents
import app.instantrecorder.tasks.rule

/**
 * Edits a saved task, or creates one when [isNew]: title, due date, category, repeat and notes. Save sits in the header so the keyboard can't
 * cover it. Edits live in saveable state so they survive folding/unfolding; dismissing discards them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorSheet(
    task: TaskItem,
    categories: List<Category>,
    isNew: Boolean,
    canOpenRecording: Boolean,
    onDismiss: () -> Unit,
    onSave: (title: String, notes: String?, due: Due?, categoryId: Long?, repeatDays: Int, link: EventLink?) -> Unit,
    onDelete: () -> Unit,
    onOpenRecording: () -> Unit,
) {
    var title by rememberSaveable(task.id) { mutableStateOf(task.title) }
    var notes by rememberSaveable(task.id) { mutableStateOf(task.notes.orEmpty()) }
    var dueAt by rememberSaveable(task.id) { mutableStateOf(task.dueAt) }
    var dueHasTime by rememberSaveable(task.id) { mutableStateOf(task.dueHasTime) }
    var categoryId by rememberSaveable(task.id) { mutableStateOf(task.categoryId) }
    var repeatDays by rememberSaveable(task.id) { mutableIntStateOf(task.repeatDays) }
    // Not saveable (EventRef isn't Parcelable); a fold mid-edit keeps the saved link, which is fine.
    var link by remember(task.id) { mutableStateOf(task.eventLink) }
    var pickingEvent by remember { mutableStateOf(false) }
    // Choosing repeat days moves the due date to the first matching day (keeping its time), if it isn't one.
    val onRepeatChange = { days: Int ->
        repeatDays = days
        if (days != Repeat.NONE) link = null
        if (days != Repeat.NONE) {
            val now = ZonedDateTime.now()
            val current = dueAt?.let { Instant.ofEpochMilli(it).atZone(now.zone) }
            if (current == null || !Repeat.has(days, current.dayOfWeek)) {
                val first = Repeat.first(days, now, current?.takeIf { dueHasTime }?.toLocalTime())
                dueAt = first.epochMillis
                dueHasTime = first.hasTime
            }
        }
    }
    var pickingDue by remember { mutableStateOf(false) }
    val due = dueAt?.let { Due(it, dueHasTime) }
    val titleFocus = remember { FocusRequester() }
    val context = LocalContext.current
    val dictation = rememberDictation()
    val rules = remember(categories) { categories.map { it.rule() } }
    // "Call the dentist tomorrow at 3 pm" → title, due date and (spoken or keyword) category.
    val applyDictation: (String) -> Unit = { text ->
        val parsed = TaskExtractor.extract(text, categories = rules)
        title = (parsed.firstOrNull { it.actionable } ?: parsed.firstOrNull())?.title ?: text.trim()
        parsed.firstNotNullOfOrNull { it.due }?.let {
            dueAt = it.epochMillis
            dueHasTime = it.hasTime
        }
        parsed.firstNotNullOfOrNull { it.categoryId }?.let { categoryId = it }
        parsed.firstOrNull { it.repeatDays != Repeat.NONE }?.let { repeatDays = it.repeatDays }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) dictation.start(applyDictation)
    }
    val startDictation = {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            dictation.start(applyDictation)
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val dictationState = dictation.state
    // A new task starts with the cursor in the title and the keyboard up.
    LaunchedEffect(isNew) { if (isNew) titleFocus.requestFocus() }

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
                Text(if (isNew) "New task" else "Edit task", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    onClick = { onSave(title.trim(), notes.trim().ifEmpty { null }, due, categoryId, repeatDays, link) },
                    enabled = title.isNotBlank(),
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Save") }
            }
            OutlinedTextField(
                // While the system recognizer is listening, the words appear here as they're recognized.
                value = (dictationState as? DictationState.Listening)?.partial?.takeIf { it.isNotEmpty() } ?: title,
                onValueChange = { title = it },
                readOnly = dictation.active,
                label = { Text("Task") },
                trailingIcon = if (!isNew) null else {
                    {
                        when (dictationState) {
                            is DictationState.Listening -> IconButton(onClick = dictation::stop) {
                                Icon(Icons.Filled.Stop, "Finish speaking", tint = MaterialTheme.colorScheme.error)
                            }
                            DictationState.Transcribing -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            else -> IconButton(onClick = startDictation) { Icon(Icons.Filled.Mic, "Say the task") }
                        }
                    }
                },
                supportingText = when (dictationState) {
                    is DictationState.Listening -> { { Text("Listening… pause or tap ■ when you're done") } }
                    DictationState.Transcribing -> { { Text("Transcribing…") } }
                    is DictationState.Failed -> { { Text(dictationState.message, color = MaterialTheme.colorScheme.error) } }
                    DictationState.Idle -> if (isNew) { { Text("Type it, or tap the mic and say it, e.g. \"call the dentist tomorrow at 3\"") } } else null
                },
                modifier = Modifier.fillMaxWidth().focusRequester(titleFocus),
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
            RepeatDaysPicker(repeatDays, onRepeatChange)
            val linked = link
            if (linked != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Event, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(linked.describe(), Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { pickingEvent = true }) { Text("Change") }
                    IconButton(onClick = { link = null }) { Icon(Icons.Filled.Close, "Remove event reminder") }
                }
                if (linked.event.recurring) {
                    // A repeating event: one task for the next meeting only, or a new one for each meeting.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (linked.everyTime) "A new task for every occurrence" else "Only for the next occurrence",
                            Modifier.weight(1f).padding(start = 26.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text("Every time", Modifier.padding(end = 8.dp), style = MaterialTheme.typography.labelLarge)
                        Switch(checked = linked.everyTime, onCheckedChange = { link = linked.copy(everyTime = it) })
                    }
                }
            } else {
                TextButton(onClick = { pickingEvent = true }) {
                    Icon(Icons.Filled.Event, null, Modifier.size(18.dp))
                    Text("Before an event…", Modifier.padding(start = 8.dp))
                }
            }
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            // Uses what's in the fields now, so it works before saving too.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { context.shareTasks(listOf(title.trim() to due)) },
                    enabled = title.isNotBlank(),
                ) {
                    Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                    Text("Share", Modifier.padding(start = 8.dp))
                }
                OutlinedButton(
                    onClick = { context.launchSafely(TaskIntents.calendar(title.trim(), notes.trim().ifEmpty { null }, due)) },
                    enabled = title.isNotBlank(),
                ) {
                    Icon(Icons.Filled.Event, null, Modifier.size(18.dp))
                    Text("Add to calendar", Modifier.padding(start = 8.dp))
                }
            }
            if (canOpenRecording) {
                TextButton(onClick = onOpenRecording) {
                    Icon(Icons.Filled.Mic, null, Modifier.size(18.dp))
                    Text("Open recording", Modifier.padding(start = 8.dp))
                }
            }
            if (!isNew) {
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete task") }
            }
        }
    }
    if (pickingDue) {
        DuePickerDialog(
            initial = due,
            onDismiss = { pickingDue = false },
            onConfirm = {
                dueAt = it?.epochMillis
                dueHasTime = it?.hasTime ?: false
                link = null // a date picked by hand replaces the event-based one
                pickingDue = false
            },
        )
    }
    if (pickingEvent) {
        EventPickerDialog(
            initialQuery = link?.event?.title ?: title,
            initialOffsetMinutes = link?.offsetMinutes ?: EventTiming.DAY,
            onDismiss = { pickingEvent = false },
            onPick = { picked ->
                link = picked
                val d = EventTiming.due(picked.event, picked.offsetMinutes, java.time.ZoneId.systemDefault())
                dueAt = d.epochMillis
                dueHasTime = d.hasTime
                repeatDays = Repeat.NONE
                pickingEvent = false
            },
        )
    }
}
