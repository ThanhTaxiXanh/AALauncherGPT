package Thanh.TaxiDriver.AALauncher

import Thanh.TaxiDriver.AALauncher.road.SpeedWayGeometry
import Thanh.TaxiDriver.AALauncher.road.SpeedWayMatch
import Thanh.TaxiDriver.AALauncher.road.SpeedWayMatcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedWayMatcherTest {
    private val scale = 111_320.0
    private fun eastWest(id: Long, north: Double, layer: Int = 0) =
        SpeedWayGeometry(id, layer, doubleArrayOf(
            north / scale, -100.0 / scale,
            north / scale, 100.0 / scale
        ))
    private fun northSouth(id: Long, east: Double = 0.0) =
        SpeedWayGeometry(id, 0, doubleArrayOf(
            -100.0 / scale, east / scale,
            100.0 / scale, east / scale
        ))

    @Test fun identifiesOsmWayAndItsTravelDirection() {
        val matcher = SpeedWayMatcher()
        val roads = listOf(eastWest(123, 0.0))
        assertEquals(SpeedWayMatch(0, 123, true), matcher.match(0.0, 0.0, 90.0, true, 3.0, roads))
        assertEquals(SpeedWayMatch(0, 123, false), matcher.match(0.0, 0.0, 270.0, true, 3.0, roads))
    }

    @Test fun parallelRoadsAreAmbiguous() {
        assertNull(SpeedWayMatcher().match(0.0, 0.0, 90.0, true, 8.0,
            listOf(eastWest(101, -6.0), eastWest(102, 6.0))))
    }

    @Test fun significantlyCloserRoadIsSelected() {
        val result = SpeedWayMatcher().match(0.0, 0.0, 90.0, true, 10.0,
            listOf(eastWest(101, 2.0), eastWest(102, 15.0)))
        assertEquals(101L, requireNotNull(result).wayId)
    }

    @Test fun crossingRoadNeedsHeading() {
        val roads = listOf(northSouth(1), eastWest(2, 0.0))
        assertNull(SpeedWayMatcher().match(0.0, 0.0, Double.NaN, false, 3.0, roads))
        assertEquals(2L, requireNotNull(SpeedWayMatcher().match(
            0.0, 0.0, 90.0, true, 3.0, roads)).wayId)
    }

    @Test fun stackedRoadLevelsCannotBeDistinguishedFromGps() {
        val roads = listOf(eastWest(1, 0.0, -1), eastWest(2, 0.0, 1))
        assertNull(SpeedWayMatcher().match(0.0, 0.0, 90.0, true, 2.0, roads))
    }

    @Test fun poorGpsAndWrongHeadingNeverProduceALimit() {
        val roads = listOf(eastWest(1, 0.0))
        assertNull(SpeedWayMatcher().match(0.0, 0.0, 90.0, true, Double.NaN, roads))
        assertNull(SpeedWayMatcher().match(0.0, 0.0, 90.0, true, 30.0, roads))
        assertNull(SpeedWayMatcher().match(0.0, 0.0, 0.0, true, 3.0, roads))
        assertNull(SpeedWayMatcher().match(0.0, 0.0, 90.0, true, 3.0, emptyList()))
    }

    @Test fun previousWayDoesNotOverrideAmbiguity() {
        val matcher = SpeedWayMatcher()
        assertNotNull(matcher.match(0.0, 0.0, 90.0, true, 3.0, listOf(eastWest(1, 0.0))))
        assertNull(matcher.match(0.0, 0.0, 90.0, true, 5.0,
            listOf(eastWest(1, -5.0), eastWest(2, 5.0))))
    }

    @Test fun onewayRejectsWrongTravelDirection() {
        val road = SpeedWayGeometry(7, 0, eastWest(7, 0.0).points, oneway = 1)
        assertNotNull(SpeedWayMatcher().match(0.0, 0.0, 90.0, true, 3.0, listOf(road)))
        assertNull(SpeedWayMatcher().match(0.0, 0.0, 270.0, true, 3.0, listOf(road)))
        val reverse = SpeedWayGeometry(8, 0, road.points, oneway = -1)
        assertNull(SpeedWayMatcher().match(0.0, 0.0, 90.0, true, 3.0, listOf(reverse)))
        assertNotNull(SpeedWayMatcher().match(0.0, 0.0, 270.0, true, 3.0, listOf(reverse)))
    }

    @Test fun switchingWaysNeedsThreeConfidentFixes() {
        val matcher = SpeedWayMatcher()
        val first = listOf(eastWest(1, 0.0))
        val next = listOf(eastWest(2, 0.0))
        assertEquals(1L, requireNotNull(matcher.match(0.0, 0.0, 90.0, true, 3.0, first)).wayId)
        assertNull(matcher.match(0.0, 0.0, 90.0, true, 3.0, next))
        assertNull(matcher.match(0.0, 0.0, 90.0, true, 3.0, next))
        assertEquals(2L, requireNotNull(matcher.match(0.0, 0.0, 90.0, true, 3.0, next)).wayId)
    }

    @Test fun multipleSegmentsOfOneWayAreNotCompetingRoads() {
        val road = SpeedWayGeometry(99, 0, doubleArrayOf(
            0.0, -10.0 / scale, 0.0, 0.0, 0.0, 10.0 / scale
        ))
        assertEquals(99L, requireNotNull(SpeedWayMatcher().match(
            0.0, 0.0, 90.0, true, 3.0, listOf(road))).wayId)
    }
}
