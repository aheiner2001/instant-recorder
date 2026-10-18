package app.instantrecorder.tasks

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.util.Log
import app.instantrecorder.Notifications
import app.instantrecorder.app
import app.instantrecorder.data.Category
import app.instantrecorder.data.TaskItem
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Saving tasks to the in-app list (and arming their reminders). */
object TaskStore {
    suspend fun save(context: Context, recordingId: Long?, suggestions: List<TaskSuggestion>, notes: String?): List<TaskItem> {
        val app = context.app
        val items = suggestions.map {
            TaskItem(
                title = it.title,
                notes = notes,
                dueAt = it.due?.epochMillis,
                dueHasTime = it.due?.hasTime ?: false,
                recordingId = recordingId,
                categoryId = it.categoryId,
            )
        }
        val ids = app.db.tasks().insertAll(items)
        val saved = items.zip(ids) { task, id -> task.copy(id = id) }
        saved.forEach { Reminders.schedule(context, it) }
        if (recordingId != null) app.db.recordings().addTasksCreated(recordingId, saved.size)
        return saved
    }

    suspend fun setDone(context: Context, task: TaskItem, done: Boolean) {
        val updated = task.copy(done = done)
        context.app.db.tasks().update(updated)
        if (done) Reminders.cancel(context, task.id) else Reminders.schedule(context, updated)
        Notifications.cancelReminder(context, task.id)
    }

    /**
     * Applies edits to the task as it is in the database now (a reminder's "Done" may have changed it
     * while the editor was open), then re-arms or cancels its reminder for the new due date.
     */
    suspend fun edit(context: Context, taskId: Long, title: String, notes: String?, due: Due?, categoryId: Long?) {
        val dao = context.app.db.tasks()
        val current = dao.get(taskId) ?: return
        val updated = current.copy(
            title = title, notes = notes, dueAt = due?.epochMillis, dueHasTime = due?.hasTime ?: false, categoryId = categoryId,
        )
        dao.update(updated)
        Reminders.schedule(context, updated)
        Notifications.cancelReminder(context, taskId)
    }

    suspend fun delete(context: Context, task: TaskItem) {
        context.app.db.tasks().delete(task)
        Reminders.cancel(context, task.id)
    }
}

fun Category.rule() = CategoryRule(id, name, keywordList)

/** Hand-offs to other apps. Neither needs a permission. */
object TaskIntents {
    fun calendar(title: String, notes: String?, due: Due?): Intent {
        val start = due?.epochMillis ?: (System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1))
        val allDay = due != null && !due.hasTime
        return Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.Events.DESCRIPTION, notes)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + TimeUnit.MINUTES.toMillis(30))
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
    }

    /** One task per line, which Todoist/TickTick/Keep all turn into sensible items. */
    fun shareText(tasks: List<Pair<String, Due?>>): String =
        tasks.joinToString("\n") { (title, due) -> if (due != null) "$title (${due.label()})" else title }

    fun share(tasks: List<Pair<String, Due?>>): Intent {
        val text = shareText(tasks)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
            .putExtra(Intent.EXTRA_SUBJECT, tasks.singleOrNull()?.first ?: "Tasks")
        return Intent.createChooser(send, "Create task with…")
    }
}

object Reminders {
    /** Date-only tasks remind at 9:00 on the day. */
    private fun triggerAt(task: TaskItem): Long? =
        task.dueAt?.let { if (task.dueHasTime) it else it + TimeUnit.HOURS.toMillis(9) }

    fun schedule(context: Context, task: TaskItem) {
        val at = triggerAt(task)
        if (at == null || task.done || at <= System.currentTimeMillis()) {
            cancel(context, task.id)
            return
        }
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = pendingIntent(context, task.id)
        // Exact alarms need the user's OK on Android 14+; otherwise Android may deliver a few minutes late.
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    fun cancel(context: Context, taskId: Long) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context, taskId))
    }

    private fun pendingIntent(context: Context, taskId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            taskId.toInt(),
            TaskActionReceiver.intent(context, TaskActionReceiver.ACTION_REMIND).putExtra(TaskActionReceiver.EXTRA_TASK_ID, taskId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

/** Notification buttons ("Save all", "Done") and reminder alarms. */
class TaskActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        context.app.appScope.launch {
            try {
                handle(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle ${intent.action}", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(context: Context, intent: Intent) {
        val db = context.app.db
        when (intent.action) {
            ACTION_SAVE_ALL -> {
                val recordingId = intent.getLongExtra(EXTRA_RECORDING_ID, -1)
                val recording = db.recordings().get(recordingId) ?: return
                val transcript = recording.transcript ?: return
                val suggestions = TaskExtractor.extract(transcript, categories = db.categories().all().map { it.rule() })
                val chosen = suggestions.filter { it.actionable }.ifEmpty { suggestions }
                val saved = TaskStore.save(context, recordingId, chosen, transcript)
                Notifications.showSaved(context, recordingId, saved)
            }
            ACTION_MARK_DONE -> {
                val task = db.tasks().get(intent.getLongExtra(EXTRA_TASK_ID, -1)) ?: return
                TaskStore.setDone(context, task, true)
            }
            ACTION_REMIND -> {
                val task = db.tasks().get(intent.getLongExtra(EXTRA_TASK_ID, -1)) ?: return
                if (!task.done) Notifications.showReminder(context, task)
            }
        }
    }

    companion object {
        private const val TAG = "TaskActionReceiver"
        const val ACTION_SAVE_ALL = "app.instantrecorder.action.SAVE_ALL"
        const val ACTION_MARK_DONE = "app.instantrecorder.action.MARK_DONE"
        const val ACTION_REMIND = "app.instantrecorder.action.REMIND"
        const val EXTRA_RECORDING_ID = "recordingId"
        const val EXTRA_TASK_ID = "taskId"

        fun intent(context: Context, action: String) = Intent(context, TaskActionReceiver::class.java).setAction(action)
    }
}

/** Alarms don't survive a reboot, so re-arm every pending reminder. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        context.app.appScope.launch {
            try {
                context.app.db.tasks().openWithDueDate().forEach { Reminders.schedule(context, it) }
            } finally {
                pending.finish()
            }
        }
    }
}
