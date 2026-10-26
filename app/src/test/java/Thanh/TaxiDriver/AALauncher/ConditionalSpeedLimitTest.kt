package Thanh.TaxiDriver.AALauncher

import Thanh.TaxiDriver.AALauncher.signs.ConditionalSpeedLimit

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConditionalSpeedLimitTest {
    private fun instant(day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("Asia/Ho_Chi_Minh")).apply {
            clear()
            set(2026, Calendar.OCTOBER, day, hour, minute, 0)
        }.timeInMillis

    @Test fun weekdayRushHourOverridesBaseAtVietnamLocalTime() {
        // Monday October 26, 2026.
        val tag = "50 @ (Mo-Fr 07:00-09:00)"
        assertEquals(50, ConditionalSpeedLimit.resolve(70, tag, instant(26, 7, 0)))
        assertEquals(50, ConditionalSpeedLimit.resolve(70, tag, instant(26, 8, 59)))
        assertEquals(70, ConditionalSpeedLimit.resolve(70, tag, instant(26, 9, 0)))
        assertEquals(70, ConditionalSpeedLimit.resolve(70, tag, instant(25, 8, 0))) // Sunday
    }

    @Test fun multipleNonOverlappingTimeWindowsAndNoBase() {
        val tag = "40 @ (Mo-Fr 07:00-09:00); 50 @ (Mo-Fr 16:00-18:00)"
        assertEquals(40, ConditionalSpeedLimit.resolve(null, tag, instant(26, 8, 15)))
        assertEquals(50, ConditionalSpeedLimit.resolve(null, tag, instant(26, 17, 15)))
        assertNull(ConditionalSpeedLimit.resolve(null, tag, instant(26, 12, 0)))
    }

    @Test fun weekendDaysAndUnitConversionsAreSupported() {
        val tag = "30 mph @ (Sa,Su 08:00-20:00)"
        assertEquals(48, ConditionalSpeedLimit.resolve(80, tag, instant(25, 9, 30)))
        assertEquals(80, ConditionalSpeedLimit.resolve(80, tag, instant(26, 9, 30)))
    }

    @Test fun unsupportedConditionsDoNotSilentlyReturnTheBaseLimit() {
        for (s in listOf(
            "30 @ (wet)",
            "40 @ (PH)",
            "30 @ (school)",
            "50 @ (Mo-Fr sunrise-sunset)",
            "60 @ (Mo-Fr 22:00-06:00)",
            "50 @ (Mo-Fr 25:00-26:00)",
            "90 @ (Mo-Fr)",
            "70 @ (2026 Oct 25-2027 Apr 1)",
            "60 @ (Fr-Mo 08:00-10:00)",
            "70 @ (Mo-Fr 07:00-08:00); invalid @ (Sa 08:00-09:00)"
        )) {
            assertNull("Should not guess: $s", ConditionalSpeedLimit.resolve(80, s, instant(26, 8, 0)))
        }
    }

    @Test fun conflictingOverlappingRulesAreAmbiguous() {
        val tag = "50 @ (Mo-Fr 07:00-10:00); 60 @ (Mo 08:00-12:00)"
        assertNull(ConditionalSpeedLimit.resolve(80, tag, instant(26, 8, 30)))
        assertEquals(50, ConditionalSpeedLimit.resolve(80, tag, instant(26, 7, 15)))
        assertEquals(60, ConditionalSpeedLimit.resolve(80, tag, instant(26, 11, 15)))
    }

    @Test fun noConditionalKeepsCurrentSpeedParserSemantics() {
        assertEquals(80, ConditionalSpeedLimit.resolve(80, null, instant(26, 8, 0)))
        assertNull(ConditionalSpeedLimit.resolve(80, "", instant(26, 8, 0)))
        assertEquals(80, ConditionalSpeedLimit.parseSpeed("50 mph"))
        assertEquals(7, ConditionalSpeedLimit.parseSpeed("walk"))
        assertNull(ConditionalSpeedLimit.parseSpeed("50;80"))
    }
}
