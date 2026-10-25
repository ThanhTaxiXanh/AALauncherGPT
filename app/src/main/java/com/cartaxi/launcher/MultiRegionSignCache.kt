package com.cartaxi.launcher

import java.io.File
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Bounded per-area Overpass archive. Each region reuses the V2 on-disk
 * checksum/time format. Disk reads and pruning must run on a worker thread.
 */
internal class MultiRegionSignCache(
    private val legacyFile: File,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val maxRegions: Int = 12
) {
    init { require(maxRegions > 0) }

    private val directory = File(legacyFile.parentFile ?: File("."), "signs_cache_regions")
    private val prefix = "region_"
    private val suffix = ".cache"
    private val step = 0.006 // roughly 667 m north/south; stored center stays precise.

    fun write(lat: Double, lon: Double, payload: String, savedAtMs: Long = nowMs()) {
        val key = regionKey(lat, lon)
        val target = File(directory, "$prefix$key$suffix")
        OfflineSignCache(target, nowMs).write(lat, lon, payload, savedAtMs)
        prune()
    }

    /** The legacy one-file V1/V2 cache remains readable but is never promoted. */
    fun readNearby(
        lat: Double, lon: Double, maxDistanceM: Double = 900.0,
        maxResults: Int = 4
    ): List<CachedSignsSnapshot> {
        if (!valid(lat, lon) || !maxDistanceM.isFinite() ||
            maxDistanceM <= 0 || maxResults <= 0) return emptyList()
        prune()
        val snapshots = ArrayList<CachedSignsSnapshot>()
        val candidates = (regionFiles() + listOf(legacyFile)).distinctBy { it.absolutePath }
        for (file in candidates) {
            val snapshot = try { OfflineSignCache(file, nowMs).read() } catch (_: Exception) { null }
                ?: continue
            if (distance(lat, lon, snapshot.lat, snapshot.lon) <= maxDistanceM)
                snapshots.add(snapshot)
        }
        // Favor the nearest archive, then the most recent when overlapping.
        return snapshots.sortedWith(
            compareBy<CachedSignsSnapshot> { distance(lat, lon, it.lat, it.lon) }
                .thenByDescending { it.savedAtEpochMs }
        ).take(maxResults)
    }

    fun regionCount(): Int = regionFiles().size

    fun prune() {
        // Only the private archive directory is managed; legacyFile remains intact.
        val valid = ArrayList<Pair<File, Long>>()
        for (file in regionFiles()) {
            val snapshot = try { OfflineSignCache(file, nowMs).read() }
                catch (_: Exception) { null }
            if (snapshot == null) file.delete()
            else valid.add(file to snapshot.savedAtEpochMs)
        }
        val retained = valid.sortedWith(
            compareByDescending<Pair<File, Long>> { it.second }.thenBy { it.first.name }
        )
        for ((file, _) in retained.drop(maxRegions)) file.delete()
    }

    private fun regionFiles(): List<File> =
        directory.listFiles()?.filter {
            it.isFile && it.name.startsWith(prefix) && it.name.endsWith(suffix)
        } ?: emptyList()

    private fun regionKey(lat: Double, lon: Double): String {
        require(valid(lat, lon))
        return "${floor(lat / step).toInt()}_${floor(lon / step).toInt()}"
    }

    private fun valid(lat: Double, lon: Double): Boolean =
        lat.isFinite() && lon.isFinite() &&
            lat in -90.0..90.0 && lon in -180.0..180.0

    private fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val east = (lon2 - lon1) * 111_320.0 *
            cos(Math.toRadians((lat1 + lat2) / 2.0))
        val north = (lat2 - lat1) * 111_320.0
        return hypot(east, north)
    }
}
