package app.instantrecorder.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class TaskExtractorTest {
    private val zone = ZoneId.of("America/New_York")

    /** Friday, 16 Oct 2026, 09:00. */
    private val now = ZonedDateTime.of(2026, 10, 16, 9, 0, 0, 0, zone)

    private fun extract(text: String) = TaskExtractor.extract(text, now)
    private fun Due.at(): LocalDateTime = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDateTime()

    @Test
    fun `lead-in and date-time are removed from the title`() {
        val t = extract("Remind me to call mom tomorrow at 5 p.m.").single()
        assertEquals("Call mom", t.title)
        assertTrue(t.actionable)
        assertEquals(LocalDateTime.of(2026, 10, 17, 17, 0), t.due!!.at())
        assertTrue(t.due!!.hasTime)
    }

    @Test
    fun `several tasks in one note`() {
        val tasks = extract("Okay so I need to buy milk and then email Sarah about the budget. Also book the dentist for next Tuesday.")
        assertEquals(listOf("Buy milk", "Email Sarah about the budget", "Book the dentist"), tasks.map { it.title })
        assertNull(tasks[0].due)
        assertEquals(LocalDateTime.of(2026, 10, 20, 0, 0), tasks[2].due!!.at())
        assertFalse(tasks[2].due!!.hasTime)
    }

    @Test
    fun `weekday deadline with by`() {
        val t = extract("I have to submit the expense report by Friday.").single()
        assertEquals("Submit the expense report", t.title)
        // Saying "Friday" on a Friday means next week's.
        assertEquals(LocalDateTime.of(2026, 10, 23, 0, 0), t.due!!.at())
    }

    @Test
    fun `next weekday skips the current week`() {
        assertEquals(LocalDateTime.of(2026, 10, 24, 0, 0), extract("Clean the garage next Saturday").single().due!!.at())
        assertEquals(LocalDateTime.of(2026, 10, 19, 0, 0), extract("Call the bank next Monday").single().due!!.at())
    }

    @Test
    fun `time without am or pm`() {
        val t = extract("Pick up the kids at 3").single()
        assertEquals("Pick up the kids", t.title)
        assertEquals(LocalDateTime.of(2026, 10, 16, 15, 0), t.due!!.at())
    }

    @Test
    fun `time already past today means tomorrow`() {
        val t = extract("Take out the trash at 8 am").single()
        assertEquals(LocalDateTime.of(2026, 10, 17, 8, 0), t.due!!.at())
    }

    @Test
    fun `relative durations`() {
        assertEquals(LocalDateTime.of(2026, 10, 16, 11, 0), extract("Check the oven in two hours").single().due!!.at())
        assertEquals(LocalDateTime.of(2026, 10, 16, 9, 30), extract("Move the car in half an hour").single().due!!.at())
    }

    @Test
    fun `month and day`() {
        val t = extract("Renew my passport on March 3rd").single()
        assertEquals("Renew my passport", t.title)
        assertEquals(LocalDateTime.of(2027, 3, 3, 0, 0), t.due!!.at())
    }

    @Test
    fun `may as a verb is not a date`() {
        val t = extract("I may need to call the plumber").single()
        assertNull(t.due)
    }

    @Test
    fun `part of day`() {
        val t = extract("Tomorrow morning I need to water the plants").single()
        assertEquals("Water the plants", t.title)
        assertEquals(LocalDateTime.of(2026, 10, 17, 9, 0), t.due!!.at())
    }

    @Test
    fun `narration is kept but not marked actionable`() {
        val tasks = extract("The meeting went well today. Call John to follow up.")
        assertEquals(2, tasks.size)
        assertFalse(tasks[0].actionable)
        assertTrue(tasks[1].actionable)
        assertEquals("Call John to follow up", tasks[1].title)
    }

    @Test
    fun `comma and verb splits tasks (real whisper output)`() {
        val tasks = extract(
            "Okay, so remind me to call Mom tomorrow at 5pm and I need to buy milk and eggs on the way home. " +
                "Also, book a dentist appointment for next Tuesday, and email Sarah the Budget Numbers by Friday."
        )
        assertEquals(
            listOf("Call Mom", "Buy milk and eggs on the way home", "Book a dentist appointment", "Email Sarah the Budget Numbers"),
            tasks.map { it.title },
        )
        assertEquals(LocalDateTime.of(2026, 10, 17, 17, 0), tasks[0].due!!.at())
        assertEquals(LocalDateTime.of(2026, 10, 20, 0, 0), tasks[2].due!!.at())
        assertEquals(LocalDateTime.of(2026, 10, 23, 0, 0), tasks[3].due!!.at())
    }

    @Test
    fun `comma and noun does not split`() {
        assertEquals(listOf("Buy apples, and pears"), extract("Buy apples, and pears").map { it.title })
    }

    @Test
    fun `empty transcript`() {
        assertTrue(extract("   ").isEmpty())
    }
}
