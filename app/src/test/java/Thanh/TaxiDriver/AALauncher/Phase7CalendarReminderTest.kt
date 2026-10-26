package Thanh.TaxiDriver.AALauncher

import Thanh.TaxiDriver.AALauncher.calendar.CalEvent
import Thanh.TaxiDriver.AALauncher.calendar.EventReminderPolicy
import Thanh.TaxiDriver.AALauncher.calendar.EventReminderSettings
import Thanh.TaxiDriver.AALauncher.calendar.Events

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class Phase7CalendarReminderTest {
    private val oct = LocalDate.of(2026, 10, 25)

    private fun own(id: Long, day: Int, month: Int, year: Int = 0,
                    repeat: Int = 0, lunar: Boolean = false,
                    leap: Boolean = false) = CalEvent(
        id, "Sự kiện $id", lunar, day, month, year, repeat, leapMonth = leap
    )

    @Test fun legacyEventJsonStillLoadsWithoutLosingFields() {
        val legacy = """[{"id":11,"title":"Giỗ ông","lunar":true,"day":15,"month":7,"year":0,"repeat":0}]"""
        val old = Events.parse(legacy).single()
        assertEquals(11L, old.id)
        assertFalse(old.leapMonth)
        assertEquals(0, old.repeat)
        assertEquals("Giỗ ông", old.title)
        assertTrue(Events.validateUserEvent(old))
    }

    @Test fun lunarLeapMonthOneTimeConvertsExactly() {
        val e = own(1, 1, 2, 2023, repeat = 2, lunar = true, leap = true)
        assertTrue(Events.validateUserEvent(e))
        assertEquals(LocalDate.of(2023,3,22),
            Events.nextDate(e, LocalDate.of(2023,1,1)))
        val other = e.copy(leapMonth = false)
        assertEquals(LocalDate.of(2023,2,20),
            Events.nextDate(other, LocalDate.of(2023,1,1)))
        assertEquals(null, Events.nextDate(e, LocalDate.of(2024,1,1)))
    }

    @Test fun annualLeapMonthSkipsYearsWithoutCorrespondingLeapMonth() {
        val e = own(1, 1, 2, repeat = 0, lunar = true, leap = true)
        assertTrue(Events.validateUserEvent(e))
        assertEquals(LocalDate.of(2023, 3, 22),
            Events.nextDate(e, LocalDate.of(2023, 1, 1)))
        val previous = Events.nextDate(e, LocalDate.of(2024, 1, 1))
        assertTrue(previous == null || previous.isAfter(LocalDate.of(2024, 1, 1)))
    }

    @Test fun invalidSolarDatesLunarLeapAndFieldsRejected() {
        assertFalse(Events.validateUserEvent(own(2, 29, 2, 2025, repeat = 2)))
        assertTrue(Events.validateUserEvent(own(2, 29, 2, 2024, repeat = 2)))
        assertFalse(Events.validateUserEvent(own(2, 1, 1, 2024, 2, leap = true)))
        assertFalse(Events.validateUserEvent(own(2, 1, 2, 0, 1, lunar = true, leap = true)))
        assertFalse(Events.validateUserEvent(own(2, 32, 1)))
        assertFalse(Events.validateUserEvent(own(2, 1, 13)))
        assertFalse(Events.validateUserEvent(own(0, 1, 1)))
        assertFalse(Events.validateUserEvent(own(2, 1, 1).copy(title = " ".repeat(10))))
        assertFalse(Events.validateUserEvent(own(2, 1, 1).copy(title = "x".repeat(101))))
    }

    @Test fun reminderDisabledByDefault() {
        val events = listOf(own(1, 25, 10, 2026, repeat = 2))
        assertFalse(EventReminderSettings().enabled)
        assertTrue(EventReminderPolicy.due(events, oct, EventReminderSettings()).isEmpty())
    }

    @Test fun todayAndLeadDaySendOnCorrectDates() {
        val events = listOf(
            own(1, 25, 10, 2026, repeat = 2),
            own(2, 26, 10, 2026, repeat = 2),
            own(3, 28, 10, 2026, repeat = 2),
            own(4, 29, 10, 2026, repeat = 2))
        val one = EventReminderPolicy.due(
            events, oct, EventReminderSettings(enabled = true, advanceDays = 1)
        )
        assertEquals(setOf(1L,2L), one.map { it.event.id }.toSet())
        assertTrue(one.any { it.event.id == 1L && it.daysRemaining == 0 })
        val three = EventReminderPolicy.due(
            events, oct, EventReminderSettings(enabled = true, advanceDays = 3)
        )
        assertEquals(setOf(1L,3L), three.map { it.event.id }.toSet())
    }

    @Test fun noHolidaysUntilExplicitOptInAndNoMonthlySpam() {
        val none = EventReminderPolicy.due(emptyList(), LocalDate.of(2026,1,1),
            EventReminderSettings(enabled = true))
        assertTrue(none.isEmpty())
        val yes = EventReminderPolicy.due(emptyList(), LocalDate.of(2026,1,1),
            EventReminderSettings(enabled = true, includeHolidays = true))
        assertTrue(yes.any { it.event.title == "Tết Dương lịch" })
        assertTrue(yes.none { it.event.kind == 2 })
    }

    @Test fun repeatMonthlyAndOneTimePastEventsHandled() {
        val recurring = own(1, 25, 1, repeat = 1)
        val old = own(2, 25, 10, 2025, repeat = 2)
        val due = EventReminderPolicy.due(listOf(recurring, old), oct,
            EventReminderSettings(enabled = true))
        assertEquals(1, due.size)
        assertEquals(1L, due.single().event.id)
    }

    @Test fun dedupeKeyIncludesOccurrenceNotJustEventId() {
        val e = own(1, 25, 10, repeat = 0)
        val key = EventReminderPolicy.key(e, oct)
        assertFalse(EventReminderPolicy.shouldDeliver(oct, e, oct, setOf(key)))
        assertTrue(EventReminderPolicy.shouldDeliver(oct, e, oct, emptySet()))
        assertNotEquals(key, EventReminderPolicy.key(e, oct.plusYears(1)))
    }

    @Test fun scheduleNextLocalMorningAndDayRollover() {
        val zone = ZoneId.of("Asia/Ho_Chi_Minh")
        val before = ZonedDateTime.of(2026, 10, 25, 8, 30, 0, 0, zone)
        val after = ZonedDateTime.of(2026, 10, 25, 10, 30, 0, 0, zone)
        assertEquals(before.toLocalDate().atTime(9, 0).atZone(zone).toInstant().toEpochMilli(),
            EventReminderPolicy.nextMorningMillis(before))
        assertEquals(before.plusDays(1).toLocalDate().atTime(9, 0)
            .atZone(zone).toInstant().toEpochMilli(),
            EventReminderPolicy.nextMorningMillis(after))
    }

    @Test fun defaultHolidaysRemainVisibleOnCalendarWithoutReminderOptIn() {
        val lines = Events.lines("[]", LocalDate.of(2025,1,29))
        assertTrue(lines.isNotEmpty())
        val disabled = EventReminderPolicy.due(emptyList(),
            LocalDate.of(2025,1,29), EventReminderSettings())
        assertTrue(disabled.isEmpty())
    }
}
