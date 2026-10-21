# Multi-Select, Bulk Deletion & Note Merging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement multi-selection across the Notes and Recordings tabs in Instant Recorder, enabling 1-tap/long-press selection, bulk deletion with confirmation and undo, and chronological note merging into unified notes.

**Architecture:**
- Pure domain helper `NoteMerger.kt` handles chronological text concatenation, timestamp bullet headers, tag deduplication, and note assembly.
- `MainViewModel.kt` extends the sealed `Undoable` / `Deletion` architecture with `Deletion.OfMultipleNotes`, `Deletion.OfMultipleRecordings`, and `MergeNotesAction` to support optimistic UI updates and Undo snackbars.
- `HomeScreen.kt` and `NotesScreen.kt` introduce selection state, animated row checkboxes, a Material 3 `ContextualTopAppBar` (counter, Select All, Merge, Delete), and a category selection dialog for mixed-category note merging.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Android Room, Coroutines / StateFlow, JUnit 4.

## Global Constraints
- Target branch: `feature/multi-select-delete-merge` branched from `main`.
- Kotlin/Compose M3 standards: zero jank / 60 FPS, all disk I/O on `Dispatchers.IO`.
- Backward compatibility: preserve existing single-note swipe-to-delete, audio playback, whisper.cpp transcription, and volume button triggers.
- Active transcription guard: recordings in `RecordingStatus.TRANSCRIBING` cannot be selected or deleted.
- Safety: bulk deletion requires an alert confirmation dialog AND supports the Undo snackbar.

---

### Task 1: NoteMerger Domain Logic & Unit Tests

**Files:**
- Create: `app/src/main/java/app/instantrecorder/notes/NoteMerger.kt`
- Create: `app/src/test/java/app/instantrecorder/notes/NoteMergerTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  object NoteMerger {
      fun formatMergedText(notes: List<NoteItem>, zoneId: ZoneId = ZoneId.systemDefault()): String
      fun combineTags(notes: List<NoteItem>): String
      fun buildMergedNote(notes: List<NoteItem>, destinationCategoryId: Long?, zoneId: ZoneId = ZoneId.systemDefault()): NoteItem
  }
  ```

- [ ] **Step 1: Write the failing tests in `NoteMergerTest.kt`**
  - Verify `formatMergedText`:
    - Chronologically sorts notes by `createdAt` ascending.
    - If all notes are on the same calendar day, formats timestamps as `- **h:mm a**: text`.
    - If notes span different calendar days, formats timestamps as `- **MMM d, h:mm a**: text`.
    - Handles blank lines cleanly.
  - Verify `combineTags`:
    - Combines tags across notes, strips duplicates, returns comma-separated string.
  - Verify `buildMergedNote`:
    - `pinned` is true if any selected note was pinned.
    - `createdAt` is the earliest timestamp among the notes.
    - `categoryId` is set to `destinationCategoryId`.

- [ ] **Step 2: Run tests and verify failure**
  ```bash
  ./gradlew :app:testDebugUnitTest --tests "app.instantrecorder.notes.NoteMergerTest"
  ```
  Expected: Compilation fails (class not found) or test fails.

- [ ] **Step 3: Implement `NoteMerger.kt`**
  - Implement `formatMergedText`, `combineTags`, and `buildMergedNote`.
  - Use `java.time.Instant`, `java.time.ZonedDateTime`, and `java.time.format.DateTimeFormatter` for locale-aware date/time formatting.

- [ ] **Step 4: Run tests and verify success**
  ```bash
  ./gradlew :app:testDebugUnitTest --tests "app.instantrecorder.notes.NoteMergerTest"
  ```
  Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit**
  ```bash
  git add app/src/main/java/app/instantrecorder/notes/NoteMerger.kt app/src/test/java/app/instantrecorder/notes/NoteMergerTest.kt
  git commit -m "feat(notes): implement NoteMerger domain logic for timestamped note combination"
  ```

---

### Task 2: ViewModel Actions & Undo Support for Batch Deletion & Merge

**Files:**
- Modify: `app/src/main/java/app/instantrecorder/ui/MainViewModel.kt`
- Modify: `app/src/main/java/app/instantrecorder/data/Database.kt` (ensure batch delete helpers on `NoteDao` and `RecordingDao` if needed)
- Modify: `app/src/test/java/app/instantrecorder/recording/RecordingPipelineIntegrationTest.kt` or create `app/src/test/java/app/instantrecorder/ui/MainViewModelBatchTest.kt`

**Interfaces:**
- Consumes: `NoteMerger.buildMergedNote`
- Produces:
  ```kotlin
  sealed interface Deletion : Undoable {
      data class OfNote(val note: NoteItem) : Deletion
      data class OfRecording(val recording: Recording) : Deletion
      data class OfTask(val task: TaskItem) : Deletion
      data class OfMultipleNotes(val notes: List<NoteItem>) : Deletion
      data class OfMultipleRecordings(val recordings: List<Recording>) : Deletion
  }

  data class MergeNotesAction(
      val originalNotes: List<NoteItem>,
      val mergedNote: NoteItem,
  ) : Undoable

  // ViewModel functions:
  fun deleteNotes(notes: List<NoteItem>)
  fun deleteRecordings(recordings: List<Recording>)
  fun mergeNotes(notes: List<NoteItem>, destinationCategoryId: Long?)
  ```

- [ ] **Step 1: Write unit tests for batch deletion and merge undo actions**
  - In `MainViewModelBatchTest.kt`:
    - Verify `deleteNotes`: adds all note IDs to `hiddenNotes`, `undoDeletion` removes all IDs from `hiddenNotes`.
    - Verify `deleteRecordings`: adds all recording IDs to `hiddenRecordings`, `undoDeletion` removes all IDs.
    - Verify `mergeNotes`: generates `mergedNote`, hides `originalNotes`, undo removes `mergedNote` and unhides `originalNotes`.

- [ ] **Step 2: Run tests and verify failure**
  ```bash
  ./gradlew :app:testDebugUnitTest --tests "app.instantrecorder.ui.MainViewModelBatchTest"
  ```

- [ ] **Step 3: Implement batch deletions and merge in `MainViewModel.kt`**
  - Add `Deletion.OfMultipleNotes` and `Deletion.OfMultipleRecordings`.
  - Add `MergeNotesAction : Undoable`.
  - In `delete(item: Deletion)`: handle `OfMultipleNotes` (`hiddenNotes.update { it + item.notes.map { n -> n.id } }`) and `OfMultipleRecordings`.
  - In `undoDeletion`: remove all IDs from hidden sets.
  - In `commitDeletion`: batch delete from Room and delete disk files (Markdown files for notes, audio files for recordings via `Dispatchers.IO`).
  - Implement `mergeNotes(notes: List<NoteItem>, destinationCategoryId: Long?)`:
    - Construct `mergedNote = NoteMerger.buildMergedNote(notes, destinationCategoryId)`.
    - Insert `mergedNote` into `app.db.notes().insert(mergedNote)`.
    - Write Markdown file via `MarkdownSync.syncNote(...)` and update `markdownPath`.
    - Hide `originalNotes` in `hiddenNotes`.
    - Send `MergeNotesAction` to `pendingUndoables`.

- [ ] **Step 4: Run all unit tests**
  ```bash
  ./gradlew :app:testDebugUnitTest
  ```
  Expected: All tests pass.

- [ ] **Step 5: Commit**
  ```bash
  git add app/src/main/java/app/instantrecorder/ui/MainViewModel.kt app/src/test/java/app/instantrecorder/ui/MainViewModelBatchTest.kt
  git commit -m "feat(ui): add batch deletion and note merge actions with undo support to MainViewModel"
  ```

---

### Task 3: Selection Mode UI, Contextual Action Bar, & Dialogs

**Files:**
- Modify: `app/src/main/java/app/instantrecorder/ui/HomeScreen.kt`
- Modify: `app/src/main/java/app/instantrecorder/ui/NotesScreen.kt`
- Modify: `app/src/test/java/app/instantrecorder/ui/NotesUiTest.kt`

**Interfaces:**
- Consumes: `vm.deleteNotes`, `vm.deleteRecordings`, `vm.mergeNotes`
- Produces:
  - Checklist button in `TopAppBar`
  - `ContextualTopAppBar` with BackHandler
  - Checkboxes in `NoteCard` and `RecordingRow`
  - Bulk Delete Confirmation Dialog
  - "Merge into category" Dialog

- [ ] **Step 1: Write UI component tests in `NotesUiTest.kt`**
  - Verify selection state management:
    - `isSelecting` toggles checkboxes.
    - Selecting items updates selected count.
    - Merge button enabled only when `count >= 2`.
    - Delete button enabled when `count >= 1`.
    - "Select All" selects all visible notes; "Deselect All" clears selection.

- [ ] **Step 2: Run UI tests and verify failure**
  ```bash
  ./gradlew :app:testDebugUnitTest --tests "app.instantrecorder.ui.NotesUiTest"
  ```

- [ ] **Step 3: Implement selection mode in `HomeScreen.kt` and `NotesScreen.kt`**
  - Add `isSelecting`, `selectedNoteIds`, `selectedRecordingIds` state in `HomeScreen.kt`.
  - Reset selection on tab switch.
  - Implement `BackHandler(enabled = isSelecting) { isSelecting = false; selectedNoteIds = emptySet(); selectedRecordingIds = emptySet() }`.
  - Add `ContextualTopAppBar`:
    - Close button (`✕`)
    - Title: `"Select items"` or `"$count selected"`
    - Select All / Deselect All icon/button
    - Merge icon button (Notes tab only, enabled if `count >= 2`)
    - Delete icon button (enabled if `count >= 1`)
  - Add Delete confirmation dialog:
    - `"Delete $count notes?"` / `"Delete $count recordings?"`
    - On confirm: calls `vm.deleteNotes(selectedNotes)` or `vm.deleteRecordings(selectedRecordings)` and exits selection mode.
  - Add Merge Category selection dialog:
    - Shown when merging notes with different categories.
    - Lists categories + Unassigned.
    - On category tap: calls `vm.mergeNotes(selectedNotes, categoryId)` and exits selection mode.
  - Update `NotesScreen.kt` / `NoteCard`:
    - Show `Checkbox` when `isSelecting`.
    - Tap row toggles selection.
    - Long-press row activates selection and selects item.
  - Update `RecordingRow` in `HomeScreen.kt`:
    - Show `Checkbox` when `isSelecting` (disabled if transcribing).
    - Tap row toggles selection.
    - Long-press row activates selection.

- [ ] **Step 4: Run all unit tests and assemble debug APK**
  ```bash
  ./gradlew :app:testDebugUnitTest
  ./gradlew :app:assembleDebug
  ```
  Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**
  ```bash
  git add app/src/main/java/app/instantrecorder/ui/HomeScreen.kt app/src/main/java/app/instantrecorder/ui/NotesScreen.kt app/src/test/java/app/instantrecorder/ui/NotesUiTest.kt
  git commit -m "feat(ui): implement multi-select contextual action bar, bulk delete dialog, and merge category dialog"
  ```

---

### Task 4: Whole-Branch Review, Verification & Integration

**Files:**
- All touched files

- [ ] **Step 1: Run comprehensive tests and lint**
  ```bash
  ./gradlew :app:testDebugUnitTest --rerun-tasks
  ./gradlew :app:assembleDebug
  ```

- [ ] **Step 2: Perform code review with reviewer subagent**
  - Verify spec compliance:
    - Multi-select works on Notes and Recordings.
    - Bulk delete has confirmation dialog and undo snackbar.
    - Merge notes combines text with chronological timestamp bullets.
    - Markdown files and audio files stay clean on disk.

- [ ] **Step 3: Merge and finalize**
  - Merge `feature/multi-select-delete-merge` into `main`.
  - Push to remote `origin`.
  - Verify debug APK at `app/build/outputs/apk/debug/app-debug.apk`.
