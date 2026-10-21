# Note Editor, Voice Formatting Triggers, Pinning, and Date Grouping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enhance Instant Recorder with an interactive note detail editor (delete, calendar export, share), date-grouped collapsible timeline, pinned notes section, and spoken markdown formatting triggers (bullet lists, titles, dynamic category creation, and note appending).

**Architecture:**
- `NoteItem` is extended with `pinned` and `tags` columns with Room migration v7 -> v8 (`MIGRATION_7_8`).
- `NoteFormatParser` intercepts speech transcripts to format markdown headings, bullet points, extract `#tags`, identify `"add category"` commands, and recognize `"append to"` directives.
- `NoteEditorSheet` bottom sheet allows viewing/editing note text, 1-tap delete, and opening Google Calendar with pre-populated note details via `Intent.ACTION_INSERT`.
- `NotesScreen` organizes notes into a top "📌 Pinned" block and collapsible date-grouped sections with sticky headers.

**Tech Stack:** Kotlin, Jetpack Compose, Room Database (v8), JUnit 4.

## Global Constraints

- **Preserve existing routing**: Spoken prefix matching against existing user categories continues to function cleanly alongside new commands.
- **Dynamic category safety**: When an explicit command `"add category [name]"` is spoken, sanitize the name and assign a distinct palette color from `CATEGORY_COLORS`.
- **Database safety**: Existing notes and tasks must be preserved through `MIGRATION_7_8` with zero data loss.
- **Keep swipe-to-delete**: In addition to the new delete button inside `NoteEditorSheet`, swipe-to-delete in the list must remain operational.

---

### Task 1: Data Model Migration (v7 → v8) for `pinned` and `tags`

**Files:**
- Modify: `app/src/main/java/app/instantrecorder/data/Note.kt`
- Modify: `app/src/main/java/app/instantrecorder/data/Database.kt`
- Modify: `app/src/main/java/app/instantrecorder/InstantRecorderApp.kt`
- Test: `app/src/test/java/app/instantrecorder/data/NoteEntityTest.kt`

**Interfaces:**
- Consumes: Room `@Entity`, `@Dao`, `AppDatabase`
- Produces: `NoteItem(pinned: Boolean = false, tags: String = "")`, `NoteDao.setPinned(id: Long, pinned: Boolean)`, `NoteDao.getLatestTodayForCategory(categoryId: Long?, startOfDay: Long): NoteItem?`

- [ ] **Step 1: Write failing unit test for NoteItem new properties and migration SQL**

Update `app/src/test/java/app/instantrecorder/data/NoteEntityTest.kt`:
```kotlin
@Test
fun `note item has pinned and tags defaults`() {
    val note = NoteItem(text = "Hello world")
    assertFalse(note.pinned)
    assertEquals("", note.tags)
}

@Test
fun `migration 7 to 8 adds pinned and tags columns`() {
    val db = ProxySQLiteDatabase()
    AppDatabase.MIGRATION_7_8.migrate(db)
    assertTrue(db.executedSql.any { it.contains("ALTER TABLE `notes` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0") })
    assertTrue(db.executedSql.any { it.contains("ALTER TABLE `notes` ADD COLUMN `tags` TEXT NOT NULL DEFAULT ''") })
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "app.instantrecorder.data.NoteEntityTest"`
Expected: FAIL.

- [ ] **Step 3: Update `Note.kt`, `Database.kt`, and `InstantRecorderApp.kt`**

In `app/src/main/java/app/instantrecorder/data/Note.kt`:
- Add `val pinned: Boolean = false` and `val tags: String = ""` to `NoteItem`.
- Add queries to `NoteDao`:
  - `@Query("UPDATE notes SET pinned = :pinned, updatedAt = :updatedAt WHERE id = :id") suspend fun setPinned(id: Long, pinned: Boolean, updatedAt: Long = System.currentTimeMillis())`
  - `@Query("SELECT * FROM notes WHERE (:categoryId IS NULL AND categoryId IS NULL) OR categoryId = :categoryId AND createdAt >= :startOfDay ORDER BY createdAt DESC LIMIT 1") suspend fun getLatestToday(categoryId: Long?, startOfDay: Long): NoteItem?`

In `app/src/main/java/app/instantrecorder/data/Database.kt`:
- Set `@Database(version = 8)`.
- Define `MIGRATION_7_8`:
```kotlin
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `tags` TEXT NOT NULL DEFAULT ''")
    }
}
```

In `app/src/main/java/app/instantrecorder/InstantRecorderApp.kt`:
- Add `AppDatabase.MIGRATION_7_8` to `addMigrations(...)`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "app.instantrecorder.data.NoteEntityTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/instantrecorder/data/Note.kt app/src/main/java/app/instantrecorder/data/Database.kt app/src/main/java/app/instantrecorder/InstantRecorderApp.kt app/src/test/java/app/instantrecorder/data/NoteEntityTest.kt
git commit -m "feat(database): add pinned and tags columns to NoteItem with MIGRATION_7_8"
```

---

### Task 2: Voice Formatting & Command Parser (`NoteFormatParser`)

**Files:**
- Create: `app/src/main/java/app/instantrecorder/notes/NoteFormatParser.kt`
- Test: `app/src/test/java/app/instantrecorder/notes/NoteFormatParserTest.kt`

**Interfaces:**
- Consumes: Raw spoken transcript String
- Produces: `NoteFormatResult(val title: String?, val formattedText: String, val tags: List<String>, val addCategoryName: String?, val appendCategoryName: String?, val isTask: Boolean)`

- [ ] **Step 1: Write failing unit tests for `NoteFormatParser`**

Create `app/src/test/java/app/instantrecorder/notes/NoteFormatParserTest.kt`:
```kotlin
package app.instantrecorder.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteFormatParserTest {

    @Test
    fun `parses title prefix into markdown heading`() {
        val result = NoteFormatParser.parse("Title: Sunday Sacrament Talk. Prepare thoughts on faith")
        assertEquals("Sunday Sacrament Talk", result.title)
        assertTrue(result.formattedText.startsWith("# Sunday Sacrament Talk\n\nPrepare thoughts on faith"))
    }

    @Test
    fun `parses spoken bullet triggers into markdown list`() {
        val result = NoteFormatParser.parse("Bullet read scriptures bullet say prayers bullet attend meetings")
        val expected = "- Read scriptures\n- Say prayers\n- Attend meetings"
        assertEquals(expected, result.formattedText)
    }

    @Test
    fun `extracts hashtag and tag keywords`() {
        val result = NoteFormatParser.parse("Great talk idea tag sermon and hashtag youth")
        assertTrue(result.tags.contains("sermon"))
        assertTrue(result.tags.contains("youth"))
    }

    @Test
    fun `parses add category command`() {
        val result = NoteFormatParser.parse("Add category Sunday Prep: review lesson material")
        assertEquals("Sunday Prep", result.addCategoryName)
        assertEquals("Review lesson material", result.formattedText)
    }

    @Test
    fun `parses append command`() {
        val result = NoteFormatParser.parse("Append to Church: check with choir director")
        assertEquals("Church", result.appendCategoryName)
        assertEquals("Check with choir director", result.formattedText)
    }

    @Test
    fun `identifies task prefix`() {
        val result = NoteFormatParser.parse("Task: call mom tomorrow at 5")
        assertTrue(result.isTask)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "app.instantrecorder.notes.NoteFormatParserTest"`
Expected: FAIL.

- [ ] **Step 3: Implement `NoteFormatParser`**

Create `app/src/main/java/app/instantrecorder/notes/NoteFormatParser.kt` implementing regex parsing for title, bullet points, tags, add category, append, and task flags.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "app.instantrecorder.notes.NoteFormatParserTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/instantrecorder/notes/NoteFormatParser.kt app/src/test/java/app/instantrecorder/notes/NoteFormatParserTest.kt
git commit -m "feat(notes): implement NoteFormatParser for spoken bullets, titles, tags, and commands"
```

---

### Task 3: Recording Pipeline Integration for Dynamic Commands & Appending

**Files:**
- Modify: `app/src/main/java/app/instantrecorder/recording/RecordingService.kt`
- Test: `app/src/test/java/app/instantrecorder/recording/RecordingPipelineIntegrationTest.kt`

**Interfaces:**
- Consumes: `NoteFormatParser.parse()`, `CategoryVoiceRouter.route()`, `app.db.notes()`, `app.db.categories()`, `MarkdownSync.syncNote()`

- [ ] **Step 1: Write integration test for dynamic category creation and note appending**

Add tests to `RecordingPipelineIntegrationTest.kt` verifying:
1. Spoken "Add category Choir: practice on Tuesday" inserts category "Choir" and files the note.
2. Spoken "Append to Church: second thought" appends to existing note created today.

- [ ] **Step 2: Update `RecordingService.kt`**

Integrate `NoteFormatParser.parse(text)`:
- If `parsed.isTask`: delegate to `TaskExtractor.extract(text)`.
- If `parsed.addCategoryName != null`: insert new Category if absent, assign ID.
- If `parsed.appendCategoryName != null`: look up today's latest note in category; if found, append formatted text with timestamp; update DB and Markdown file.
- Otherwise create standard note with `parsed.formattedText` and `parsed.tags`.

- [ ] **Step 3: Run unit tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: ALL PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/app/instantrecorder/recording/RecordingService.kt app/src/test/java/app/instantrecorder/recording/RecordingPipelineIntegrationTest.kt
git commit -m "feat(recording): handle dynamic category creation, markdown formatting, and note appending in RecordingService"
```

---

### Task 4: Note Detail Editor Sheet (`NoteEditorSheet`)

**Files:**
- Create: `app/src/main/java/app/instantrecorder/ui/NoteEditorSheet.kt`
- Modify: `app/src/main/java/app/instantrecorder/ui/MainViewModel.kt`
- Test: `app/src/test/java/app/instantrecorder/ui/NotesUiTest.kt`

**Interfaces:**
- Produces: `NoteEditorSheet(note: NoteItem, categories: List<Category>, onSave: (NoteItem) -> Unit, onDelete: () -> Unit, onCalendar: () -> Unit, onDismiss: () -> Unit)`

- [ ] **Step 1: Add update methods in `MainViewModel.kt`**

- `updateNoteText(note: NoteItem, newText: String)`
- `toggleNotePinned(note: NoteItem)`

- [ ] **Step 2: Implement `NoteEditorSheet.kt`**

- Full-screen or ModalBottomSheet with:
  - Header: Category badge, Pin icon button, Close button.
  - Body: Scrollable `OutlinedTextField` with full note text.
  - Category selector chip row.
  - Bottom action bar:
    - 🗑️ Delete button (calls `onDelete()`).
    - 📅 Add to Calendar button (fires `Intent.ACTION_INSERT`).
    - 📤 Share button (shares note text).

- [ ] **Step 3: Wire into `NotesScreen.kt`**

Tapping any note row opens `NoteEditorSheet`.

- [ ] **Step 4: Run tests and assemble**

Run: `./gradlew :app:testDebugUnitTest` and `./gradlew :app:assembleDebug`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/instantrecorder/ui/NoteEditorSheet.kt app/src/main/java/app/instantrecorder/ui/NotesScreen.kt app/src/main/java/app/instantrecorder/ui/MainViewModel.kt
git commit -m "feat(ui): add NoteEditorSheet with inline editing, delete button, and calendar integration"
```

---

### Task 5: Date Grouping, Collapsible Headers, & Pinned Thoughts Section

**Files:**
- Modify: `app/src/main/java/app/instantrecorder/ui/NotesScreen.kt`
- Test: `app/src/test/java/app/instantrecorder/ui/NotesUiTest.kt`

**Interfaces:**
- Consumes: `List<NoteItem>`, `Category` list
- Produces: Date grouping helper, Pinned cards section, collapsible accordion headers.

- [ ] **Step 1: Add date grouping helper and tests**

In `NotesUiTest.kt`:
Verify grouping into relative buckets ("Today", "Yesterday", "Oct 19, 2026").

- [ ] **Step 2: Implement Pinned Section and Collapsible Date Headers in `NotesScreen.kt`**

- If `pinnedNotes.isNotEmpty()`: render distinct top section with pin badge.
- Group non-pinned notes by day using `LocalDate`.
- `stickyHeader` with expand/collapse chevron toggle and note count badge.
- Animate expansion using `AnimatedVisibility`.

- [ ] **Step 3: Run full test suite and assemble**

Run: `./gradlew :app:testDebugUnitTest` and `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/app/instantrecorder/ui/NotesScreen.kt app/src/test/java/app/instantrecorder/ui/NotesUiTest.kt
git commit -m "feat(ui): implement pinned thoughts section and collapsible date-grouped timeline"
```
