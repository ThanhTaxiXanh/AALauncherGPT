package com.cartaxi.launcher

import java.io.StringReader
import java.io.Reader
import java.io.IOException
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

/** Audit regressions: streamed payload bounds and event data integrity. */
class PrePhase11AuditTest {
    @Test fun networkBodyAcceptedUpToExactLimit() {
        assertEquals("abcdef", BoundedOverpassBody.read(StringReader("abcdef"), 6))
        assertEquals("", BoundedOverpassBody.read(StringReader(""), 10))
        assertEquals("đường", BoundedOverpassBody.read(StringReader("đường"), 5))
    }

    @Test fun oversizedNetworkBodyRejectedWhileReadingNotAfter() {
        val raw = "x".repeat(200_000)
        val reader = object : Reader() {
            var at = 0
            var consumed = 0
            override fun read(buffer: CharArray, off: Int, len: Int): Int {
                if (at == raw.length) return -1
                val count = minOf(len, 512, raw.length - at)
                raw.toCharArray(buffer, off, at, at + count)
                at += count
                consumed += count
                return count
            }
            override fun close() {}
        }
        assertThrows(IllegalArgumentException::class.java) {
            BoundedOverpassBody.read(reader, 1_024)
        }
        assertTrue("Must not buffer the complete 200 kB payload",
            reader.consumed <= 2_048)
    }

    @Test fun readerFailuresPropagateInsteadOfReturningPartialJson() {
        val reader = object : Reader() {
            override fun read(buffer: CharArray, off: Int, len: Int): Int {
                throw IOException("Connection interrupted")
            }
            override fun close() {}
        }
        assertThrows(IOException::class.java) {
            BoundedOverpassBody.read(reader, 1_000)
        }
    }

    @Test fun validLegacyEventArrayAcceptedByStrictParser() {
        val raw = """[{"id":42,"title":"Ngày nghỉ","lunar":false,"day":1,"month":1,"repeat":0}]"""
        val original = Events.parseStrict(raw)
        assertEquals(1, original.size)
        assertEquals("Ngày nghỉ", original.single().title)
        assertFalse(original.single().leapMonth)
    }

    @Test fun syntacticallyValidButBrokenLegacyRecordIsNotSafeToOverwrite() {
        val raw = """[{"id":42,"title":"Ngày nghỉ","lunar":false,"day":1,"month":1},{"id":43,"title":"Thiếu ngày","lunar":true,"month":8}]"""
        assertEquals(2, JSONArray(raw).length()) // Previous write guard accepted this.
        assertTrue(Events.parse(raw).isEmpty()) // Tolerant display fallback retained.
        assertThrows(Exception::class.java) { Events.parseStrict(raw) }
    }

    @Test fun wrongJsonShapeIsNeverAcceptedByStrictParser() {
        assertThrows(Exception::class.java) {
            Events.parseStrict("""{"version":99,"events":[]}""")
        }
        assertThrows(Exception::class.java) {
            Events.parseStrict("[not-json]")
        }
    }
}
