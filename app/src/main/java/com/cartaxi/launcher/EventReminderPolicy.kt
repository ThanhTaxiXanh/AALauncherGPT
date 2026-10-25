package com.cartaxi.launcher

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** Explicitly opt-in, private, local-only reminder preferences. */
data class EventReminderSettings(
    val enabled: Boolean = false,
    val advanceDays: Int = 1,
    val includeHolidays: Boolean = false
) {
    init {
        require(advanceDays in setOf(0, 1, 3, 7))
    }
}

data class EventReminderOccurrence(
    val event: CalEvent, val date: LocalDate, val daysRemaining: Int
)

/** Pure scheduling/selection logic, independent of Android alarm delivery. */
object EventReminderPolicy {
    fun due(
        own: List<CalEvent>, today: LocalDate, settings: EventReminderSettings
    ): List<EventReminderOccurrence> {
        if (!settings.enabled) return emptyList()
        val all = own.filter { it.kind == 0 } +
            if (settings.includeHolidays) Events.defaults().filter { it.kind == 1 }
            else emptyList()
        return all.distinctBy { it.id }.mapNotNull { event ->
            val date = Events.nextDate(event, today) ?: return@mapNotNull null
            val days = ChronoUnit.DAYS.between(today, date).toInt()
            if (days == 0 || (settings.advanceDays > 0 && days == settings.advanceDays))
                EventReminderOccurrence(event, date, days)
            else null
        }.sortedWith(compareBy({ it.daysRemaining }, { it.event.id })).take(6)
    }

    fun shouldDeliver(
        today: LocalDate, event: CalEvent, occurred: LocalDate,
        previouslySent: Set<String>
    ): Boolean = key(event, occurred) !in previouslySent &&
        !occurred.isBefore(today)

    fun key(event: CalEvent, occurred: LocalDate): String =
        "${event.id}:${occurred.toEpochDay()}"

    /** Inexact alarm is only a daily wake-up, not a precise appointment alarm. */
    fun nextMorningMillis(now: ZonedDateTime, hour: Int = 9): Long {
        require(hour in 0..23)
        var at = now.toLocalDate().atTime(hour, 0).atZone(now.zone)
        if (!at.isAfter(now)) at = at.plusDays(1)
        return at.toInstant().toEpochMilli()
    }
}
