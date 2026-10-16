package app.instantrecorder.transcribe

import android.content.Context
import android.util.Log
import app.instantrecorder.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * [shrinkWindow]: whether short clips may use a smaller encoder window (see Transcriber.encoderWindow).
 * Measured on the user's recordings: free accuracy-wise for Base, but it broke 2 of 5 clips for Small.
 *
 * [threads]: best whisper thread count, measured on the Pixel 10 Pro Fold transcribing in the background
 * (5.6 s clip). Base with its short window: 3 threads 0.8 s vs 5 threads 1.0 s locked and 2.0 s with the
 * screen on (its threads mostly wait on each other). Small does far more work per step: 5 threads 5.4 s,
 * 3 threads 7.6 s, 6 threads 6.1 s.
 */
/** Users see [label] ("Fast"/"Accurate"); whisper's own names (base.en, small.en) stay internal. */
enum class WhisperModel(
    val id: String, val label: String, val fileName: String, val sizeBytes: Long, val note: String,
    val shrinkWindow: Boolean, val threads: Int,
) {
    // Listed in this order on the setup card and in Settings; Accurate is the default.
    SMALL_EN_Q5(
        "small.en-q5_1", "Accurate", "ggml-small.en-q5_1.bin", 190_098_681,
        "Best with names and unusual words. Takes a few seconds per note.", shrinkWindow = false, threads = 5,
    ),
    BASE_EN(
        "base.en", "Fast", "ggml-base.en.bin", 147_964_211,
        "Results in about a second. Fine for clear speech.", shrinkWindow = true, threads = 3,
    );

    val url: String get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"

    companion object {
        fun byId(id: String): WhisperModel? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Downloads whisper models once into app storage. After that, transcription never touches the network.
 */
class ModelManager(context: Context, private val scope: CoroutineScope, private val settings: AppSettings) {
    private val dir = File(context.filesDir, "models")

    private val _installed = MutableStateFlow(scanInstalled())
    val installed: StateFlow<Set<WhisperModel>> = _installed.asStateFlow()

    init {
        // Files for models the app no longer offers (Tiny was dropped) or abandoned partial downloads.
        scope.launch(Dispatchers.IO) {
            val known = WhisperModel.entries.map { it.fileName }.toSet()
            dir.listFiles()?.filter { it.name !in known }?.forEach { it.delete() }
        }
    }

    /** Models currently downloading, with progress in 0..1. */
    private val _downloads = MutableStateFlow<Map<WhisperModel, Float>>(emptyMap())
    val downloads: StateFlow<Map<WhisperModel, Float>> = _downloads.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors

    fun file(model: WhisperModel) = File(dir, model.fileName)

    /** The selected model if it's installed, otherwise any installed model. */
    fun modelForTranscription(): WhisperModel? {
        val installed = _installed.value
        val selected = WhisperModel.byId(settings.value.modelId)
        return selected?.takeIf { it in installed } ?: WhisperModel.entries.firstOrNull { it in installed }
    }

    fun download(model: WhisperModel) {
        if (model in _downloads.value || model in _installed.value) return
        _downloads.update { it + (model to 0f) }
        scope.launch(Dispatchers.IO) {
            val part = File(dir, model.fileName + ".part")
            try {
                dir.mkdirs()
                val conn = URL(model.url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                try {
                    if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                    val total = conn.contentLengthLong.takeIf { it > 0 } ?: model.sizeBytes
                    var done = 0L
                    var lastReported = -1
                    conn.inputStream.use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                done += n
                                val percent = (done * 100 / total).toInt()
                                if (percent != lastReported) {
                                    lastReported = percent
                                    _downloads.update { it + (model to done.toFloat() / total) }
                                }
                            }
                        }
                    }
                    if (done != total) throw IOException("Download incomplete ($done of $total bytes)")
                } finally {
                    conn.disconnect()
                }
                if (!part.renameTo(file(model))) throw IOException("Could not save model file")
                _installed.value = scanInstalled()
                if (WhisperModel.byId(settings.value.modelId) !in _installed.value) {
                    settings.update { it.copy(modelId = model.id) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download of ${model.id} failed", e)
                part.delete()
                _errors.tryEmit("Couldn't download ${model.label}: ${e.message}")
            } finally {
                _downloads.update { it - model }
            }
        }
    }

    fun delete(model: WhisperModel) {
        file(model).delete()
        _installed.value = scanInstalled()
    }

    /** Only complete files count: a truncated model would fail to load (or worse) at transcription time. */
    private fun scanInstalled(): Set<WhisperModel> =
        WhisperModel.entries.filter { file(it).length() == it.sizeBytes }.toSet()

    private companion object {
        const val TAG = "ModelManager"
    }
}
