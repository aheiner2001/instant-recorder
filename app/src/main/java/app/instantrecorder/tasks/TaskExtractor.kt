package app.instantrecorder.tasks

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

data class Due(val epochMillis: Long, val hasTime: Boolean) {
    fun label(now: ZonedDateTime = ZonedDateTime.now()): String {
        val at = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), now.zone)
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), at.toLocalDate())
        val day = when (days) {
            0L -> "Today"
            1L -> "Tomorrow"
            -1L -> "Yesterday"
            in 2L..6L -> at.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))
            else -> at.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))
        }
        return if (hasTime) "$day ${at.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))}" else day
    }
}

data class TaskSuggestion(
    val title: String,
    val due: Due?,
    /** Looked like an instruction ("remind me to…", "call…") rather than narration. */
    val actionable: Boolean,
    val categoryId: Long? = null,
    /** [Repeat] bitmask; 0 = one-off. When set, [due] is the first occurrence. */
    val repeatDays: Int = Repeat.NONE,
    /** "…3 days before the board meeting": the event to look up in the calendar (done outside, by the UI). */
    val event: EventRequest? = null,
    /** The calendar event [event] was matched to; then [due] is computed from it. */
    val linkedEvent: EventRef? = null,
)

/** A category as the extractor needs it: its name (spoken as "Work: …") and its keywords. */
data class CategoryRule(val id: Long, val name: String, val keywords: List<String>)

/**
 * Rule-based, on-device task extraction for English voice notes:
 * split into clauses → pull out dates/times → strip "remind me to"-style lead-ins.
 */
object TaskExtractor {

    /**
     * Categories: a clause starting with a category name ("Work: email Sarah", "Personal. Call mom") files
     * it and the following clauses there until another one is named. A category's keyword in a clause
     * wins over that carried-over choice, since it's about this task specifically.
     */
    fun extract(
        transcript: String,
        now: ZonedDateTime = ZonedDateTime.now(),
        categories: List<CategoryRule> = emptyList(),
    ): List<TaskSuggestion> {
        val text = normalize(transcript)
        if (text.isBlank()) return emptyList()

        var spoken: Long? = null
        val suggestions = text.split(SENTENCE_SPLIT)
            .flatMap { it.split(CLAUSE_SPLIT) }
            .mapNotNull { rawClause ->
                var clause = rawClause
                categoryPrefix(clause, categories)?.let { (category, rest) ->
                    spoken = category.id
                    clause = rest
                }
                suggestionFor(clause, now)?.let { s ->
                    s.copy(categoryId = keywordCategory(clause, categories) ?: spoken)
                }
            }
            .distinctBy { it.title.lowercase() }
            .take(MAX_SUGGESTIONS)

        if (suggestions.isNotEmpty()) return suggestions
        // Nothing usable after cleanup: offer the note itself as one task.
        return listOf(TaskSuggestion(capitalize(text.take(MAX_TITLE).trim()), null, actionable = false))
    }

    /** Pulls date/time phrases out of [input]; returns the due date and the text without them. */
    fun parseDue(input: String, now: ZonedDateTime = ZonedDateTime.now()): Pair<Due?, String> {
        val p = DueParser(input, now)
        return p.parse() to p.remaining
    }

    /** "Work: email Sarah" → (Work, "email Sarah"); a bare "Work." → (Work, ""). */
    private fun categoryPrefix(clause: String, categories: List<CategoryRule>): Pair<CategoryRule, String>? {
        val text = stripFillers(clause)
        for (category in categories) {
            val prefix = Regex(
                """^(?:(?:for|in|under)\s+)?${Regex.escape(category.name)}(?:\s+tasks?)?\s*(?:[:,.;\-–—]+\s*|$)""",
                RegexOption.IGNORE_CASE,
            )
            val match = prefix.find(text) ?: continue
            return category to text.substring(match.range.last + 1)
        }
        return null
    }

    private fun keywordCategory(clause: String, categories: List<CategoryRule>): Long? =
        categories.firstOrNull { category ->
            category.keywords.any { kw -> Regex("""\b${Regex.escape(kw)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(clause) }
        }?.id

    private fun suggestionFor(clause: String, now: ZonedDateTime): TaskSuggestion? {
        // "…3 days before the board meeting": the date comes from the calendar later, so take that phrase out first.
        val (event, withoutEvent) = parseEventRequest(clause)
        // Repeats first, so "every Monday" isn't read as a one-off "Monday" date.
        val (repeatDays, withoutRepeat) = if (event != null) Repeat.NONE to withoutEvent else parseRepeat(withoutEvent)
        val (parsedDue, withoutDates) = parseDue(withoutRepeat, now)
        val due = if (event != null) null else if (repeatDays == Repeat.NONE) parsedDue else {
            val time = parsedDue?.takeIf { it.hasTime }?.let { java.time.Instant.ofEpochMilli(it.epochMillis).atZone(now.zone).toLocalTime() }
            Repeat.first(repeatDays, now, time)
        }
        var title = stripFillers(withoutDates)
        val leadIn = LEAD_IN.find(title)
        if (leadIn != null) title = title.substring(leadIn.range.last + 1)
        title = cleanup(stripFillers(title))
        if (title.isEmpty()) return null

        val words = title.split(' ')
        val firstWord = words.first().lowercase()
        val actionable = leadIn != null || firstWord in ACTION_VERBS
        // Tiny fragments ("Yeah", "I") are leftovers from splitting, not tasks — unless a date or repeat says
        // otherwise ("Stand-up every weekday at 9").
        if (words.size < 2 && !actionable && due == null && event == null) return null
        return TaskSuggestion(capitalize(title.take(MAX_TITLE)), due, actionable, repeatDays = repeatDays, event = event)
    }

    /** "3 days before X", "the day before X", "a week before X", "2 hours before X" → request + rest of the text. */
    private fun parseEventRequest(text: String): Pair<EventRequest?, String> {
        val m = BEFORE_EVENT.find(text) ?: return null to text
        val offset = if (m.groupValues[3].isNotEmpty()) {
            when (m.groupValues[3].lowercase()) {
                "week" -> EventTiming.WEEK
                else -> EventTiming.DAY // the day / night / evening / morning before
            }
        } else {
            val n = m.groupValues[1].lowercase().let { it.toIntOrNull() ?: OFFSET_NUMBERS[it] } ?: return null to text
            n * when {
                m.groupValues[2].startsWith("w", ignoreCase = true) -> EventTiming.WEEK
                m.groupValues[2].startsWith("d", ignoreCase = true) -> EventTiming.DAY
                m.groupValues[2].startsWith("h", ignoreCase = true) -> EventTiming.HOUR
                else -> 1
            }
        }
        val query = m.groupValues[4].trim().trimEnd('.', '!', '?', ',', ';')
        if (query.isBlank() || offset <= 0) return null to text
        return EventRequest(query, offset, m.value.trim().trimEnd('.', '!', '?', ',', ';')) to text.removeRange(m.range)
    }

    /**
     * "every day"/"daily", "every weekday", "on weekends", "every Monday and Thursday", "on Mondays" → the
     * repeat days and the text without that phrase. A singular "on Monday" stays a one-off date.
     */
    private fun parseRepeat(text: String): Pair<Int, String> {
        for ((regex, days) in REPEAT_PRESETS) {
            val m = regex.find(text) ?: continue
            return days to text.removeRange(m.range)
        }
        for (m in REPEAT_DAYS.findAll(text)) {
            val prefix = m.groupValues[1].lowercase()
            val list = m.groupValues[2]
            val names = DAY_NAME.findAll(list).map { it.value.lowercase() }.toList()
            val plural = names.any { it.endsWith("s") }
            if (prefix == "on" && !plural) continue
            val days = names.fold(Repeat.NONE) { acc, name ->
                acc or Repeat.bit(java.time.DayOfWeek.entries.first { name.startsWith(it.name.lowercase().take(3)) })
            }
            return days to text.removeRange(m.range)
        }
        return Repeat.NONE to text
    }

    private fun normalize(raw: String): String = raw
        .replace('\u2019', '\'')
        .replace(Regex("""\b([aApP])\.\s?[mM]\.?"""), "$1m")
        .replace(Regex("""\b(Mr|Mrs|Ms|Dr|St|Jr|Sr|vs|etc)\.""", RegexOption.IGNORE_CASE), "$1")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun stripFillers(s: String): String = s.trim().replace(LEADING_FILLER, "")

    private fun cleanup(s: String): String {
        var t = s.replace(Regex("""\s+([,.;:!?])"""), "$1").replace(Regex("""\s+"""), " ").trim()
        var previous: String
        do {
            previous = t
            t = t.replace(TRAILING_JUNK, "").replace(Regex("""^[\s,.;:!?-]+"""), "").trim()
        } while (t != previous)
        return t
    }

    private fun capitalize(s: String) = s.replaceFirstChar { it.titlecase(Locale.getDefault()) }

    private const val MAX_SUGGESTIONS = 10

    private val OFFSET_NUMBERS = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "a couple of" to 2, "a few" to 3,
    )
    /** The event name runs to the end of the clause: "…before the board meeting." */
    private val BEFORE_EVENT = Regex(
        """\b(?:(\d+|a couple of|a few|an?|one|two|three|four|five|six|seven|eight|nine|ten)\s+(minutes?|mins?|hours?|days?|weeks?)|the\s+(day|night|evening|morning|week))\s+before\s+(.+)$""",
        RegexOption.IGNORE_CASE,
    )

    private val DAY = "(?:mon|tues|wednes|thurs|fri|satur|sun)days?"
    private val DAY_NAME = Regex(DAY, RegexOption.IGNORE_CASE)
    private val REPEAT_PRESETS = listOf(
        Regex("""\b(?:every\s*day|each\s+day|daily)\b""", RegexOption.IGNORE_CASE) to Repeat.EVERY_DAY,
        Regex("""\b(?:(?:every|each|on)\s+weekdays?|weekdays)\b""", RegexOption.IGNORE_CASE) to Repeat.WEEKDAYS,
        Regex("""\b(?:(?:every|each|on)\s+weekends?|weekends)\b""", RegexOption.IGNORE_CASE) to Repeat.WEEKENDS,
    )
    /** "every Monday and Thursday", "weekly on Friday", "on Mondays, Wednesdays and Fridays". */
    private val REPEAT_DAYS = Regex(
        """\b(every\s+week\s+on|weekly\s+on|every|each|on)\s+($DAY(?:\s*(?:,\s*and|,|and|&)\s*$DAY)*)\b""",
        RegexOption.IGNORE_CASE,
    )
    private const val MAX_TITLE = 140

    private val ACTION_VERBS = setOf(
        "ask", "back", "book", "bring", "buy", "call", "cancel", "charge", "check", "clean", "clear", "confirm",
        "contact", "cook", "deploy", "do", "drop", "email", "e-mail", "feed", "file", "fill", "find", "finish",
        "fix", "follow", "get", "go", "grab", "invite", "look", "make", "meet", "merge", "message", "order",
        "organize", "pack", "pay", "pick", "ping", "plan", "post", "prepare", "print", "push", "read", "register",
        "renew", "reply", "research", "reserve", "respond", "return", "review", "schedule", "send", "set",
        "share", "sign", "sort", "start", "submit", "take", "talk", "tell", "test", "text", "update", "upload",
        "visit", "wash", "water", "write",
    )
    private val SENTENCE_SPLIT = Regex("""(?<=[.!?;])\s+|\n+""")
    private val CLAUSE_SPLIT = Regex(
        """,?\s+(?:and then|and also|after that|then also)\s+|,\s+(?:then|also|plus)\s+|""" +
            """,?\s+and\s+(?=(?:i\s+(?:also\s+)?(?:need|have|should|must|gotta|want|got)|remind me|don'?t forget|remember to)\b)|""" +
            // "…dentist appointment, and email Sarah…": a comma plus a verb starts a new task.
            """,\s+and\s+(?=(?:${ACTION_VERBS.joinToString("|")})\b)""",
        RegexOption.IGNORE_CASE,
    )
    private val LEADING_FILLER = Regex(
        """^(?:(?:ok(?:ay)?|so|um+|uh+|uhm|hmm+|well|alright|all right|right|hey|and|but|also|then|plus|now|oh|yeah|yes)\b[,\s]*)+""",
        RegexOption.IGNORE_CASE,
    )
    private val LEAD_IN = Regex(
        "^(?:please\\s+)?(?:" +
            "(?:can you |could you )?remind me (?:to |that i (?:need|have) to |about )|" +
            "(?:i|we) (?:really |also |still |definitely )?(?:need|have|must|should|want|ought) (?:to )?|" +
            "(?:i|we)(?:'ve| have) got to |(?:i|we) (?:gotta|got to) |(?:i|we)(?:'d| would) like to |" +
            "(?:i|we)(?:'ll| will)(?: need to| have to)? |(?:i'm|i am|we're|we are) (?:going to|gonna) |" +
            "(?:don'?t|do not) forget (?:to )?|remember to |make sure (?:to |that i |i )?|" +
            "note to self[:,]?\\s*|to-?do[:,]?\\s*|task[:,]?\\s*|(?:add|create) (?:a )?(?:task|reminder|to-?do) (?:to )?|" +
            "need to |have to |got to |gotta |must |let'?s " +
            ")",
        RegexOption.IGNORE_CASE,
    )
    private val TRAILING_JUNK = Regex(
        """(?:\s+(?:on|at|by|for|in|this|next|the|before|until|around|and|or|to)|[\s,.;:!?-])+$""",
        RegexOption.IGNORE_CASE,
    )
}

/** Finds the first usable date and time phrases in [input], removing them from [remaining]. */
private class DueParser(input: String, private val now: ZonedDateTime) {
    var remaining = input
        private set
    private var date: LocalDate? = null
    private var time: LocalTime? = null
    private var dateTime: ZonedDateTime? = null
    private var partOfDay: LocalTime? = null
    private val today = now.toLocalDate()

    fun parse(): Due? {
        consume(IN_DURATION) { m ->
            val amount = numberWord(m.groupValues[1]) ?: return@consume false
            val unit = m.groupValues[2].lowercase()
            when {
                unit.startsWith("min") -> dateTime = now.plusMinutes(amount.toLong())
                unit.startsWith("h") -> dateTime = now.plusMinutes((amount * 60).toLong())
                unit.startsWith("d") -> date = today.plusDays(amount.toLong())
                unit.startsWith("w") -> date = today.plusWeeks(amount.toLong())
                else -> return@consume false
            }
            true
        }
        consume(IN_HALF_HOUR) { dateTime = now.plusMinutes(30); true }
        consume(RELATIVE_DAY) { m ->
            if (date != null) return@consume false
            date = when (m.groupValues[1].lowercase()) {
                "today" -> today
                "tonight" -> today.also { partOfDay = NIGHT }
                "the day after tomorrow" -> today.plusDays(2)
                else -> today.plusDays(1)
            }
            partOfDayFrom(m.groupValues[2])
            true
        }
        consume(THIS_PART_OF_DAY) { m ->
            if (date == null) date = today
            partOfDayFrom(m.groupValues[1])
            true
        }
        consume(WEEK_PHRASE) { m ->
            if (date != null) return@consume false
            val phrase = m.groupValues[1].lowercase().replace(Regex("""\s+"""), " ")
            when {
                phrase == "next week" -> date = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                phrase == "this weekend" -> date = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
                    .let { if (today.dayOfWeek == DayOfWeek.SUNDAY) today else it }
                phrase == "next weekend" -> date = today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY)).plusWeeks(
                    if (today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY) 0 else 1
                )
                phrase.endsWith("day") -> { date = today; if (time == null) time = END_OF_DAY }
                phrase.endsWith("week") -> date = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY))
                phrase.endsWith("month") -> date = today.with(TemporalAdjusters.lastDayOfMonth())
                else -> return@consume false
            }
            true
        }
        consume(WEEKDAY) { m ->
            if (date != null) return@consume false
            val target = DayOfWeek.valueOf(m.groupValues[3].uppercase())
            val qualifier = (m.groupValues[2].ifEmpty { m.groupValues[1] }).lowercase()
            var ahead = (target.value - today.dayOfWeek.value + 7) % 7
            if (ahead == 0 && qualifier != "this") ahead = 7
            var d = today.plusDays(ahead.toLong())
            if (qualifier == "next" && !d.isAfter(today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)))) {
                d = d.plusWeeks(1)
            }
            date = d
            partOfDayFrom(m.groupValues[4])
            true
        }
        consume(MONTH_DAY) { m ->
            if (date != null) return@consume false
            val day = (m.groupValues[1].ifEmpty { m.groupValues[3] }).toIntOrNull() ?: return@consume false
            val month = MONTHS.indexOfFirst { m.groupValues[2].lowercase().startsWith(it) } + 1
            if (month == 0) return@consume false
            val candidate = runCatching { LocalDate.of(today.year, month, day) }.getOrNull() ?: return@consume false
            date = if (candidate.isBefore(today)) candidate.plusYears(1) else candidate
            true
        }
        consume(ORDINAL_DAY) { m ->
            if (date != null) return@consume false
            val day = m.groupValues[1].toInt()
            val thisMonth = runCatching { today.withDayOfMonth(day) }.getOrNull() ?: return@consume false
            date = if (thisMonth.isBefore(today)) thisMonth.plusMonths(1) else thisMonth
            true
        }
        consume(NAMED_TIME) { m ->
            if (time != null) return@consume false
            time = if (m.groupValues[1].lowercase() == "midnight") LocalTime.of(23, 59) else LocalTime.NOON
            true
        }
        consume(PREFIXED_TIME) { m -> time == null && setClock(m.groupValues[1], m.groupValues[2], m.groupValues[3]) }
        consume(BARE_TIME) { m -> time == null && setClock(m.groupValues[1], m.groupValues[2], m.groupValues[3]) }

        dateTime?.let { dt ->
            return Due(dt.toInstant().toEpochMilli(), hasTime = true)
        }
        val t = time ?: partOfDay
        val d = date ?: if (t != null) {
            if (today.atTime(t).atZone(now.zone).isAfter(now)) today else today.plusDays(1)
        } else {
            return null
        }
        return if (t != null) {
            Due(d.atTime(t).atZone(now.zone).toInstant().toEpochMilli(), hasTime = true)
        } else {
            Due(d.atStartOfDay(now.zone).toInstant().toEpochMilli(), hasTime = false)
        }
    }

    private fun setClock(hourText: String, minuteText: String, marker: String): Boolean {
        var hour = hourText.toIntOrNull() ?: return false
        val minute = minuteText.toIntOrNull() ?: 0
        if (hour > 23 || minute > 59) return false
        when (marker.lowercase()) {
            "am" -> if (hour == 12) hour = 0
            "pm" -> if (hour < 12) hour += 12
            // No am/pm: people mean 1–7 in the afternoon/evening far more often than at night.
            else -> if (hour in 1..7) hour += 12
        }
        time = LocalTime.of(hour % 24, minute)
        return true
    }

    private fun partOfDayFrom(word: String) {
        partOfDay = when (word.lowercase()) {
            "morning" -> MORNING
            "afternoon" -> AFTERNOON
            "evening" -> EVENING
            "night" -> NIGHT
            else -> partOfDay
        }
    }

    /** Removes the first match the handler accepts. */
    private fun consume(regex: Regex, handler: (MatchResult) -> Boolean) {
        for (m in regex.findAll(remaining)) {
            if (handler(m)) {
                remaining = (remaining.substring(0, m.range.first) + " " + remaining.substring(m.range.last + 1))
                return
            }
        }
    }

    private fun numberWord(s: String): Int? = s.lowercase().let { w ->
        w.toIntOrNull() ?: NUMBER_WORDS[w]
    }

    companion object {
        private val MORNING = LocalTime.of(9, 0)
        private val AFTERNOON = LocalTime.of(14, 0)
        private val EVENING = LocalTime.of(18, 0)
        private val NIGHT = LocalTime.of(20, 0)
        private val END_OF_DAY = LocalTime.of(17, 0)

        private val NUMBER_WORDS = mapOf(
            "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
            "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
            "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty-five" to 45, "a couple of" to 2, "a few" to 3,
        )
        private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

        private val I = RegexOption.IGNORE_CASE
        private val IN_DURATION = Regex(
            """\b(?:in|within)\s+(\d+|a couple of|a few|an?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty-five)\s+(minutes?|mins?|hours?|hrs?|days?|weeks?)\b""",
            I,
        )
        private val IN_HALF_HOUR = Regex("""\bin\s+(?:half an hour|a half hour|half hour)\b""", I)
        private val RELATIVE_DAY = Regex(
            """\b(?:on\s+|by\s+|for\s+)?(the day after tomorrow|today|tonight|tomorrow)\b(?:\s+(morning|afternoon|evening|night))?""",
            I,
        )
        private val THIS_PART_OF_DAY = Regex("""\b(?:this|in the)\s+(morning|afternoon|evening)\b""", I)
        private val WEEK_PHRASE = Regex(
            """\b(?:by\s+|for\s+|at\s+)?(next week|this weekend|next weekend|(?:the\s+)?end of (?:the\s+)?(?:day|week|month))\b""",
            I,
        )
        private val WEEKDAY = Regex(
            """\b(?:(on|by|this|next|until|before|for)\s+)?(?:(this|next)\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)s?\b(?:\s+(morning|afternoon|evening|night))?""",
            I,
        )
        private val MONTH_DAY = Regex(
            """\b(?:on\s+|by\s+)?(?:the\s+)?(?:(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?)?(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\.?(?:\s+(\d{1,2})(?:st|nd|rd|th)?)?\b""",
            I,
        )
        private val ORDINAL_DAY = Regex("""\b(?:on|by)\s+the\s+(\d{1,2})(?:st|nd|rd|th)\b""", I)
        private val NAMED_TIME = Regex("""\b(?:at|by|around|before)?\s*(noon|midday|midnight)\b""", I)
        private val PREFIXED_TIME = Regex("""\b(?:at|by|around|before)\s+(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm|o'?clock)?\b""", I)
        private val BARE_TIME = Regex("""\b(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm)\b""", I)
    }
}
