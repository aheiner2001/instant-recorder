# Design Spec: Inline Note Tasks, Keyword Cleanup, and Auto-Save

**Date:** 2026-10-21  
**Status:** Approved  
**Author:** Antigravity / Aaron Heiner  

---

## 1. Overview & Goals

1. **Clean Task Titles (Keyword Stripping)**:
   - Strip leading task markers (e.g. `"Task: ..."`, `"My task is to..."`, `"Todo: ..."`) and trailing task words (e.g. `"homework task"` → `"Homework"`).
2. **Inline Action Items in `NoteEditorSheet`**:
   - When viewing any note, extract actionable task suggestions from the note text using `TaskExtractor`.
   - Render them in a clean `📋 Action Items in this Note` section inside `NoteEditorSheet`.
   - Provide a 1-tap `[+ Add to Tasks]` button for each suggestion, creating a real `TaskItem` in the **Tasks** tab.
3. **Optional Auto-Save Setting (`autoSaveTasks`)**:
   - Add a toggle in Settings: *"Automatically save actionable tasks"*.
   - When enabled, high-confidence actionable tasks are automatically persisted to the `tasks` table upon recording completion.

---

## 2. Technical Architecture & Component Changes

```
               +---------------------------------------+
               | Note Text (Viewed in NoteEditorSheet) |
               +-------------------+-------------------+
                                   |
                                   v
               +---------------------------------------+
               | TaskExtractor.extract(note.text)      |
               | (Keyword cleanup: "task", "todo", etc)|
               +-------------------+-------------------+
                                   |
                                   v
               +---------------------------------------+
               | Suggested Action Items Section        |
               | - Checkbox / Title / Due Date Chip    |
               | - [+ Add to Tasks] Button             |
               +-------------------+-------------------+
                                   |
                       (Tap [+ Add])
                                   v
               +---------------------------------------+
               | MainViewModel.addTaskFromSuggestion   |
               | -> Room db.tasks().insert(TaskItem)   |
               +---------------------------------------+
```

### 2.1 Keyword Cleanup in `TaskExtractor.kt`

1. **Lead-in Patterns (`LEAD_IN`)**:
   Add support for:
   - `^(?:(?:my\s+)?(?:task|todo|to-do|reminder)(?:\s+is)?(?:\s+to)?\s*[:,\.\-–—]*\s*)`
2. **Trailer Cleanup**:
   Strip trailing keyword suffixes:
   - `\s+(?:task|todo|to-do)$`
3. **Expected Behavior**:
   - `"Task: submit expense report"` → `"Submit expense report"`
   - `"Homework task"` → `"Homework"`
   - `"My task is to call bishop tomorrow"` → `"Call bishop"` · *Tomorrow*

### 2.2 Inline Action Items in `NoteEditorSheet.kt`

1. **Extraction**:
   - In `NoteEditorSheet`: run `remember(note.text, categories) { TaskExtractor.extract(note.text, categories = categories.map { it.rule() }) }`.
2. **Display**:
   - For each suggestion:
     - Title and due date badge (e.g. `Tomorrow 5:00 PM`).
     - Checkbox or `[+ Add]` button.
     - Once added, state reflects saved status (e.g. `✓ Added`).
3. **Persistence**:
   - Calls `viewModel.addTask(title = suggestion.title, due = suggestion.due, categoryId = suggestion.categoryId ?: note.categoryId, recordingId = note.recordingId)`.

### 2.3 Auto-Save Tasks Setting (`autoSaveTasks`)

1. **Settings**:
   - Add `val autoSaveTasks: Boolean = false` to `Settings` in `AppSettings.kt`.
   - Add switch in `SettingsScreen.kt`: *"Automatically save recognized tasks"*.
2. **Pipeline in `RecordingService.kt`**:
   - When a note is transcribed:
     - If `app.settings.value.autoSaveTasks`:
       - Filter `suggestions.filter { it.actionable || it.due != null }`.
       - Bulk insert into `app.db.tasks()`.
       - Update recording's `tasksCreated` count.

---

## 3. Testing Plan

1. **Unit Tests in `TaskExtractorTest.kt`**:
   - `"Task: call mom tomorrow"` → title `"Call mom"`.
   - `"Homework task"` → title `"Homework"`.
   - `"My task is to finish slides by Friday"` → title `"Finish slides"`, due Friday.
2. **Unit Tests in `NotesUiTest.kt`**:
   - Verify `addTaskFromSuggestion` creates `TaskItem` in Room with correct title, due date, category, and recording ID.
3. **Integration Verification**:
   - Run `./gradlew :app:testDebugUnitTest`.
   - Build `./gradlew :app:assembleDebug`.
