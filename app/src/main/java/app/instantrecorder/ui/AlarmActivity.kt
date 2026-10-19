package app.instantrecorder.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.instantrecorder.Notifications
import app.instantrecorder.app
import app.instantrecorder.data.TaskItem
import app.instantrecorder.tasks.Due
import app.instantrecorder.tasks.Reminders
import app.instantrecorder.tasks.TaskStore
import app.instantrecorder.ui.theme.InstantRecorderTheme
import kotlinx.coroutines.launch

/** Full-screen alarm for an Important task, shown over the lock screen: Done or Snooze 10 min. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1)
        setContent {
            InstantRecorderTheme {
                val task by produceState<TaskItem?>(null, taskId) { value = app.db.tasks().get(taskId) }
                AlarmScreen(task, onDone = { act(taskId, done = true) }, onSnooze = { act(taskId, done = false) })
            }
        }
    }

    private fun act(taskId: Long, done: Boolean) {
        app.appScope.launch {
            val task = app.db.tasks().get(taskId) ?: return@launch
            Notifications.cancelReminder(this@AlarmActivity, taskId)
            if (done) TaskStore.complete(this@AlarmActivity, task) else Reminders.snooze(this@AlarmActivity, task)
        }
        finish()
    }

    companion object {
        private const val EXTRA_TASK_ID = "taskId"

        fun intent(context: Context, taskId: Long): Intent =
            Intent(context, AlarmActivity::class.java)
                .putExtra(EXTRA_TASK_ID, taskId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}

@androidx.compose.runtime.Composable
private fun AlarmScreen(task: TaskItem?, onDone: () -> Unit, onSnooze: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.Alarm, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(24.dp))
            Text(task?.title.orEmpty(), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            task?.dueAt?.let {
                Spacer(Modifier.height(8.dp))
                Text(Due(it, task.dueHasTime).label(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(48.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Done") }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onSnooze, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Snooze 10 min") }
        }
    }
}
