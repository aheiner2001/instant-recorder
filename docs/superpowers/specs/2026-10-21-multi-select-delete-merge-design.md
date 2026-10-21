# Design Specification: Multi-Select, Bulk Deletion & Note Merging

**Date:** 2026-10-21  
**Status:** Approved  
**Feature Branch:** `feature/multi-select-delete-merge`

---

## 1. Overview & Goals

This specification details the design for multi-selection across Instant Recorder's **Notes** and **Recordings** tabs, enabling:
1. **Quick Multi-Selection:** Easy selection of multiple notes or recordings via a top-bar "Select" button or long-press gestures.
2. **Bulk Deletion:** Safe deletion of multiple selected notes or recordings at once with a confirmation dialog and full Undo support.
3. **Note Merging:** Combining two or more related thoughts into a single consolidated note formatted with chronological timestamp bullets, unified tags, and seamless category resolution.

---

## 2. User Experience & Interaction Model

### 2.1 Entering Selection Mode
- **Via Top App Bar:** A checklist icon button (`Icons.Filled.Checklist` / `Select`) appears in the top app bar on both the Notes and Recordings tabs. Tapping it enters selection mode.
- **Via Long-Press:** Long-pressing any note card or recording row enters selection mode and immediately toggles that item as selected.
- **Back Key Navigation:** Pressing Android's back button / gesture when in selection mode exits selection mode first without closing the app.
- **Tab Switching:** Switching tabs automatically clears selection mode to prevent cross-tab state pollution.

### 2.2 Contextual Top Action Bar
When selection mode is active, the standard `TopAppBar` transforms into a Material 3 Contextual Action Bar:
- **`✕` (Close Button):** Clears selection and exits selection mode.
- **Title / Count:** Shows `"Select items"` when count is 0, or `"$count selected"`.
- **Select All / Deselect All Button:** Tapping toggles between selecting all visible items in the current filter and deselecting all items.
- **Merge Button (`Icons.Filled.CallMerge` / `Merge`):**
  - Only visible on the **Notes** tab.
  - Enabled when **2 or more notes** are selected; disabled when fewer than 2 notes are selected.
- **Delete Button (`Icons.Filled.Delete`):**
  - Enabled when **1 or more items** are selected; disabled when 0 are selected.

### 2.3 Row Presentation in Selection Mode
- **Notes Tab:** Each `NoteCard` displays an animated leading `Checkbox(checked = note.id in selectedIds)`. Normal swipe-to-delete is disabled. Clicking anywhere on the row toggles its checkbox rather than opening `NoteEditorSheet`.
- **Recordings Tab:** Each `RecordingRow` displays a leading `Checkbox(checked = recording.id in selectedIds)`. Rows with `status == RecordingStatus.TRANSCRIBING` have disabled checkboxes. Clicking anywhere on the row toggles its checkbox rather than opening audio review.

---

## 3. Bulk Deletion Flow

1. User selects 1 or more items and taps the **Delete** (Trash) icon.
2. **Confirmation Dialog:**
   - *Title:* `"Delete $count notes?"` or `"Delete $count recordings?"`
   - *Message:* `"This will remove the selected items and their associated files."`
   - *Buttons:* `Cancel` and `Delete` (Error color).
3. **Optimistic Deletion & Undo:**
   - On confirmation, the selected IDs are added to `hiddenNotes` or `hiddenRecordings` in `MainViewModel`.
   - The selection mode exits immediately.
   - An Undo snackbar displays: `"Deleted $count notes · Undo"` or `"Deleted $count recordings · Undo"`.
   - **Undo:** Tapping "Undo" removes the IDs from the hidden state, restoring the items instantly.
   - **Commit:** When the snackbar times out or a new action takes over:
     - Notes: Room `notes().delete(notes)` deletes DB entries; associated Markdown files on disk are removed on `Dispatchers.IO`.
     - Recordings: Room `recordings().delete(recordings)` deletes DB entries; audio files on disk (`app.recordings.file(it)`) are removed.

---

## 4. Note Merging Flow (Notes Tab Only)

### 4.1 Category Resolution
When 2 or more notes are selected and the user taps **Merge**:
- **Same Category:** If all selected notes have the exact same `categoryId` (e.g. all belong to "Work"), merging executes immediately under that category.
- **Mixed or Unassigned Categories:** If notes have different categories or some are unassigned, a dialog opens:
  - *Title:* `"Merge into category"`
  - *Options:* List of all existing user categories (with color dots) plus an `"Unassigned"` option.
  - Tapping a category selects it as the destination category and executes the merge.

### 4.2 Merged Content Formatting (`NoteMerger.kt`)
The notes are sorted chronologically by `createdAt` ascending.
- **Timestamp Prefix:**
  - If all selected notes were recorded on the **same calendar day**:
    `- **9:30 AM**: Note 1 text`
    
    `- **10:15 AM**: Note 2 text`
  - If notes span **different calendar days**:
    `- **Oct 20, 9:30 AM**: Note 1 text`
    
    `- **Oct 21, 10:15 AM**: Note 2 text`
- **Separation:** Double newline (`\n\n`) between entries.
- **Attributes Combination:**
  - `pinned`: `selectedNotes.any { it.pinned }`
  - `tags`: Unified distinct tags (`(noteA.tagList + noteB.tagList).distinct().joinToString(",")`)
  - `createdAt`: Earliest `createdAt` among selected notes.
  - `updatedAt`: `System.currentTimeMillis()`
  - `recordingId`: Earliest `recordingId` or `null` if mixed.

### 4.3 Replace & Undo Architecture
- A new `MergeNotesAction(val originalNotes: List<NoteItem>, val mergedNote: NoteItem) : Undoable` is dispatched to `MainViewModel`.
- **Immediate Effect:**
  - `mergedNote` is inserted into Room and synced to a new Markdown file on disk.
  - `originalNotes` IDs are added to `hiddenNotes`.
  - Selection mode is cleared.
  - Snackbar: `"Merged ${originalNotes.size} notes · Undo"`.
- **On Undo:**
  - `mergedNote` is deleted from Room and its file removed from disk.
  - `originalNotes` IDs are removed from `hiddenNotes`, restoring the original separate notes and their files.
- **On Commit:**
  - `originalNotes` are deleted from Room.
  - Old Markdown files (`originalNotes.mapNotNull { it.markdownPath }`) are deleted from disk.

---

## 5. Automated Testing Plan

1. **`NoteMergerTest.kt`:**
   - Unit tests for `formatMergedText`:
     - Same-day timestamps format with `h:mm a`.
     - Multi-day timestamps format with `MMM d, h:mm a`.
     - Chronological sorting respects `createdAt`.
     - Empty or blank lines within notes are handled cleanly.
   - Unit tests for `combineTags` and `buildMergedNote`.
2. **`MainViewModelTest.kt`:**
   - Tests `Deletion.OfMultipleNotes`: hides notes, restores on undo, deletes from DB on commit.
   - Tests `Deletion.OfMultipleRecordings`: hides recordings, restores on undo, deletes from DB on commit.
   - Tests `MergeNotesAction`: inserts merged note, hides originals, restores on undo.
3. **`NotesUiTest.kt`:**
   - Contextual action bar displays correct item count.
   - Merge button is disabled when count < 2; enabled when count >= 2.
   - Select All selects all items; Deselect All clears all items.
