package app.instantrecorder.notes

import app.instantrecorder.data.NoteItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class NoteMergerTest {

    private val zoneId: ZoneId = ZoneId.of("America/New_York")

    private fun epochMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int
    ): Long {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zoneId)
            .toInstant()
            .toEpochMilli()
    }

    @Test
    fun `formatMergedText with empty list returns empty string`() {
        val result = NoteMerger.formatMergedText(emptyList<NoteItem>(), zoneId)
        assertEquals("", result)
    }

    @Test
    fun `formatMergedText with single note formats with timestamp bullet`() {
        val note = NoteItem(
            id = 1L,
            text = "Single note entry",
            createdAt = epochMillis(2026, 10, 21, 10, 15)
        )
        val result = NoteMerger.formatMergedText(listOf(note), zoneId)
        assertEquals("- **10:15 AM**: Single note entry", result)
    }

    @Test
    fun `formatMergedText with same-day notes formats with h-mm a timestamps`() {
        val note1 = NoteItem(
            id = 1L,
            text = "Morning standup thoughts",
            createdAt = epochMillis(2026, 10, 21, 9, 5)
        )
        val note2 = NoteItem(
            id = 2L,
            text = "Afternoon review notes",
            createdAt = epochMillis(2026, 10, 21, 14, 30)
        )

        val result = NoteMerger.formatMergedText(listOf(note1, note2), zoneId)
        val expected = "- **9:05 AM**: Morning standup thoughts\n\n- **2:30 PM**: Afternoon review notes"
        assertEquals(expected, result)
    }

    @Test
    fun `formatMergedText with multi-day notes formats with MMM d, h-mm a timestamps`() {
        val note1 = NoteItem(
            id = 1L,
            text = "Planning sprint",
            createdAt = epochMillis(2026, 10, 20, 10, 15)
        )
        val note2 = NoteItem(
            id = 2L,
            text = "Sprint kickoff",
            createdAt = epochMillis(2026, 10, 21, 14, 0)
        )

        val result = NoteMerger.formatMergedText(listOf(note1, note2), zoneId)
        val expected = "- **Oct 20, 10:15 AM**: Planning sprint\n\n- **Oct 21, 2:00 PM**: Sprint kickoff"
        assertEquals(expected, result)
    }

    @Test
    fun `formatMergedText sorts notes chronologically ascending regardless of input order`() {
        val noteEarlier = NoteItem(
            id = 1L,
            text = "Early note",
            createdAt = epochMillis(2026, 10, 21, 8, 0)
        )
        val noteLater = NoteItem(
            id = 2L,
            text = "Late note",
            createdAt = epochMillis(2026, 10, 21, 17, 45)
        )

        // Pass in reverse order
        val result = NoteMerger.formatMergedText(listOf(noteLater, noteEarlier), zoneId)
        val expected = "- **8:00 AM**: Early note\n\n- **5:45 PM**: Late note"
        assertEquals(expected, result)
    }

    @Test
    fun `formatMergedText trims whitespace from note text`() {
        val note1 = NoteItem(
            id = 1L,
            text = "   Leading and trailing spaces   \n",
            createdAt = epochMillis(2026, 10, 21, 11, 0)
        )
        val note2 = NoteItem(
            id = 2L,
            text = "\n\nMessy multiline note\nwith content\n\n",
            createdAt = epochMillis(2026, 10, 21, 12, 0)
        )

        val result = NoteMerger.formatMergedText(listOf(note1, note2), zoneId)
        val expected = "- **11:00 AM**: Leading and trailing spaces\n\n- **12:00 PM**: Messy multiline note\nwith content"
        assertEquals(expected, result)
    }

    @Test
    fun `combineTags with empty list returns empty string`() {
        val result = NoteMerger.combineTags(emptyList<NoteItem>())
        assertEquals("", result)
    }

    @Test
    fun `combineTags deduplicates tags and filters blank tags`() {
        val note1 = NoteItem(text = "Note 1", tags = "ideas, work, ")
        val note2 = NoteItem(text = "Note 2", tags = "work, project, ideas")
        val note3 = NoteItem(text = "Note 3", tags = "   , urgent")

        val result = NoteMerger.combineTags(listOf(note1, note2, note3))
        assertEquals("ideas,work,project,urgent", result)
    }

    @Test
    fun `buildMergedNote preserves pinned if any note is pinned`() {
        val note1 = NoteItem(text = "Note 1", pinned = false, createdAt = 1000L)
        val note2 = NoteItem(text = "Note 2", pinned = true, createdAt = 2000L)

        val merged = NoteMerger.buildMergedNote(listOf(note1, note2), destinationCategoryId = null, zoneId = zoneId)
        assertTrue(merged.pinned)

        val unpinnedMerged = NoteMerger.buildMergedNote(
            listOf(note1, note1.copy(id = 3L)),
            destinationCategoryId = null,
            zoneId = zoneId
        )
        assertFalse(unpinnedMerged.pinned)
    }

    @Test
    fun `buildMergedNote sets earliest createdAt and updates updatedAt`() {
        val t0 = 1_000_000L
        val t1 = 2_000_000L
        val note1 = NoteItem(text = "Note 1", createdAt = t1)
        val note2 = NoteItem(text = "Note 2", createdAt = t0)

        val before = System.currentTimeMillis()
        val merged = NoteMerger.buildMergedNote(listOf(note1, note2), destinationCategoryId = 5L, zoneId = zoneId)
        val after = System.currentTimeMillis()

        assertEquals(t0, merged.createdAt)
        assertTrue(merged.updatedAt in before..after)
        assertEquals(5L, merged.categoryId)
        assertNull(merged.markdownPath)
    }

    @Test
    fun `buildMergedNote sets recordingId if all notes match or null if mixed`() {
        val noteWithRecA = NoteItem(text = "Note A", recordingId = 10L)
        val noteWithRecA2 = NoteItem(text = "Note A2", recordingId = 10L)
        val noteWithRecB = NoteItem(text = "Note B", recordingId = 20L)
        val noteWithoutRec = NoteItem(text = "Note C", recordingId = null)

        val mergedSame = NoteMerger.buildMergedNote(
            listOf(noteWithRecA, noteWithRecA2, noteWithoutRec),
            destinationCategoryId = null,
            zoneId = zoneId
        )
        assertEquals(10L, mergedSame.recordingId)

        val mergedMixed = NoteMerger.buildMergedNote(
            listOf(noteWithRecA, noteWithRecB),
            destinationCategoryId = null,
            zoneId = zoneId
        )
        assertNull(mergedMixed.recordingId)

        val mergedNone = NoteMerger.buildMergedNote(
            listOf(noteWithoutRec),
            destinationCategoryId = null,
            zoneId = zoneId
        )
        assertNull(mergedNone.recordingId)
    }

    @Test
    fun `buildMergedNote handles empty list gracefully`() {
        val before = System.currentTimeMillis()
        val merged = NoteMerger.buildMergedNote(emptyList<NoteItem>(), destinationCategoryId = 7L, zoneId = zoneId)
        val after = System.currentTimeMillis()

        assertEquals("", merged.text)
        assertEquals(7L, merged.categoryId)
        assertFalse(merged.pinned)
        assertEquals("", merged.tags)
        assertNull(merged.recordingId)
        assertNull(merged.markdownPath)
        assertTrue(merged.createdAt in before..after)
        assertTrue(merged.updatedAt in before..after)
    }
}
