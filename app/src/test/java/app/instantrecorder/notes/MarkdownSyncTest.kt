package app.instantrecorder.notes

import app.instantrecorder.data.NoteItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MarkdownSyncTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `syncNote creates markdown file with frontmatter in category directory`() {
        val baseDir = tempFolder.newFolder("InstantRecorder")
        val note = NoteItem(
            id = 42,
            text = "Outline for sacrament meeting talk",
            categoryId = 1L,
            recordingId = 10L,
            createdAt = 1776510000000L,
            updatedAt = 1776510000000L
        )

        val file = MarkdownSync.syncNote(baseDir, note, categoryName = "Church")
        assertTrue("File should exist", file.exists())
        val parent = file.parentFile
        assertNotNull(parent)
        assertEquals("Church", parent?.name)
        assertTrue(
            "File name should match pattern: ${file.name}",
            file.name.matches(Regex("""^\d{4}-\d{2}-\d{2}_\d{6}_note_42\.md$"""))
        )

        val content = file.readText()
        assertTrue("Frontmatter should start with ---", content.startsWith("---\n"))
        assertTrue("Should contain id: 42", content.contains("id: 42"))
        assertTrue("Should contain category: Church", content.contains("category: Church"))
        assertTrue("Should contain recording_id: 10", content.contains("recording_id: 10"))
        assertTrue("Should contain created_at", content.contains("created_at: 2026-04-18T11:00:00Z"))
        assertTrue("Should contain updated_at", content.contains("updated_at: 2026-04-18T11:00:00Z"))
        assertTrue("Should contain note body", content.contains("Outline for sacrament meeting talk"))
    }

    @Test
    fun `syncNote places unassigned note in Unassigned directory`() {
        val baseDir = tempFolder.newFolder("InstantRecorder")
        val note = NoteItem(
            id = 99,
            text = "Random idea about fixing the garage door",
            categoryId = null,
            recordingId = null,
            createdAt = 1776510000000L
        )

        val file = MarkdownSync.syncNote(baseDir, note, categoryName = null)
        assertTrue(file.exists())
        val parent = file.parentFile
        assertNotNull(parent)
        assertEquals("Unassigned", parent?.name)

        val content = file.readText()
        assertTrue(content.contains("category: Unassigned"))
        assertTrue(content.contains("Random idea about fixing the garage door"))
        assertFalse("Should not include recording_id when null", content.contains("recording_id:"))
    }

    @Test
    fun `syncNote places blank category note in Unassigned directory`() {
        val baseDir = tempFolder.newFolder("InstantRecorder")
        val note = NoteItem(
            id = 100,
            text = "Note with blank category",
            createdAt = 1776510000000L
        )

        val file = MarkdownSync.syncNote(baseDir, note, categoryName = "   ")
        assertTrue(file.exists())
        val parent = file.parentFile
        assertNotNull(parent)
        assertEquals("Unassigned", parent?.name)

        val content = file.readText()
        assertTrue(content.contains("category: Unassigned"))
    }

    @Test
    fun `syncNote sanitizes category folder names`() {
        val baseDir = tempFolder.newFolder("InstantRecorder")
        val note = NoteItem(
            id = 101,
            text = "Note with special chars category",
            createdAt = 1776510000000L
        )

        val file = MarkdownSync.syncNote(baseDir, note, categoryName = "Work/Tasks?Special*")
        assertTrue(file.exists())
        val parent = file.parentFile
        assertNotNull(parent)
        assertEquals("Work_Tasks_Special_", parent?.name)
    }

    @Test
    fun `syncNote removes old file if note is moved to a new category folder`() {
        val baseDir = tempFolder.newFolder("InstantRecorder")
        val note = NoteItem(
            id = 42,
            text = "Initial note in Unassigned",
            categoryId = null,
            createdAt = 1776510000000L
        )

        val initialFile = MarkdownSync.syncNote(baseDir, note, categoryName = null)
        assertTrue("Initial file should exist", initialFile.exists())
        val initialParent = initialFile.parentFile
        assertNotNull(initialParent)
        assertEquals("Unassigned", initialParent?.name)

        // Note is moved to Church category, markdownPath pointing to old file
        val movedNote = note.copy(
            categoryId = 1L,
            text = "Updated note moved to Church",
            markdownPath = initialFile.absolutePath
        )

        val movedFile = MarkdownSync.syncNote(baseDir, movedNote, categoryName = "Church")
        assertTrue("New file should exist", movedFile.exists())
        val movedParent = movedFile.parentFile
        assertNotNull(movedParent)
        assertEquals("Church", movedParent?.name)
        assertFalse("Old file should have been deleted", initialFile.exists())
    }

    @Test
    fun `syncNote does not delete old file if path is unchanged`() {
        val baseDir = tempFolder.newFolder("InstantRecorder")
        val note = NoteItem(
            id = 42,
            text = "First version",
            categoryId = 1L,
            createdAt = 1776510000000L
        )

        val initialFile = MarkdownSync.syncNote(baseDir, note, categoryName = "Church")
        assertTrue(initialFile.exists())

        val updatedNote = note.copy(
            text = "Second version with same path",
            markdownPath = initialFile.absolutePath
        )

        val updatedFile = MarkdownSync.syncNote(baseDir, updatedNote, categoryName = "Church")
        assertEquals(initialFile.canonicalPath, updatedFile.canonicalPath)
        assertTrue(updatedFile.exists())
        assertTrue(updatedFile.readText().contains("Second version with same path"))
    }

    @Test
    fun `deleteNoteFile removes file cleanly`() {
        val testFile = tempFolder.newFile("test_note.md")
        testFile.writeText("sample content")
        assertTrue(testFile.exists())

        val result = MarkdownSync.deleteNoteFile(testFile.absolutePath)
        assertTrue("deleteNoteFile should return true when file deleted", result)
        assertFalse("File should no longer exist", testFile.exists())

        val secondResult = MarkdownSync.deleteNoteFile(testFile.absolutePath)
        assertFalse("deleteNoteFile should return false if file does not exist", secondResult)

        val nullResult = MarkdownSync.deleteNoteFile(null)
        assertFalse("deleteNoteFile should return false for null path", nullResult)

        val blankResult = MarkdownSync.deleteNoteFile("   ")
        assertFalse("deleteNoteFile should return false for blank path", blankResult)

        // deleteNote alias
        val testFile2 = tempFolder.newFile("test_note_2.md")
        assertTrue(MarkdownSync.deleteNote(testFile2.absolutePath))
        assertFalse(testFile2.exists())
    }
}
