package app.instantrecorder.notes

import app.instantrecorder.data.NoteItem
import app.instantrecorder.data.tagList
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object NoteMerger {

    fun formatMergedText(notes: List<NoteItem>, zoneId: ZoneId = ZoneId.systemDefault()): String {
        if (notes.isEmpty()) return ""
        val sorted = notes.sortedBy { it.createdAt }
        val localDates = sorted.map {
            Instant.ofEpochMilli(it.createdAt).atZone(zoneId).toLocalDate()
        }
        val sameDay = localDates.distinct().size <= 1
        val pattern = if (sameDay) "h:mm a" else "MMM d, h:mm a"
        val formatter = DateTimeFormatter.ofPattern(pattern, Locale.US)

        return sorted.joinToString("\n\n") { note ->
            val timeStr = Instant.ofEpochMilli(note.createdAt).atZone(zoneId).format(formatter)
            "- **$timeStr**: ${note.text.trim()}"
        }
    }

    fun combineTags(notes: List<NoteItem>): String {
        return notes
            .flatMap { it.tagList }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(",")
    }

    fun buildMergedNote(
        notes: List<NoteItem>,
        destinationCategoryId: Long?,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): NoteItem {
        val earliestCreatedAt = notes.minOfOrNull { it.createdAt } ?: System.currentTimeMillis()
        val anyPinned = notes.any { it.pinned }
        val mergedTags = combineTags(notes)
        val mergedText = formatMergedText(notes, zoneId)
        val resolvedRecordingId = notes.mapNotNull { it.recordingId }.distinct().singleOrNull()

        return NoteItem(
            text = mergedText,
            recordingId = resolvedRecordingId,
            categoryId = destinationCategoryId,
            createdAt = earliestCreatedAt,
            updatedAt = System.currentTimeMillis(),
            markdownPath = null,
            pinned = anyPinned,
            tags = mergedTags
        )
    }
}
