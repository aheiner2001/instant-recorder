package app.instantrecorder.recording

/**
 * Decides when a recording has gone quiet long enough to stop by itself.
 *
 * Fed one level (dBFS) per audio frame. Speech is anything clearly above the background-noise floor,
 * which follows the quietest frames down immediately and creeps up slowly, so a noisy street or car
 * doesn't count as talking forever. If background noise jumps too much to adapt, every frame looks
 * like speech and the recording falls back to the maximum-length limit: it fails long, never short.
 *
 * Pure logic so it can be unit-tested; [RecordingService] feeds it from the capture thread.
 */
class SilenceDetector(private val silenceMs: Long, private val frameMs: Long) {
    private var floorDb = Double.NaN
    private var quietMs = 0L

    var heardSpeech = false
        private set

    /** Quiet for [silenceMs] since the last speech, or a little longer if nobody has spoken yet. */
    val shouldStop: Boolean
        get() = quietMs >= silenceMs + if (heardSpeech) 0 else NO_SPEECH_GRACE_MS

    /** Feeds one frame; returns whether it contained speech. */
    fun isSpeech(levelDb: Double): Boolean {
        floorDb = if (floorDb.isNaN() || levelDb < floorDb) levelDb else floorDb + FLOOR_RISE_DB_PER_FRAME
        val speech = levelDb > maxOf(floorDb + SPEECH_ABOVE_FLOOR_DB, MIN_SPEECH_DB)
        if (speech) {
            heardSpeech = true
            quietMs = 0
        } else {
            quietMs += frameMs
        }
        return speech
    }

    companion object {
        private const val SPEECH_ABOVE_FLOOR_DB = 10.0
        /** Quieter than this is never speech, however low the floor (e.g. digital silence at start-up). */
        private const val MIN_SPEECH_DB = -48.0
        /** About 0.2 dB/s at 100 ms frames: adapts to rising background noise within a minute. */
        private const val FLOOR_RISE_DB_PER_FRAME = 0.02
        private const val NO_SPEECH_GRACE_MS = 5_000L
    }
}
