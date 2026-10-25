package Thanh.TaxiDriver.AALauncher.signs



import java.io.Reader

/**
 * Streaming size guard for third-party Overpass JSON bodies.
 * Response length is checked BEFORE appending more data to memory.
 */
internal object BoundedOverpassBody {
    fun read(reader: Reader, maxChars: Int): String {
        require(maxChars > 0)
        val chunk = CharArray(minOf(8192, maxChars + 1))
        val body = StringBuilder(minOf(8192, maxChars))
        while (true) {
            val count = reader.read(chunk)
            if (count < 0) return body.toString()
            if (count == 0) continue
            require(body.length <= maxChars - count) {
                "Overpass response exceeds allowed size"
            }
            body.append(chunk, 0, count)
        }
    }
}
