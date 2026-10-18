package app.instantrecorder.ui

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.instantrecorder.data.Category
import app.instantrecorder.data.TriggerGesture
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
    val promotedAllowed = remember(tick) {
        Build.VERSION.SDK_INT < 36 || context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
    }

    val categories by vm.categories.collectAsStateWithLifecycle()
    var editingCategory by remember { mutableStateOf<Category?>(null) }
    editingCategory?.let { editing ->
        val isNew = editing === NEW_CATEGORY
        CategoryDialog(
            initial = editing.takeUnless { isNew },
            onDismiss = { editingCategory = null },
            onSave = {
                vm.saveCategory(it)
                editingCategory = null
            },
            onDelete = if (isNew) null else {
                {
                    vm.deleteCategory(editing)
                    editingCategory = null
                }
            },
        )
    }

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
            SectionHeader("Volume buttons")
            ListItem(
                headlineContent = { Text("Record with the volume buttons") },
                supportingContent = {
                    Text(
                        if (triggerOn) "Works on the lock screen and the always-on display."
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
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                TriggerGesture.entries.forEachIndexed { i, gesture ->
                    SegmentedButton(
                        selected = settings.triggerGesture == gesture,
                        onClick = { vm.updateSettings { it.copy(triggerGesture = gesture) } },
                        shape = SegmentedButtonDefaults.itemShape(i, TriggerGesture.entries.size),
                    ) { Text(gesture.label) }
                }
            }
            Text(
                settings.triggerGesture.description,
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (settings.triggerGesture == TriggerGesture.HOLD_ONE) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    TriggerKey.entries.forEachIndexed { i, key ->
                        SegmentedButton(
                            selected = settings.triggerKey == key,
                            onClick = { vm.updateSettings { it.copy(triggerKey = key) } },
                            shape = SegmentedButtonDefaults.itemShape(i, TriggerKey.entries.size),
                        ) { Text(key.label) }
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
            ListItem(
                headlineContent = { Text("Vibrate on start and stop") },
                trailingContent = { Switch(settings.haptics, { on -> vm.updateSettings { it.copy(haptics = on) } }) },
            )
            ListItem(
                headlineContent = { Text("Pause music while recording") },
                trailingContent = { Switch(settings.pauseMedia, { on -> vm.updateSettings { it.copy(pauseMedia = on) } }) },
            )

            SectionHeader("Recording")
            ChoiceRow(
                title = "Quick notes stop after a pause of",
                options = listOf(0 to "Never", 5 to "5 s", 8 to "8 s", 15 to "15 s"),
                selected = settings.silenceStopSec,
                onSelect = { v -> vm.updateSettings { it.copy(silenceStopSec = v) } },
            )
            ChoiceRow(
                title = "Quick notes stop at",
                options = listOf(15 to "15 s", 30 to "30 s", 60 to "1 min"),
                selected = settings.quickMaxSec,
                onSelect = { v -> vm.updateSettings { it.copy(quickMaxSec = v) } },
            )
            ChoiceRow(
                title = "Long recordings stop at",
                options = listOf(10 to "10 min", 30 to "30 min", 60 to "60 min"),
                selected = settings.longMaxMin,
                onSelect = { v -> vm.updateSettings { it.copy(longMaxMin = v) } },
            )
            Text(
                "For a long recording, slide the mic button up to lock it, or tap Switch to long recording while a quick note runs.",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (Build.VERSION.SDK_INT >= 36) {
                ListItem(
                    headlineContent = { Text("Recording timer in the status bar") },
                    supportingContent = {
                        Text(
                            if (promotedAllowed) "On: a red timer chip shows while recording, in every app and on the lock screen."
                            else "Off: turn on \"Live updates\" for Instant Recorder to see the timer chip."
                        )
                    },
                    trailingContent = {
                        if (!promotedAllowed) {
                            Button(onClick = {
                                context.launchSafely(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                )
                            }) { Text("Turn on") }
                        }
                    },
                )
            }

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

            SectionHeader("Categories")
            categories.forEach { c ->
                ListItem(
                    modifier = Modifier.clickable(onClickLabel = "Edit category") { editingCategory = c },
                    leadingContent = { CategoryDot(c.color, 14.dp) },
                    headlineContent = { Text(c.name) },
                    supportingContent = { Text(c.keywords.ifBlank { "No keywords" }) },
                )
            }
            TextButton(onClick = { editingCategory = NEW_CATEGORY }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Icon(Icons.Filled.Add, null)
                Text("Add category", Modifier.padding(start = 8.dp))
            }
            Text(
                "Start a note with a category's name (\"Work: email Sarah…\") to file the tasks that follow, or give it " +
                    "keywords. You can always change the category before saving.",
                Modifier.padding(horizontal = 16.dp),
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

/** A labelled row of mutually exclusive choices. */
@Composable
private fun <T> ChoiceRow(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            options.forEachIndexed { i, (value, label) ->
                SegmentedButton(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(label) }
            }
        }
    }
}

/** Marker for "add a category" in the editing state. */
private val NEW_CATEGORY = Category(id = -1, name = "", color = 0)
