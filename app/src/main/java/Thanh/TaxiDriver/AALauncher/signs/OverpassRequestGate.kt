package Thanh.TaxiDriver.AALauncher.signs



import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min

/**
 * Prevents duplicate Overpass queries within an active 350 m neighbourhood.
 * One request at a time, with a global minimum interval and bounded
 * exponential retry for failed areas. Time is monotonic elapsedRealtime().
 */
internal class OverpassRequestGate(
    private val coalesceRadiusM: Double = 350.0,
    private val minRequestGapMs: Long = 30_000L,
    private val successTtlMs: Long = 15L * 60_000L,
    private val maxRetryMs: Long = 5L * 60_000L
) {
    init {
        require(coalesceRadiusM > 0 && minRequestGapMs > 0)
        require(successTtlMs > 0 && maxRetryMs >= minRequestGapMs)
    }
    private data class Center(val lat: Double, val lon: Double)
    private data class Failure(val center: Center, val count: Int, val retryAtMs: Long)
    private var active: Center? = null
    private var lastAttemptMs: Long? = null
    private var lastSuccess: Center? = null
    private var lastSuccessAtMs: Long? = null
    private val failures = ArrayList<Failure>()

    @Synchronized fun begin(lat: Double, lon: Double, nowMs: Long): Boolean {
        if (!lat.isFinite() || !lon.isFinite() ||
            lat !in -90.0..90.0 || lon !in -180.0..180.0) return false
        val here = Center(lat, lon)
        if (active != null) return false
        val attempted = lastAttemptMs
        if (attempted != null && nowMs >= attempted && nowMs - attempted < minRequestGapMs)
            return false
        val success = lastSuccess
        val successTime = lastSuccessAtMs
        if (success != null && successTime != null &&
            nowMs >= successTime && nowMs - successTime < successTtlMs &&
            distance(here, success) < coalesceRadiusM) return false
        for (f in failures) {
            if (distance(here, f.center) < coalesceRadiusM &&
                nowMs < f.retryAtMs) return false
        }
        active = here
        lastAttemptMs = nowMs
        return true
    }

    @Synchronized fun complete(success: Boolean, nowMs: Long) {
        val center = active ?: return
        active = null
        if (success) {
            lastSuccess = center
            lastSuccessAtMs = nowMs
            failures.removeAll { distance(it.center, center) < coalesceRadiusM }
            return
        }
        val index = failures.indexOfFirst {
            distance(it.center, center) < coalesceRadiusM
        }
        val priorCount = if (index >= 0) failures[index].count else 0
        val count = min(12, priorCount + 1)
        val delay = min(maxRetryMs, minRequestGapMs * (1L shl min(10, count - 1)))
        if (index >= 0) failures.removeAt(index)
        failures.add(Failure(center, count, nowMs + delay))
        if (failures.size > 24) failures.removeAt(0)
    }

    @Synchronized fun inFlight(): Boolean = active != null

    private fun distance(a: Center, b: Center): Double {
        val east = (b.lon - a.lon) * 111_320.0 *
            cos(Math.toRadians((a.lat + b.lat) / 2))
        val north = (b.lat - a.lat) * 111_320.0
        return hypot(east, north)
    }
}
