# Design Spec: Note Editor, Voice Formatting Triggers, Pinning, and Date Grouping

**Date:** 2026-10-21  
**Status:** Approved  
**Author:** Antigravity / Aaron Heiner  

---

## 1. Overview & Goals

This specification outlines the next evolution of Instant Recorder's note-taking capabilities:
1. **Interactive Note Detail & Editor (`NoteEditorSheet`)**: Full editing, explicit delete button, and 1-tap "Add to Calendar" button (while keeping swipe-to-delete).
2. **Date-Grouped Timeline**: Notes organized by day ("Today", "Yesterday", "Oct 19", etc.) with collapsible sticky headers.
3. **Voice Formatting Triggers**: Spoken commands for structured Markdown notes:
   - `"Title: ..."` / `"Heading: ..."` → `# Title`
   - `"Bullet: ..."` / `"List: ..."` → `- Bullet item`
   - `"Task: ..."` → Routes to Tasks tab
   - `"Append to [Category]: ..."` → Appends timestamped bullet to today's category note
   - `"Add category [Name]: ..."` → Dynamically creates new user category
4. **Pinned Brainstorm Notes**: Pin active prep notes to the top (`pinned` flag in DB) and support voice-appending to pinned notes.
5. **Topic #Tags**: Voice trigger `"tag [word]"` / `"hashtag [word]"` auto-generates `#tag` chips for easy sub-filtering within categories.

---

## 2. Architecture & Data Model

### 2.1 Database Schema Updates (v7 → v8)

Add `pinned` and `tags` columns to `NoteItem`:
```kotlin
@Entity(tableName = "notes")
data class NoteItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val recordingId: Long? = null,
    val categoryId: Long? = null, // null = Unassigned
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val markdownPath: String? = null,
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    @ColumnInfo(defaultValue = "''") val tags: String = "" // comma-separated tags e.g. "talk,lesson"
)
```

**Migration `MIGRATION_7_8`**:
```sql
ALTER TABLE `notes` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0;
ALTER TABLE `notes` ADD COLUMN `tags` TEXT NOT NULL DEFAULT '';
```

---

## 3. Component Specifications

### 3.1 Spoken Formatting Parser (`NoteFormatParser`)
Operates during speech-to-text post-processing:

1. **Title / Heading Trigger**:
   - `"Title: [Text]"` or `"Heading: [Text]"` → `# [Text]`
2. **Bullet List Trigger**:
   - Splits on `"bullet"` or `"next bullet"`:
     - `"Bullet [Item 1] bullet [Item 2]"` →
       ```markdown
       - Item 1
       - Item 2
       ```
3. **Dynamic Category Creation**:
   - `"Add category [Name]: [Content]"`:
     - Creates `Category(name = Name, color = nextColor())` in `CategoryDao`.
     - Assigns note to newly created category.
4. **Append Trigger**:
   - `"Append to [Category]: [Content]"`:
     - Finds the most recent note in `[Category]` created today.
     - Appends `\n- **[HH:mm]**: [Content]`.
     - Updates existing note and its `.md` file on disk.
5. **Topic Tags**:
   - `"tag [word]"` or `"hashtag [word]"` → extracts `#word`, saves in `note.tags`, and inserts `#word` into note footer.
6. **Task Redirection**:
   - If transcript begins with `"Task:"`, `"Todo:"`, or `"Remind me to"`, bypasses note creation and passes to `TaskExtractor` for the Tasks tab.

---

### 3.2 UI: Note Detail & Editor (`NoteEditorSheet`)
Tapping any note row in `HomeScreen` opens a full bottom sheet:
- **Title Bar**: Category tag, Pin toggle icon (`📌`), Close button.
- **Editable Content**: Full `OutlinedTextField` allowing instant text editing.
- **Category Picker**: Fast 1-tap chip row to switch category or unassign.
- **Action Toolbar**:
  - 🗑️ **Delete Button**: Deletes note with undo snackbar and cleans up Markdown file.
  - 📅 **Add to Calendar**: Fires `Intent.ACTION_INSERT` with `Events.CONTENT_URI`, pre-populating title, note text, and current/parsed time.
  - 📤 **Share**: Standard Android share sheet to Keep, WhatsApp, Docs, etc.

---

### 3.3 UI: Date-Grouped Timeline & Pinned Section
In `NotesScreen.kt`:
1. **Pinned Section**:
   - If any notes have `pinned = true`, render a distinct top section:
     `📌 Pinned Thoughts`
   - Pinned cards have an accent border and stay visible regardless of date collapse.
2. **Collapsible Date Sections**:
   - Notes are grouped by calendar day:
     - *Today*
     - *Yesterday*
     - *[Day of Week], [Month] [Day]* (e.g. *Tuesday, Oct 20*)
   - Each header displays:
     - Date title
     - Total note count
     - Expand/collapse chevron with smooth `AnimatedVisibility`
   - Persists collapsed/expanded states in UI memory.

---

## 4. Testing & Verification Plan

1. **Unit Tests**:
   - `NoteFormatParserTest`:
     - Test `"Title: Sunday Talk"` → `# Sunday Talk`.
     - Test `"Bullet faith bullet repentance"` → `- Faith\n- Repentance`.
     - Test `"Add category Choir: practice at 7"` → creates Choir, sets text.
     - Test `"Append to Church: remember handouts"` → appends with timestamp.
     - Test `"tag youth"` → extracts tag `youth`.
   - `NoteEntityMigrationTest`:
     - Test `MIGRATION_7_8` preserving existing notes while adding `pinned` and `tags`.
2. **UI Tests**:
   - Verify date grouping logic across multiple timestamps.
   - Verify note editor saves text changes and triggers calendar intent.
