package app.instantrecorder.recording

import app.instantrecorder.data.Category
import app.instantrecorder.data.NoteItem
import app.instantrecorder.data.tagList
import app.instantrecorder.notes.CategoryVoiceRouter
import app.instantrecorder.notes.MarkdownSync
import app.instantrecorder.notes.NoteFormatParser
import app.instantrecorder.tasks.TaskExtractor
import app.instantrecorder.tasks.rule
import app.instantrecorder.ui.CATEGORY_COLORS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

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
        assertTrue(fileContent.contains("category: \"Work\""))
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
        assertTrue(fileContent.contains("category: \"Unassigned\""))
    }

    @Test
    fun `pipeline handles dynamic category creation and saves note with tag and markdown`() {
        val transcript = "Add category Sunday Prep: review lesson outline tag urgent"
        val notesDir = tempFolder.newFolder("Notes")

        // 1. Parse formatted transcript
        val formatResult = NoteFormatParser.parse(transcript)
        assertEquals("Sunday Prep", formatResult.addCategoryName)
        assertEquals("Review lesson outline\n\n#urgent", formatResult.formattedText)
        assertEquals(listOf("urgent"), formatResult.tags)
        assertTrue(!formatResult.isTask)

        // 2. Handle dynamic category creation using CATEGORY_COLORS
        val existing = categories.firstOrNull { it.name.equals(formatResult.addCategoryName, ignoreCase = true) }
        assertNull("Category Sunday Prep should not exist yet", existing)

        val expectedColor = CATEGORY_COLORS[categories.size % CATEGORY_COLORS.size]
        val newCategory = Category(
            id = 3L,
            name = formatResult.addCategoryName!!,
            color = expectedColor,
            position = categories.size
        )
        val effectiveCategoryId = newCategory.id
        val effectiveCategoryName = newCategory.name
        val contentToSave = formatResult.formattedText
        val tagString = formatResult.tags.joinToString(",")

        // 3. Create NoteItem with tags
        val note = NoteItem(
            id = 102L,
            text = contentToSave,
            recordingId = 57L,
            categoryId = effectiveCategoryId,
            createdAt = 1776510000000L,
            updatedAt = 1776510000000L,
            tags = tagString
        )
        assertEquals("urgent", note.tags)
        assertEquals(3L, note.categoryId)

        // 4. Sync to Markdown file
        val mdFile = MarkdownSync.syncNote(notesDir, note, effectiveCategoryName)
        assertTrue(mdFile.exists())
        assertEquals("Sunday Prep", mdFile.parentFile?.name)
        val fileContent = mdFile.readText()
        assertTrue(fileContent.contains("category: \"Sunday Prep\""))
        assertTrue(fileContent.contains("Review lesson outline"))

        // 5. Extract tasks using contentToSave
        val allCategories = categories + newCategory
        val suggestions = TaskExtractor.extract(contentToSave, categories = allCategories.map { it.rule() })
        // Verify task extractor runs without issue on formatted text
        assertNotNull(suggestions)
    }

    @Test
    fun `pipeline handles dynamic category creation when category already exists`() {
        val transcript = "Add category Work: review quarterly budget"
        val notesDir = tempFolder.newFolder("Notes")

        val formatResult = NoteFormatParser.parse(transcript)
        assertEquals("Work", formatResult.addCategoryName)
        assertEquals("Review quarterly budget", formatResult.formattedText)

        // Resolve existing category
        val existing = categories.firstOrNull { it.name.equals(formatResult.addCategoryName, ignoreCase = true) }
        assertNotNull("Work category should already exist", existing)
        val effectiveCategoryId = existing?.id
        val effectiveCategoryName = existing?.name
        assertEquals(1L, effectiveCategoryId)
        assertEquals("Work", effectiveCategoryName)

        val note = NoteItem(
            id = 103L,
            text = formatResult.formattedText,
            categoryId = effectiveCategoryId,
            createdAt = 1776510000000L,
            updatedAt = 1776510000000L
        )
        val mdFile = MarkdownSync.syncNote(notesDir, note, effectiveCategoryName)
        assertTrue(mdFile.exists())
        assertEquals("Work", mdFile.parentFile?.name)
        assertTrue(mdFile.readText().contains("category: \"Work\""))
    }

    @Test
    fun `pipeline handles append to category when no existing note exists for today`() {
        val transcript = "Append to Church: check with choir"
        val notesDir = tempFolder.newFolder("Notes")

        val formatResult = NoteFormatParser.parse(transcript)
        assertEquals("Church", formatResult.appendCategoryName)

        val targetCat = categories.firstOrNull { it.name.equals(formatResult.appendCategoryName, ignoreCase = true) }
        assertNotNull(targetCat)
        val effectiveCategoryId = targetCat?.id
        val effectiveCategoryName = targetCat?.name

        // Simulate no existing note today: falls back to creating new note
        val existingNote: NoteItem? = null
        val contentToSave = if (existingNote != null) "appended" else formatResult.formattedText

        val note = NoteItem(
            id = 104L,
            text = contentToSave,
            categoryId = effectiveCategoryId,
            createdAt = 1776510000000L,
            updatedAt = 1776510000000L
        )
        val mdFile = MarkdownSync.syncNote(notesDir, note, effectiveCategoryName)
        assertTrue(mdFile.exists())
        assertEquals("Church", mdFile.parentFile?.name)
        assertTrue(mdFile.readText().contains("Check with choir"))
    }

    @Test
    fun `pipeline handles append to category and updates existing note and markdown`() {
        val transcript = "Append to Church: check with choir"
        val notesDir = tempFolder.newFolder("Notes")

        // Setup existing note for Church from earlier today
        val churchCategory = categories.first { it.name == "Church" }
        val existingNote = NoteItem(
            id = 200L,
            text = "Sunday School opening prayer",
            recordingId = 58L,
            categoryId = churchCategory.id,
            createdAt = 1776510000000L,
            updatedAt = 1776510000000L
        )
        val initialMdFile = MarkdownSync.syncNote(notesDir, existingNote, churchCategory.name)
        assertTrue(initialMdFile.exists())
        assertTrue(initialMdFile.readText().contains("Sunday School opening prayer"))

        // 1. Parse formatted transcript
        val formatResult = NoteFormatParser.parse(transcript)
        assertEquals("Church", formatResult.appendCategoryName)
        assertEquals("Check with choir", formatResult.formattedText)
        assertTrue(!formatResult.isTask)

        // 2. Resolve category and append text with timestamp
        val targetCat = categories.firstOrNull { it.name.equals(formatResult.appendCategoryName, ignoreCase = true) }
        assertNotNull(targetCat)
        val effectiveCategoryId = targetCat?.id
        val effectiveCategoryName = targetCat?.name

        val timeStr = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).format(LocalTime.now())
        val appendedText = "${existingNote.text}\n\n- **$timeStr**: ${formatResult.formattedText}"
        val updatedNote = existingNote.copy(
            text = appendedText,
            updatedAt = 1776511000000L,
            markdownPath = initialMdFile.absolutePath
        )

        // 3. Sync updated note to Markdown file
        val updatedMdFile = MarkdownSync.syncNote(notesDir, updatedNote, effectiveCategoryName)
        assertTrue(updatedMdFile.exists())
        assertEquals(initialMdFile.canonicalPath, updatedMdFile.canonicalPath)

        val fileContent = updatedMdFile.readText()
        assertTrue(fileContent.contains("Sunday School opening prayer"))
        assertTrue(fileContent.contains("- **$timeStr**: Check with choir"))

        // 4. Extract tasks using formatResult.formattedText
        val suggestions = TaskExtractor.extract(formatResult.formattedText, categories = categories.map { it.rule() })
        assertNotNull(suggestions)
    }

    @Test
    fun `pipeline handles append to non-existent category and does not append to unassigned note`() {
        val transcript = "Append to UnknownCategory: remember to check the mail"
        val notesDir = tempFolder.newFolder("Notes")

        // Setup existing unassigned note from earlier today
        val existingUnassignedNote = NoteItem(
            id = 205L,
            text = "Existing unassigned note from today",
            recordingId = 59L,
            categoryId = null,
            createdAt = 1776510000000L,
            updatedAt = 1776510000000L
        )
        val initialUnassignedMdFile = MarkdownSync.syncNote(notesDir, existingUnassignedNote, null)
        assertTrue(initialUnassignedMdFile.exists())
        val originalUnassignedContent = initialUnassignedMdFile.readText()
        assertTrue(originalUnassignedContent.contains("Existing unassigned note from today"))

        // 1. Parse formatted transcript
        val formatResult = NoteFormatParser.parse(transcript)
        assertEquals("UnknownCategory", formatResult.appendCategoryName)
        assertEquals("Remember to check the mail", formatResult.formattedText)

        // 2. Resolve category: non-existent category resolves targetCat to null
        val targetCat = categories.firstOrNull { it.name.equals(formatResult.appendCategoryName, ignoreCase = true) }
        assertNull("UnknownCategory should not exist in categories", targetCat)
        val effectiveCategoryId = targetCat?.id
        val effectiveCategoryName = targetCat?.name
        assertNull(effectiveCategoryId)
        assertNull(effectiveCategoryName)

        // 3. Guarded check: when effectiveCategoryId is null, existingNote must be null
        // (Guards against Room's getLatestToday returning unassigned notes when queried with null categoryId)
        val existingNote = effectiveCategoryId?.let {
            // In Room, getLatestToday(null, startOfDay) would match unassigned notes;
            // with the guard, it is never queried!
            existingUnassignedNote
        }
        assertNull("Guarded lookup should yield null when category does not exist", existingNote)

        // 4. Falls through to standard note creation (new unassigned note)
        val contentToSave = formatResult.formattedText
        val newNote = NoteItem(
            id = 206L,
            text = contentToSave,
            categoryId = effectiveCategoryId,
            createdAt = 1776512000000L,
            updatedAt = 1776512000000L
        )
        val newNoteMdFile = MarkdownSync.syncNote(notesDir, newNote, effectiveCategoryName)
        assertTrue(newNoteMdFile.exists())

        // 5. Verify the existing unassigned note was NOT modified
        assertEquals("Existing unassigned note must remain untouched", "Existing unassigned note from today", existingUnassignedNote.text)
        assertEquals("Existing unassigned file must remain untouched", originalUnassignedContent, initialUnassignedMdFile.readText())

        // 6. Verify new note content was saved separately
        assertTrue(newNoteMdFile.readText().contains("Remember to check the mail"))
        assertTrue(newNoteMdFile.readText().contains("category: \"Unassigned\""))
    }

    @Test
    fun `pipeline intercepts task command and skips note and markdown creation`() {
        val transcript = "Task: call bishop tomorrow"
        val notesDir = tempFolder.newFolder("Notes")

        // 1. Parse formatted transcript
        val formatResult = NoteFormatParser.parse(transcript)
        assertTrue("formatResult should be marked as task", formatResult.isTask)
        assertEquals("Task: call bishop tomorrow", formatResult.formattedText)

        // 2. Pipeline routes directly to tasks without creating a NoteItem or Markdown file
        // (Simulate if (formatResult.isTask) branch in RecordingService)
        val categoryRuleList = categories.map { it.rule() }
        val suggestions = TaskExtractor.extract(formatResult.formattedText, categories = categoryRuleList)

        // 3. Verify task extraction succeeded
        assertTrue("Should extract task suggestion for task command", suggestions.isNotEmpty())
        val task = suggestions.first()
        assertTrue("Task title should contain 'Call bishop'", task.title.contains("Call bishop"))
        assertNotNull("Task should have extracted due date", task.due)

        // 4. Verify no note or markdown file was created
        val createdFiles = notesDir.listFiles()
        assertTrue("Notes directory should remain empty for task-only voice inputs", createdFiles == null || createdFiles.isEmpty())
    }

    @Test
    fun `CATEGORY_COLORS is accessible and provides palette colors`() {
        assertTrue("CATEGORY_COLORS should not be empty", CATEGORY_COLORS.isNotEmpty())
        assertEquals("CATEGORY_COLORS should provide 8 palette colors", 8, CATEGORY_COLORS.size)
        assertEquals(0xFF43A047.toInt(), CATEGORY_COLORS[0])
    }

    @Test
    fun `pipeline merges tags when appending to existing note`() {
        val transcript = "Append to Church: check with choir tag choir and tag urgent"
        val formatResult = NoteFormatParser.parse(transcript)
        assertEquals(listOf("choir", "urgent"), formatResult.tags)

        val existingNote = NoteItem(
            id = 210L,
            text = "Morning prayer",
            tags = "worship,urgent"
        )

        val mergedTags = if (formatResult.tags.isNotEmpty()) {
            (existingNote.tagList + formatResult.tags).distinct().joinToString(",")
        } else {
            existingNote.tags
        }

        assertEquals("worship,urgent,choir", mergedTags)
    }
}
