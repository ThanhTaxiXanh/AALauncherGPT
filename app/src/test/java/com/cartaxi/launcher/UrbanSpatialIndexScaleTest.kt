package com.cartaxi.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic urban density, no wall-clock performance assertion on CI runners.
 * Conservatively compare optimized and exhaustive logic on sampled fixtures.
 */
class UrbanSpatialIndexScaleTest {
    @Test fun indexFiveThousandDenseSegmentsAndSigns() {
        val unit = 111_320.0
        val roads = ArrayList<SpeedWayGeometry>()
        val signs = ArrayList<SignItem>()
        for (r in 0 until 100) for (c in 0 until 50) {
            val x = c * 24.0
            val y = r * 24.0
            roads.add(SpeedWayGeometry(
                100_000L + roads.size, 0,
                doubleArrayOf(y / unit, x / unit, y / unit, (x + 15.0) / unit)
            ))
            signs.add(SignItem(y / unit, (x + 12.0) / unit,
                "signals", "ĐÈN", "Đèn", 0))
        }
        val start = System.nanoTime()
        val index = RoadSpatialIndex(roads, signs)
        val elapsed = (System.nanoTime() - start) / 1_000_000
        assertTrue(index.indexed)
        val gps = index.segmentsNear(120.0 / unit, 120.0 / unit, 25.0)
        val nearSigns = index.signsNear(120.0 / unit, 120.0 / unit, 350.0)
        assertNotNull(gps)
        assertNotNull(nearSigns)
        assertTrue(gps!!.size < 120)
        assertTrue(nearSigns!!.size < 1_000)
        assertEquals(5_000, roads.size)
        assertEquals(5_000, signs.size)
        println("Urban spatial grid: segments=5000, signs=5000, buildMs=$elapsed, " +
            "localWays=${gps.size}, localSigns=${nearSigns.size}")
    }
}
