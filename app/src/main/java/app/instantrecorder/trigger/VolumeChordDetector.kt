package app.instantrecorder.trigger

import android.view.KeyEvent

/**
 * Recognizes "press Volume Up and Volume Down together" without getting in the way of normal volume use.
 *
 * Single presses are never held back: they pass straight to Android, so taps and hold-to-ramp work as
 * stock. Only when the second button goes down while the first is still held do we take over: that
 * second press is swallowed, the step the first button already made is undone, and recording starts.
 * While recording, any volume press stops it.
 *
 * Pure logic (no Android calls) so it can be unit-tested; [VolumeKeyTriggerService] applies the effects.
 */
class VolumeChordDetector {
    enum class Effect {
        START_RECORDING,
        STOP_RECORDING,

        /** Remember the media volume before Android applies this press. */
        SNAPSHOT_VOLUME,

        /** Put the media volume back to the snapshot (undoes the first button's step or ramp). */
        RESTORE_VOLUME,
    }

    data class Decision(val consume: Boolean, val effects: List<Effect> = emptyList())

    /** Volume keys currently down, with the time each went down. */
    private val held = mutableMapOf<Int, Long>()

    /** Keys whose DOWN we swallowed; their UP must be swallowed too. */
    private val consumed = mutableSetOf<Int>()

    /** This press cycle (until every volume key is up) started or stopped a recording. */
    private var acted = false
    private var startedByChord = false

    fun onKey(keyCode: Int, down: Boolean, repeatCount: Int, eventTime: Long, recording: Boolean, holdToTalk: Boolean): Decision {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return PASS
        return if (down) onDown(keyCode, repeatCount, eventTime, recording) else onUp(keyCode, holdToTalk)
    }

    /** Forget everything, e.g. when a call starts mid-press. */
    fun reset() {
        held.clear()
        consumed.clear()
        acted = false
        startedByChord = false
    }

    private fun onDown(key: Int, repeatCount: Int, eventTime: Long, recording: Boolean): Decision {
        if (repeatCount > 0) return Decision(consume = key in consumed)
        // An UP we never saw (service restarted, focus change) must not turn the next press into a chord.
        held.entries.removeAll { (k, t) -> k == key || eventTime - t > STALE_MS }
        if (held.isEmpty()) {
            consumed.clear()
            acted = false
            startedByChord = false
        }

        val other = held.entries.firstOrNull()
        held[key] = eventTime
        val effects = mutableListOf<Effect>()
        if (other == null) effects += Effect.SNAPSHOT_VOLUME

        when {
            // Rest of a gesture we've already acted on (e.g. the second button of a "stop" chord).
            acted -> Unit
            recording -> {
                acted = true
                effects += Effect.STOP_RECORDING
            }
            other != null && eventTime - other.value <= CHORD_WINDOW_MS -> {
                acted = true
                startedByChord = true
                effects += Effect.RESTORE_VOLUME
                effects += Effect.START_RECORDING
            }
            // An ordinary volume press (or one long after the other button went down): Android handles it.
            else -> return Decision(consume = false, effects = effects)
        }
        consumed += key
        return Decision(consume = true, effects = effects)
    }

    private fun onUp(key: Int, holdToTalk: Boolean): Decision {
        if (held.remove(key) == null) return Decision(consume = key in consumed).also { consumed -= key }
        val effects = mutableListOf<Effect>()
        if (startedByChord && holdToTalk) {
            // Walkie-talkie: letting go of either button ends the recording.
            startedByChord = false
            effects += Effect.STOP_RECORDING
        }
        // Android kept ramping the button it saw while the chord was held; undo that once both are up.
        if (held.isEmpty() && acted) effects += Effect.RESTORE_VOLUME
        return Decision(consume = consumed.remove(key), effects = effects)
    }

    companion object {
        /** How far apart the two presses may land and still count as "together". */
        const val CHORD_WINDOW_MS = 400L
        private const val STALE_MS = 10_000L
        private val PASS = Decision(consume = false)
    }
}
