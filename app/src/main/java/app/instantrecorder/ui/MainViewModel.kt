package app.instantrecorder.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.instantrecorder.Notifications
import app.instantrecorder.app
import app.instantrecorder.data.Category
import app.instantrecorder.data.Recording
import app.instantrecorder.data.RecordingStatus
import app.instantrecorder.data.Settings
import app.instantrecorder.data.TaskItem
import app.instantrecorder.recording.RecorderState
import app.instantrecorder.recording.RecordingService
import app.instantrecorder.tasks.Due
import app.instantrecorder.tasks.Reminders
import app.instantrecorder.tasks.TaskStore
import app.instantrecorder.tasks.TaskSuggestion
import app.instantrecorder.transcribe.WhisperLib
import app.instantrecorder.transcribe.WhisperModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** Something the user deleted, still undoable until its snackbar goes away. */
sealed interface Deletion {
    val message: String

    data class OfRecording(val recording: Recording) : Deletion {
        override val message get() = "Recording deleted"
    }

    data class OfTask(val task: TaskItem) : Deletion {
        override val message get() = "Task deleted"
    }
}

sealed interface Route {
    data object Home : Route
    data object Settings : Route
    data class Review(val recordingId: Long) : Route
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.app
    private val context get() = getApplication<Application>()

    private val _route = MutableStateFlow<Route>(Route.Home)
    val route: StateFlow<Route> = _route.asStateFlow()

    /**
     * Deleted items are hidden at once and only really deleted when their "Undo" snackbar goes away.
     * Ids stay hidden after the delete so a row can't flash back before Room re-emits (Room never reuses them).
     */
    private val hiddenRecordings = MutableStateFlow<Set<Long>>(emptySet())
    private val hiddenTasks = MutableStateFlow<Set<Long>>(emptySet())

    val recordings: StateFlow<List<Recording>> =
        combine(graph.db.recordings().observeAll(), hiddenRecordings) { all, hidden -> all.filterNot { it.id in hidden } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tasks: StateFlow<List<TaskItem>> =
        combine(graph.db.tasks().observeAll(), hiddenTasks) { all, hidden -> all.filterNot { it.id in hidden } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Deletions waiting for the home screen's Undo snackbar; buffered so none is missed while navigating there. */
    private val pendingDeletions = Channel<Deletion>(Channel.BUFFERED)
    val deletions: Flow<Deletion> = pendingDeletions.receiveAsFlow()

    val settings: StateFlow<Settings> = graph.settings.state
    val installedModels = graph.models.installed
    val downloads = graph.models.downloads
    val modelErrors = graph.models.errors

    val recorderStatus = RecorderState.status
    val level = RecorderState.level
    val transcribing = RecorderState.transcribing

    val whisperSystemInfo: String by lazy { runCatching { WhisperLib.nativeSystemInfo() }.getOrDefault("unavailable") }

    fun open(route: Route) {
        _route.value = route
    }

    fun recording(id: Long): Flow<Recording?> = graph.db.recordings().observe(id)

    fun toggleRecording() = RecordingService.send(context, RecordingService.ACTION_TOGGLE)
    fun startLongRecording() = RecordingService.send(context, RecordingService.ACTION_START_LONG)
    fun lockRecording() = RecordingService.send(context, RecordingService.ACTION_LOCK)
    fun stopRecording() = RecordingService.send(context, RecordingService.ACTION_STOP)
    fun discardRecording() = RecordingService.send(context, RecordingService.ACTION_CANCEL)

    fun transcribe(recordingId: Long) = RecordingService.send(context, RecordingService.ACTION_TRANSCRIBE) {
        putExtra(RecordingService.EXTRA_RECORDING_ID, recordingId)
    }

    /** Every delete in the app goes through here, so all of them can be undone the same way. */
    fun delete(item: Deletion) {
        when (item) {
            is Deletion.OfRecording -> hiddenRecordings.update { it + item.recording.id }
            is Deletion.OfTask -> {
                hiddenTasks.update { it + item.task.id }
                Reminders.cancel(context, item.task.id) // don't remind about a task that's on its way out
            }
        }
        pendingDeletions.trySend(item)
    }

    fun undoDeletion(item: Deletion) {
        when (item) {
            is Deletion.OfRecording -> hiddenRecordings.update { it - item.recording.id }
            is Deletion.OfTask -> {
                hiddenTasks.update { it - item.task.id }
                Reminders.schedule(context, item.task)
            }
        }
    }

    fun commitDeletion(item: Deletion) {
        // App scope: when the activity finishes with a snackbar up, this runs after viewModelScope is cancelled.
        graph.appScope.launch(Dispatchers.IO) {
            when (item) {
                is Deletion.OfRecording -> {
                    val current = graph.db.recordings().get(item.recording.id) ?: return@launch
                    // A queued recording can get picked up for transcription while its snackbar is showing.
                    if (current.status == RecordingStatus.TRANSCRIBING) {
                        hiddenRecordings.update { it - current.id }
                    } else {
                        File(current.filePath).delete()
                        graph.db.recordings().delete(current)
                        Notifications.cancelResult(context, current.id)
                    }
                }
                is Deletion.OfTask -> TaskStore.delete(context, item.task)
            }
        }
    }

    fun saveTranscript(recordingId: Long, text: String) {
        viewModelScope.launch { graph.db.recordings().setTranscript(recordingId, text.trim()) }
    }

    fun saveTasks(recordingId: Long, suggestions: List<TaskSuggestion>, notes: String?, onSaved: (Int) -> Unit) {
        viewModelScope.launch {
            val saved = TaskStore.save(context, recordingId, suggestions, notes)
            onSaved(saved.size)
        }
    }

    fun setTaskDone(task: TaskItem, done: Boolean) {
        viewModelScope.launch { TaskStore.setDone(context, task, done) }
    }

    /** A task typed in by hand, not from a recording. */
    fun addTask(title: String, notes: String?, due: Due?, categoryId: Long?) {
        viewModelScope.launch {
            TaskStore.save(context, recordingId = null, listOf(TaskSuggestion(title, due, actionable = true, categoryId = categoryId)), notes)
        }
    }

    fun editTask(taskId: Long, title: String, notes: String?, due: Due?, categoryId: Long?) {
        viewModelScope.launch { TaskStore.edit(context, taskId, title, notes, due, categoryId) }
    }

    val categories: StateFlow<List<Category>> = graph.db.categories().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun saveCategory(category: Category) {
        viewModelScope.launch {
            val dao = graph.db.categories()
            if (category.id == 0L) dao.insert(category.copy(position = dao.nextPosition())) else dao.update(category)
        }
    }

    /** Its tasks stay, uncategorized. */
    fun deleteCategory(category: Category) {
        viewModelScope.launch {
            graph.db.tasks().clearCategory(category.id)
            graph.db.categories().delete(category)
        }
    }

    fun updateSettings(transform: (Settings) -> Settings) = graph.settings.update(transform)
    fun downloadModel(model: WhisperModel) = graph.models.download(model)
    fun deleteModel(model: WhisperModel) = graph.models.delete(model)
    fun selectModel(model: WhisperModel) = updateSettings { it.copy(modelId = model.id) }
}
