# Instant Recorder: Initial Changes & Brainstorm

This document synthesizes the requirements and ideas outlined in [`my_contributions.md`](my_contributions.md), evaluates the capabilities of the current `instant-recorder` codebase, and defines the approved technical architecture and implementation approach.

---

## 1. Specifications Extracted from `my_contributions.md`

1. **Easily Searchable Note Organization & Centralization**:
   - Store thoughts and notes in a centralized, easily searchable file or format (e.g., Markdown files + local searchable database) to avoid scattering notes across different tools.
2. **Frictionless Voice Brain-Dumping**:
   - Fast, reliable voice-to-text recording that captures free-form thoughts (e.g. preparing a talk or exploring complex ideas) without manual friction.
3. **Spoken Category Prefix ("Category-First Routing")**:
   - When triggering a recording, the user can speak a category name at the start (e.g., *"Church"*, *"Work"*, *"Family"*), followed immediately by the thought.
   - The app parses the initial spoken word/prefix and matches it **strictly against existing user-defined categories**.
   - Categories are created and managed by the user in-app; the app **never auto-creates unlimited categories from speech**.
4. **"Unassigned" Folder & Post-Capture Triage**:
   - If the speech recognizer/parser does not match any existing user category, the note is filed into an **Unassigned** folder/bucket.
   - Quick manual triage UI: a single tap or "Assign" button on the note to file it into an existing category later.
5. **"Hey Google" / Voice Assistant Integration**:
   - Hands-free voice trigger ("Hey Google, take a note...") so the user can capture notes completely hands-free while sitting and thinking.

---

## 2. Approved Architecture & Approaches

### Storage: Hybrid (Room DB + SQLite FTS5 + Markdown Mirroring)
- **In-App Search**: Notes are stored in Room with an SQLite FTS5 virtual table for instant, typo-tolerant full-text searching across all transcripts and notes.
- **Filesystem Mirroring**: Every note is automatically synced as a standard `.md` file to `Documents/InstantRecorder/Notes/{Category}/YYYY-MM-DD-Note.md` (or `Unassigned/` if uncategorized).
- **Benefits**: Instant native UI performance plus open file portability (accessible via Obsidian, Google Drive, Syncthing, or any file manager).

### Category-First Routing: Strict Prefix Parser
- **Strict Validation**: Matches only against categories the user has explicitly added via the in-app Category settings.
- **Prefix Processing**:
  - If the note starts with `<CategoryName>[:\.\s]`, the note is assigned that category ID and the prefix is stripped from the note body.
  - If no existing category matches, `categoryId` is set to `null` (routes directly to **Unassigned**).
  - Whisper prompt conditioning: Active user category names are passed to Whisper's initial prompt vocabulary to ensure accurate spelling of category names.

### UI & Triage: Dedicated "Notes" Tab & "Unassigned" Badge
- **Notes Tab**: Added alongside Recordings and Tasks in `HomeScreen`.
- **Unassigned Filter**: Prominent filter badge (e.g., `Unassigned (N)`) when uncategorized notes exist.
- **1-Tap Triage**: Each unassigned note features an "Assign" button that opens a category bottom sheet to file the note in one tap.

### Assistant Integration: Android `CREATE_NOTE` & App Actions
- Register `android.intent.action.CREATE_NOTE` in `AndroidManifest.xml` so Android system shortcuts and Google Assistant voice commands can dispatch directly to Instant Recorder.
- Add Google Assistant Built-In Intents (`actions.intent.CREATE_NOTE`, `actions.intent.RECORD_AUDIO`) in `shortcuts.xml`.

---

## 3. Implementation Plan Overview

1. **Data Model**:
   - Add `NoteItem` entity with FTS5 search table and Room migrations.
   - Implement `MarkdownExporter` / sync service to write `.md` files to storage.
2. **Category Parser**:
   - Implement prefix matching against user-defined categories.
   - Strip spoken prefix from note text and route unmatched notes to Unassigned.
3. **UI / Compose**:
   - Add "Notes" tab to `HomeScreen`.
   - Add "Unassigned" chip and category assignment bottom sheet.
   - Add full-text search bar.
4. **Intents & Triggers**:
   - Add `CREATE_NOTE` intent filter and Assistant BII mappings.
