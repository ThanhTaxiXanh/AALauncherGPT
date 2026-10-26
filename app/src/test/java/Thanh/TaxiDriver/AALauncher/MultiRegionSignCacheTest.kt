package Thanh.TaxiDriver.AALauncher

import Thanh.TaxiDriver.AALauncher.signs.MultiRegionSignCache

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiRegionSignCacheTest {
    private val now = 1_900_000_000_000L
    private fun payload(id: Int) = """{"elements":[{"type":"node","id":$id,"tags":{"highway":"stop"}}]}"""

    private fun scenario(block: (MultiRegionSignCache, File) -> Unit) {
        val dir = Files.createTempDirectory("multi-region-sign-cache").toFile()
        try {
            val legacy = File(dir, "signs_cache.json")
            block(MultiRegionSignCache(legacy, { now }, 3), legacy)
        } finally { dir.deleteRecursively() }
    }

    @Test fun storesSeveralRegionsAndSelectsNearby() = scenario { cache, _ ->
        cache.write(21.0, 105.0, payload(1), now - 3000L)
        cache.write(21.010, 105.010, payload(2), now - 2000L)
        cache.write(21.020, 105.020, payload(3), now - 1000L)
        assertEquals(3, cache.regionCount())
        val first = cache.readNearby(21.0, 105.0)
        assertEquals(1, first.size)
        assertEquals(payload(1), first.first().payload)
        assertEquals(payload(3), cache.readNearby(21.020, 105.020).single().payload)
    }

    @Test fun sameGeographicBucketOverwritesInsteadOfDuplicating() = scenario { cache, _ ->
        cache.write(21.0, 105.0, payload(1))
        cache.write(21.001, 105.001, payload(2))
        assertEquals(1, cache.regionCount())
        assertEquals(payload(2), cache.readNearby(21.001, 105.001).single().payload)
    }

    @Test fun evictionUsesBoundedMaximumAndPreservesNewest() = scenario { cache, _ ->
        for (i in 0..3) {
            cache.write(21.0 + i * 0.02, 105.0, payload(i), now - (4000L - i * 1000L))
        }
        assertEquals(3, cache.regionCount())
        assertTrue(cache.readNearby(21.06, 105.0).isNotEmpty())
    }

    @Test fun expiredAndCorruptArchivesAreRemoved() = scenario { cache, legacy ->
        cache.write(21.0, 105.0, payload(1), now - 8L * 24 * 3600_000L)
        assertEquals(0, cache.regionCount())
        cache.write(21.0, 105.0, payload(2), now)
        val archive = File(legacy.parentFile, "signs_cache_regions").listFiles()!!.single()
        archive.writeText("corrupt")
        assertTrue(cache.readNearby(21.0, 105.0).isEmpty())
        assertEquals(0, cache.regionCount())
    }

    @Test fun legacyFileRemainsReadableButIsNotMigratedAsLive() = scenario { cache, legacy ->
        legacy.writeText("21.0,105.0\n${payload(77)}")
        legacy.setLastModified(now - 1000L)
        assertEquals(payload(77), cache.readNearby(21.0, 105.0).single().payload)
        assertEquals(0, cache.regionCount())
    }
}
