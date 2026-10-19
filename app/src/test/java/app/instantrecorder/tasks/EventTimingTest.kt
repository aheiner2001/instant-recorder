package app.instantrecorder.tasks

import app.instantrecorder.tasks.EventTiming.DAY
import app.instantrecorder.tasks.EventTiming.HOUR
import app.instantrecorder.tasks.EventTiming.WEEK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class EventTimingTest {
    private val zone = ZoneId.of("America/Denver")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) = ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()
    private fun Due.at(): LocalDateTime = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDateTime()

    /** Board meeting Friday 20 Nov 2026, 19:00 local. */
    private val board = EventRef(1, "Q4 Board Meeting", at(2026, 11, 20, 19), allDay = false)

    /** All-day birthday Saturday 21 Nov 2026: stored as UTC midnight, which is the evening before in Denver. */
    private val birthday = EventRef(2, "Sarah Birthday 🎂", LocalDate.of(2026, 11, 21).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), allDay = true)

    @Test
    fun `day offsets are date-only, counted from the event's date`() {
        val due = EventTiming.due(board, DAY, zone)
        assertEquals(LocalDateTime.of(2026, 11, 19, 0, 0), due.at())
        assertFalse(due.hasTime)
        assertEquals(LocalDateTime.of(2026, 11, 13, 0, 0), EventTiming.due(board, WEEK, zone).at())
    }

    @Test
    fun `all-day events use their calendar date, not the UTC instant`() {
        assertEquals(LocalDateTime.of(2026, 11, 18, 0, 0), EventTiming.due(birthday, 3 * DAY, zone).at())
    }

    @Test
    fun `hour offsets are exact times`() {
        val due = EventTiming.due(board, 2 * HOUR, zone)
        assertEquals(LocalDateTime.of(2026, 11, 20, 17, 0), due.at())
        assertTrue(due.hasTime)
    }

    @Test
    fun `descriptions`() {
        assertEquals("the day before", EventTiming.describe(DAY))
        assertEquals("3 days before", EventTiming.describe(3 * DAY))
        assertEquals("1 week before", EventTiming.describe(WEEK))
        assertEquals("2 hours before", EventTiming.describe(2 * HOUR))
    }

    @Test
    fun `name matching ignores filler, possessives and plurals`() {
        assertEquals(1.0, EventTiming.matchScore("Sarah's birthday", "Sarah Birthday 🎂"), 0.0)
        assertEquals(1.0, EventTiming.matchScore("the board meeting", "Q4 Board Meeting"), 0.0)
        assertEquals(1.0, EventTiming.matchScore("ward council", "Ward Council meetings"), 0.0)
        assertEquals(0.0, EventTiming.matchScore("dentist", "Q4 Board Meeting"), 0.0)
    }

    @Test
    fun `best match prefers the better name, then the earliest`() {
        val laterBoard = board.copy(eventId = 3, begin = at(2027, 2, 19, 19))
        val boardTraining = EventRef(4, "Board training", at(2026, 11, 1, 9), allDay = false)
        assertEquals(1L, EventTiming.bestMatch("board meeting", listOf(laterBoard, boardTraining, board))?.eventId)
        assertNull(EventTiming.bestMatch("dentist", listOf(board, birthday)))
    }
}
