# Inline Note Tasks, Keyword Cleanup, and Auto-Save Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Clean task keyword clutter ("task", "todo") from task titles, display extracted action items directly inside `NoteEditorSheet` with 1-tap addition to the Tasks tab, and add an optional setting to automatically save recognized tasks upon recording completion.

**Architecture:**
- `TaskExtractor.kt` strips leading `"Task:"`, `"Todo:"`, `"My task is to"` and trailing `"task"` / `"todo"` words so task titles are clean.
- `NoteEditorSheet.kt` extracts tasks from note text and displays an interactive `📋 Action Items in this Note` list allowing 1-tap addition to Room DB via `MainViewModel`.
- `AppSettings.kt` and `RecordingService.kt` introduce an `autoSaveTasks` setting to automatically save actionable tasks directly to Room DB upon recording completion.

**Tech Stack:** Kotlin, Jetpack Compose, Room Database, JUnit 4.

## Global Constraints

- **Natural Speech Detection**: Preserve existing verb/date extraction so users do not need to speak rigid keywords.
- **Clean Titles**: When "task" or "todo" is spoken, strip it cleanly without mangling the actual task description.
- **Immediate Feedback**: In `NoteEditorSheet`, adding a task provides instant visual confirmation (`✓ Added`).
- **All tests green**: Full unit test suite must pass with zero regressions.

---

### Task 1: Keyword Cleanup in `TaskExtractor.kt`

**Files:**
- Modify: `app/src/main/java/app/instantrecorder/tasks/TaskExtractor.kt`
- Test: `app/src/test/java/app/instantrecorder/tasks/TaskExtractorTest.kt`

**Interfaces:**
- Produces: Cleaned `TaskSuggestion.title` without "task" / "todo" lead-ins or trailers.

- [ ] **Step 1: Write failing unit tests for task keyword cleanup**

In `app/src/test/java/app/instantrecorder/tasks/TaskExtractorTest.kt`:
```kotlin
@Test
fun `strips task and todo lead-in keywords`() {
    val t1 = extract("Task: call mom tomorrow at 5 p.m.").single()
    assertEquals("Call mom", t1.title)

    val t2 = extract("Todo: submit expense report").single()
    assertEquals("Submit expense report", t2.title)

    val t3 = extract("My task is to buy groceries").single()
    assertEquals("Buy groceries", t3.title)
}

@Test
fun `strips trailing task keyword`() {
    val t = extract("Homework task").single()
    assertEquals("Homework", t.title)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "app.instantrecorder.tasks.TaskExtractorTest"`
Expected: FAIL.

- [ ] **Step 3: Update `TaskExtractor.kt`**

- Update `LEAD_IN` regex to include:
  `|(?:(?:my\s+)?(?:task|todo|to-do|reminder)(?:\s+is)?(?:\s+to)?\s*[:,\.\-–—]*\s*)`
- In `cleanup()` or `suggestionFor()`: strip trailing `\s+(?:task|todo|to-do)$` (case-insensitive).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "app.instantrecorder.tasks.TaskExtractorTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/instantrecorder/tasks/TaskExtractor.kt app/src/test/java/app/instantrecorder/tasks/TaskExtractorTest.kt
git commit -m "fix(tasks): strip task and todo keywords from task titles"
```

---

### Task 2: Action Items Inside Note Detail (`NoteEditorSheet`)

**Files:**
- Modify: `app/src/main/java/app/instantrecorder/ui/MainViewModel.kt`
- Modify: `app/src/main/java/app/instantrecorder/ui/NoteEditorSheet.kt`
- Modify: `app/src/main/java/app/instantrecorder/ui/NotesScreen.kt`
- Test: `app/src/test/java/app/instantrecorder/ui/NotesUiTest.kt`

**Interfaces:**
- Produces: `MainViewModel.addTaskFromSuggestion(suggestion: TaskSuggestion, categoryId: Long?, recordingId: Long?): Long`
- Renders suggested tasks section in `NoteEditorSheet`.

- [ ] **Step 1: Add `addTaskFromSuggestion` to `MainViewModel.kt`**

```kotlin
fun addTaskFromSuggestion(suggestion: TaskSuggestion, categoryId: Long?, recordingId: Long?) {
    viewModelScope.launch(Dispatchers.IO) {
        graph.db.tasks().insert(
            TaskItem(
                title = suggestion.title,
                dueAt = suggestion.due?.epochMillis,
                dueHasTime = suggestion.due?.hasTime ?: false,
                categoryId = suggestion.categoryId ?: categoryId,
                recordingId = recordingId,
                repeatDays = suggestion.repeatDays,
                important = suggestion.important
            )
        )
    }
}
```

- [ ] **Step 2: Add suggested action items UI to `NoteEditorSheet.kt`**

Extract `val suggestions = remember(text, categories) { TaskExtractor.extract(text, categories = categories.map { it.rule() }) }`.
If `suggestions.isNotEmpty()`, render a section:
- Header: `📋 Action Items in this Note (${suggestions.size})`
- Card for each suggestion:
  - Title and due date label (if present).
  - Button `[+ Add]` (or `✓ Added` once tapped).

- [ ] **Step 3: Unit test in `NotesUiTest.kt`**

Verify `addTaskFromSuggestion` inserts `TaskItem` into Room DB.

- [ ] **Step 4: Run tests and verify**

Run: `./gradlew :app:testDebugUnitTest`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/instantrecorder/ui/MainViewModel.kt app/src/main/java/app/instantrecorder/ui/NoteEditorSheet.kt app/src/main/java/app/instantrecorder/ui/NotesScreen.kt app/src/test/java/app/instantrecorder/ui/NotesUiTest.kt
git commit -m "feat(ui): display and add suggested tasks directly from NoteEditorSheet"
```

---

### Task 3: Automatic Task Auto-Save Setting

**Files:**
- Modify: `app/src/main/java/app/instantrecorder/data/AppSettings.kt`
- Modify: `app/src/main/java/app/instantrecorder/ui/SettingsScreen.kt`
- Modify: `app/src/main/java/app/instantrecorder/recording/RecordingService.kt`
- Test: `app/src/test/java/app/instantrecorder/recording/RecordingPipelineIntegrationTest.kt`

**Interfaces:**
- Produces: `Settings.autoSaveTasks: Boolean`, `AppSettings.setAutoSaveTasks(Boolean)`
- Automatically persists actionable tasks to `app.db.tasks()` when enabled.

- [ ] **Step 1: Add `autoSaveTasks` to `AppSettings.kt` and `SettingsScreen.kt`**

- In `data class Settings(...)`: add `val autoSaveTasks: Boolean = false`.
- In `AppSettings`: add preference key `auto_save_tasks` and setter `setAutoSaveTasks`.
- In `SettingsScreen.kt`: add a SwitchRow: *"Automatically save recognized tasks"*.

- [ ] **Step 2: Integrate into `RecordingService.kt`**

In `RecordingService.transcribe`:
```kotlin
val actionableSuggestions = suggestions.filter { it.actionable || it.due != null }
if (app.settings.value.autoSaveTasks && actionableSuggestions.isNotEmpty()) {
    actionableSuggestions.forEach { suggestion ->
        app.db.tasks().insert(
            TaskItem(
                title = suggestion.title,
                dueAt = suggestion.due?.epochMillis,
                dueHasTime = suggestion.due?.hasTime ?: false,
                categoryId = suggestion.categoryId ?: effectiveCategoryId,
                recordingId = updated.id,
                repeatDays = suggestion.repeatDays,
                important = suggestion.important
            )
        )
    }
}
```

- [ ] **Step 3: Test auto-save in `RecordingPipelineIntegrationTest.kt`**

Verify tasks are automatically inserted when `autoSaveTasks = true`.

- [ ] **Step 4: Run all tests and assemble**

Run: `./gradlew :app:testDebugUnitTest` and `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/instantrecorder/data/AppSettings.kt app/src/main/java/app/instantrecorder/ui/SettingsScreen.kt app/src/main/java/app/instantrecorder/recording/RecordingService.kt app/src/test/java/app/instantrecorder/recording/RecordingPipelineIntegrationTest.kt
git commit -m "feat(settings): add autoSaveTasks setting to automatically persist actionable tasks"
```
