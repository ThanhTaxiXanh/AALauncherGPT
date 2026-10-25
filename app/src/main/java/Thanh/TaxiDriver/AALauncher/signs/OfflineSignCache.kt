package Thanh.TaxiDriver.AALauncher.signs



import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

internal data class CachedSignsSnapshot(
    val lat: Double,
    val lon: Double,
    val savedAtEpochMs: Long,
    val payload: String
)

/**
 * Bounded, timestamped and checksummed Overpass cache.
 *
 * SHA-256 detects partial/corrupt disk writes, it is not authentication.
 * Legacy lat,lon\nJSON files are supported via file.lastModified and may
 * only be displayed as unverified hints by RoadSigns.
 */
internal class OfflineSignCache(
    private val file: File,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    companion object {
        private const val HEADER = "CARTAXI_OSM_SIGNS_V2"
        private const val MAX_PAYLOAD_CHARS = 3_500_000
        private const val MAX_FILE_BYTES = 4_000_000L
    }

    fun read(): CachedSignsSnapshot? {
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_FILE_BYTES) return null
        val content = file.readText(Charsets.UTF_8)
        val saved: Long
        val coordinates: String
        val payload: String
        if (content.startsWith("$HEADER\n")) {
            val fields = content.split('\n', limit = 5)
            if (fields.size != 5 || fields[0] != HEADER) return null
            saved = fields[1].toLongOrNull() ?: return null
            coordinates = fields[2]
            payload = fields[4]
            if (!fields[3].equals(checksum(payload), ignoreCase = true)) return null
        } else {
            // Compatibility with the previously released one-file cache.
            val newline = content.indexOf('\n')
            if (newline <= 0) return null
            saved = file.lastModified()
            coordinates = content.substring(0, newline)
            payload = content.substring(newline + 1)
        }
        if (payload.isBlank() || payload.length > MAX_PAYLOAD_CHARS) return null
        val pair = coordinates.split(',')
        if (pair.size != 2) return null
        val lat = pair[0].toDoubleOrNull() ?: return null
        val lon = pair[1].toDoubleOrNull() ?: return null
        if (!lat.isFinite() || !lon.isFinite() ||
            lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val current = nowMs()
        if (saved <= 0L || current < saved ||
            current - saved > SignCachePolicy.OFFLINE_HINT_TTL_MS) return null
        return CachedSignsSnapshot(lat, lon, saved, payload)
    }

    fun write(lat: Double, lon: Double, payload: String, savedAtMs: Long = nowMs()) {
        require(lat.isFinite() && lat in -90.0..90.0)
        require(lon.isFinite() && lon in -180.0..180.0)
        require(payload.isNotBlank() && payload.length <= MAX_PAYLOAD_CHARS)
        require(savedAtMs > 0L)
        val text = "$HEADER\n$savedAtMs\n$lat,$lon\n${checksum(payload)}\n$payload"
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile ?: File("."), "${file.name}.tmp")
        try {
            temp.outputStream().use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.flush()
                stream.fd.sync()
            }
            try {
                Files.move(temp.toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun checksum(s: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val digits = "0123456789abcdef"
        return buildString(digest.size * 2) {
            for (byte in digest) {
                val v = byte.toInt() and 0xff
                append(digits[v ushr 4])
                append(digits[v and 15])
            }
        }
    }
}
