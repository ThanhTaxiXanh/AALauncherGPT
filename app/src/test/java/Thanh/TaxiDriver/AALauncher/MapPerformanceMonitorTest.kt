package Thanh.TaxiDriver.AALauncher

import Thanh.TaxiDriver.AALauncher.map.MapPerformanceMonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapPerformanceMonitorTest {
    @Test fun reportsFrameRateAndAverageCostOnWindowBoundary() {
        val m = MapPerformanceMonitor(1_000L)
        m.reset(100L)
        m.recordFrame(200L, 2_000_000L)
        m.recordFrame(400L, 4_000_000L)
        m.recordGpsNs(6_000_000L)
        m.recordGpsNs(2_000_000L)
        m.recordStepNs(1_000_000L)
        assertNull(m.snapshotIfDue(1_099L))
        val s = requireNotNull(m.snapshotIfDue(1_100L))
        assertEquals(2, s.deliveredFrames)
        assertEquals(2.0, s.observedFrameFps, 0.001)
        assertEquals(3.0, s.avgCopyMs!!, 0.001)
        assertEquals(4.0, s.maxCopyMs!!, 0.001)
        assertEquals(2, s.gpsUpdates)
        assertEquals(4.0, s.avgGpsMs!!, 0.001)
        assertEquals(6.0, s.maxGpsMs!!, 0.001)
        assertEquals(1.0, s.avgStepMs!!, 0.001)
    }

    @Test fun noActivityIsNotFabricatedAsFpsAndWindowsDoNotLeakSamples() {
        val m = MapPerformanceMonitor(1_000L)
        m.reset(0L)
        val empty = requireNotNull(m.snapshotIfDue(1_000L))
        assertEquals(0.0, empty.observedFrameFps, 0.0)
        assertNull(empty.avgCopyMs)
        assertNull(empty.avgGpsMs)
        assertNull(m.snapshotIfDue(1_001L))
        m.recordFrame(1_100L, 1_000_000L)
        val later = requireNotNull(m.snapshotIfDue(2_000L))
        assertEquals(1, later.deliveredFrames)
        assertEquals(1.0, later.observedFrameFps, 0.001)
    }

    @Test fun initialStyleLoadIsMeasuredOnceEvenAcrossSamplingWindows() {
        val m = MapPerformanceMonitor(100L)
        m.reset(0L)
        m.startMapLoad(10L)
        m.snapshotIfDue(100L)
        assertEquals(140L, m.finishMapLoad(150L))
        assertNull(m.finishMapLoad(160L))
        m.startMapLoad(200L)
        assertNull(m.finishMapLoad(199L))
        assertEquals(20L, m.finishMapLoad(220L))
    }

    @Test fun countersRejectInvalidDurationsAndLogContainsOnlyAggregates() {
        val m = MapPerformanceMonitor(100L)
        m.reset(0L)
        m.recordFrame(20L, -5L)
        m.recordGpsNs(-1L)
        m.recordStepNs(-1L)
        val s = requireNotNull(m.snapshotIfDue(100L))
        assertEquals(0, s.deliveredFrames)
        assertEquals(0, s.gpsUpdates)
        assertEquals(0, s.mapSteps)
        val line = s.logLine(1_048_576L)
        assertTrue(line.contains("java_heap_mb=1.0"))
        assertTrue(line.contains("delivered_frame_fps="))
        assertFalse(line.contains("latitude"))
        assertFalse(line.contains("longitude"))
    }
}
