package app.instantrecorder.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.THURSDAY
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class RepeatTest {
    private val zone = ZoneId.of("America/Denver")

    /** Friday, 16 Oct 2026, 09:00. */
    private val friday = ZonedDateTime.of(2026, 10, 16, 9, 0, 0, 0, zone)
    private val monThu = Repeat.of(MONDAY, THURSDAY)

    private fun Due.at(): LocalDateTime = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDateTime()
    private fun due(y: Int, m: Int, d: Int, h: Int? = null) = Due(
        (if (h == null) LocalDateTime.of(y, m, d, 0, 0) else LocalDateTime.of(y, m, d, h, 0)).atZone(zone).toInstant().toEpochMilli(),
        hasTime = h != null,
    )

    @Test
    fun `labels`() {
        assertEquals("Every day", Repeat.label(Repeat.EVERY_DAY))
        assertEquals("Weekdays", Repeat.label(Repeat.WEEKDAYS))
        assertEquals("Mon, Thu", Repeat.label(monThu, java.util.Locale.US))
    }

    @Test
    fun `first occurrence is the next selected day`() {
        assertEquals(LocalDateTime.of(2026, 10, 19, 0, 0), Repeat.first(monThu, friday, null).at())
    }

    @Test
    fun `first occurrence can be today unless its time has passed`() {
        val fri = Repeat.of(FRIDAY)
        assertEquals(LocalDateTime.of(2026, 10, 16, 0, 0), Repeat.first(fri, friday, null).at())
        assertEquals(LocalDateTime.of(2026, 10, 16, 17, 0), Repeat.first(fri, friday, LocalTime.of(17, 0)).at())
        assertEquals(LocalDateTime.of(2026, 10, 23, 8, 0), Repeat.first(fri, friday, LocalTime.of(8, 0)).at())
    }

    @Test
    fun `completed on time - next selected day after the due date`() {
        val monday = ZonedDateTime.of(2026, 10, 19, 10, 0, 0, 0, zone)
        assertEquals(LocalDateTime.of(2026, 10, 22, 0, 0), Repeat.next(monThu, due(2026, 10, 19), monday).at())
    }

    @Test
    fun `completed late - skips ahead instead of piling up`() {
        // Due Monday 19th, done Wednesday 28th (over a week late): next is Thursday 29th.
        val lateWednesday = ZonedDateTime.of(2026, 10, 28, 10, 0, 0, 0, zone)
        assertEquals(LocalDateTime.of(2026, 10, 29, 0, 0), Repeat.next(monThu, due(2026, 10, 19), lateWednesday).at())
    }

    @Test
    fun `completed early - still moves past the due date`() {
        // Due Monday 19th, done the Saturday before: next is Thursday 22nd, not Monday again.
        val saturday = ZonedDateTime.of(2026, 10, 17, 10, 0, 0, 0, zone)
        assertEquals(LocalDateTime.of(2026, 10, 22, 0, 0), Repeat.next(monThu, due(2026, 10, 19), saturday).at())
    }

    @Test
    fun `time of day carries over`() {
        val monday = ZonedDateTime.of(2026, 10, 19, 10, 0, 0, 0, zone)
        val next = Repeat.next(Repeat.WEEKDAYS, due(2026, 10, 19, 9), monday)
        assertEquals(LocalDateTime.of(2026, 10, 20, 9, 0), next.at())
        assertTrue(next.hasTime)
    }

    @Test
    fun `every day`() {
        val next = Repeat.next(Repeat.EVERY_DAY, due(2026, 10, 16), friday)
        assertEquals(LocalDateTime.of(2026, 10, 17, 0, 0), next.at())
        assertFalse(next.hasTime)
    }
}
