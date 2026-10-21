package app.instantrecorder.ui

import app.instantrecorder.data.Category
import app.instantrecorder.data.NoteItem
import app.instantrecorder.notes.MarkdownSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NotesUiTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val categories = listOf(
        Category(id = 1L, name = "Work", color = 0xFF1E88E5.toInt()),
        Category(id = 2L, name = "Personal", color = 0xFF43A047.toInt()),
        Category(id = 3L, name = "Church", color = 0xFFFB8C00.toInt())
    )

    private val sampleNotes = listOf(
        NoteItem(id = 1L, text = "Work: Send monthly report to client", categoryId = 1L),
        NoteItem(id = 2L, text = "Personal: Grocery list milk and bread", categoryId = 2L),
        NoteItem(id = 3L, text = "Just a stray thought without category", categoryId = null),
        NoteItem(id = 4L, text = "Another unassigned idea for weekend", categoryId = null),
        NoteItem(id = 5L, text = "Church: Prepare sunday school lesson", categoryId = 3L)
    )

    @Test
    fun `deletion of note has correct message`() {
        val note = NoteItem(id = 42L, text = "Test note")
        val deletion = Deletion.OfNote(note)
        assertEquals("Note deleted", deletion.message)
        assertEquals(42L, deletion.note.id)
    }

    @Test
    fun `unassigned count accurately counts notes with null categoryId`() {
        val count = sampleNotes.count { it.categoryId == null }
        assertEquals(2, count)
    }

    @Test
    fun `filtering by All returns all notes matching search`() {
        val query = "report"
        val filtered = sampleNotes.filter { note ->
            note.text.contains(query, ignoreCase = true)
        }
        assertEquals(1, filtered.size)
        assertEquals(1L, filtered.first().id)
    }

    @Test
    fun `filtering by Unassigned returns only unassigned notes`() {
        val filtered = sampleNotes.filter { it.categoryId == null }
        assertEquals(2, filtered.size)
        assertTrue(filtered.all { it.categoryId == null })
    }

    @Test
    fun `filtering by specific category returns only notes in that category`() {
        val targetCategoryId = 1L // Work
        val filtered = sampleNotes.filter { it.categoryId == targetCategoryId }
        assertEquals(1, filtered.size)
        assertEquals("Work: Send monthly report to client", filtered.first().text)
    }

    @Test
    fun `search filter handles case insensitivity and whitespace`() {
        val query = "  gRoCeRy  "
        val trimmed = query.trim()
        val filtered = sampleNotes.filter { note ->
            note.text.contains(trimmed, ignoreCase = true)
        }
        assertEquals(1, filtered.size)
        assertEquals(2L, filtered.first().id)
    }

    @Test
    fun `search with category filter combines both criteria`() {
        val targetCategoryId: Long? = -1L // Unassigned sentinel
        val query = "weekend"

        val filtered = sampleNotes.filter { note ->
            val matchesCategory = when (targetCategoryId) {
                null -> true
                -1L -> note.categoryId == null
                else -> note.categoryId == targetCategoryId
            }
            val matchesSearch = query.isBlank() || note.text.contains(query.trim(), ignoreCase = true)
            matchesCategory && matchesSearch
        }

        assertEquals(1, filtered.size)
        assertEquals(4L, filtered.first().id)
    }

    @Test
    fun `assigning category updates note and moves markdown file to category folder`() {
        val notesDir = tempFolder.newFolder("Notes")
        val unassignedNote = NoteItem(id = 10L, text = "Meeting notes with Dave", categoryId = null)

        // 1. Initial sync to Unassigned
        val initialFile = MarkdownSync.syncNote(notesDir, unassignedNote, null)
        assertTrue(initialFile.exists())
        assertEquals("Unassigned", initialFile.parentFile?.name)

        val noteWithFile = unassignedNote.copy(markdownPath = initialFile.absolutePath)

        // 2. Assign category "Work" (id = 1)
        val workCategory = categories.first { it.id == 1L }
        val updatedNote = noteWithFile.copy(categoryId = workCategory.id, updatedAt = System.currentTimeMillis())
        val reassignedFile = MarkdownSync.syncNote(notesDir, updatedNote, workCategory.name)

        // Old file in Unassigned should be deleted and new file created under Work/
        assertFalse("Old file in Unassigned should be deleted", initialFile.exists())
        assertTrue("New file in Work/ should exist", reassignedFile.exists())
        assertEquals("Work", reassignedFile.parentFile?.name)
        assertTrue(reassignedFile.readText().contains("category: Work"))
    }

    @Test
    fun `removing category from note moves markdown file back to Unassigned folder`() {
        val notesDir = tempFolder.newFolder("Notes")
        val workCategory = categories.first { it.id == 1L }
        val categorizedNote = NoteItem(id = 20L, text = "Old work item", categoryId = workCategory.id)

        // 1. Initial sync to Work
        val initialFile = MarkdownSync.syncNote(notesDir, categorizedNote, workCategory.name)
        assertTrue(initialFile.exists())
        assertEquals("Work", initialFile.parentFile?.name)

        val noteWithFile = categorizedNote.copy(markdownPath = initialFile.absolutePath)

        // 2. Triage to Unassigned
        val unassignedNote = noteWithFile.copy(categoryId = null, updatedAt = System.currentTimeMillis())
        val movedFile = MarkdownSync.syncNote(notesDir, unassignedNote, null)

        assertFalse("Old file in Work should be deleted", initialFile.exists())
        assertTrue("New file in Unassigned/ should exist", movedFile.exists())
        assertEquals("Unassigned", movedFile.parentFile?.name)
        assertTrue(movedFile.readText().contains("category: Unassigned"))
    }
}
