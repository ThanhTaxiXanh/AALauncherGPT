package Thanh.TaxiDriver.AALauncher

import Thanh.TaxiDriver.AALauncher.signs.CachedSignsSnapshot
import Thanh.TaxiDriver.AALauncher.signs.OfflineSignCache

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSignCacheTest {
    private val now = 1_900_000_000_000L
    private val payload = """{"elements":[{"type":"node","id":100,"tags":{"highway":"stop"}}]}"""

    private fun withCache(block: (OfflineSignCache, File) -> Unit) {
        val dir = Files.createTempDirectory("osm-signs-cache-test").toFile()
        try {
            val file = File(dir, "signs.cache")
            block(OfflineSignCache(file) { now }, file)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun persistsAndReloadsTimestampAndGeometry() = withCache { cache, file ->
        cache.write(21.0285, 105.8542, payload, now - 100L)
        assertTrue(file.isFile)
        assertEquals(CachedSignsSnapshot(21.0285, 105.8542, now - 100L, payload), cache.read())
        assertEquals(null, File(file.parentFile, "signs.cache.tmp").takeIf { it.exists() })
    }

    @Test fun rejectsDamagedChecksumAndMalformedCoordinates() = withCache { cache, file ->
        cache.write(21.0, 105.0, payload, now)
        file.writeText(file.readText().replace("stop", "yield"))
        assertNull(cache.read())
        file.writeText("broken,coordinates\n$payload")
        assertNull(cache.read())
    }

    @Test fun ageAndClockRollbackPreventOfflineHints() = withCache { cache, file ->
        cache.write(21.0, 105.0, payload, now - 8L * 24L * 60L * 60L * 1000L)
        assertNull(cache.read())
        cache.write(21.0, 105.0, payload, now + 1L)
        assertNull(cache.read())
    }

    @Test fun legacyCacheLoadsAsDisplayOnlySnapshot() = withCache { cache, file ->
        file.writeText("21.0,105.0\n$payload")
        file.setLastModified(now - 1000L)
        assertEquals(CachedSignsSnapshot(21.0, 105.0, now - 1000L, payload), cache.read())
    }

    @Test fun oversizedOrBlankContentIsDiscarded() = withCache { cache, file ->
        file.writeText("")
        assertNull(cache.read())
        file.writeText("x".repeat(4_000_001))
        assertNull(cache.read())
    }
}
