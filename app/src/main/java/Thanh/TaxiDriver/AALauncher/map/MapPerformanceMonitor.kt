package Thanh.TaxiDriver.AALauncher.map



import java.util.Locale

/**
 * Debug-only observer for MapLibre frame delivery and hot-path processing costs.
 * A delivered ImageReader frame is NOT a display vsync or Android Auto host FPS.
 * Calls are synchronized because GPS and image callbacks may originate on different threads.
 */
internal class MapPerformanceMonitor(private val sampleWindowMs: Long = 10_000L) {
    init { require(sampleWindowMs > 0L) }

    private var windowStartMs = -1L
    private var mapLoadStartMs: Long? = null
    private var frames = 0
    private var frameCopyTotalNs = 0L
    private var frameCopyMaxNs = 0L
    private var gpsUpdates = 0
    private var gpsTotalNs = 0L
    private var gpsMaxNs = 0L
    private var mapSteps = 0
    private var mapStepTotalNs = 0L
    private var mapStepMaxNs = 0L

    @Synchronized fun reset(nowMs: Long) {
        windowStartMs = nowMs
        mapLoadStartMs = null
        frames = 0
        frameCopyTotalNs = 0L
        frameCopyMaxNs = 0L
        gpsUpdates = 0
        gpsTotalNs = 0L
        gpsMaxNs = 0L
        mapSteps = 0
        mapStepTotalNs = 0L
        mapStepMaxNs = 0L
    }

    @Synchronized fun startMapLoad(nowMs: Long) {
        mapLoadStartMs = nowMs
    }

    /** Only the first successful style load is measured, not subsequent day/night reloads. */
    @Synchronized fun finishMapLoad(nowMs: Long): Long? {
        val start = mapLoadStartMs ?: return null
        if (nowMs < start) return null
        mapLoadStartMs = null
        return nowMs - start
    }

    @Synchronized fun recordFrame(nowMs: Long, copyNs: Long) {
        if (copyNs < 0L) return
        if (windowStartMs < 0L) windowStartMs = nowMs
        frames++
        frameCopyTotalNs += copyNs
        frameCopyMaxNs = maxOf(frameCopyMaxNs, copyNs)
    }

    @Synchronized fun recordGpsNs(durationNs: Long) {
        if (durationNs < 0L) return
        gpsUpdates++
        gpsTotalNs += durationNs
        gpsMaxNs = maxOf(gpsMaxNs, durationNs)
    }

    @Synchronized fun recordStepNs(durationNs: Long) {
        if (durationNs < 0L) return
        mapSteps++
        mapStepTotalNs += durationNs
        mapStepMaxNs = maxOf(mapStepMaxNs, durationNs)
    }

    /** Returns at most one snapshot per window; does not retain historical samples. */
    @Synchronized fun snapshotIfDue(nowMs: Long): MapPerfSnapshot? {
        if (windowStartMs < 0L) {
            windowStartMs = nowMs
            return null
        }
        val elapsed = nowMs - windowStartMs
        if (elapsed < sampleWindowMs || elapsed <= 0L) return null
        val result = MapPerfSnapshot(
            durationMs = elapsed,
            deliveredFrames = frames,
            observedFrameFps = frames * 1_000.0 / elapsed,
            avgCopyMs = if (frames == 0) null else frameCopyTotalNs / 1_000_000.0 / frames,
            maxCopyMs = if (frames == 0) null else frameCopyMaxNs / 1_000_000.0,
            gpsUpdates = gpsUpdates,
            avgGpsMs = if (gpsUpdates == 0) null else gpsTotalNs / 1_000_000.0 / gpsUpdates,
            maxGpsMs = if (gpsUpdates == 0) null else gpsMaxNs / 1_000_000.0,
            mapSteps = mapSteps,
            avgStepMs = if (mapSteps == 0) null else mapStepTotalNs / 1_000_000.0 / mapSteps,
            maxStepMs = if (mapSteps == 0) null else mapStepMaxNs / 1_000_000.0
        )
        // Preserve an explicit load timer until style callback; clear only sample counters.
        val loading = mapLoadStartMs
        reset(nowMs)
        mapLoadStartMs = loading
        return result
    }
}

internal data class MapPerfSnapshot(
    val durationMs: Long,
    val deliveredFrames: Int,
    val observedFrameFps: Double,
    val avgCopyMs: Double?,
    val maxCopyMs: Double?,
    val gpsUpdates: Int,
    val avgGpsMs: Double?,
    val maxGpsMs: Double?,
    val mapSteps: Int,
    val avgStepMs: Double?,
    val maxStepMs: Double?
) {
    /** No GPS coordinates, location history, trip IDs or personally identifying data. */
    fun logLine(heapUsedBytes: Long): String = String.format(
        Locale.US,
        "window_ms=%d delivered_frame_fps=%.1f frames=%d copy_avg_ms=%s copy_max_ms=%s gps_updates=%d gps_avg_ms=%s gps_max_ms=%s map_steps=%d step_avg_ms=%s step_max_ms=%s java_heap_mb=%.1f",
        durationMs, observedFrameFps, deliveredFrames,
        avgCopyMs?.let { String.format(Locale.US, "%.2f", it) } ?: "-",
        maxCopyMs?.let { String.format(Locale.US, "%.2f", it) } ?: "-",
        gpsUpdates,
        avgGpsMs?.let { String.format(Locale.US, "%.2f", it) } ?: "-",
        maxGpsMs?.let { String.format(Locale.US, "%.2f", it) } ?: "-",
        mapSteps,
        avgStepMs?.let { String.format(Locale.US, "%.2f", it) } ?: "-",
        maxStepMs?.let { String.format(Locale.US, "%.2f", it) } ?: "-",
        heapUsedBytes / 1_048_576.0
    )
}
