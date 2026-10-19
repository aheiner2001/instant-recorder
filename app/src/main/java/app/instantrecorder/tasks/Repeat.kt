package app.instantrecorder.tasks

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * Weekly repeat days, stored as a bitmask (Monday = bit 0 … Sunday = bit 6; 0 = doesn't repeat).
 *
 * Only one occurrence of a repeating task exists at a time: completing it creates the next ([next]),
 * so the list never fills up with future copies.
 */
object Repeat {
    const val NONE = 0
    const val EVERY_DAY = 0b1111111
    const val WEEKDAYS = 0b0011111
    const val WEEKENDS = 0b1100000

    fun bit(day: DayOfWeek): Int = 1 shl (day.value - 1)
    fun has(days: Int, day: DayOfWeek): Boolean = days and bit(day) != 0
    fun of(vararg days: DayOfWeek): Int = days.fold(NONE) { acc, d -> acc or bit(d) }

    fun label(days: Int, locale: Locale = Locale.getDefault()): String = when (days) {
        EVERY_DAY -> "Every day"
        WEEKDAYS -> "Weekdays"
        WEEKENDS -> "Weekends"
        else -> DayOfWeek.entries.filter { has(days, it) }.joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) }
    }

    /** First occurrence of a newly repeating task: today if it's one of the days (and [time] hasn't passed), else the next. */
    fun first(days: Int, now: ZonedDateTime, time: LocalTime?): Due {
        require(days != NONE)
        val today = now.toLocalDate()
        var date = today
        while (!has(days, date.dayOfWeek) || (date == today && time != null && !today.atTime(time).atZone(now.zone).isAfter(now))) {
            date = date.plusDays(1)
        }
        return dueOn(date, time, now.zone)
    }

    /**
     * The occurrence after one due [current] was completed: the next selected day after both its due date and
     * today. Finishing late skips ahead instead of piling up missed occurrences. The time of day carries over.
     */
    fun next(days: Int, current: Due?, now: ZonedDateTime): Due {
        require(days != NONE)
        val today = now.toLocalDate()
        val currentAt = current?.let { Instant.ofEpochMilli(it.epochMillis).atZone(now.zone) }
        val time = currentAt?.takeIf { current.hasTime }?.toLocalTime()
        var date = maxOf(currentAt?.toLocalDate() ?: today, today).plusDays(1)
        while (!has(days, date.dayOfWeek)) date = date.plusDays(1)
        return dueOn(date, time, now.zone)
    }

    private fun dueOn(date: LocalDate, time: LocalTime?, zone: ZoneId): Due =
        if (time != null) Due(date.atTime(time).atZone(zone).toInstant().toEpochMilli(), hasTime = true)
        else Due(date.atStartOfDay(zone).toInstant().toEpochMilli(), hasTime = false)
}
