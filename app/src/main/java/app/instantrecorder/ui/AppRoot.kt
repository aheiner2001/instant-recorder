package app.instantrecorder.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.instantrecorder.recording.RecorderState
import app.instantrecorder.tasks.Due
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

@Composable
fun AppRoot(vm: MainViewModel) {
    val route by vm.route.collectAsStateWithLifecycle()
    val status by vm.recorderStatus.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    BackHandler(enabled = route != Route.Home) { vm.open(Route.Home) }

    // A transcription that finishes while you're looking at the list opens straight into review.
    LaunchedEffect(Unit) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            RecorderState.finished.collect { id -> if (vm.route.value == Route.Home) vm.open(Route.Review(id)) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (val r = route) {
            Route.Home -> HomeScreen(vm)
            Route.Settings -> SettingsScreen(vm)
            is Route.Review -> ReviewScreen(vm, r.recordingId)
        }
        val recording = status as? RecorderState.Status.Recording
        AnimatedVisibility(visible = recording != null, enter = fadeIn(), exit = fadeOut()) {
            if (recording != null) {
                RecordingOverlay(recording, vm)
            }
        }
    }
}

@Composable
private fun RecordingOverlay(recording: RecorderState.Status.Recording, vm: MainViewModel) {
    val level by vm.level.collectAsStateWithLifecycle()
    val animatedLevel by animateFloatAsState(level, label = "level")
    val elapsed by produceState(0L, recording.startedAt) {
        while (true) {
            value = System.currentTimeMillis() - recording.startedAt
            delay(200)
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (recording.long) Icon(Icons.Filled.Lock, null, Modifier.padding(end = 8.dp), tint = MaterialTheme.colorScheme.error)
                Text(
                    if (recording.long) "Long recording" else "Quick note",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(formatDuration(elapsed), style = MaterialTheme.typography.displayMedium, fontFamily = FontFamily.Monospace)
            Text(
                if (recording.long) "Stops at ${formatDuration(recording.limitMs)}"
                else "Stops after a pause, or at ${formatDuration(recording.limitMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
            Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(120.dp + 120.dp * animatedLevel)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer)
                )
                FilledIconButton(
                    onClick = vm::stopRecording,
                    modifier = Modifier.size(112.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop", Modifier.size(56.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("Tap to stop and transcribe", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))
            if (!recording.long) {
                OutlinedButton(onClick = vm::lockRecording) {
                    Icon(Icons.Filled.Lock, null, Modifier.size(18.dp))
                    Text("Switch to long recording", Modifier.padding(start = 8.dp))
                }
            }
            TextButton(onClick = vm::discardRecording) { Text("Discard") }
        }
    }
}

/** Bumps every time the screen resumes, to re-read things changed in system settings. */
@Composable
fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    return tick
}

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

fun Context.launchSafely(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "No app found to handle this", Toast.LENGTH_SHORT).show()
    }
}

/** Date first, then an optional time ("All day" keeps it date-only). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuePickerDialog(initial: Due?, onDismiss: () -> Unit, onConfirm: (Due?) -> Unit) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val initialAt = initial?.let { Instant.ofEpochMilli(it.epochMillis).atZone(zone) }
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }

    val date = pickedDate
    if (date == null) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (initialAt?.toLocalDate() ?: LocalDate.now())
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { pickedDate = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                }) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = { onConfirm(null) }) { Text("No date") } },
        ) {
            DatePicker(state)
        }
    } else {
        val initialTime = initialAt?.takeIf { initial.hasTime }?.toLocalTime() ?: LocalTime.of(9, 0)
        val state = rememberTimePickerState(initialTime.hour, initialTime.minute, DateFormat.is24HourFormat(context))
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Time") },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    onConfirm(Due(date.atTime(state.hour, state.minute).atZone(zone).toInstant().toEpochMilli(), hasTime = true))
                }) { Text("Set time") }
            },
            dismissButton = {
                TextButton(onClick = {
                    onConfirm(Due(date.atStartOfDay(zone).toInstant().toEpochMilli(), hasTime = false))
                }) { Text("All day") }
            },
        )
    }
}
