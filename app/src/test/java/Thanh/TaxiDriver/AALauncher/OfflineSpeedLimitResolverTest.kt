package Thanh.TaxiDriver.AALauncher

import Thanh.TaxiDriver.AALauncher.road.SpeedWayGeometry
import Thanh.TaxiDriver.AALauncher.signs.BundledRoadContext
import Thanh.TaxiDriver.AALauncher.signs.OfflineSpeedLimitResolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineSpeedLimitResolverTest {
    private val scale = 111_320.0

    private fun road(
        id: Long,
        northM: Double = 0.0,
        name: String = "Quoc Lo 1",
        all: Int? = 80,
        forward: Int? = null,
        backward: Int? = null,
        hasSpeedTag: Boolean = true
    ) = BundledRoadContext(
        geometry = SpeedWayGeometry(id, 0, doubleArrayOf(
            northM / scale, -100.0 / scale,
            northM / scale, 100.0 / scale
        )),
        highway = "primary",
        name = name,
        all = all,
        forward = forward,
        backward = backward,
        allConditional = null,
        forwardConditional = null,
        backwardConditional = null,
        hasSpeedTag = hasSpeedTag
    )

    private fun state(
        roads: List<BundledRoadContext>,
        heading: Double = 90.0,
        moving: Boolean = true,
        accuracy: Double = 3.0,
        street: String = "Quốc Lộ 1"
    ) = OfflineSpeedLimitResolver().state(
        lat = 0.0,
        lon = 0.0,
        heading = heading,
        moving = moving,
        accuracyM = accuracy,
        gpsFixMs = 1_000L,
        nowElapsedMs = 1_500L,
        wallTimeMs = 1_800_000_000_000L,
        streetName = street,
        roads = roads
    )

    @Test fun usesFullGeometryAndRoadNameForOfflineLimit() {
        val result = state(listOf(road(10)))
        assertEquals(80, result.limit)
        assertEquals(10L, result.roadWayId)
        assertEquals(false, result.estimated)
    }

    @Test fun directionalLimitUsesTravelHeading() {
        val r = road(20, all = 60, forward = 90, backward = 50)
        assertEquals(90, state(listOf(r), heading = 90.0).limit)
        assertEquals(50, state(listOf(r), heading = 270.0).limit)
        assertNull(state(listOf(r), heading = Double.NaN, moving = false).limit)
    }

    @Test fun parallelWaysRemainUnknown() {
        val roads = listOf(
            road(1, northM = -5.0),
            road(2, northM = 5.0)
        )
        assertNull(state(roads, accuracy = 5.0).limit)
    }

    @Test fun matchedRoadWithoutSpeedBlocksNearbySpeedLeak() {
        val actualRoad = road(
            id = 100,
            northM = 0.0,
            name = "Duong Dan Sinh",
            all = null,
            hasSpeedTag = false
        )
        val nearbyFastRoad = road(
            id = 101,
            northM = 12.0,
            name = "Quoc Lo 1",
            all = 80,
            hasSpeedTag = true
        )
        val result = state(
            listOf(actualRoad, nearbyFastRoad),
            accuracy = 3.0,
            street = "Duong Dan Sinh"
        )
        assertNull(result.limit)
        assertEquals(100L, result.roadWayId)
    }

    @Test fun mismatchedKnownRoadNameFailsClosed() {
        assertNull(state(listOf(road(30, name = "Duong Khac")),
            street = "Quoc Lo 1").limit)
    }

    @Test fun unnamedRoadRequiresVeryCloseGps() {
        assertNull(state(listOf(road(40, northM = 6.0, name = "")),
            street = "", accuracy = 10.0).limit)
        assertEquals(80, state(listOf(road(41, northM = 2.0, name = "")),
            street = "", accuracy = 5.0).limit)
    }

    @Test fun staleOrPoorGpsNeverReturnsOfflineLimit() {
        val resolver = OfflineSpeedLimitResolver()
        val roads = listOf(road(50))
        assertNull(resolver.state(0.0,0.0,90.0,true,13.0,1_000L,1_500L,
            1_800_000_000_000L,"Quoc Lo 1",roads).limit)
        assertNull(resolver.state(0.0,0.0,90.0,true,3.0,1_000L,6_000L,
            1_800_000_000_000L,"Quoc Lo 1",roads).limit)
    }
}
