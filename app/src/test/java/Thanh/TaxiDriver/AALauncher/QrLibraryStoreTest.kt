package Thanh.TaxiDriver.AALauncher

import Thanh.TaxiDriver.AALauncher.qr.QrLibraryStore

import java.io.File
import java.nio.file.Files
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class QrLibraryStoreTest {
    private val sample = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+nmVoAAAAASUVORK5CYII="
    )

    private fun fixture(action: (File, QrLibraryStore) -> Unit) {
        val dir = Files.createTempDirectory("qrphase8").toFile()
        try {
            var count = 0
            val store = QrLibraryStore(dir, { 1_900_000_000_000 },
                { "item_${++count}" })
            action(dir, store)
        } finally { dir.deleteRecursively() }
    }

    @Test fun freshLibraryAddsAndSelectsWithoutCroppingOrRescaling() = fixture { dir, store ->
        assertTrue(store.entries().isEmpty())
        val first = store.add(" Công việc ", sample)
        assertEquals("Công việc", first.title)
        assertEquals(first.id, store.current()?.id)
        assertArrayEquals(sample, File(dir, "qr.png").readBytes())
        val second = store.add("Gia đình", sample)
        assertEquals(first.id, store.current()?.id)
        store.select(second.id)
        assertEquals(second.id, store.current()?.id)
        assertArrayEquals(sample, File(dir, "qr.png").readBytes())
        assertEquals(2, store.entries().size)
    }

    @Test fun legacyQrPngIsImportedLosslesslyWithoutDeletingOriginal() = fixture { dir, store ->
        val old = File(dir, "qr.png")
        old.writeBytes(sample)
        val list = store.entries()
        assertEquals(1, list.size)
        assertEquals("legacy", list.single().id)
        assertArrayEquals(sample, old.readBytes())
        assertArrayEquals(sample, store.fileFor("legacy")!!.readBytes())
        assertEquals("legacy", QrLibraryStore(dir).current()?.id)
    }

    @Test fun corruptedMirrorIsRestoredFromValidatedCatalogAfterRestart() = fixture { dir, store ->
        val selected = store.add("Ready", sample)
        val mirror = File(dir, "qr.png")
        val corrupted = ByteArray(sample.size) { 42 }
        mirror.writeBytes(corrupted)
        assertArrayEquals(corrupted, mirror.readBytes())
        val restarted = QrLibraryStore(dir)
        assertEquals(selected.id, restarted.current()?.id)
        assertArrayEquals(sample, mirror.readBytes())
        assertArrayEquals(sample, restarted.fileFor(selected.id)!!.readBytes())
    }

    @Test fun ghostMirrorFromInterruptedDeleteIsCleanedAfterRestart() = fixture { dir, store ->
        val selected = store.add("To remove", sample)
        assertTrue(store.remove(selected.id))
        File(dir, "qr.png").writeBytes(sample)
        assertTrue(File(dir, "qr.png").exists())
        val restarted = QrLibraryStore(dir)
        assertTrue(restarted.entries().isEmpty())
        assertFalse(File(dir, "qr.png").exists())
    }

    @Test fun renameAndSelectionPersistAfterRestart() = fixture { dir, store ->
        val a = store.add("A", sample)
        val b = store.add("B", sample)
        store.rename(a.id, " C ")
        store.select(b.id)
        val restart = QrLibraryStore(dir)
        assertEquals("C", restart.entries().first().title)
        assertEquals(b.id, restart.current()!!.id)
        assertArrayEquals(sample, restart.fileFor(a.id)!!.readBytes())
    }

    @Test fun removeSelectedFallsBackThenRemovesMirrorWhenEmpty() = fixture { dir, store ->
        val a = store.add("A", sample)
        val b = store.add("B", sample)
        store.select(b.id)
        assertTrue(store.remove(b.id))
        assertEquals(a.id, store.current()?.id)
        assertTrue(File(dir, "qr.png").exists())
        assertTrue(store.remove(a.id))
        assertFalse(File(dir, "qr.png").exists())
        assertNull(store.current())
        assertFalse(store.remove("nonexistent"))
    }

    @Test fun corruptCatalogNeverOverwrittenOnAddOrSelection() = fixture { dir, store ->
        store.add("Original", sample)
        val metadata = File(dir, "qr_library/index.json")
        metadata.writeText("{broken")
        val raw = metadata.readBytes()
        assertThrows(Exception::class.java) { store.add("Later", sample) }
        assertThrows(Exception::class.java) { store.select("item_1") }
        assertArrayEquals(raw, metadata.readBytes())
        assertArrayEquals(sample, File(dir, "qr.png").readBytes())
    }

    @Test fun newerSchemaIsNotSilentlyReset() = fixture { dir, store ->
        store.add("A", sample)
        val meta = File(dir, "qr_library/index.json")
        meta.writeText("""{"version":200,"selectedId":null,"items":[]}""")
        assertThrows(IllegalArgumentException::class.java) {
            store.add("B", sample)
        }
        assertTrue(meta.readText().contains("\"version\":200"))
    }

    @Test fun validatesPngHeaderSizeAndTitle() = fixture { _, store ->
        assertThrows(IllegalArgumentException::class.java) {
            store.add("Invalid", ByteArray(100))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.add(" ", sample)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.add("x".repeat(61), sample)
        }
        assertEquals(0, store.entries().size)
    }

    @Test fun noTraversalInStoredFileName() = fixture { dir, store ->
        store.add("Safe", sample)
        val index = File(dir, "qr_library/index.json")
        val root = org.json.JSONObject(index.readText())
        root.getJSONArray("items").getJSONObject(0)
            .put("fileName", "../qr.png")
        index.writeText(root.toString())
        assertThrows(IllegalArgumentException::class.java) { store.entries() }
        assertTrue(File(dir, "qr.png").exists())
    }

    @Test fun capacityLimitDoesNotSilentlyEvictOldQrImages() = fixture { _, store ->
        repeat(30) { store.add("Ảnh $it", sample) }
        assertEquals(30, store.entries().size)
        assertThrows(IllegalStateException::class.java) {
            store.add("Excess", sample)
        }
        assertEquals(30, store.entries().size)
    }
}
