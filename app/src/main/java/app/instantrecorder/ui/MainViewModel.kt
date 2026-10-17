package app.instantrecorder.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.instantrecorder.Notifications
import app.instantrecorder.app
import app.instantrecorder.data.Recording
import app.instantrecorder.data.RecordingStatus
import app.instantrecorder.data.Settings
import app.instantrecorder.data.TaskItem
import app.instantrecorder.recording.RecorderState
import app.instantrecorder.recording.RecordingService
import app.instantrecorder.tasks.Due
import app.instantrecorder.tasks.TaskStore
import app.instantrecorder.tasks.TaskSuggestion
import app.instantrecorder.transcribe.WhisperLib
import app.instantrecorder.transcribe.WhisperModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

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
     * Recordings swiped away on the home screen: hidden at once, deleted only when their undo snackbar goes away.
     * Ids stay here after the delete so the row can't flash back before Room re-emits (Room never reuses them).
     */
    private val hiddenRecordings = MutableStateFlow<Set<Long>>(emptySet())

    val recordings: StateFlow<List<Recording>> =
        combine(graph.db.recordings().observeAll(), hiddenRecordings) { all, hidden -> all.filterNot { it.id in hidden } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tasks: StateFlow<List<TaskItem>> = graph.db.tasks().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    fun deleteRecording(recording: Recording) {
        viewModelScope.launch(Dispatchers.IO) { delete(recording) }
    }

    fun hideForDeletion(recording: Recording) = hiddenRecordings.update { it + recording.id }
    fun undoDeletion(recording: Recording) = hiddenRecordings.update { it - recording.id }

    fun commitDeletion(recording: Recording) {
        // App scope: when the activity finishes with a snackbar up, this runs after viewModelScope is cancelled.
        graph.appScope.launch(Dispatchers.IO) {
            val current = graph.db.recordings().get(recording.id) ?: return@launch
            // A queued recording can get picked up for transcription while its snackbar is showing.
            if (current.status == RecordingStatus.TRANSCRIBING) undoDeletion(current) else delete(current)
        }
    }

    private suspend fun delete(recording: Recording) {
        File(recording.filePath).delete()
        graph.db.recordings().delete(recording)
        Notifications.cancelResult(context, recording.id)
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

    fun editTask(taskId: Long, title: String, notes: String?, due: Due?) {
        viewModelScope.launch { TaskStore.edit(context, taskId, title, notes, due) }
    }

    fun deleteTask(task: TaskItem) {
        viewModelScope.launch { TaskStore.delete(context, task) }
    }

    fun updateSettings(transform: (Settings) -> Settings) = graph.settings.update(transform)
    fun downloadModel(model: WhisperModel) = graph.models.download(model)
    fun deleteModel(model: WhisperModel) = graph.models.delete(model)
    fun selectModel(model: WhisperModel) = updateSettings { it.copy(modelId = model.id) }
}
