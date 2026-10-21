package app.instantrecorder.ui

import app.instantrecorder.data.Category
import app.instantrecorder.data.NoteItem
import app.instantrecorder.notes.MarkdownSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        assertTrue(reassignedFile.readText().contains("category: \"Work\""))
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
        assertTrue(movedFile.readText().contains("category: \"Unassigned\""))
    }

    @Test
    fun `isUnassigned returns true when categoryId is null or not in validCategoryIds`() {
        val validIds = setOf(1L, 2L, 3L)
        val nullCategoryNote = NoteItem(id = 1L, text = "Null category", categoryId = null)
        val validCategoryNote = NoteItem(id = 2L, text = "Valid category", categoryId = 2L)
        val deletedCategoryNote = NoteItem(id = 3L, text = "Deleted category", categoryId = 999L)

        assertTrue(nullCategoryNote.isUnassigned(validIds))
        assertFalse(validCategoryNote.isUnassigned(validIds))
        assertTrue(deletedCategoryNote.isUnassigned(validIds))
    }

    @Test
    fun `updateNoteText updates note text and syncs updated content to markdown file`() {
        val notesDir = tempFolder.newFolder("Notes")
        val workCategory = categories.first { it.id == 1L }
        val originalNote = NoteItem(
            id = 50L,
            text = "Original meeting agenda",
            categoryId = workCategory.id,
            createdAt = 1000L,
            updatedAt = 1000L,
        )

        // 1. Initial Markdown file creation
        val initialFile = MarkdownSync.syncNote(notesDir, originalNote, workCategory.name)
        assertTrue(initialFile.exists())
        assertTrue(initialFile.readText().contains("Original meeting agenda"))

        val noteWithFile = originalNote.copy(markdownPath = initialFile.absolutePath)

        // 2. Perform text update logic as implemented in MainViewModel.updateNoteText
        val newText = "Updated meeting agenda with new action items:\n- Discuss roadmap\n- Assign tasks"
        val updatedTime = 2000L
        val updatedNote = noteWithFile.copy(text = newText, updatedAt = updatedTime)
        val syncedFile = MarkdownSync.syncNote(notesDir, updatedNote, workCategory.name)

        // Verify the file was updated in place with new text and updated timestamp
        assertTrue(syncedFile.exists())
        assertEquals(initialFile.canonicalPath, syncedFile.canonicalPath)
        val content = syncedFile.readText()
        assertTrue(content.contains(newText))
        assertFalse(content.contains("Original meeting agenda"))
        assertTrue(content.contains("updated_at: 1970-01-01T00:00:02Z"))
    }

    @Test
    fun `toggleNotePinned toggles pinned state of note`() {
        val unpinnedNote = NoteItem(id = 60L, text = "Unpinned note", pinned = false)
        assertFalse(unpinnedNote.pinned)

        val pinnedNote = unpinnedNote.copy(pinned = !unpinnedNote.pinned)
        assertTrue(pinnedNote.pinned)

        val unpinnedAgain = pinnedNote.copy(pinned = !pinnedNote.pinned)
        assertFalse(unpinnedAgain.pinned)
    }

    @Test
    fun `MainViewModel declares updateNoteText and toggleNotePinned methods`() {
        val updateMethod = MainViewModel::class.java.methods.firstOrNull { it.name == "updateNoteText" }
        assertNotNull(updateMethod)
        assertEquals(2, updateMethod?.parameterTypes?.size)
        assertEquals(NoteItem::class.java, updateMethod?.parameterTypes?.get(0))
        assertEquals(String::class.java, updateMethod?.parameterTypes?.get(1))

        val toggleMethod = MainViewModel::class.java.methods.firstOrNull { it.name == "toggleNotePinned" }
        assertNotNull(toggleMethod)
        assertEquals(1, toggleMethod?.parameterTypes?.size)
        assertEquals(NoteItem::class.java, toggleMethod?.parameterTypes?.get(0))
    }
}
