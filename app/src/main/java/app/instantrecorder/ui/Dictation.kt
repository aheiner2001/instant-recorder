package app.instantrecorder.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.instantrecorder.app
import app.instantrecorder.recording.SilenceDetector
import app.instantrecorder.recording.WavFile
import app.instantrecorder.transcribe.Transcriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

sealed interface DictationState {
    data object Idle : DictationState
    /** [partial]: words recognized so far (live with the system recognizer, empty with Whisper). */
    data class Listening(val partial: String) : DictationState
    data object Transcribing : DictationState
    data class Failed(val message: String) : DictationState
}

/**
 * Short dictation for typing a task by voice, always on the phone: Android's on-device recognizer when
 * there is one (live words, fast), otherwise the app's own Whisper model. Call from the main thread.
 */
class Dictation(private val context: Context, private val scope: CoroutineScope) {
    var state by mutableStateOf<DictationState>(DictationState.Idle)
        private set

    private var recognizer: SpeechRecognizer? = null
    private var whisperJob: Job? = null
    @Volatile private var whisperStopRequested = false

    val active: Boolean get() = state is DictationState.Listening || state is DictationState.Transcribing

    fun start(onText: (String) -> Unit) {
        if (active) return
        if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) startSystem(onText) else startWhisper(onText)
    }

    /** Stop listening and use what was said so far. */
    fun stop() {
        recognizer?.stopListening()
        whisperStopRequested = true
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
        whisperJob?.cancel()
    }

    private fun startSystem(onText: (String) -> Unit) {
        val r = recognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                state = DictationState.Listening("")
            }

            override fun onPartialResults(partialResults: Bundle?) {
                state = DictationState.Listening(partialResults.bestText())
            }

            override fun onResults(results: Bundle?) {
                state = DictationState.Idle
                results.bestText().takeIf { it.isNotBlank() }?.let(onText)
            }

            override fun onError(error: Int) {
                when (error) {
                    // No on-device language pack: fall back to Whisper, which is always local.
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                    SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> {
                        Log.w(TAG, "On-device recognizer unavailable ($error); using Whisper")
                        state = DictationState.Idle
                        startWhisper(onText)
                    }
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> state = DictationState.Failed("Didn't catch that")
                    else -> state = DictationState.Failed("Voice input failed ($error)")
                }
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        )
    }

    /** Records until a short pause (or [stop]), then transcribes with the installed Whisper model. */
    private fun startWhisper(onText: (String) -> Unit) {
        val model = context.app.models.modelForTranscription()
        if (model == null) {
            state = DictationState.Failed("Download a speech model in Settings first")
            return
        }
        whisperStopRequested = false
        state = DictationState.Listening("")
        whisperJob = scope.launch {
            val wav = File(context.cacheDir, "dictation.wav")
            try {
                withContext(Dispatchers.IO) { record(wav) }
                state = DictationState.Transcribing
                val text = withContext(Dispatchers.Default) {
                    Transcriber(model, context.app.models.file(model)).use { it.transcribe(wav) }
                }
                state = DictationState.Idle
                if (text.isNotBlank()) onText(text) else state = DictationState.Failed("Didn't catch that")
            } catch (e: Exception) {
                Log.e(TAG, "Whisper dictation failed", e)
                state = DictationState.Failed("Voice input failed")
            } finally {
                wav.delete()
            }
        }
    }

    @SuppressLint("MissingPermission") // The caller asks for the microphone first.
    private suspend fun record(wav: File) {
        val minBuffer = AudioRecord.getMinBufferSize(WavFile.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC, WavFile.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, max(minBuffer * 2, WavFile.SAMPLE_RATE),
        )
        val silence = SilenceDetector(silenceMs = PAUSE_MS, frameMs = FRAME_MS)
        val buffer = ShortArray((WavFile.SAMPLE_RATE * FRAME_MS / 1000).toInt())
        var samples = 0L
        WavFile.Writer(wav).use { writer ->
            record.startRecording()
            try {
                while (currentCoroutineContext().isActive && !whisperStopRequested) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n <= 0) break
                    writer.write(buffer, n)
                    samples += n
                    var sum = 0.0
                    for (i in 0 until n) sum += buffer[i].toDouble() * buffer[i]
                    silence.isSpeech(20 * log10((sqrt(sum / n) / Short.MAX_VALUE).coerceAtLeast(1e-6)))
                    // Stops at the first pause after speech, or a few seconds in if nothing is said at all.
                    if (silence.shouldStop || samples >= WavFile.SAMPLE_RATE * MAX_SECONDS) break
                }
            } finally {
                record.stop()
                record.release()
            }
        }
    }

    private fun Bundle?.bestText(): String =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private companion object {
        const val TAG = "Dictation"
        const val FRAME_MS = 100L
        /** A dictated task is one sentence: stop at the first real pause. */
        const val PAUSE_MS = 1_500L
        const val MAX_SECONDS = 20
    }
}

@Composable
fun rememberDictation(): Dictation {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dictation = remember { Dictation(context.applicationContext, scope) }
    DisposableEffect(dictation) { onDispose { dictation.release() } }
    return dictation
}
