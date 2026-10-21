# Voice Note Organizer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transform Instant Recorder into a searchable voice note organizer that routes spoken thoughts into user-defined categories via spoken prefix, files unmatched notes into an Unassigned folder with 1-tap triage, mirrors notes as Markdown files, and supports Google Assistant triggers.

**Architecture:**
- Spoken transcripts are processed through `CategoryVoiceRouter` which strictly checks the opening prefix against the user's existing `categories` table.
- Notes are persisted in Room (`NoteItem` entity) with full-text search capability and mirrored to human-readable `.md` Markdown files in device storage (`Documents/InstantRecorder/Notes/`).
- The Compose UI adds a primary "Notes" tab with a search bar, an "Unassigned (N)" filter chip, and a 1-tap category assignment sheet.
- Android system note intent (`CREATE_NOTE`) and Google Assistant BII are added to `AndroidManifest.xml` and `shortcuts.xml`.

**Tech Stack:** Kotlin, Jetpack Compose, Android Room Database, Whisper.cpp (JNI), JUnit 4.

## Global Constraints

- **Strict user-defined categories**: Category matching must only match against categories explicitly defined by the user in the database. The app must never create new categories from speech.
- **Prefix cleanup**: When a category prefix is matched at the start of a transcript, strip the category prefix and trailing punctuation/whitespace from the note body.
- **Fallback to Unassigned**: When no existing category is matched, `categoryId` must be `null` (representing Unassigned).
- **Preserve existing functionality**: Volume chord trigger, silence detection, and existing task extraction capabilities must remain fully operational.

---

### Task 1: CategoryVoiceRouter Spoken Prefix Parser

**Files:**
- Create: `app/src/main/java/app/instantrecorder/notes/CategoryVoiceRouter.kt`
- Test: `app/src/test/java/app/instantrecorder/notes/CategoryVoiceRouterTest.kt`

**Interfaces:**
- Consumes: `app.instantrecorder.data.Category` (from database)
- Produces: `CategoryVoiceRouter.route(transcript: String, categories: List<Category>): CategoryRouteResult`
  - `data class CategoryRouteResult(val categoryId: Long?, val cleanedText: String)`

- [ ] **Step 1: Write the failing unit tests for `CategoryVoiceRouter`**

Create `app/src/test/java/app/instantrecorder/notes/CategoryVoiceRouterTest.kt`:
```kotlin
package app.instantrecorder.notes

import app.instantrecorder.data.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryVoiceRouterTest {

    private val categories = listOf(
        Category(id = 1, name = "Church", color = 0xFF43A047.toInt()),
        Category(id = 2, name = "Work", color = 0xFF1E88E5.toInt()),
        Category(id = 3, name = "Family", color = 0xFFFB8C00.toInt())
    )

    @Test
    fun `matching category prefix assigns id and cleans text with colon`() {
        val result = CategoryVoiceRouter.route("Church: Prepare the lesson on Sunday", categories)
        assertEquals(1L, result.categoryId)
        assertEquals("Prepare the lesson on Sunday", result.cleanedText)
    }

    @Test
    fun `matching category prefix with period`() {
        val result = CategoryVoiceRouter.route("Work. Follow up with Sarah regarding Q3 numbers", categories)
        assertEquals(2L, result.categoryId)
        assertEquals("Follow up with Sarah regarding Q3 numbers", result.cleanedText)
    }

    @Test
    fun `matching category prefix with dash and space`() {
        val result = CategoryVoiceRouter.route("Family - Plan weekend trip to the coast", categories)
        assertEquals(3L, result.categoryId)
        assertEquals("Plan weekend trip to the coast", result.cleanedText)
    }

    @Test
    fun `matching category prefix case-insensitive`() {
        val result = CategoryVoiceRouter.route("church, talk to bishop", categories)
        assertEquals(1L, result.categoryId)
        assertEquals("Talk to bishop", result.cleanedText)
    }

    @Test
    fun `matching category prefix with conversational lead-in`() {
        val result = CategoryVoiceRouter.route("For church: review sacrament schedule", categories)
        assertEquals(1L, result.categoryId)
        assertEquals("Review sacrament schedule", result.cleanedText)
    }

    @Test
    fun `unmatched category leaves text intact and category null for unassigned`() {
        val raw = "Remember to buy extra AAA batteries and paint"
        val result = CategoryVoiceRouter.route(raw, categories)
        assertNull(result.categoryId)
        assertEquals(raw, result.cleanedText)
    }

    @Test
    fun `does not create or match non-existent category`() {
        val raw = "Gym: do leg day tomorrow"
        val result = CategoryVoiceRouter.route(raw, categories)
        assertNull(result.categoryId)
        assertEquals(raw, result.cleanedText)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "app.instantrecorder.notes.CategoryVoiceRouterTest"`
Expected: FAIL with compilation error (Unresolved reference: notes / CategoryVoiceRouter).

- [ ] **Step 3: Implement `CategoryVoiceRouter`**

Create `app/src/main/java/app/instantrecorder/notes/CategoryVoiceRouter.kt`:
```kotlin
package app.instantrecorder.notes

import app.instantrecorder.data.Category

data class CategoryRouteResult(
    val categoryId: Long?,
    val cleanedText: String
)

object CategoryVoiceRouter {

    /**
     * Inspects the opening of [transcript] and checks if it begins with an existing category name.
     * Only categories present in [categories] can be matched.
     * If matched, returns the category id and the note text with the prefix stripped and capitalized.
     * If no match, returns categoryId = null (Unassigned) and the original text trimmed.
     */
    fun route(transcript: String, categories: List<Category>): CategoryRouteResult {
        val trimmed = transcript.trim()
        if (trimmed.isEmpty()) {
            return CategoryRouteResult(categoryId = null, cleanedText = "")
        }

        for (category in categories) {
            val pattern = Regex(
                """^(?:(?:for|in|under)\s+)?${Regex.escape(category.name)}(?:\s+notes?)?\s*(?:[:,.;\-–—]+\s*|\s+)""",
                RegexOption.IGNORE_CASE
            )
            val match = pattern.find(trimmed)
            if (match != null) {
                val remainder = trimmed.substring(match.range.last + 1).trim()
                val capitalized = remainder.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase() else it.toString()
                }
                return CategoryRouteResult(
                    categoryId = category.id,
                    cleanedText = capitalized.ifEmpty { trimmed }
                )
            }
        }

        return CategoryRouteResult(
            categoryId = null,
            cleanedText = trimmed
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "app.instantrecorder.notes.CategoryVoiceRouterTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/instantrecorder/notes/CategoryVoiceRouter.kt app/src/test/java/app/instantrecorder/notes/CategoryVoiceRouterTest.kt
git commit -m "feat(notes): implement CategoryVoiceRouter for spoken category prefix detection"
```

---

### Task 2: NoteItem Entity, Room DAO, and Database Migration

**Files:**
- Create: `app/src/main/java/app/instantrecorder/data/Note.kt`
- Modify: `app/src/main/java/app/instantrecorder/data/Database.kt`
- Test: `app/src/test/java/app/instantrecorder/data/NoteEntityTest.kt`

**Interfaces:**
- Consumes: Room `@Entity`, `@Dao`, `@Database`
- Produces: `NoteItem` entity, `NoteDao` interface with `observeAll()`, `observeByCategory()`, `observeUnassigned()`, `insert()`, `update()`, `delete()`

- [ ] **Step 1: Write unit tests for Note entity creation and filtering helper**

Create `app/src/test/java/app/instantrecorder/data/NoteEntityTest.kt`:
```kotlin
package app.instantrecorder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteEntityTest {

    @Test
    fun `note item defaults and properties`() {
        val note = NoteItem(
            text = "Sunday school lesson outline",
            categoryId = 1L
        )
        assertEquals("Sunday school lesson outline", note.text)
        assertEquals(1L, note.categoryId)
        assertNull(note.markdownPath)
        assertTrue(note.createdAt > 0)
    }

    @Test
    fun `unassigned note has null categoryId`() {
        val note = NoteItem(
            text = "Miscellaneous thought"
        )
        assertNull(note.categoryId)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "app.instantrecorder.data.NoteEntityTest"`
Expected: FAIL with "Unresolved reference: NoteItem".

- [ ] **Step 3: Create `NoteItem` entity and `NoteDao`**

Create `app/src/main/java/app/instantrecorder/data/Note.kt`:
```kotlin
package app.instantrecorder.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "notes")
data class NoteItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val recordingId: Long? = null,
    val categoryId: Long? = null, // null = Unassigned
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val markdownPath: String? = null
)

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<NoteItem>>

    @Query("SELECT * FROM notes WHERE categoryId = :categoryId ORDER BY createdAt DESC")
    fun observeByCategory(categoryId: Long): Flow<List<NoteItem>>

    @Query("SELECT * FROM notes WHERE categoryId IS NULL ORDER BY createdAt DESC")
    fun observeUnassigned(): Flow<List<NoteItem>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: Long): NoteItem?

    @Query("SELECT * FROM notes WHERE text LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    fun searchNotes(query: String): Flow<List<NoteItem>>

    @Insert
    suspend fun insert(note: NoteItem): Long

    @Update
    suspend fun update(note: NoteItem)

    @Delete
    suspend fun delete(note: NoteItem)

    @Query("UPDATE notes SET categoryId = :categoryId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setCategory(id: Long, categoryId: Long?, updatedAt: Long = System.currentTimeMillis())
}
```

- [ ] **Step 4: Register `NoteItem` and `NoteDao` in `AppDatabase` with migration**

Modify `app/src/main/java/app/instantrecorder/data/Database.kt`:
Add `NoteItem::class` to `@Database(entities = [...])`, increment version, add `abstract fun notes(): NoteDao`, and add migration:
```kotlin
val MIGRATION_ADD_NOTES = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS notes (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                text TEXT NOT NULL,
                recordingId INTEGER,
                categoryId INTEGER,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                markdownPath TEXT
            )
            """.trimIndent()
        )
    }
}
```

- [ ] **Step 5: Run tests to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "app.instantrecorder.data.NoteEntityTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/instantrecorder/data/Note.kt app/src/main/java/app/instantrecorder/data/Database.kt app/src/test/java/app/instantrecorder/data/NoteEntityTest.kt
git commit -m "feat(database): add NoteItem entity, NoteDao, and database migration"
```

---

### Task 3: Markdown File Sync Manager

**Files:**
- Create: `app/src/main/java/app/instantrecorder/notes/MarkdownSync.kt`
- Test: `app/src/test/java/app/instantrecorder/notes/MarkdownSyncTest.kt`

**Interfaces:**
- Consumes: `NoteItem`, category name (optional), base directory `File`
- Produces: `MarkdownSync.syncNote(baseDir: File, note: NoteItem, categoryName: String?): File`

- [ ] **Step 1: Write failing tests for `MarkdownSync`**

Create `app/src/test/java/app/instantrecorder/notes/MarkdownSyncTest.kt`:
```kotlin
package app.instantrecorder.notes

import app.instantrecorder.data.NoteItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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
            createdAt = 1776510000000L
        )

        val file = MarkdownSync.syncNote(baseDir, note, categoryName = "Church")
        assertTrue(file.exists())
        assertEquals("Church", file.parentFile.name)
        val content = file.readText()
        assertTrue(content.contains("id: 42"))
        assertTrue(content.contains("category: Church"))
        assertTrue(content.contains("Outline for sacrament meeting talk"))
    }

    @Test
    fun `syncNote places unassigned note in Unassigned directory`() {
        val baseDir = tempFolder.newFolder("InstantRecorder")
        val note = NoteItem(
            id = 99,
            text = "Random idea about fixing the garage door",
            categoryId = null,
            createdAt = 1776510000000L
        )

        val file = MarkdownSync.syncNote(baseDir, note, categoryName = null)
        assertTrue(file.exists())
        assertEquals("Unassigned", file.parentFile.name)
        val content = file.readText()
        assertTrue(content.contains("category: Unassigned"))
        assertTrue(content.contains("Random idea about fixing the garage door"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "app.instantrecorder.notes.MarkdownSyncTest"`
Expected: FAIL with "Unresolved reference: MarkdownSync".

- [ ] **Step 3: Implement `MarkdownSync`**

Create `app/src/main/java/app/instantrecorder/notes/MarkdownSync.kt`:
```kotlin
package app.instantrecorder.notes

import app.instantrecorder.data.NoteItem
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MarkdownSync {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)

    /**
     * Writes or overwrites a note as a Markdown file with frontmatter inside baseDir/{categoryName}/.
     */
    fun syncNote(baseDir: File, note: NoteItem, categoryName: String?): File {
        val folderName = sanitizeFileName(categoryName ?: "Unassigned")
        val categoryFolder = File(baseDir, folderName)
        if (!categoryFolder.exists()) {
            categoryFolder.mkdirs()
        }

        val timestamp = dateFormat.format(Date(note.createdAt))
        val fileName = "${timestamp}_note_${note.id}.md"
        val noteFile = File(categoryFolder, fileName)

        val frontmatter = buildString {
            appendLine("---")
            appendLine("id: ${note.id}")
            appendLine("category: ${categoryName ?: "Unassigned"}")
            appendLine("created_at: ${isoFormat.format(Date(note.createdAt))}")
            appendLine("updated_at: ${isoFormat.format(Date(note.updatedAt))}")
            if (note.recordingId != null) {
                appendLine("recording_id: ${note.recordingId}")
            }
            appendLine("---")
            appendLine()
            appendLine(note.text)
        }

        noteFile.writeText(frontmatter)
        return noteFile
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "app.instantrecorder.notes.MarkdownSyncTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/instantrecorder/notes/MarkdownSync.kt app/src/test/java/app/instantrecorder/notes/MarkdownSyncTest.kt
git commit -m "feat(notes): implement MarkdownSync for saving notes as markdown files"
```

---

### Task 4: Recording Pipeline Integration

**Files:**
- Modify: `app/src/main/java/app/instantrecorder/recording/RecordingService.kt`
- Modify: `app/src/main/java/app/instantrecorder/transcribe/Transcriber.kt` (optional vocabulary prompt hint)

**Interfaces:**
- Consumes: `CategoryVoiceRouter.route`, `MarkdownSync.syncNote`, `app.db.notes()`, `app.db.categories()`
- Produces: Automatic note creation and category routing upon transcription completion.

- [ ] **Step 1: Inspect transcription result handler in `RecordingService.kt`**

Lines 270-280 in `RecordingService.kt`:
```kotlin
val updated = recording.copy(...)
dao.update(updated)
if (text.isNotBlank()) Notifications.showResult(this, updated, TaskExtractor.extract(text))
```

- [ ] **Step 2: Add note creation and spoken routing to `RecordingService.kt`**

In `RecordingService.transcribe(id: Long)`:
```kotlin
if (text.isNotBlank()) {
    val categories = app.db.categories().getAll() // or list from db
    val route = CategoryVoiceRouter.route(text, categories)
    val categoryName = categories.firstOrNull { it.id == route.categoryId }?.name

    val noteId = app.db.notes().insert(
        NoteItem(
            text = route.cleanedText,
            recordingId = updated.id,
            categoryId = route.categoryId,
            createdAt = updated.createdAt
        )
    )

    // Sync to Markdown file in device external files or documents
    val notesDir = File(getExternalFilesDir(null) ?: filesDir, "Notes")
    val savedNote = app.db.notes().get(noteId)
    if (savedNote != null) {
        val mdFile = MarkdownSync.syncNote(notesDir, savedNote, categoryName)
        app.db.notes().update(savedNote.copy(markdownPath = mdFile.absolutePath))
    }

    Notifications.showResult(this, updated, TaskExtractor.extract(route.cleanedText))
}
```

- [ ] **Step 3: Run unit tests to verify no regressions**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/app/instantrecorder/recording/RecordingService.kt
git commit -m "feat(recording): route transcripts to notes with category prefix matching and markdown sync"
```

---

### Task 5: Compose UI - Notes Tab, Search, and Unassigned Triage Sheet

**Files:**
- Create: `app/src/main/java/app/instantrecorder/ui/NotesScreen.kt`
- Modify: `app/src/main/java/app/instantrecorder/ui/HomeScreen.kt`
- Modify: `app/src/main/java/app/instantrecorder/ui/MainViewModel.kt`

**Interfaces:**
- Consumes: `MainViewModel.notes`, `MainViewModel.unassignedNotes`, `MainViewModel.setNoteCategory()`
- Produces:
  - 3-tab `PrimaryTabRow` (Notes, Tasks, Recordings)
  - Search bar filtering notes in real-time
  - Filter chips: `All`, `Unassigned (N)` (highlighted badge), plus user categories
  - 1-tap "Assign" button opening category selector sheet

- [ ] **Step 1: Add note flows and actions to `MainViewModel.kt`**

Expose `notes: StateFlow<List<NoteItem>>`, `searchQuery: MutableStateFlow<String>`, and `setNoteCategory(noteId: Long, categoryId: Long?)`.

- [ ] **Step 2: Create `NotesScreen.kt` components**

Create `app/src/main/java/app/instantrecorder/ui/NotesScreen.kt` containing:
- `NoteRow(note: NoteItem, category: Category?, onAssignCategory: () -> Unit, onDelete: () -> Unit)`
- `AssignCategoryBottomSheet(categories: List<Category>, onSelectCategory: (Category) -> Unit, onDismiss: () -> Unit)`
- Note search bar and list.

- [ ] **Step 3: Update `HomeScreen.kt` tabs**

Update `PrimaryTabRow`:
- Tab 0: `Notes`
- Tab 1: `Tasks (${tasks.count { !it.done }})`
- Tab 2: `Recordings`

Add "Unassigned" chip with count badge.

- [ ] **Step 4: Verify build and compile**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/instantrecorder/ui/NotesScreen.kt app/src/main/java/app/instantrecorder/ui/HomeScreen.kt app/src/main/java/app/instantrecorder/ui/MainViewModel.kt
git commit -m "feat(ui): add Notes tab, search, unassigned filter chip, and 1-tap assign sheet"
```

---

### Task 6: Google Assistant & "Hey Google" Shortcut Integration

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/xml/shortcuts.xml`

**Interfaces:**
- Consumes: Android App Actions / BII
- Produces: System-level voice trigger capability for "Hey Google, take a note with Instant Recorder"

- [ ] **Step 1: Add `android.intent.action.CREATE_NOTE` to `MainActivity` in `AndroidManifest.xml`**

```xml
<intent-filter>
    <action android:name="android.intent.action.CREATE_NOTE" />
    <category android:name="android.intent.category.DEFAULT" />
</intent-filter>
```

- [ ] **Step 2: Update `shortcuts.xml` with Google Assistant capability**

```xml
<capability android:name="actions.intent.CREATE_NOTE">
    <intent
        android:action="app.instantrecorder.action.QUICK_RECORD"
        android:targetPackage="io.github.instantrecorder"
        android:targetClass="app.instantrecorder.ui.MainActivity" />
</capability>
```

- [ ] **Step 3: Run full unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: ALL PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/res/xml/shortcuts.xml
git commit -m "feat(assistant): add CREATE_NOTE intent and Google Assistant shortcut capabilities"
```
