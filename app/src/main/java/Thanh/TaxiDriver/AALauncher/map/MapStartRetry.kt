package Thanh.TaxiDriver.AALauncher.map



/**
 * Bounds retries of MapLibre's virtual display startup after transient failures.
 * Uses elapsedRealtime milliseconds; caller owns lifecycle and calls on the main thread.
 */
internal class MapStartRetry(
    private val initialDelayMs: Long = 5_000L,
    private val maxDelayMs: Long = 60_000L
) {
    init {
        require(initialDelayMs > 0L)
        require(maxDelayMs >= initialDelayMs)
    }

    private var delayMs = initialDelayMs
    private var retryAfterMs = 0L

    fun canAttempt(nowMs: Long): Boolean = nowMs >= retryAfterMs

    fun recordFailure(nowMs: Long) {
        retryAfterMs = nowMs + delayMs
        delayMs = (delayMs * 2).coerceAtMost(maxDelayMs)
    }

    fun recordSuccess() {
        retryAfterMs = 0L
        delayMs = initialDelayMs
    }
}
