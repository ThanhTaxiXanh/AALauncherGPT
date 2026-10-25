package Thanh.TaxiDriver.AALauncher

import Thanh.TaxiDriver.AALauncher.calendar.LunarCalendar

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression dates for the Vietnamese UTC+7 lunar calendar. */
class LunarCalendarTest {
    @Test fun tet2024StartsOnFebruary10() {
        val lunar = LunarCalendar.solarToLunar(10, 2, 2024)
        assertEquals(1, lunar.day)
        assertEquals(1, lunar.month)
        assertEquals(2024, lunar.year)
        assertFalse(lunar.leap)
        assertArrayEquals(intArrayOf(10, 2, 2024), LunarCalendar.lunarToSolar(1, 1, 2024, false))
        assertEquals("Giáp Thìn", LunarCalendar.canChi(2024))
    }

    @Test fun tet2025StartsOnJanuary29() {
        val lunar = LunarCalendar.solarToLunar(29, 1, 2025)
        assertEquals(1, lunar.day)
        assertEquals(1, lunar.month)
        assertEquals(2025, lunar.year)
        assertFalse(lunar.leap)
        assertArrayEquals(intArrayOf(29, 1, 2025), LunarCalendar.lunarToSolar(1, 1, 2025, false))
    }

    @Test fun leapSecondMonthOf2023IsRecognized() {
        val lunar = LunarCalendar.solarToLunar(22, 3, 2023)
        assertEquals(1, lunar.day)
        assertEquals(2, lunar.month)
        assertEquals(2023, lunar.year)
        assertTrue(lunar.leap)
        val solar = LunarCalendar.lunarToSolar(1, 2, 2023, true)
        assertNotNull(solar)
        assertArrayEquals(intArrayOf(22, 3, 2023), solar)
    }
}
