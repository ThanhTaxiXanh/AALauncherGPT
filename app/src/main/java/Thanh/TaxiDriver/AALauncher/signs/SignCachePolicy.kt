package Thanh.TaxiDriver.AALauncher.signs



internal enum class SignCacheUse {
    LIVE,
    OFFLINE_HINT,
    NONE
}

/**
 * Offline OSM signs are *informational map hints* for at most 7 days.
 * They must never be upgraded to live warnings or legal speed limits.
 */
internal object SignCachePolicy {
    const val OFFLINE_HINT_TTL_MS: Long = 7L * 24L * 60L * 60L * 1_000L

    fun classify(
        fromDisk: Boolean,
        ageElapsedMs: Long,
        savedAtEpochMs: Long,
        nowEpochMs: Long,
        distanceM: Double,
        maxDistanceM: Double = 900.0,
        liveTtlMs: Long = 15L * 60L * 1_000L
    ): SignCacheUse {
        if (!distanceM.isFinite() || distanceM < 0.0 || distanceM > maxDistanceM ||
            savedAtEpochMs <= 0L || nowEpochMs < savedAtEpochMs ||
            nowEpochMs - savedAtEpochMs > OFFLINE_HINT_TTL_MS
        ) return SignCacheUse.NONE
        if (!fromDisk && ageElapsedMs in 0L..liveTtlMs)
            return SignCacheUse.LIVE
        return SignCacheUse.OFFLINE_HINT
    }
}
