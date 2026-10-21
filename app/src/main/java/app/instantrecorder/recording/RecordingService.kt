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
import app.instantrecorder.data.Category
import app.instantrecorder.data.NoteItem
import app.instantrecorder.data.Recording
import app.instantrecorder.data.RecordingStatus
import app.instantrecorder.notes.CategoryVoiceRouter
import app.instantrecorder.notes.MarkdownSync
import app.instantrecorder.notes.NoteFormatParser
import app.instantrecorder.tasks.TaskExtractor
import app.instantrecorder.tasks.rule
import app.instantrecorder.transcribe.Transcriber
import app.instantrecorder.transcribe.WhisperLib
import app.instantrecorder.transcribe.WhisperModel
import app.instantrecorder.ui.CATEGORY_COLORS
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
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Live recorder state, shared with the UI, the tile and the volume-key trigger. */
object RecorderState {
    sealed interface Status {
        data object Idle : Status
        /** [long]: locked for a long session (no silence stop); [limitMs]: when it stops by itself, from start. */
        data class Recording(val startedAt: Long, val long: Boolean, val limitMs: Long) : Status
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
    /** Maximum length reached, or the silence detector fired. */
    private val autoStop = Runnable {
        stopRecording(keep = true)
        stopIfIdle()
    }
    /** A short buzz shortly before the automatic cutoff, so it doesn't come as a surprise. */
    private val warnBeforeStop = Runnable {
        if (app.settings.value.haptics) vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
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
            ACTION_START -> startRecording(long = false)
            ACTION_START_LONG -> if (capture == null) startRecording(long = true) else lock()
            ACTION_LOCK -> lock()
            ACTION_STOP -> stopRecording(keep = true)
            ACTION_TOGGLE -> if (capture == null) startRecording(long = false) else stopRecording(keep = true)
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

    /** Quick notes stop after silence or [Settings.quickMaxSec]; long ones only at [Settings.longMaxMin]. */
    private fun startRecording(long: Boolean) {
        if (capture != null) return
        val startedAt = System.currentTimeMillis()
        val dir = File(filesDir, "recordings").apply { mkdirs() }
        val settings = app.settings.value
        val silenceMs = if (long) 0L else settings.silenceStopSec * 1000L
        val c = try {
            Capture(File(dir, "rec_$startedAt.wav"), startedAt, silenceMs) { handler.post(autoStop) }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't open the microphone", e)
            Notifications.showProblem(this, "Microphone unavailable", e.message ?: "Another app may be using it.")
            return
        }
        capture = c
        if (settings.pauseMedia) requestAudioFocus()
        if (settings.haptics) vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
        setMode(startedAt, long)
    }

    /** Turns the running quick note into a long recording: no silence stop, the long time limit. */
    private fun lock() {
        val c = capture ?: return
        val status = RecorderState.status.value as? RecorderState.Status.Recording ?: return
        if (status.long) return
        c.stopListeningForSilence()
        if (app.settings.value.haptics) vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        setMode(status.startedAt, long = true)
    }

    private fun setMode(startedAt: Long, long: Boolean) {
        val settings = app.settings.value
        val limitMs = if (long) settings.longMaxMin * 60_000L else settings.quickMaxSec * 1000L
        RecorderState.status.value = RecorderState.Status.Recording(startedAt, long, limitMs)
        refreshNotification()
        handler.removeCallbacks(autoStop)
        handler.removeCallbacks(warnBeforeStop)
        val elapsed = System.currentTimeMillis() - startedAt
        handler.postDelayed(autoStop, (limitMs - elapsed).coerceAtLeast(0))
        handler.postDelayed(warnBeforeStop, (limitMs - WARN_BEFORE_STOP_MS - elapsed).coerceAtLeast(0))
    }

    private fun stopRecording(keep: Boolean) {
        val c = capture ?: return
        capture = null
        handler.removeCallbacks(autoStop)
        handler.removeCallbacks(warnBeforeStop)
        c.finish()
        abandonAudioFocus()
        RecorderState.status.value = RecorderState.Status.Idle
        RecorderState.level.value = 0f
        if (app.settings.value.haptics) vibrate(VibrationEffect.createWaveform(longArrayOf(0, 50, 90, 50), -1))

        val durationMs = WavFile.durationMs(c.samples)
        // Stopped by silence without a word said: an accidental trigger, not a note.
        if (!keep || durationMs < MIN_RECORDING_MS || c.nothingSaid) {
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
            if (text.isNotBlank()) {
                val formatResult = NoteFormatParser.parse(text)
                val categories = app.db.categories().all()

                if (formatResult.isTask) {
                    // Direct task routing: do not create NoteItem
                    val categoryRuleList = categories.map { it.rule() }
                    Notifications.showResult(this, updated, TaskExtractor.extract(formatResult.formattedText, categories = categoryRuleList))
                    return
                }

                val notesDir = File(getExternalFilesDir(null) ?: filesDir, "Notes")
                val now = ZonedDateTime.now()
                val startOfDay = now.toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli()

                // Handle Dynamic Category Creation
                val effectiveCategoryId: Long?
                val effectiveCategoryName: String?
                val contentToSave: String
                val tagString = formatResult.tags.joinToString(",")

                if (formatResult.addCategoryName != null) {
                    val existing = categories.firstOrNull { it.name.equals(formatResult.addCategoryName, ignoreCase = true) }
                    if (existing != null) {
                        effectiveCategoryId = existing.id
                        effectiveCategoryName = existing.name
                    } else {
                        val color = CATEGORY_COLORS[categories.size % CATEGORY_COLORS.size]
                        val nextPos = app.db.categories().nextPosition()
                        val newCatId = app.db.categories().insert(
                            Category(name = formatResult.addCategoryName, color = color, position = nextPos)
                        )
                        effectiveCategoryId = newCatId
                        effectiveCategoryName = formatResult.addCategoryName
                    }
                    contentToSave = formatResult.formattedText
                } else if (formatResult.appendCategoryName != null) {
                    // Handle Append to Category
                    val targetCat = categories.firstOrNull { it.name.equals(formatResult.appendCategoryName, ignoreCase = true) }
                    effectiveCategoryId = targetCat?.id
                    effectiveCategoryName = targetCat?.name

                    val existingNote = app.db.notes().getLatestToday(effectiveCategoryId, startOfDay)
                    if (existingNote != null) {
                        val timeStr = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).format(LocalTime.now())
                        val appendedText = "${existingNote.text}\n\n- **$timeStr**: ${formatResult.formattedText}"
                        val updatedNote = existingNote.copy(
                            text = appendedText,
                            updatedAt = System.currentTimeMillis()
                        )
                        app.db.notes().update(updatedNote)
                        val mdFile = MarkdownSync.syncNote(notesDir, updatedNote, effectiveCategoryName)
                        app.db.notes().update(updatedNote.copy(markdownPath = mdFile.absolutePath))

                        val categoryRuleList = categories.map { it.rule() }
                        Notifications.showResult(this, updated, TaskExtractor.extract(formatResult.formattedText, categories = categoryRuleList))
                        return
                    } else {
                        contentToSave = formatResult.formattedText
                    }
                } else {
                    // Standard category-first prefix routing
                    val routeResult = CategoryVoiceRouter.route(formatResult.formattedText, categories)
                    effectiveCategoryId = routeResult.categoryId
                    effectiveCategoryName = categories.firstOrNull { it.id == effectiveCategoryId }?.name
                    contentToSave = routeResult.cleanedText
                }

                // Standard Note Creation
                val noteId = app.db.notes().insert(
                    NoteItem(
                        text = contentToSave,
                        recordingId = updated.id,
                        categoryId = effectiveCategoryId,
                        createdAt = updated.createdAt,
                        updatedAt = System.currentTimeMillis(),
                        tags = tagString
                    )
                )

                val savedNote = app.db.notes().get(noteId)
                if (savedNote != null) {
                    val mdFile = MarkdownSync.syncNote(notesDir, savedNote, effectiveCategoryName)
                    app.db.notes().update(savedNote.copy(markdownPath = mdFile.absolutePath))
                }

                val categoryRuleList = categories.map { it.rule() }
                Notifications.showResult(this, updated, TaskExtractor.extract(contentToSave, categories = categoryRuleList))
            }
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
        if (status is RecorderState.Status.Recording) return Notifications.recording(this, status)
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

    /**
     * Reads the microphone on its own thread and streams it into a WAV file. With [silenceMs] > 0 it
     * calls [onSilence] (from the capture thread) once that much quiet follows the speech.
     */
    private class Capture(val file: File, val startedAt: Long, silenceMs: Long, private val onSilence: () -> Unit) {
        @Volatile private var running = true
        @Volatile var samples = 0L
            private set
        private val record: AudioRecord
        private val writer: WavFile.Writer
        private val worker: Thread
        private val silence = if (silenceMs > 0) SilenceDetector(silenceMs, FRAME_MS) else null
        @Volatile private var listeningForSilence = silence != null
        @Volatile private var stoppedBySilence = false
        private var lastSpeechAt = 0L // in samples

        /** The silence detector ended this recording and never heard speech. */
        val nothingSaid: Boolean get() = stoppedBySilence && silence?.heardSpeech == false

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
            val buffer = ShortArray((WavFile.SAMPLE_RATE * FRAME_MS / 1000).toInt())
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
                    val db = levelDb(buffer, n)
                    RecorderState.level.value = ((db + 60) / 60).toFloat().coerceIn(0f, 1f)
                    if (silence != null && listeningForSilence && !stoppedBySilence) {
                        if (silence.isSpeech(db)) lastSpeechAt = samples
                        if (silence.shouldStop) {
                            stoppedBySilence = true
                            onSilence()
                        }
                    }
                }
            } finally {
                // Drop the silent tail (keeping a second): it only slows whisper down and invites made-up text.
                if (stoppedBySilence && silence?.heardSpeech == true) {
                    val keep = minOf(samples, lastSpeechAt + WavFile.SAMPLE_RATE)
                    writer.truncate(keep)
                    samples = keep
                }
                writer.close()
            }
        }

        /** The recording was locked into a long session: keep going through pauses. */
        fun stopListeningForSilence() {
            listeningForSilence = false
        }

        fun finish() {
            running = false
            worker.join(2_000)
            record.stop()
            record.release()
        }

        private fun levelDb(buffer: ShortArray, n: Int): Double {
            var sum = 0.0
            for (i in 0 until n) sum += buffer[i].toDouble() * buffer[i]
            val rms = sqrt(sum / n) / Short.MAX_VALUE
            return 20 * log10(rms.coerceAtLeast(1e-6))
        }
    }

    companion object {
        private const val TAG = "RecordingService"
        /** Hard ceiling for the wake lock, above the longest "maximum length" setting. */
        private const val MAX_RECORDING_MS = 30L * 60 * 1000
        private const val FRAME_MS = 100L
        private const val WARN_BEFORE_STOP_MS = 5_000L
        private const val MIN_RECORDING_MS = 600L
        private const val WAKE_LOCK_SLACK_MS = 10L * 60 * 1000

        const val ACTION_START = "app.instantrecorder.action.START"
        const val ACTION_START_LONG = "app.instantrecorder.action.START_LONG"
        const val ACTION_LOCK = "app.instantrecorder.action.LOCK"
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
