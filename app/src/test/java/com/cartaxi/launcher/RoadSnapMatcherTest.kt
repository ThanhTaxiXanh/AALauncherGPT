package com.cartaxi.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadSnapMatcherTest {
    private fun eastWest(northM: Double, level: Int = 0): RoadSegment {
        val lat = northM / 111_320.0
        return RoadSegment(lat, -100.0 / 111_320.0, lat, 100.0 / 111_320.0, level)
    }

    private fun northSouth(eastM: Double = 0.0, level: Int = 0): RoadSegment {
        val lon = eastM / 111_320.0
        return RoadSegment(-100.0 / 111_320.0, lon, 100.0 / 111_320.0, lon, level)
    }

    @Test fun choosesAnUnambiguousRoadWithoutLateralOffset() {
        val m = requireNotNull(RoadSnapMatcher().match(0.0, 0.0, 90.0, 4.0, listOf(eastWest(0.0))))
        assertEquals(0.0, m.lat, 1e-8)
        assertEquals(0.0, m.lon, 1e-8)
        assertEquals(90.0, m.heading, 0.001)
        assertEquals(0.0, m.distanceM, 0.01)
    }

    @Test fun headingDistinguishesCrossingRoads() {
        val matcher = RoadSnapMatcher()
        val roads = listOf(northSouth(), eastWest(0.0))
        val m = requireNotNull(matcher.match(0.0, 0.0, 90.0, 5.0, roads))
        assertEquals(90.0, m.heading, 0.001)
    }

    @Test fun crossingWithoutHeadingIsAmbiguous() {
        val roads = listOf(northSouth(), eastWest(0.0))
        assertNull(RoadSnapMatcher().match(0.0, 0.0, Double.NaN, 5.0, roads))
    }

    @Test fun symmetricParallelRoadsAreAmbiguous() {
        val roads = listOf(eastWest(-6.0), eastWest(6.0))
        assertNull(RoadSnapMatcher().match(0.0, 0.0, 90.0, 8.0, roads))
    }

    @Test fun significantlyCloserRoadCanMatch() {
        val roads = listOf(eastWest(2.0), eastWest(14.0))
        val m = requireNotNull(RoadSnapMatcher().match(0.0, 0.0, 90.0, 10.0, roads))
        assertEquals(2.0, m.lat * 111_320.0, 0.1)
    }

    @Test fun overlappingRoadLevelsAreAmbiguous() {
        val roads = listOf(eastWest(0.0, 0), eastWest(0.0, 1))
        assertNull(RoadSnapMatcher().match(0.0, 0.0, 90.0, 4.0, roads))
    }

    @Test fun weakGpsAndWrongHeadingDoNotForceMatching() {
        assertNull(RoadSnapMatcher().match(0.0, 0.0, 90.0, 40.0, listOf(eastWest(0.0))))
        assertNull(RoadSnapMatcher().match(0.0, 0.0, 0.0, 4.0, listOf(eastWest(0.0))))
        assertNull(RoadSnapMatcher().match(0.0, 0.0, 90.0, 4.0, listOf(eastWest(20.0))))
    }

    @Test fun reverseTravelProducesOppositeHeading() {
        val m = requireNotNull(RoadSnapMatcher().match(0.0, 0.0, 270.0, 4.0, listOf(eastWest(0.0))))
        assertEquals(270.0, m.heading, 0.001)
    }

    @Test fun duplicateSegmentsAreNotTreatedAsCompetingRoads() {
        val road = eastWest(0.0)
        val m = RoadSnapMatcher().match(0.0, 0.0, 90.0, 4.0, listOf(road, road))
        assertNotNull(m)
    }
}
