package app.instantrecorder.trigger

import android.view.KeyEvent.KEYCODE_VOLUME_DOWN
import android.view.KeyEvent.KEYCODE_VOLUME_UP
import app.instantrecorder.trigger.VolumeChordDetector.Effect.RESTORE_VOLUME
import app.instantrecorder.trigger.VolumeChordDetector.Effect.SNAPSHOT_VOLUME
import app.instantrecorder.trigger.VolumeChordDetector.Effect.START_RECORDING
import app.instantrecorder.trigger.VolumeChordDetector.Effect.STOP_RECORDING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeChordDetectorTest {
    private val d = VolumeChordDetector()
    private var recording = false
    private var holdToTalk = false

    private fun down(key: Int, t: Long, repeat: Int = 0) = d.onKey(key, true, repeat, t, recording, holdToTalk)
    private fun up(key: Int, t: Long) = d.onKey(key, false, 0, t, recording, holdToTalk)

    @Test
    fun `single tap passes through untouched`() {
        val dn = down(KEYCODE_VOLUME_DOWN, 0)
        assertFalse(dn.consume)
        assertEquals(listOf(SNAPSHOT_VOLUME), dn.effects)
        val u = up(KEYCODE_VOLUME_DOWN, 100)
        assertFalse(u.consume)
        assertTrue(u.effects.isEmpty())
    }

    @Test
    fun `holding one button to ramp the volume passes through`() {
        assertFalse(down(KEYCODE_VOLUME_DOWN, 0).consume)
        // Android synthesizes repeats for keys it saw; we must not swallow them.
        assertFalse(down(KEYCODE_VOLUME_DOWN, 500, repeat = 1).consume)
        assertFalse(down(KEYCODE_VOLUME_DOWN, 550, repeat = 2).consume)
        assertFalse(up(KEYCODE_VOLUME_DOWN, 2_000).consume)
    }

    @Test
    fun `pressing both together starts recording and undoes the first step`() {
        assertFalse(down(KEYCODE_VOLUME_DOWN, 0).consume)
        val second = down(KEYCODE_VOLUME_UP, 80)
        assertTrue(second.consume)
        assertEquals(listOf(RESTORE_VOLUME, START_RECORDING), second.effects)
        recording = true
        // The first button's UP belongs to Android (it saw the DOWN); the second's is ours.
        assertFalse(up(KEYCODE_VOLUME_DOWN, 200).consume)
        val last = up(KEYCODE_VOLUME_UP, 210)
        assertTrue(last.consume)
        assertEquals(listOf(RESTORE_VOLUME), last.effects)
    }

    @Test
    fun `any press stops a recording, and the rest of that press is swallowed`() {
        recording = true
        val stop = down(KEYCODE_VOLUME_DOWN, 0)
        assertTrue(stop.consume)
        assertEquals(listOf(SNAPSHOT_VOLUME, STOP_RECORDING), stop.effects)
        recording = false
        // Second button of a "both buttons" stop must not start a new recording.
        val second = down(KEYCODE_VOLUME_UP, 60)
        assertTrue(second.consume)
        assertTrue(second.effects.isEmpty())
        assertTrue(up(KEYCODE_VOLUME_DOWN, 150).consume)
        assertTrue(up(KEYCODE_VOLUME_UP, 160).consume)
    }

    @Test
    fun `second button long after the first is a normal press`() {
        assertFalse(down(KEYCODE_VOLUME_DOWN, 0).consume)
        val late = down(KEYCODE_VOLUME_UP, 1_500)
        assertFalse(late.consume)
        assertTrue(late.effects.isEmpty())
    }

    @Test
    fun `walkie-talkie stops when either button is released`() {
        holdToTalk = true
        down(KEYCODE_VOLUME_UP, 0)
        assertEquals(listOf(RESTORE_VOLUME, START_RECORDING), down(KEYCODE_VOLUME_DOWN, 50).effects)
        recording = true
        assertEquals(listOf(STOP_RECORDING), up(KEYCODE_VOLUME_UP, 3_000).effects)
        recording = false
        assertEquals(listOf(RESTORE_VOLUME), up(KEYCODE_VOLUME_DOWN, 3_100).effects)
    }

    @Test
    fun `a missed UP does not turn the next press into a chord`() {
        down(KEYCODE_VOLUME_DOWN, 0) // its UP never arrives
        val next = down(KEYCODE_VOLUME_UP, 60_000)
        assertFalse(next.consume)
        assertEquals(listOf(SNAPSHOT_VOLUME), next.effects)
    }

    @Test
    fun `next gesture after a chord works again`() {
        down(KEYCODE_VOLUME_DOWN, 0)
        down(KEYCODE_VOLUME_UP, 50)
        recording = true
        up(KEYCODE_VOLUME_DOWN, 200)
        up(KEYCODE_VOLUME_UP, 210)
        // Later: one tap stops.
        val stop = down(KEYCODE_VOLUME_UP, 5_000)
        assertTrue(stop.consume)
        assertTrue(STOP_RECORDING in stop.effects)
        recording = false
        up(KEYCODE_VOLUME_UP, 5_100)
        // And a plain tap after that is a normal volume press again.
        assertFalse(down(KEYCODE_VOLUME_DOWN, 9_000).consume)
    }
}
