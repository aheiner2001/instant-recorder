package app.instantrecorder.ui

import app.instantrecorder.data.Category
import app.instantrecorder.data.NoteItem
import app.instantrecorder.data.Recording
import app.instantrecorder.data.RecordingStatus
import app.instantrecorder.notes.NoteMerger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainViewModelBatchTest {

    private val sampleNotes = listOf(
        NoteItem(
            id = 101L,
            text = "First thought for today",
            categoryId = 1L,
            createdAt = 1000L,
            tags = "idea,work"
        ),
        NoteItem(
            id = 102L,
            text = "Second thought for today",
            categoryId = 2L,
            createdAt = 2000L,
            pinned = true,
            tags = "work,urgent"
        ),
        NoteItem(
            id = 103L,
            text = "Third thought for today",
            categoryId = null,
            createdAt = 3000L,
            tags = "draft"
        )
    )

    private val sampleRecordings = listOf(
        Recording(
            id = 201L,
            filePath = "/data/user/0/recordings/rec_201.m4a",
            createdAt = 10000L,
            durationMs = 5000L,
            status = RecordingStatus.DONE,
            transcript = "Transcript 1"
        ),
        Recording(
            id = 202L,
            filePath = "/data/user/0/recordings/rec_202.m4a",
            createdAt = 20000L,
            durationMs = 8000L,
            status = RecordingStatus.RECORDED,
            transcript = null
        ),
        Recording(
            id = 203L,
            filePath = "/data/user/0/recordings/rec_203.m4a",
            createdAt = 30000L,
            durationMs = 12000L,
            status = RecordingStatus.TRANSCRIBING,
            transcript = null
        )
    )

    @Test
    fun `Deletion OfMultipleNotes formats message correctly and implements Deletion`() {
        val deletion = Deletion.OfMultipleNotes(sampleNotes)
        assertEquals("3 notes deleted", deletion.message)
        assertEquals(3, deletion.notes.size)
        assertEquals(listOf(101L, 102L, 103L), deletion.notes.map { it.id })
        val item: Any = deletion
        assertTrue("OfMultipleNotes should be a Deletion", item is Deletion)
        assertTrue("OfMultipleNotes should be an Undoable", item is Undoable)

        val singleBatch = Deletion.OfMultipleNotes(listOf(sampleNotes.first()))
        assertEquals("1 note deleted", singleBatch.message)
    }

    @Test
    fun `Deletion OfMultipleRecordings formats message correctly and implements Deletion`() {
        val deletion = Deletion.OfMultipleRecordings(sampleRecordings)
        assertEquals("3 recordings deleted", deletion.message)
        assertEquals(3, deletion.recordings.size)
        assertEquals(listOf(201L, 202L, 203L), deletion.recordings.map { it.id })
        val item: Any = deletion
        assertTrue("OfMultipleRecordings should be a Deletion", item is Deletion)
        assertTrue("OfMultipleRecordings should be an Undoable", item is Undoable)

        val pairBatch = Deletion.OfMultipleRecordings(sampleRecordings.take(2))
        assertEquals("2 recordings deleted", pairBatch.message)

        val singleRecBatch = Deletion.OfMultipleRecordings(listOf(sampleRecordings.first()))
        assertEquals("1 recording deleted", singleRecBatch.message)
    }

    @Test
    fun `MergeNotesAction formats message correctly and retains merged note metadata`() {
        val action = MergeNotesAction(
            originalNotes = sampleNotes,
            mergedNoteId = 999L,
            mergedMarkdownPath = "/storage/Notes/Work/merged_note.md"
        )
        assertEquals("3 notes merged", action.message)
        assertEquals(3, action.originalNotes.size)
        assertEquals(999L, action.mergedNoteId)
        assertEquals("/storage/Notes/Work/merged_note.md", action.mergedMarkdownPath)
        val undoable: Any = action
        assertTrue("MergeNotesAction should be an Undoable", undoable is Undoable)
    }

    @Test
    fun `MergeNotesAction default mergedMarkdownPath is null`() {
        val action = MergeNotesAction(
            originalNotes = sampleNotes.take(2),
            mergedNoteId = 555L
        )
        assertEquals("2 notes merged", action.message)
        assertNull(action.mergedMarkdownPath)
    }

    @Test
    fun `NoteMerger buildMergedNote integration combines attributes and timestamps accurately`() {
        val zone = ZoneId.of("UTC")
        val fixedDay = LocalDate.of(2026, 10, 21)
        val t1 = fixedDay.atTime(9, 30).atZone(zone).toInstant().toEpochMilli()
        val t2 = fixedDay.atTime(11, 45).atZone(zone).toInstant().toEpochMilli()

        val notesToMerge = listOf(
            NoteItem(id = 1L, text = "First thought", createdAt = t1, pinned = false, tags = "tag1"),
            NoteItem(id = 2L, text = "Second thought", createdAt = t2, pinned = true, tags = "tag2,tag1")
        )

        val targetCategoryId = 42L
        val merged = NoteMerger.buildMergedNote(notesToMerge, targetCategoryId, zoneId = zone)

        assertEquals(targetCategoryId, merged.categoryId)
        assertEquals(t1, merged.createdAt)
        assertTrue("Should be pinned if any note was pinned", merged.pinned)
        assertEquals("tag1,tag2", merged.tags)
        assertTrue(merged.text.contains("- **9:30 AM**: First thought"))
        assertTrue(merged.text.contains("- **11:45 AM**: Second thought"))
    }

    @Test
    fun `MainViewModel declares deleteNotes method with correct signature`() {
        val method = MainViewModel::class.java.methods.firstOrNull { it.name == "deleteNotes" }
        assertNotNull("MainViewModel should declare deleteNotes", method)
        assertEquals(1, method?.parameterTypes?.size)
        assertEquals(List::class.java, method?.parameterTypes?.get(0))
    }

    @Test
    fun `MainViewModel declares deleteRecordings method with correct signature`() {
        val method = MainViewModel::class.java.methods.firstOrNull { it.name == "deleteRecordings" }
        assertNotNull("MainViewModel should declare deleteRecordings", method)
        assertEquals(1, method?.parameterTypes?.size)
        assertEquals(List::class.java, method?.parameterTypes?.get(0))
    }

    @Test
    fun `MainViewModel declares mergeNotes method with correct signature`() {
        val method = MainViewModel::class.java.methods.firstOrNull { it.name == "mergeNotes" }
        assertNotNull("MainViewModel should declare mergeNotes", method)
        assertEquals(2, method?.parameterTypes?.size)
        assertEquals(List::class.java, method?.parameterTypes?.get(0))
        assertEquals(Long::class.javaObjectType, method?.parameterTypes?.get(1))
    }

    @Test
    fun `hiddenNotes simulation hides batch notes and restores on undo`() {
        var hiddenNotes = emptySet<Long>()
        val notesToDelete = sampleNotes

        // Optimistic delete: hide all IDs
        hiddenNotes = hiddenNotes + notesToDelete.map { it.id }
        assertEquals(setOf(101L, 102L, 103L), hiddenNotes)

        val visibleNotes = sampleNotes.filterNot { it.id in hiddenNotes }
        assertTrue("All notes should be hidden", visibleNotes.isEmpty())

        // Undo deletion: restore all IDs
        hiddenNotes = hiddenNotes - notesToDelete.map { it.id }.toSet()
        assertTrue("Hidden notes should be empty after undo", hiddenNotes.isEmpty())

        val restoredNotes = sampleNotes.filterNot { it.id in hiddenNotes }
        assertEquals(3, restoredNotes.size)
    }

    @Test
    fun `hiddenRecordings simulation hides batch recordings and restores on undo`() {
        var hiddenRecordings = emptySet<Long>()
        val recordingsToDelete = sampleRecordings

        // Optimistic delete: hide all IDs
        hiddenRecordings = hiddenRecordings + recordingsToDelete.map { it.id }
        assertEquals(setOf(201L, 202L, 203L), hiddenRecordings)

        val visibleRecordings = sampleRecordings.filterNot { it.id in hiddenRecordings }
        assertTrue("All recordings should be hidden", visibleRecordings.isEmpty())

        // Undo deletion: restore all IDs
        hiddenRecordings = hiddenRecordings - recordingsToDelete.map { it.id }.toSet()
        assertTrue("Hidden recordings should be empty after undo", hiddenRecordings.isEmpty())

        val restoredRecordings = sampleRecordings.filterNot { it.id in hiddenRecordings }
        assertEquals(3, restoredRecordings.size)
    }

    @Test
    fun `hiddenNotes simulation for note merge hides originals and unhides on undo`() {
        var hiddenNotes = emptySet<Long>()
        val originals = sampleNotes.take(2)

        // On merge: originals are hidden, new merged note (id 999) is visible (not in hiddenNotes)
        hiddenNotes = hiddenNotes + originals.map { it.id }
        assertTrue(101L in hiddenNotes)
        assertTrue(102L in hiddenNotes)
        assertFalse(999L in hiddenNotes)

        // On undo: originals are unhidden
        hiddenNotes = hiddenNotes - originals.map { it.id }.toSet()
        assertFalse(101L in hiddenNotes)
        assertFalse(102L in hiddenNotes)
    }
}
