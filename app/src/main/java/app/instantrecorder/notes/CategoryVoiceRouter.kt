package app.instantrecorder.notes

import app.instantrecorder.data.Category

data class CategoryRouteResult(
    val categoryId: Long?,
    val cleanedText: String
)

object CategoryVoiceRouter {

    /**
     * Inspects the opening of [transcript] and checks if it begins with an existing category name.
     * Only categories present in [categories] can be matched.
     * If matched, returns the category id and the note text with the prefix stripped and capitalized.
     * If no match, returns categoryId = null (Unassigned) and the original text trimmed.
     */
    fun route(transcript: String, categories: List<Category>): CategoryRouteResult {
        val trimmed = transcript.trim()
        if (trimmed.isEmpty()) {
            return CategoryRouteResult(categoryId = null, cleanedText = "")
        }

        // Sort by length descending to match more specific names first (e.g. "Work Project" before "Work")
        // and ignore empty/blank category names.
        val validCategories = categories
            .filter { it.name.isNotBlank() }
            .sortedByDescending { it.name.length }

        for (category in validCategories) {
            val pattern = Regex(
                """^(?:(?:for|in|under)\s+)?${Regex.escape(category.name)}(?:\s+notes?)?\s*(?:[:,.;\-–—]+\s*|\s+)""",
                RegexOption.IGNORE_CASE
            )
            val match = pattern.find(trimmed)
            if (match != null) {
                val remainder = trimmed.substring(match.range.last + 1).trim()
                val capitalized = remainder.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase() else it.toString()
                }
                return CategoryRouteResult(
                    categoryId = category.id,
                    cleanedText = capitalized.ifEmpty { trimmed }
                )
            }
        }

        return CategoryRouteResult(
            categoryId = null,
            cleanedText = trimmed
        )
    }
}
