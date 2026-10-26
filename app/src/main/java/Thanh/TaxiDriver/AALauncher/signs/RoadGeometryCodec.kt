package Thanh.TaxiDriver.AALauncher.signs

import Thanh.TaxiDriver.AALauncher.road.SpeedWayGeometry

/**
 * Decoder for the nationwide SQLite V3.2 geometry BLOB.
 *
 * Format: latitudeE6 delta then longitudeE6 delta for every point.
 * Each signed delta is ZigZag encoded and stored as an unsigned varint.
 * Quantization is 1e-6 degree (~0.11 m latitude), below consumer GPS error.
 */
internal object RoadGeometryCodec {
    private const val SCALE = 1_000_000.0
    private const val MAX_POINTS = 5_000
    private const val MAX_VARINT_BYTES = 10

    fun decode(
        blob: ByteArray,
        wayId: Long,
        level: Int,
        oneway: Int
    ): SpeedWayGeometry? {
        if (blob.size !in 4..100_000) return null
        // Two one-byte varints per point is the theoretical minimum, so the
        // number of output doubles can never exceed blob.size.
        val points = DoubleArray(blob.size)
        var out = 0
        var index = 0
        var latE6 = 0L
        var lonE6 = 0L
        var pointCount = 0

        while (index < blob.size) {
            if (pointCount >= MAX_POINTS) return null
            val latRead = readVarint(blob, index) ?: return null
            index = latRead.next
            val lonRead = readVarint(blob, index) ?: return null
            index = lonRead.next

            latE6 += zigZagDecode(latRead.value)
            lonE6 += zigZagDecode(lonRead.value)
            if (latE6 !in -90_000_000L..90_000_000L ||
                lonE6 !in -180_000_000L..180_000_000L) return null
            if (out + 1 >= points.size) return null
            points[out++] = latE6 / SCALE
            points[out++] = lonE6 / SCALE
            pointCount++
        }
        if (pointCount < 2 || out != pointCount * 2) return null
        return SpeedWayGeometry(
            wayId = wayId,
            level = level,
            points = points.copyOf(out),
            nodeIds = LongArray(0),
            oneway = oneway
        )
    }

    private data class Read(val value: Long, val next: Int)

    private fun readVarint(bytes: ByteArray, start: Int): Read? {
        var value = 0L
        var shift = 0
        var index = start
        var count = 0
        while (index < bytes.size && count < MAX_VARINT_BYTES) {
            val b = bytes[index].toInt() and 0xff
            if (shift >= 64 && (b and 0x7f) != 0) return null
            value = value or ((b and 0x7f).toLong() shl shift)
            index++
            count++
            if ((b and 0x80) == 0) return Read(value, index)
            shift += 7
            if (shift > 63) return null
        }
        return null
    }

    private fun zigZagDecode(value: Long): Long =
        (value ushr 1) xor -(value and 1L)
}
