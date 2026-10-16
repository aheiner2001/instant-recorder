package app.instantrecorder.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SilenceDetectorTest {
    private val frameMs = 100L
    private val random = Random(42)

    /** Feeds [seconds] of frames around [db] (±[jitter]); returns the frame index (from 0) where it said stop, or -1. */
    private fun SilenceDetector.feed(seconds: Double, db: Double, jitter: Double = 2.0): Int {
        val frames = (seconds * 1000 / frameMs).toInt()
        for (i in 0 until frames) {
            isSpeech(db + random.nextDouble(-jitter, jitter))
            if (shouldStop) return i
        }
        return -1
    }

    @Test
    fun `quiet room - stops 8 s after speech ends`() {
        val d = SilenceDetector(8_000, frameMs)
        d.feed(1.0, -60.0)
        assertEquals(-1, d.feed(6.0, -25.0))
        // 8 s of quiet = 80 frames; the 80th (index 79) trips it.
        assertEquals(79, d.feed(20.0, -60.0))
    }

    @Test
    fun `pauses shorter than the limit don't stop it`() {
        val d = SilenceDetector(8_000, frameMs)
        repeat(6) {
            assertEquals(-1, d.feed(4.0, -25.0))
            assertEquals(-1, d.feed(5.0, -60.0))
        }
    }

    @Test
    fun `a minute of continuous talking never stops`() {
        val d = SilenceDetector(8_000, frameMs)
        d.feed(0.5, -60.0)
        assertEquals(-1, d.feed(60.0, -22.0, jitter = 6.0))
    }

    @Test
    fun `noisy street - speech is still told apart from the noise`() {
        val d = SilenceDetector(8_000, frameMs)
        d.feed(1.0, -38.0)
        assertEquals(-1, d.feed(5.0, -20.0))
        assertTrue(d.feed(15.0, -38.0) >= 0)
    }

    @Test
    fun `nobody speaks - stops after the limit plus a grace period`() {
        val d = SilenceDetector(8_000, frameMs)
        assertEquals(129, d.feed(30.0, -60.0)) // 13 s
        assertFalse(d.heardSpeech)
    }

    @Test
    fun `digital silence at start-up doesn't make room noise look like speech`() {
        val d = SilenceDetector(8_000, frameMs)
        d.isSpeech(-120.0)
        assertFalse(d.isSpeech(-55.0))
    }
}
