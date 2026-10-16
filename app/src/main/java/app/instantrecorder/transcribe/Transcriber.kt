package app.instantrecorder.transcribe

import android.os.SystemClock
import android.util.Log
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

    /**
     * Blocking; call off the main thread. Returns cleaned text, empty if no speech was found.
     * [audioCtx] overrides the encoder window; by default it's sized to the clip when the model allows it.
     */
    fun transcribe(wav: File, audioCtx: Int? = null): String {
        val audio = WavFile.readAsFloat(wav)
        val peak = audio.maxOfOrNull { abs(it) } ?: 0f
        // Silence (or a sub-half-second blip) makes whisper hallucinate phrases like "Thank you."
        if (audio.size < WavFile.SAMPLE_RATE / 2 || peak < SILENCE_PEAK) return ""

        // Quiet recordings transcribe noticeably worse, so bring the peak up to a consistent level.
        if (peak < TARGET_PEAK) {
            val gain = TARGET_PEAK / peak
            for (i in audio.indices) audio[i] *= gain
        }

        val loadStart = SystemClock.elapsedRealtime()
        if (ctx == 0L) {
            ctx = WhisperLib.nativeInit(modelFile.absolutePath)
            if (ctx == 0L) throw IOException("Couldn't load speech model ${model.label}")
        }
        val inferStart = SystemClock.elapsedRealtime()
        val window = audioCtx ?: if (model.shrinkWindow) encoderWindow(audio.size) else 0
        val threads = threadCount()
        val bytes = WhisperLib.nativeTranscribe(ctx, audio, threads, window, "en")
            ?: throw IOException("Transcription failed")
        val done = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "${model.id}: ${WavFile.durationMs(audio.size.toLong())} ms audio, load ${inferStart - loadStart} ms, " +
                "inference ${done - inferStart} ms, audioCtx $window, $threads threads",
        )
        return clean(String(bytes, Charsets.UTF_8))
    }

    override fun close() {
        if (ctx != 0L) {
            WhisperLib.nativeFree(ctx)
            ctx = 0L
        }
    }

    companion object {
        private const val TAG = "Transcriber"
        private const val SILENCE_PEAK = 0.01f
        private const val TARGET_PEAK = 0.9f
        private const val FULL_WINDOW = 1500 // encoder positions in whisper's 30 s window (50 per second)
        // Measured on the Pixel 10 Pro Fold: 1 s made base.en repeat itself on a 2 s clip; 2 s was clean.
        private const val WINDOW_MARGIN_S = 2.0

        /**
         * Whisper always encodes a 30 s window, so a 3 s note costs as much as a 30 s one. Shrinking the
         * window to the clip (plus a margin) cuts encoder time roughly in proportion. Long clips use the
         * full window, since whisper then processes 30 s chunks.
         */
        fun encoderWindow(samples: Int, marginSeconds: Double = WINDOW_MARGIN_S): Int {
            val positions = ((samples.toDouble() / WavFile.SAMPLE_RATE + marginSeconds) * 50).toInt()
            val rounded = (positions + 63) / 64 * 64
            return if (rounded >= FULL_WINDOW) 0 else rounded
        }

        /**
         * Threads for the cores this process may use *right now*. Android confines background work to a
         * subset (on the Pixel 10 Pro Fold: all 8 on screen, 7 for a foreground service, 3 in the
         * background), and whisper's threads spin-wait on each other, so oversubscribing collapses
         * throughput. Leave headroom on big sets so the slow efficiency cores aren't the bottleneck.
         */
        fun threadCount(): Int {
            val cores = allowedCores() ?: Runtime.getRuntime().availableProcessors()
            return when {
                cores >= 6 -> (cores - 2).coerceAtMost(6)
                cores >= 2 -> cores - 1
                else -> 1
            }
        }

        /** Counts "Cpus_allowed_list" in /proc/self/status, e.g. "0-2,4" → 4. */
        private fun allowedCores(): Int? = runCatching {
            File("/proc/self/status").useLines { lines ->
                lines.first { it.startsWith("Cpus_allowed_list:") }
                    .substringAfter(':').trim()
                    .split(',')
                    .sumOf { range ->
                        val (lo, hi) = range.split('-').map(String::toInt).let { it.first() to it.last() }
                        hi - lo + 1
                    }
            }
        }.getOrNull()

        /** Strips whisper's non-speech annotations such as "[BLANK_AUDIO]" or "(music)". */
        fun clean(raw: String): String = raw
            .replace(Regex("""\[[^\]]*]|\([^)]*\)|\*[^*]*\*"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
