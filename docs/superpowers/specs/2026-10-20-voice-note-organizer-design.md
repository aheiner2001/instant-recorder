# Design Spec: Voice Note Organizer with Category-First Routing

**Date:** 2026-10-20  
**Status:** Approved  
**Author:** Antigravity / Aaron Heiner  

---

## 1. Goal

Transform Instant Recorder into a comprehensive, searchable voice note organizer that allows hands-free thought capture, automatically routes spoken thoughts into user-defined categories based on a spoken prefix, stores notes both in-app and as portable Markdown files, and provides an "Unassigned" inbox for notes requiring manual triage.

---

## 2. Architecture & Components

```
                +---------------------------------------+
                | Audio Capture (Volume Chord / QS /   |
                | Back Tap / "Hey Google" Assistant)   |
                +-------------------+-------------------+
                                    |
                                    v
                +---------------------------------------+
                | Whisper.cpp Speech-to-Text            |
                | (Conditioned with Category Vocabulary)|
                +-------------------+-------------------+
                                    |
                                    v
                +---------------------------------------+
                | Category-First Spoken Prefix Parser   |
                | (Strict matching against user categories)
                +---------+-------------------+---------+
                          |                   |
               Match Found|                   |No Match
                          v                   v
                +-------------------+ +-------------------+
                | Category Assigned | |    Unassigned     |
                | (Prefix Stripped) | |  (Null Category)  |
                +---------+---------+ +---------+---------+
                          |                     |
                          +----------+----------+
                                     |
                                     v
                +---------------------------------------+
                | Storage Layer:                        |
                | 1. Room DB (NoteItem + FTS5 Search)  |
                | 2. Markdown File Mirror (Documents/)  |
                +-------------------+-------------------+
                                    |
                                    v
                +---------------------------------------+
                | Compose UI:                           |
                | - "Notes" Tab with Search Bar         |
                | - "Unassigned (N)" Filter Chip        |
                | - 1-Tap "Assign" Bottom Sheet         |
                +---------------------------------------+
```

---

## 3. Detailed Component Specifications

### 3.1 Data Model & Storage

1. **`NoteItem` Entity**:
   ```kotlin
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
   ```

2. **Full-Text Search (`NoteFts`)**:
   - Room `@Fts4` or `@Fts5` virtual table indexing `text` for instant keyword searching in the UI.

3. **Markdown Mirroring (`MarkdownSyncManager`)**:
   - Stores notes as standard Markdown in the app's external files or public `Documents/InstantRecorder/Notes/` folder:
     - Path: `Documents/InstantRecorder/Notes/{CategoryName}/{YYYY-MM-DD-HHmmss}.md`
     - Notes without a category: `Documents/InstantRecorder/Notes/Unassigned/{YYYY-MM-DD-HHmmss}.md`
   - Header with frontmatter:
     ```markdown
     ---
     id: 42
     category: Church
     created_at: 2026-10-20T22:30:00Z
     ---
     Lesson prep on faith and works...
     ```

### 3.2 Spoken Category Prefix Parser (`CategoryVoiceRouter`)

1. **Category Constraints**:
   - Only categories previously defined by the user in `Category` table are candidates.
   - The parser **never creates new categories**.
2. **Matching Rules**:
   - Matches beginning of transcript: `^(?:(?:for|in|under)\s+)?${category.name}(?:\s+notes?)?\s*(?:[:,.;\-–—]+\s*|\s+)` (case-insensitive).
   - If matched:
     - Strips matched category prefix from `text`.
     - Sets `categoryId = matchedCategory.id`.
   - If not matched:
     - Keeps full transcript as `text`.
     - Sets `categoryId = null`.
3. **Whisper Vocabulary Bias**:
   - Provide comma-separated list of active user categories as prompt hint to `WhisperLib` so rare or unique category names are transcribed with high fidelity.

### 3.3 UI: Notes Tab & Unassigned Triage

1. **Tabs in `HomeScreen`**:
   - Update `PrimaryTabRow` to include: `Notes`, `Tasks`, and `Recordings`.
2. **Search Bar**:
   - Top search field on the Notes tab querying the FTS index in real-time.
3. **Category Chips**:
   - Horizontal filter row: `All`, `Unassigned (count)`, followed by user categories (`Church`, `Work`, `Family`, etc.).
   - If `Unassigned` has items, highlight the badge with an alert tint.
4. **1-Tap Assignment**:
   - Unassigned notes display an **"Assign"** button.
   - Tapping it displays a bottom sheet of all existing user categories.
   - Selecting a category immediately updates `NoteItem.categoryId`, moves the `.md` file to the category subfolder, and updates the UI.

### 3.4 Google Assistant & Hands-Free Intents

1. **`AndroidManifest.xml`**:
   - Register `android.intent.action.CREATE_NOTE`.
   - Add category `android.intent.category.DEFAULT`.
2. **`shortcuts.xml`**:
   - Add App Action for `actions.intent.CREATE_NOTE` mapped to `MainActivity` or direct `RecordingService` start.

---

## 4. Error Handling & Edge Cases

- **Whisper Hallucination / Silence**: Discard recordings below minimum audio threshold as done currently (`MIN_RECORDING_MS`).
- **File System Permissions**: Use app-specific external storage or Storage Access Framework to prevent permission crashes across Android 12-17.
- **Accidental Category Prefix**: If user's note starts with an existing category name naturally (e.g. "Work is really stressful today"), check for punctuation/pause delimiter; if uncertain, keep note intact and route to category or allow user to undo.
- **Database Migrations**: Add Room migration to version DB cleanly without losing existing recordings or tasks.

---

## 5. Testing Plan

- **Unit Tests**:
  - `CategoryVoiceRouterTest`: Test prefix stripping, punctuation variations ("Church:", "Church -", "Church, "), unmatched phrases ("A walk in the park"), and case-insensitivity.
  - `MarkdownSyncTest`: Test writing and updating `.md` files upon note creation and category reassignment.
  - `NoteFtsTest`: Verify full-text search queries return expected notes with prefix matching.
- **Manual Verification**:
  - Voice recording with "Church" prefix -> verify categorized under Church in Notes tab.
  - Voice recording without category -> verify appears in "Unassigned (1)".
  - Tap "Assign" -> choose category -> verify reassignment.
