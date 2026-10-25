package com.cartaxi.launcher

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * GPS-only evidence for having actually entered an OSM way after a junction.
 *
 * Each input must correspond to a new accepted GPS fix. Repeating the same
 * timestamp never increments confidence, however often the UI redraws.
 * It does NOT predict a future turn or reinterpret legal OSM restrictions.
 */
internal class ObservedRoadTurnTracker {
    private data class Fix(
        val match: SpeedWayMatch,
        val lat: Double,
        val lon: Double,
        val timeMs: Long
    )
    private var confirmed: Fix? = null
    private var candidate: Fix? = null
    private var lastFix: Fix? = null
    private var votes = 0
    private var lastTimestampMs = Long.MIN_VALUE

    fun reset() {
        confirmed = null
        candidate = null
        lastFix = null
        votes = 0
        lastTimestampMs = Long.MIN_VALUE
    }

    fun observe(
        matched: SpeedWayMatch, lat: Double, lon: Double, heading: Double,
        moving: Boolean, accuracyM: Double, timestampMs: Long,
        graph: OsmRoadGraph
    ): SpeedWayMatch? {
        if (!moving || !heading.isFinite() ||
            !lat.isFinite() || !lon.isFinite() ||
            lat !in -90.0..90.0 || lon !in -180.0..180.0 ||
            !accuracyM.isFinite() || accuracyM !in 0.0..25.0 ||
            timestampMs < 0L || !graph.allowsTravel(matched)
        ) {
            reset()
            return null
        }
        val stable = confirmed
        if (timestampMs == lastTimestampMs)
            return if (stable?.match == matched) matched else null
        val earlier = lastFix
        if (timestampMs < lastTimestampMs ||
            (earlier != null && (timestampMs - earlier.timeMs > 8_000L ||
                meters(earlier.lat, earlier.lon, lat, lon) > 130.0))
        ) reset()

        val observation = Fix(matched, lat, lon, timestampMs)
        lastTimestampMs = timestampMs
        lastFix = observation
        val previous = confirmed
        if (previous != null && previous.match == matched) {
            confirmed = observation
            candidate = null
            votes = 0
            return matched
        }

        val pending = candidate
        if (pending == null || pending.match != matched) {
            candidate = observation
            votes = 1
            return null
        }
        votes++
        val displacement = meters(pending.lat, pending.lon, lat, lon)
        val duration = timestampMs - pending.timeMs
        if (duration < 750L || displacement < 8.0) return null

        if (previous == null) {
            // Initial acquisition after two independent moving samples.
            confirmed = observation
        } else {
            val connected = graph.observedTransition(
                previous.match, matched, previous.lat, previous.lon, lat, lon
            )
            if (!connected && (votes < 4 || displacement < 25.0 ||
                duration < 2_000L)) return null
            // A disconnected road can only be independently reacquired after
            // sustained observed motion, never inferred as a turn.
            confirmed = observation
        }
        candidate = null
        votes = 0
        return matched
    }

    private fun meters(a: Double, b: Double, c: Double, d: Double): Double {
        val east = (d - b) * 111_320.0 * cos(Math.toRadians((a + c) / 2.0))
        val north = (c - a) * 111_320.0
        return hypot(east, north)
    }
}
