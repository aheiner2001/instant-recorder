package app.instantrecorder.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.instantrecorder.data.Recording
import app.instantrecorder.data.RecordingStatus
import app.instantrecorder.data.Settings
import app.instantrecorder.data.TaskItem
import app.instantrecorder.data.volumeGestureHint
import app.instantrecorder.tasks.Due
import app.instantrecorder.tasks.TaskIntents
import app.instantrecorder.transcribe.WhisperModel
import app.instantrecorder.trigger.VolumeKeyTriggerService
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val recordings by vm.recordings.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val transcribing by vm.transcribing.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deleteWithUndo = { r: Recording ->
        vm.hideForDeletion(r)
        scope.launch {
            // A newer swipe takes over the snackbar, which commits the previous deletion.
            snackbar.currentSnackbarData?.dismiss()
            var undo = false
            try {
                undo = snackbar.showSnackbar("Recording deleted", "Undo", duration = SnackbarDuration.Long) ==
                    SnackbarResult.ActionPerformed
            } finally {
                // Also reached when Home leaves composition (navigation, rotation, finishing) with the snackbar up.
                if (undo) vm.undoDeletion(r) else vm.commitDeletion(r)
            }
        }
    }

    // Runs a recording action once the microphone is allowed (asking first if needed).
    var afterMicGranted by remember { mutableStateOf<(() -> Unit)?>(null) }
    val recordPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) afterMicGranted?.invoke()
        afterMicGranted = null
    }
    val withMic = { action: () -> Unit ->
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            afterMicGranted = action
            recordPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Instant Recorder") },
                actions = {
                    IconButton(onClick = { vm.open(Route.Settings) }) { Icon(Icons.Filled.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            RecordButton(
                onQuickNote = { withMic(vm::toggleRecording) },
                onLongRecording = { withMic(vm::startLongRecording) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 120.dp,
            ),
        ) {
            item { SetupCard(vm) }
            item {
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Recordings") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Tasks (${tasks.count { !it.done }})") })
                }
            }
            if (tab == 0) {
                if (recordings.isEmpty()) {
                    item {
                        EmptyHint(
                            "No recordings yet. To record a quick note, ${settings.volumeGestureHint}, double-tap the back " +
                                "of the phone, or tap the mic. For a long recording, slide the mic up onto the lock."
                        )
                    }
                }
                items(recordings, key = { it.id }) { r ->
                    SwipeToDelete(
                        // RecordingService is working on it.
                        enabled = r.status != RecordingStatus.TRANSCRIBING,
                        onDelete = { deleteWithUndo(r) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        RecordingRow(r, transcribing?.takeIf { it.first == r.id }?.second) { vm.open(Route.Review(r.id)) }
                    }
                }
            } else {
                if (tasks.isEmpty()) item { EmptyHint("No tasks yet. Record a note and save the tasks it finds.") }
                items(tasks, key = { it.id }) { t ->
                    TaskRow(
                        task = t,
                        onDone = { vm.setTaskDone(t, it) },
                        onCalendar = {
                            context.launchSafely(
                                TaskIntents.calendar(t.title, t.notes, t.dueAt?.let { Due(it, t.dueHasTime) })
                            )
                        },
                        onDelete = { vm.deleteTask(t) },
                    )
                }
            }
        }
    }
}

/** Shows only the steps that are still missing; disappears once everything is set up. */
@Composable
private fun SetupCard(vm: MainViewModel) {
    val context = LocalContext.current
    val tick = rememberResumeTick()
    val installed by vm.installedModels.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    var permissionTick by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionTick++ }

    val (hasMic, hasNotifications, triggerOn) = remember(tick, permissionTick) {
        Triple(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            VolumeKeyTriggerService.isEnabled(context),
        )
    }
    val hasModel = installed.isNotEmpty()
    if (hasMic && hasNotifications && hasModel && triggerOn) return

    OutlinedCard(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Finish setting up", style = MaterialTheme.typography.titleMedium)
            if (!hasMic) {
                SetupStep("Allow the microphone", "Needed to record.") {
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Allow") }
                }
            }
            if (!hasNotifications) {
                SetupStep("Allow notifications", "Shows the recording controls and the tasks it found.") {
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow") }
                }
            }
            if (!hasModel) {
                SetupStep(
                    "Choose a speech model",
                    "A one-time download. Transcription then runs offline on this phone, and you can switch later in Settings.",
                ) {}
                val downloading = downloads.entries.firstOrNull()
                if (downloading != null) {
                    Text("Downloading the ${downloading.key.label} model… ${(downloading.value * 100).toInt()}%")
                    LinearProgressIndicator(progress = { downloading.value }, modifier = Modifier.fillMaxWidth())
                } else {
                    WhisperModel.entries.forEach { model ->
                        OutlinedButton(onClick = { vm.downloadModel(model) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                val recommended = if (model.id == Settings().modelId) " · Recommended" else ""
                                Text("${model.label} · ${model.sizeBytes / 1_000_000} MB$recommended", style = MaterialTheme.typography.titleSmall)
                                Text(model.note, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            if (!triggerOn) {
                SetupStep(
                    "Turn on the volume-button trigger",
                    "In Accessibility, open \"Instant Recorder volume-button trigger\" and switch it on.",
                ) {
                    Button(onClick = { context.launchSafely(VolumeKeyTriggerService.settingsIntent(context)) }) { Text("Open") }
                }
            }
        }
    }
}

@Composable
private fun SetupStep(title: String, detail: String, action: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action()
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(32.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val timestampFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

fun formatTimestamp(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(timestampFormat)

/** Swipe end-to-start to delete, over an error-colored background shaped like the card. */
@Composable
private fun SwipeToDelete(
    enabled: Boolean,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // Not rememberSwipeToDismissBoxState(): it's saveable, and the list keeps saved state per key, so after
    // Undo the row would come back already dismissed and fire onDismiss again.
    val threshold = SwipeToDismissBoxDefaults.positionalThreshold
    val state = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, threshold) }
    // onDismiss is an effect key inside SwipeToDismissBox; a stable lambda keeps it from firing twice.
    val currentOnDelete by rememberUpdatedState(onDelete)
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        gesturesEnabled = enabled,
        onDismiss = { currentOnDelete() },
        backgroundContent = {
            if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error, CardDefaults.shape).padding(horizontal = 24.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onError)
                }
            }
        },
    ) {
        content()
    }
}

@Composable
private fun RecordingRow(recording: Recording, progress: Int?, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTimestamp(recording.createdAt), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Text(formatDuration(recording.durationMs), style = MaterialTheme.typography.labelLarge)
            }
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            when (recording.status) {
                RecordingStatus.DONE -> Text(
                    recording.transcript.orEmpty(), maxLines = 3, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                RecordingStatus.TRANSCRIBING -> {
                    Text("Transcribing… ${progress ?: 0}%", color = muted)
                    LinearProgressIndicator(progress = { (progress ?: 0) / 100f }, modifier = Modifier.fillMaxWidth())
                }
                RecordingStatus.RECORDED -> Text("Waiting to transcribe", color = muted)
                RecordingStatus.NEEDS_MODEL -> Text("Download a speech model to transcribe this", color = muted)
                RecordingStatus.NO_SPEECH -> Text("No speech detected", color = muted)
                RecordingStatus.FAILED -> Text("Transcription failed: ${recording.error}", color = MaterialTheme.colorScheme.error)
            }
            if (recording.tasksCreated > 0) {
                Text(
                    if (recording.tasksCreated == 1) "1 task created" else "${recording.tasksCreated} tasks created",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun TaskRow(task: TaskItem, onDone: (Boolean) -> Unit, onCalendar: () -> Unit, onDelete: () -> Unit) {
    val due = task.dueAt?.let { Due(it, task.dueHasTime) }
    val overdue = !task.done && task.dueAt != null && task.dueHasTime && task.dueAt < System.currentTimeMillis()
    ListItem(
        leadingContent = { Checkbox(checked = task.done, onCheckedChange = onDone) },
        headlineContent = {
            Text(task.title, textDecoration = if (task.done) TextDecoration.LineThrough else null)
        },
        supportingContent = due?.let {
            { Text(it.label(), color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        },
        trailingContent = {
            Row {
                IconButton(onClick = onCalendar) { Icon(Icons.Filled.Event, "Add to calendar") }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete") }
            }
        },
    )
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

private val LOCK_DISTANCE = 96.dp

/**
 * The mic button. Tap: a quick note (stops after a pause or a short limit). Press and slide up onto the
 * lock: a long recording, like locking a voice message.
 */
@Composable
private fun RecordButton(onQuickNote: () -> Unit, onLongRecording: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val lockDistancePx = with(LocalDensity.current) { LOCK_DISTANCE.toPx() }
    var slidPx by remember { mutableFloatStateOf(0f) }
    var pressed by remember { mutableStateOf(false) }
    val progress = (slidPx / lockDistancePx).coerceIn(0f, 1f)
    val quickNote by rememberUpdatedState(onQuickNote)
    val longRecording by rememberUpdatedState(onLongRecording)
    val shape = FloatingActionButtonDefaults.largeShape

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Delayed, so a plain tap never flashes it; it appears while the finger stays down or slides.
        AnimatedVisibility(
            visible = pressed,
            enter = fadeIn(tween(150, delayMillis = 200)) + expandVertically(tween(150, delayMillis = 200), expandFrom = Alignment.Bottom),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(100), shrinkTowards = Alignment.Bottom),
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.padding(bottom = 8.dp).width(72.dp).height(LOCK_DISTANCE),
            ) {
                Column(
                    Modifier.padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Icon(if (progress >= 1f) Icons.Filled.Lock else Icons.Filled.LockOpen, contentDescription = null)
                    Text("Long", style = MaterialTheme.typography.labelSmall)
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, Modifier.alpha(1f - progress))
                }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(0, -slidPx.roundToInt()) }
                .size(96.dp)
                .shadow(6.dp, shape)
                .clip(shape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = "Record"
                    onClick(label = "Record a quick note") { quickNote(); true }
                    customActions = listOf(CustomAccessibilityAction("Start a long recording") { longRecording(); true })
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        pressed = true
                        var moved = false
                        var locked = false
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val up = down.position.y - change.position.y
                            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                            slidPx = up.coerceIn(0f, lockDistancePx)
                            change.consume()
                            if (up >= lockDistancePx) {
                                locked = true
                                break
                            }
                        }
                        pressed = false
                        slidPx = 0f
                        when {
                            locked -> {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                longRecording()
                            }
                            !moved -> quickNote()
                            // Slid part-way and let go: changed their mind.
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Mic, contentDescription = null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}
