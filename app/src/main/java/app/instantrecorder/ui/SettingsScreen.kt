package app.instantrecorder.ui

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.instantrecorder.data.TriggerKey
import app.instantrecorder.data.TriggerMode
import app.instantrecorder.transcribe.WhisperModel
import app.instantrecorder.trigger.VolumeKeyTriggerService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val installed by vm.installedModels.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val tick = rememberResumeTick()
    val triggerOn = remember(tick) { VolumeKeyTriggerService.isEnabled(context) }
    val exactAlarms = remember(tick) { context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }

    LaunchedEffect(Unit) {
        vm.modelErrors.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { vm.open(Route.Home) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = { Text("Settings") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionHeader("Volume button")
            ListItem(
                headlineContent = { Text("Hold a volume button to record") },
                supportingContent = {
                    Text(
                        if (triggerOn) "Works whenever the screen is on, including the lock screen."
                        else "Needs the accessibility service: open Accessibility and switch on \"Instant Recorder volume-button trigger\"."
                    )
                },
                trailingContent = {
                    Switch(checked = settings.volumeTrigger, onCheckedChange = { on -> vm.updateSettings { it.copy(volumeTrigger = on) } })
                },
            )
            if (!triggerOn) {
                Button(
                    onClick = { context.launchSafely(VolumeKeyTriggerService.settingsIntent(context)) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) { Text("Open accessibility settings") }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(16.dp)) {
                TriggerKey.entries.forEachIndexed { i, key ->
                    SegmentedButton(
                        selected = settings.triggerKey == key,
                        onClick = { vm.updateSettings { it.copy(triggerKey = key) } },
                        shape = SegmentedButtonDefaults.itemShape(i, TriggerKey.entries.size),
                    ) { Text(key.label) }
                }
            }
            TriggerMode.entries.forEach { mode ->
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(
                            selected = settings.triggerMode == mode,
                            role = Role.RadioButton,
                            onClick = { vm.updateSettings { it.copy(triggerMode = mode) } },
                        )
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.triggerMode == mode, onClick = null)
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(mode.label)
                        Text(mode.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Hold for %.1f seconds".format(settings.holdMs / 1000f))
                Slider(
                    value = settings.holdMs.toFloat(),
                    onValueChange = { v -> vm.updateSettings { it.copy(holdMs = v.toInt()) } },
                    valueRange = 300f..1000f,
                    steps = 6,
                )
            }
            ListItem(
                headlineContent = { Text("Vibrate on start and stop") },
                trailingContent = { Switch(settings.haptics, { on -> vm.updateSettings { it.copy(haptics = on) } }) },
            )
            ListItem(
                headlineContent = { Text("Pause music while recording") },
                trailingContent = { Switch(settings.pauseMedia, { on -> vm.updateSettings { it.copy(pauseMedia = on) } }) },
            )

            SectionHeader("Double-tap the back (Quick Tap)")
            Text(
                "Settings → System → Gestures → Quick Tap → Open app → choose \"Quick Record\". " +
                    "Double-tap to start, double-tap again (or tap Stop) to finish. Quick Tap only works while the phone is unlocked.",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )

            SectionHeader("Speech model")
            Text(
                "Runs entirely on this phone. English only.",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            WhisperModel.entries.forEach { model ->
                val isInstalled = model in installed
                val progress = downloads[model]
                ListItem(
                    modifier = Modifier.selectable(
                        selected = settings.modelId == model.id,
                        enabled = isInstalled,
                        role = Role.RadioButton,
                        onClick = { vm.selectModel(model) },
                    ),
                    leadingContent = { RadioButton(selected = settings.modelId == model.id && isInstalled, onClick = null, enabled = isInstalled) },
                    headlineContent = { Text("${model.label} · ${model.sizeBytes / 1_000_000} MB") },
                    supportingContent = {
                        Text(if (progress != null) "Downloading… ${(progress * 100).toInt()}%" else model.note)
                    },
                    trailingContent = {
                        when {
                            progress != null -> CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(32.dp))
                            isInstalled -> IconButton(onClick = { vm.deleteModel(model) }) { Icon(Icons.Filled.Delete, "Delete model") }
                            else -> IconButton(onClick = { vm.downloadModel(model) }) { Icon(Icons.Filled.Download, "Download model") }
                        }
                    },
                )
            }
            Text(
                "whisper.cpp ${vm.whisperSystemInfo}",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionHeader("Reminders")
            ListItem(
                headlineContent = { Text("Exact reminder times") },
                supportingContent = {
                    Text(if (exactAlarms) "Allowed" else "Not allowed: reminders may arrive a few minutes late.")
                },
                trailingContent = {
                    if (!exactAlarms) {
                        Button(onClick = {
                            context.launchSafely(Intent(ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                        }) { Text("Allow") }
                    }
                },
            )
        }
    }
}
