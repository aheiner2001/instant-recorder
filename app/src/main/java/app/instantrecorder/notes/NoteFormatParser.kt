package app.instantrecorder.notes

data class NoteFormatResult(
    val title: String? = null,
    val formattedText: String,
    val tags: List<String> = emptyList(),
    val addCategoryName: String? = null,
    val appendCategoryName: String? = null,
    val isTask: Boolean = false
)

object NoteFormatParser {

    private val TASK_TRIGGER = Regex(
        """^(?:task|todo|remind me to)\b""",
        RegexOption.IGNORE_CASE
    )

    private val ADD_CATEGORY_TRIGGER = Regex(
        """^(?:please\s+)?(?:add|create|new)\s+category\s+(?:([a-zA-Z0-9_\- ]+?)\s*[:,\.\-–—]+\s*|([a-zA-Z0-9_\- ]+?)\s+(?:and|that)\s+|([a-zA-Z0-9_\-]+)\s+)(.*)$""",
        RegexOption.IGNORE_CASE
    )

    private val APPEND_TRIGGER = Regex(
        """^(?:append\s+to|add\s+to)\s+(?:([a-zA-Z0-9_\- ]+?)\s*[:,\.\-–—]+\s*|([a-zA-Z0-9_\- ]+?)\s+(?:and|that)\s+|([a-zA-Z0-9_\-]+)\s+)(.*)$""",
        RegexOption.IGNORE_CASE
    )

    private val TITLE_TRIGGER = Regex(
        """^(?:title|heading)\s*(?:[:,\.\-–—]+\s*|\s+)(.*)$""",
        RegexOption.IGNORE_CASE
    )

    private val TAG_TRIGGER = Regex(
        """\b(?:tag|hashtag)\s+([a-zA-Z0-9_\-]+)\b""",
        RegexOption.IGNORE_CASE
    )

    private val LITERAL_HASHTAG = Regex(
        """(?<!\w)#([a-zA-Z0-9_\-]+)\b"""
    )

    private val TAG_CLEANUP = Regex(
        """(?:\s*(?:,|;|\band\b)\s*|\s+)?\b(?:tag|hashtag)\s+[a-zA-Z0-9_\-]+(?:\s*\.)?""",
        RegexOption.IGNORE_CASE
    )

    private val BULLET_TRIGGER = Regex(
        """(?:\s*(?:,|;|\.)\s*|\s+)?\b(?:next\s+)?(?:bullet|point)\s*[:,\.\-–—]*\s*""",
        RegexOption.IGNORE_CASE
    )

    fun parse(transcript: String): NoteFormatResult {
        val trimmed = transcript.trim()
        if (trimmed.isEmpty()) {
            return NoteFormatResult(formattedText = "")
        }

        val isTask = TASK_TRIGGER.containsMatchIn(trimmed)

        var addCategoryName: String? = null
        var appendCategoryName: String? = null
        var workingText = trimmed

        // 1. Check Add Category command
        val addMatch = ADD_CATEGORY_TRIGGER.find(workingText)
        if (addMatch != null) {
            val category = (addMatch.groupValues[1].ifEmpty { null }
                ?: addMatch.groupValues[2].ifEmpty { null }
                ?: addMatch.groupValues[3]).trim()
            addCategoryName = category
            workingText = addMatch.groupValues[4].trim()
        } else {
            // 2. Check Append command
            val appendMatch = APPEND_TRIGGER.find(workingText)
            if (appendMatch != null) {
                val category = (appendMatch.groupValues[1].ifEmpty { null }
                    ?: appendMatch.groupValues[2].ifEmpty { null }
                    ?: appendMatch.groupValues[3]).trim()
                appendCategoryName = category
                workingText = appendMatch.groupValues[4].trim()
            }
        }

        // 3. Extract Tags and clean spoken tag phrases
        val spokenTags = TAG_TRIGGER.findAll(workingText)
            .map { it.groupValues[1].lowercase() }
            .toList()
        val literalTags = LITERAL_HASHTAG.findAll(workingText)
            .map { it.groupValues[1].lowercase() }
            .toList()
        val tags = (spokenTags + literalTags).distinct()

        val textWithoutTags = if (spokenTags.isNotEmpty()) {
            workingText.replace(TAG_CLEANUP, " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
        } else {
            workingText
        }

        // 4. Parse Title / Heading
        var title: String? = null
        val titleMatch = TITLE_TRIGGER.find(textWithoutTags)
        val formattedContent = if (titleMatch != null) {
            val content = titleMatch.groupValues[1].trim()
            val delimiterIndex = content.indexOfFirst { it == '.' || it == '\n' || it == ':' }
            val (rawTitle, rawBody) = if (delimiterIndex != -1) {
                content.substring(0, delimiterIndex).trim() to content.substring(delimiterIndex + 1).trim()
            } else {
                content.trim() to ""
            }

            title = rawTitle.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase() else it.toString()
            }.takeIf { it.isNotEmpty() }

            val formattedBody = if (rawBody.isNotEmpty()) {
                val parsedBullets = parseBullets(rawBody)
                parsedBullets.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase() else it.toString()
                }
            } else {
                ""
            }

            if (title != null) {
                if (formattedBody.isNotEmpty()) {
                    "# $title\n\n$formattedBody"
                } else {
                    "# $title"
                }
            } else {
                formattedBody
            }
        } else {
            // 5. Parse Bullets
            val parsedBullets = parseBullets(textWithoutTags)
            parsedBullets.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase() else it.toString()
            }
        }

        // 6. Append tags footer if spoken tags extracted and not already present inline
        val missingTags = tags.filterNot { tag ->
            formattedContent.contains("#$tag", ignoreCase = true)
        }
        val finalText = if (missingTags.isNotEmpty()) {
            val tagFooter = missingTags.joinToString(" ") { "#$it" }
            if (formattedContent.isNotEmpty()) {
                "$formattedContent\n\n$tagFooter"
            } else {
                tagFooter
            }
        } else {
            formattedContent
        }

        return NoteFormatResult(
            title = title,
            formattedText = finalText,
            tags = tags,
            addCategoryName = addCategoryName,
            appendCategoryName = appendCategoryName,
            isTask = isTask
        )
    }

    private fun parseBullets(text: String): String {
        val matches = BULLET_TRIGGER.findAll(text).toList()
        if (matches.isEmpty()) return text

        val firstMatch = matches.first()
        val prefix = text.substring(0, firstMatch.range.first).trim()
        val hasColon = matches.any { it.value.contains(":") }
        val startsAtBeginning = firstMatch.range.first == 0 || prefix.isEmpty()
        val prefixEndsWithColon = prefix.endsWith(":")

        val isLikelyBulletList = startsAtBeginning ||
            matches.size >= 2 ||
            hasColon ||
            prefixEndsWithColon

        if (!isLikelyBulletList) return text

        val items = mutableListOf<String>()
        for (i in matches.indices) {
            val start = matches[i].range.last + 1
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
            if (start < end) {
                val rawItem = text.substring(start, end).trim().trimEnd(',', ';')
                if (rawItem.isNotEmpty()) {
                    val capitalized = rawItem.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase() else it.toString()
                    }
                    items.add(capitalized)
                }
            }
        }

        if (items.isEmpty()) return text

        val bulletsBlock = items.joinToString("\n") { "- $it" }
        return if (prefix.isEmpty()) {
            bulletsBlock
        } else {
            val separator = if (prefix.endsWith("\n")) "" else if (prefix.endsWith(":")) "\n" else "\n\n"
            "$prefix$separator$bulletsBlock"
        }
    }
}
