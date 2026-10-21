package app.instantrecorder.notes

import app.instantrecorder.data.NoteItem
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

object MarkdownSync {

    private val fileNameFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss", Locale.US)
        .withZone(ZoneId.systemDefault())

    private val isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .withZone(ZoneOffset.UTC)

    /**
     * Writes or updates a note as a Markdown file with YAML frontmatter.
     * Saved to baseDir/{categoryName}/ (or baseDir/Unassigned/ if categoryName is null or blank).
     * If the note was moved from another folder (indicated by note.markdownPath), the old file is deleted.
     */
    fun syncNote(baseDir: File, note: NoteItem, categoryName: String?): File {
        val folderName = if (categoryName.isNullOrBlank()) {
            "Unassigned"
        } else {
            sanitizeFolderName(categoryName)
        }

        val categoryFolder = File(baseDir, folderName)
        if (!categoryFolder.exists()) {
            categoryFolder.mkdirs()
        }

        val timestamp = fileNameFormatter.format(Instant.ofEpochMilli(note.createdAt))
        val fileName = "${timestamp}_note_${note.id}.md"
        val noteFile = File(categoryFolder, fileName)

        val effectiveCategory = if (categoryName.isNullOrBlank()) "Unassigned" else categoryName.trim()

        val content = buildString {
            appendLine("---")
            appendLine("id: ${note.id}")
            appendLine("category: \"${effectiveCategory.replace("\"", "\\\"")}\"")
            appendLine("created_at: ${isoFormatter.format(Instant.ofEpochMilli(note.createdAt))}")
            appendLine("updated_at: ${isoFormatter.format(Instant.ofEpochMilli(note.updatedAt))}")
            if (note.recordingId != null) {
                appendLine("recording_id: ${note.recordingId}")
            }
            appendLine("---")
            appendLine()
            appendLine(note.text)
        }

        noteFile.writeText(content)

        // If the note moved to a new destination, clean up the previous file
        if (!note.markdownPath.isNullOrBlank()) {
            try {
                val oldFile = File(note.markdownPath)
                if (oldFile.exists() && oldFile.isFile && oldFile.canonicalPath != noteFile.canonicalPath) {
                    oldFile.delete()
                }
            } catch (_: Exception) {
                // Ignore failure to delete old file
            }
        }

        return noteFile
    }

    /**
     * Deletes the note file at [markdownPath] if it exists on disk.
     * Returns true if deleted, false if file does not exist or path is invalid.
     */
    fun deleteNoteFile(markdownPath: String?): Boolean {
        if (markdownPath.isNullOrBlank()) return false
        return try {
            val file = File(markdownPath)
            if (file.exists() && file.isFile) {
                file.delete()
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Alias for [deleteNoteFile].
     */
    fun deleteNote(markdownPath: String?): Boolean = deleteNoteFile(markdownPath)

    /**
     * Replaces characters invalid for directory names with underscores.
     */
    fun sanitizeFolderName(name: String): String {
        val sanitized = name.replace(Regex("""[\\/:*?"<>|\x00-\x1F]"""), "_").trim()
        return sanitized.ifBlank { "Unassigned" }
    }
}
