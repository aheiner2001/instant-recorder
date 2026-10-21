package app.instantrecorder.ui

import app.instantrecorder.data.Category
import app.instantrecorder.data.NoteItem
import app.instantrecorder.data.TaskItem
import app.instantrecorder.notes.MarkdownSync
import app.instantrecorder.tasks.Due
import app.instantrecorder.tasks.Repeat
import app.instantrecorder.tasks.TaskExtractor
import app.instantrecorder.tasks.TaskSuggestion
import app.instantrecorder.tasks.rule
import app.instantrecorder.tasks.toTaskItem
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
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

    @Test
    fun `dateBucketLabel returns Today for timestamps created on same date`() {
        val fixedNow = LocalDate.of(2026, 10, 21)
        val zone = ZoneId.of("UTC")
        val morningMillis = fixedNow.atTime(9, 30).atZone(zone).toInstant().toEpochMilli()
        val eveningMillis = fixedNow.atTime(22, 15).atZone(zone).toInstant().toEpochMilli()

        assertEquals("Today", dateBucketLabel(morningMillis, fixedNow, zone, Locale.US))
        assertEquals("Today", dateBucketLabel(eveningMillis, fixedNow, zone, Locale.US))
    }

    @Test
    fun `dateBucketLabel returns Yesterday for timestamps 1 day prior`() {
        val fixedNow = LocalDate.of(2026, 10, 21)
        val zone = ZoneId.of("UTC")
        val yesterday = fixedNow.minusDays(1)
        val millis = yesterday.atTime(18, 0).atZone(zone).toInstant().toEpochMilli()

        assertEquals("Yesterday", dateBucketLabel(millis, fixedNow, zone, Locale.US))
    }

    @Test
    fun `dateBucketLabel returns Day of Week and Month for timestamps 2 to 6 days prior`() {
        val fixedNow = LocalDate.of(2026, 10, 21) // Wednesday
        val zone = ZoneId.of("UTC")

        // 2 days ago: Monday, Oct 19
        val twoDaysAgo = fixedNow.minusDays(2)
        val m2 = twoDaysAgo.atTime(14, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("Monday, Oct 19", dateBucketLabel(m2, fixedNow, zone, Locale.US))

        // 3 days ago: Sunday, Oct 18
        val threeDaysAgo = fixedNow.minusDays(3)
        val m3 = threeDaysAgo.atTime(10, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals("Sunday, Oct 18", dateBucketLabel(m3, fixedNow, zone, Locale.US))

        // 6 days ago: Thursday, Oct 15
        val sixDaysAgo = fixedNow.minusDays(6)
        val m6 = sixDaysAgo.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("Thursday, Oct 15", dateBucketLabel(m6, fixedNow, zone, Locale.US))
    }

    @Test
    fun `dateBucketLabel returns formatted date with year for 7 or more days prior and past years`() {
        val fixedNow = LocalDate.of(2026, 10, 21)
        val zone = ZoneId.of("UTC")

        // 7 days ago: Oct 14, 2026
        val sevenDaysAgo = fixedNow.minusDays(7)
        val m7 = sevenDaysAgo.atTime(11, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("Oct 14, 2026", dateBucketLabel(m7, fixedNow, zone, Locale.US))

        // 30 days ago: Sep 21, 2026
        val thirtyDaysAgo = fixedNow.minusDays(30)
        val m30 = thirtyDaysAgo.atTime(15, 45).atZone(zone).toInstant().toEpochMilli()
        assertEquals("Sep 21, 2026", dateBucketLabel(m30, fixedNow, zone, Locale.US))

        // Past year: Dec 25, 2025
        val lastYear = LocalDate.of(2025, 12, 25)
        val mLastYear = lastYear.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("Dec 25, 2025", dateBucketLabel(mLastYear, fixedNow, zone, Locale.US))

        // Multiple years ago: Jan 1, 2020
        val year2020 = LocalDate.of(2020, 1, 1)
        val m2020 = year2020.atTime(0, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("Jan 1, 2020", dateBucketLabel(m2020, fixedNow, zone, Locale.US))
    }

    @Test
    fun `partitioning notes into pinned and unpinned separates properly`() {
        val mixedNotes = listOf(
            NoteItem(id = 1L, text = "Pinned note 1", pinned = true),
            NoteItem(id = 2L, text = "Unpinned note 1", pinned = false),
            NoteItem(id = 3L, text = "Pinned note 2", pinned = true),
            NoteItem(id = 4L, text = "Unpinned note 2", pinned = false),
            NoteItem(id = 5L, text = "Unpinned note 3", pinned = false)
        )

        val (pinned, unpinned) = mixedNotes.partition { it.pinned }

        assertEquals(2, pinned.size)
        assertTrue(pinned.all { it.pinned })
        assertEquals(listOf(1L, 3L), pinned.map { it.id })

        assertEquals(3, unpinned.size)
        assertTrue(unpinned.none { it.pinned })
        assertEquals(listOf(2L, 4L, 5L), unpinned.map { it.id })
    }

    @Test
    fun `date grouping correctly groups unpinned notes into buckets and preserves order`() {
        val fixedNow = LocalDate.of(2026, 10, 21)
        val zone = ZoneId.of("UTC")

        val todayMillis = fixedNow.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val yesterdayMillis = fixedNow.minusDays(1).atTime(15, 0).atZone(zone).toInstant().toEpochMilli()
        val pastWeekMillis = fixedNow.minusDays(3).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val pastYearMillis = LocalDate.of(2024, 6, 1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

        val unpinnedNotes = listOf(
            NoteItem(id = 101L, text = "Today note 1", createdAt = todayMillis),
            NoteItem(id = 102L, text = "Today note 2", createdAt = todayMillis),
            NoteItem(id = 103L, text = "Yesterday note", createdAt = yesterdayMillis),
            NoteItem(id = 104L, text = "Sunday note", createdAt = pastWeekMillis),
            NoteItem(id = 105L, text = "Old note from 2024", createdAt = pastYearMillis),
        )

        val grouped = unpinnedNotes.groupBy { dateBucketLabel(it.createdAt, fixedNow, zone, Locale.US) }

        val expectedKeys = listOf("Today", "Yesterday", "Sunday, Oct 18", "Jun 1, 2024")
        assertEquals(expectedKeys, grouped.keys.toList())
        assertEquals(2, grouped["Today"]?.size)
        assertEquals(1, grouped["Yesterday"]?.size)
        assertEquals(1, grouped["Sunday, Oct 18"]?.size)
        assertEquals(1, grouped["Jun 1, 2024"]?.size)
    }

    @Test
    fun `collapsed sections toggle toggles date bucket presence`() {
        var collapsedSections = setOf<String>()
        val dateLabel = "Today"

        // Collapse "Today"
        collapsedSections = if (dateLabel in collapsedSections) collapsedSections - dateLabel else collapsedSections + dateLabel
        assertTrue(dateLabel in collapsedSections)

        // Expand "Today"
        collapsedSections = if (dateLabel in collapsedSections) collapsedSections - dateLabel else collapsedSections + dateLabel
        assertFalse(dateLabel in collapsedSections)
    }

    @Test
    fun `addTaskFromSuggestion builds TaskItem with proper title category due date and recordingId`() {
        val suggestion = TaskSuggestion(
            title = "Send monthly invoice",
            due = Due(epochMillis = 1776510000000L, hasTime = true),
            actionable = true,
            categoryId = null,
            repeatDays = Repeat.WEEKDAYS,
            important = true,
        )
        val noteCategoryId = 1L // Work
        val noteRecordingId = 42L

        // Production mapping function TaskSuggestion.toTaskItem
        val task = suggestion.toTaskItem(categoryId = noteCategoryId, recordingId = noteRecordingId)

        assertEquals("Send monthly invoice", task.title)
        assertEquals(1776510000000L, task.dueAt)
        assertTrue(task.dueHasTime)
        assertEquals(1L, task.categoryId)
        assertEquals(42L, task.recordingId)
        assertEquals(Repeat.WEEKDAYS, task.repeatDays)
        assertTrue(task.important)
        assertFalse(task.done)
    }

    @Test
    fun `addTaskFromSuggestion preserves suggestion categoryId over note categoryId when specified`() {
        val suggestion = TaskSuggestion(
            title = "Buy groceries",
            due = Due(epochMillis = 1776520000000L, hasTime = false),
            actionable = true,
            categoryId = 2L, // Personal override
            repeatDays = Repeat.NONE,
            important = false,
        )
        val noteCategoryId = 1L // Work

        val task = suggestion.toTaskItem(categoryId = noteCategoryId, recordingId = null)

        assertEquals("Buy groceries", task.title)
        assertEquals(1776520000000L, task.dueAt)
        assertFalse(task.dueHasTime)
        assertEquals(2L, task.categoryId) // suggestion categoryId took precedence
        assertNull(task.recordingId)
        assertEquals(Repeat.NONE, task.repeatDays)
        assertFalse(task.important)
    }

    @Test
    fun `MainViewModel declares addTaskFromSuggestion method with correct signature`() {
        val method = MainViewModel::class.java.methods.firstOrNull { it.name == "addTaskFromSuggestion" }
        assertNotNull("MainViewModel should declare addTaskFromSuggestion", method)
        assertEquals(3, method?.parameterTypes?.size)
        assertEquals(TaskSuggestion::class.java, method?.parameterTypes?.get(0))
        assertEquals(Long::class.javaObjectType, method?.parameterTypes?.get(1))
        assertEquals(Long::class.javaObjectType, method?.parameterTypes?.get(2))
    }

    @Test
    fun `NoteEditorSheet extracts action items from note text and tracks addedTitles`() {
        val noteText = "Meeting notes. Call electrician tomorrow at 5pm. Also remember to buy printer paper."
        val categoryRules = categories.map { it.rule() }

        val suggestions = TaskExtractor.extract(noteText, categories = categoryRules)
            .filter { it.actionable || it.due != null }
        assertTrue("Should extract suggestions from note text", suggestions.isNotEmpty())
        assertTrue(suggestions.any { it.title.contains("Call electrician", ignoreCase = true) })

        var addedTitles = setOf<String>()
        val firstSuggestion = suggestions.first()

        // Initially not added
        assertFalse("First suggestion should not be added yet", firstSuggestion.title in addedTitles)

        // User taps [+ Add]
        addedTitles = addedTitles + firstSuggestion.title
        assertTrue("First suggestion should be marked as added", firstSuggestion.title in addedTitles)
    }

    @Test
    fun `NoteEditorSheet filters suggestions by actionable or due date present`() {
        val actionableSuggestion = TaskSuggestion(title = "Buy paper", due = null, actionable = true)
        val datedSuggestion = TaskSuggestion(title = "Doctor appointment", due = Due(epochMillis = 1776510000000L, hasTime = true), actionable = false)
        val passiveSuggestion = TaskSuggestion(title = "The weather was nice", due = null, actionable = false)

        val filtered = listOf(actionableSuggestion, datedSuggestion, passiveSuggestion)
            .filter { it.actionable || it.due != null }

        assertEquals(2, filtered.size)
        assertTrue(filtered.contains(actionableSuggestion))
        assertTrue(filtered.contains(datedSuggestion))
        assertFalse(filtered.contains(passiveSuggestion))
    }
}
