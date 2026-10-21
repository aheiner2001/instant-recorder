package app.instantrecorder.recording

import app.instantrecorder.data.Category
import app.instantrecorder.data.NoteItem
import app.instantrecorder.notes.CategoryVoiceRouter
import app.instantrecorder.notes.MarkdownSync
import app.instantrecorder.tasks.TaskExtractor
import app.instantrecorder.tasks.rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecordingPipelineIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val categories = listOf(
        Category(id = 1L, name = "Work", color = 0xFF1E88E5.toInt(), keywords = "office, meeting"),
        Category(id = 2L, name = "Church", color = 0xFF43A047.toInt(), keywords = "talk, lesson")
    )

    @Test
    fun `pipeline routes spoken transcript to note, markdown sync, and task extractor`() {
        val transcript = "Work: Remind me to send meeting notes by Friday"
        val notesDir = tempFolder.newFolder("Notes")

        // 1. Route transcript through CategoryVoiceRouter
        val routeResult = CategoryVoiceRouter.route(transcript, categories)
        assertEquals(1L, routeResult.categoryId)
        assertEquals("Remind me to send meeting notes by Friday", routeResult.cleanedText)

        val categoryName = categories.firstOrNull { it.id == routeResult.categoryId }?.name
        assertEquals("Work", categoryName)

        // 2. Build NoteItem
        val note = NoteItem(
            id = 100L,
            text = routeResult.cleanedText,
            recordingId = 55L,
            categoryId = routeResult.categoryId,
            createdAt = 1776510000000L,
            updatedAt = 1776510000000L
        )

        // 3. Sync to Markdown file
        val mdFile = MarkdownSync.syncNote(notesDir, note, categoryName)
        assertTrue(mdFile.exists())
        assertEquals("Work", mdFile.parentFile?.name)
        val fileContent = mdFile.readText()
        assertTrue(fileContent.contains("category: Work"))
        assertTrue(fileContent.contains("Remind me to send meeting notes by Friday"))

        // 4. Extract tasks using cleaned text and category rules
        val categoryRules = categories.map { it.rule() }
        val suggestions = TaskExtractor.extract(routeResult.cleanedText, categories = categoryRules)
        assertTrue("Should extract task suggestions", suggestions.isNotEmpty())
        val task = suggestions.first()
        assertTrue("Task title should not include 'Work:' prefix", !task.title.startsWith("Work:"))
        // "meeting" matches keyword in Work category
        assertEquals(1L, task.categoryId)
    }

    @Test
    fun `pipeline routes unassigned voice note without category`() {
        val transcript = "Buy milk and bread on the way home"
        val notesDir = tempFolder.newFolder("Notes")

        val routeResult = CategoryVoiceRouter.route(transcript, categories)
        assertNull(routeResult.categoryId)
        assertEquals("Buy milk and bread on the way home", routeResult.cleanedText)

        val categoryName = categories.firstOrNull { it.id == routeResult.categoryId }?.name
        assertNull(categoryName)

        val note = NoteItem(
            id = 101L,
            text = routeResult.cleanedText,
            recordingId = 56L,
            categoryId = routeResult.categoryId,
            createdAt = 1776510000000L,
            updatedAt = 1776510000000L
        )

        val mdFile = MarkdownSync.syncNote(notesDir, note, categoryName)
        assertTrue(mdFile.exists())
        assertEquals("Unassigned", mdFile.parentFile?.name)
        val fileContent = mdFile.readText()
        assertTrue(fileContent.contains("category: Unassigned"))
    }
}
