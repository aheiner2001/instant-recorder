package app.instantrecorder.transcribe

import app.instantrecorder.recording.WavFile
import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlin.math.abs

/**
 * Owns one loaded whisper context. Loading a model takes a moment, so the recording service keeps
 * this around while it has work queued and closes it when it shuts down.
 */
class Transcriber(val model: WhisperModel, private val modelFile: File) : Closeable {
    private var ctx = 0L

    /** Blocking; call off the main thread. Returns cleaned text, empty if no speech was found. */
    fun transcribe(wav: File): String {
        val audio = WavFile.readAsFloat(wav)
        val peak = audio.maxOfOrNull { abs(it) } ?: 0f
        // Silence (or a sub-half-second blip) makes whisper hallucinate phrases like "Thank you."
        if (audio.size < WavFile.SAMPLE_RATE / 2 || peak < SILENCE_PEAK) return ""

        // Quiet recordings transcribe noticeably worse, so bring the peak up to a consistent level.
        if (peak < TARGET_PEAK) {
            val gain = TARGET_PEAK / peak
            for (i in audio.indices) audio[i] *= gain
        }

        if (ctx == 0L) {
            ctx = WhisperLib.nativeInit(modelFile.absolutePath)
            if (ctx == 0L) throw IOException("Couldn't load speech model ${model.label}")
        }
        val bytes = WhisperLib.nativeTranscribe(ctx, audio, threadCount(), "en")
            ?: throw IOException("Transcription failed")
        return clean(String(bytes, Charsets.UTF_8))
    }

    override fun close() {
        if (ctx != 0L) {
            WhisperLib.nativeFree(ctx)
            ctx = 0L
        }
    }

    companion object {
        private const val SILENCE_PEAK = 0.01f
        private const val TARGET_PEAK = 0.9f

        /** Leave the two efficiency cores alone; whisper is bottlenecked by its slowest thread. */
        fun threadCount(): Int = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)

        /** Strips whisper's non-speech annotations such as "[BLANK_AUDIO]" or "(music)". */
        fun clean(raw: String): String = raw
            .replace(Regex("""\[[^\]]*]|\([^)]*\)|\*[^*]*\*"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
