package app.instantrecorder.recording

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.instantrecorder.Notifications
import app.instantrecorder.app
import app.instantrecorder.data.Recording
import app.instantrecorder.data.RecordingStatus
import app.instantrecorder.tasks.TaskExtractor
import app.instantrecorder.transcribe.Transcriber
import app.instantrecorder.transcribe.WhisperLib
import app.instantrecorder.transcribe.WhisperModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Live recorder state, shared with the UI, the tile and the volume-key trigger. */
object RecorderState {
    sealed interface Status {
        data object Idle : Status
        data class Recording(val startedAt: Long) : Status
    }

    val status = MutableStateFlow<Status>(Status.Idle)

    /** Input level in 0..1 for the UI meter. */
    val level = MutableStateFlow(0f)

    /** Recording being transcribed and its progress (0..100). */
    val transcribing = MutableStateFlow<Pair<Long, Int>?>(null)

    /** Emits a recording id whenever its transcription finishes. */
    val finished = MutableSharedFlow<Long>(extraBufferCapacity = 8)

    val isRecording: Boolean get() = status.value is Status.Recording
}

/**
 * Foreground service that records 16 kHz mono WAV, then transcribes each recording with whisper.cpp.
 * It stays in the foreground until the recording and every queued transcription are done.
 */
class RecordingService : LifecycleService() {
    private var capture: Capture? = null
    private val queue = Channel<Long>(Channel.UNLIMITED)
    /** Recordings saved or queued but not yet transcribed. */
    private val pending = AtomicInteger(0)
    private var transcriber: Transcriber? = null
    private var focusRequest: AudioFocusRequest? = null
    private val handler = Handler(Looper.getMainLooper())
    /** Keeps the CPU running while the screen is off, so transcription doesn't stall until the next wake-up. */
    private val wakeLock by lazy {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "InstantRecorder:recording")
            .apply { setReferenceCounted(false) }
    }
    private val autoStop = Runnable {
        stopRecording(keep = true)
        stopIfIdle()
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleScope.launch {
            for (id in queue) {
                try {
                    transcribe(id)
                } finally {
                    pending.decrementAndGet()
                    stopIfIdle()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Every startForegroundService() must be answered with startForeground(), whatever the action.
        if (!enterForeground()) return START_NOT_STICKY
        wakeLock.acquire(MAX_RECORDING_MS + WAKE_LOCK_SLACK_MS)
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording(keep = true)
            ACTION_TOGGLE -> if (capture == null) startRecording() else stopRecording(keep = true)
            ACTION_CANCEL -> stopRecording(keep = false)
            ACTION_TRANSCRIBE -> enqueue(intent.getLongExtra(EXTRA_RECORDING_ID, -1))
        }
        stopIfIdle()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        capture?.let { stopRecording(keep = true) }
        handler.removeCallbacksAndMessages(null)
        // Never free the model under a running whisper_full(); ask it to stop instead.
        if (RecorderState.transcribing.value == null) transcriber?.close() else WhisperLib.nativeAbort()
        transcriber = null
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    private fun enterForeground(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Notifications.showProblem(this, "Microphone permission needed", "Open Instant Recorder and allow microphone access.")
            stopSelf()
            return false
        }
        return try {
            ServiceCompat.startForeground(
                this, Notifications.ID_SERVICE, currentNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException / SecurityException: Android refused mic access from the background.
            Log.w(TAG, "Couldn't enter the foreground", e)
            Notifications.showProblem(
                this, "Couldn't start recording", "Android blocked recording from the background. Open Instant Recorder and try again.",
            )
            stopSelf()
            false
        }
    }

    private fun startRecording() {
        if (capture != null) return
        val startedAt = System.currentTimeMillis()
        val dir = File(filesDir, "recordings").apply { mkdirs() }
        val c = try {
            Capture(File(dir, "rec_$startedAt.wav"), startedAt)
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't open the microphone", e)
            Notifications.showProblem(this, "Microphone unavailable", e.message ?: "Another app may be using it.")
            return
        }
        capture = c
        RecorderState.status.value = RecorderState.Status.Recording(startedAt)
        refreshNotification()
        val settings = app.settings.value
        if (settings.pauseMedia) requestAudioFocus()
        if (settings.haptics) vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
        handler.postDelayed(autoStop, MAX_RECORDING_MS)
    }

    private fun stopRecording(keep: Boolean) {
        val c = capture ?: return
        capture = null
        handler.removeCallbacks(autoStop)
        c.finish()
        abandonAudioFocus()
        RecorderState.status.value = RecorderState.Status.Idle
        RecorderState.level.value = 0f
        if (app.settings.value.haptics) vibrate(VibrationEffect.createWaveform(longArrayOf(0, 50, 90, 50), -1))

        val durationMs = WavFile.durationMs(c.samples)
        if (!keep || durationMs < MIN_RECORDING_MS) {
            c.file.delete()
            return
        }
        pending.incrementAndGet()
        refreshNotification()
        // App scope, so the row is still written if this stop comes from onDestroy().
        app.appScope.launch {
            val id = app.db.recordings().insert(
                Recording(filePath = c.file.absolutePath, createdAt = c.startedAt, durationMs = durationMs)
            )
            queue.trySend(id)
        }
    }

    private fun enqueue(recordingId: Long) {
        if (recordingId < 0) return
        pending.incrementAndGet()
        queue.trySend(recordingId)
        refreshNotification()
    }

    private suspend fun transcribe(id: Long) {
        val dao = app.db.recordings()
        val recording = dao.get(id) ?: return
        val model = app.models.modelForTranscription()
        if (model == null) {
            dao.setStatus(id, RecordingStatus.NEEDS_MODEL)
            Notifications.showProblem(this, "Recording saved", "Download a speech model in Instant Recorder to transcribe it.")
            return
        }
        dao.setStatus(id, RecordingStatus.TRANSCRIBING)
        RecorderState.transcribing.value = id to 0
        refreshNotification()

        val progressJob = lifecycleScope.launch {
            while (isActive) {
                delay(500)
                RecorderState.transcribing.value = id to WhisperLib.nativeProgress()
                refreshNotification()
            }
        }
        val started = SystemClock.elapsedRealtime()
        try {
            val text = withContext(Dispatchers.Default) { transcriberFor(model).transcribe(File(recording.filePath)) }
            val elapsed = SystemClock.elapsedRealtime() - started
            Log.i(TAG, "Transcribed ${recording.durationMs} ms of audio in $elapsed ms with ${model.id}")
            val updated = recording.copy(
                status = if (text.isBlank()) RecordingStatus.NO_SPEECH else RecordingStatus.DONE,
                transcript = text.ifBlank { null },
                error = null,
                transcribeMs = elapsed,
                modelId = model.id,
            )
            dao.update(updated)
            if (text.isNotBlank()) Notifications.showResult(this, updated, TaskExtractor.extract(text))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Transcription of $id failed", e)
            dao.update(recording.copy(status = RecordingStatus.FAILED, error = e.message))
        } finally {
            progressJob.cancel()
            RecorderState.transcribing.value = null
            RecorderState.finished.tryEmit(id)
        }
    }

    private fun transcriberFor(model: WhisperModel): Transcriber {
        transcriber?.let { if (it.model == model) return it else it.close() }
        return Transcriber(model, app.models.file(model)).also { transcriber = it }
    }

    private fun stopIfIdle() {
        if (capture == null && pending.get() == 0) {
            if (wakeLock.isHeld) wakeLock.release()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun currentNotification(): Notification {
        val status = RecorderState.status.value
        if (status is RecorderState.Status.Recording) return Notifications.recording(this, status.startedAt)
        val progress = RecorderState.transcribing.value?.second
        return Notifications.working(this, if (pending.get() > 0) "Transcribing…" else "Starting…", progress ?: 0)
    }

    @SuppressLint("MissingPermission") // Dropped silently without the notification permission; the service still runs.
    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(Notifications.ID_SERVICE, currentNotification())
    }

    private fun requestAudioFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { }
            .build()
        getSystemService(AudioManager::class.java).requestAudioFocus(request)
        focusRequest = request
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun vibrate(effect: VibrationEffect) {
        getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(effect)
    }

    /** Reads the microphone on its own thread and streams it into a WAV file. */
    private class Capture(val file: File, val startedAt: Long) {
        @Volatile private var running = true
        @Volatile var samples = 0L
            private set
        private val record: AudioRecord
        private val writer: WavFile.Writer
        private val worker: Thread

        init {
            val minBuffer = AudioRecord.getMinBufferSize(
                WavFile.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            @SuppressLint("MissingPermission") // Checked in enterForeground().
            val r = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                WavFile.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuffer * 2, WavFile.SAMPLE_RATE),
            )
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                throw IllegalStateException("Couldn't open the microphone")
            }
            record = r
            writer = WavFile.Writer(file)
            record.startRecording()
            worker = thread(name = "audio-capture") { loop() }
        }

        private fun loop() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = ShortArray(WavFile.SAMPLE_RATE / 10) // 100 ms
            try {
                while (running) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n < 0) {
                        Log.w(TAG, "AudioRecord.read returned $n")
                        break
                    }
                    if (n == 0) continue
                    writer.write(buffer, n)
                    samples += n
                    RecorderState.level.value = level(buffer, n)
                }
            } finally {
                writer.close()
            }
        }

        fun finish() {
            running = false
            worker.join(2_000)
            record.stop()
            record.release()
        }

        private fun level(buffer: ShortArray, n: Int): Float {
            var sum = 0.0
            for (i in 0 until n) sum += buffer[i].toDouble() * buffer[i]
            val rms = sqrt(sum / n) / Short.MAX_VALUE
            val db = 20 * log10(rms.coerceAtLeast(1e-6))
            return ((db + 60) / 60).toFloat().coerceIn(0f, 1f)
        }
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val MAX_RECORDING_MS = 30L * 60 * 1000
        private const val MIN_RECORDING_MS = 600L
        private const val WAKE_LOCK_SLACK_MS = 10L * 60 * 1000

        const val ACTION_START = "app.instantrecorder.action.START"
        const val ACTION_STOP = "app.instantrecorder.action.STOP"
        const val ACTION_TOGGLE = "app.instantrecorder.action.TOGGLE"
        const val ACTION_CANCEL = "app.instantrecorder.action.CANCEL"
        const val ACTION_TRANSCRIBE = "app.instantrecorder.action.TRANSCRIBE"
        const val EXTRA_RECORDING_ID = "recordingId"

        fun intent(context: Context, action: String): Intent =
            Intent(context, RecordingService::class.java).setAction(action)

        fun send(context: Context, action: String, configure: Intent.() -> Unit = {}) {
            ContextCompat.startForegroundService(context, intent(context, action).apply(configure))
        }
    }
}
