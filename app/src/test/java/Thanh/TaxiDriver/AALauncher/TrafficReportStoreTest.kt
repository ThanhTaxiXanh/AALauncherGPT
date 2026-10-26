package Thanh.TaxiDriver.AALauncher

import Thanh.TaxiDriver.AALauncher.reports.TrafficReportKind
import Thanh.TaxiDriver.AALauncher.reports.TrafficReportLocation
import Thanh.TaxiDriver.AALauncher.reports.TrafficReportSeverity
import Thanh.TaxiDriver.AALauncher.reports.TrafficReportStore

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class TrafficReportStoreTest {
    private val time = 1_900_000_000_000L

    private fun fixture(block: (TrafficReportStore, File) -> Unit) {
        val dir = Files.createTempDirectory("traffic-report-test").toFile()
        try {
            val file = File(dir, "reports.json")
            var next = 0
            val store = TrafficReportStore(file, { time + next }, { "report-${++next}" })
            block(store, file)
        } finally { dir.deleteRecursively() }
    }

    @Test fun savesAndReloadsReportsAcrossStoreInstancesOffline() = fixture { store, file ->
        val location = TrafficReportLocation(21.0285, 105.8542, 7.0)
        val saved = store.create(TrafficReportKind.CONGESTION,
            TrafficReportSeverity.MEDIUM, "  Ùn tắc trên đường  ", location)
        assertEquals("Ùn tắc trên đường", saved.note)
        assertEquals("report-1", saved.id)
        assertEquals(location, saved.location)
        val afterRestart = TrafficReportStore(file).list()
        assertEquals(listOf(saved), afterRestart)
        assertEquals(1, afterRestart.size)
    }

    @Test fun locationIsOptionalAndNeverAddedWithoutConsent() = fixture { store, file ->
        store.create(TrafficReportKind.ROADWORK, TrafficReportSeverity.LOW, "")
        assertNull(TrafficReportStore(file).list().single().location)
        assertFalse(file.readText().contains("\"lat\""))
        assertTrue(file.readText().contains("\"version\":1"))
    }

    @Test fun deletesOnlySelectedReportAndPersistsDeletion() = fixture { store, file ->
        val first = store.create(TrafficReportKind.HAZARD,
            TrafficReportSeverity.HIGH, "Chướng ngại vật")
        val second = store.create(TrafficReportKind.COLLISION,
            TrafficReportSeverity.HIGH, "Va chạm")
        assertTrue(store.delete(first.id))
        assertFalse(store.delete("not-existent"))
        assertEquals(listOf(second), TrafficReportStore(file).list())
    }

    @Test fun unreadableDataMustNeverBeOverwrittenOnCreateOrDelete() = fixture { store, file ->
        file.writeText("{this is damaged")
        val original = file.readBytes()
        assertThrows(Exception::class.java) {
            store.create(TrafficReportKind.OTHER, TrafficReportSeverity.LOW, "test")
        }
        assertThrows(Exception::class.java) { store.delete("any") }
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun unknownNewerSchemaPreventsSilentLoss() = fixture { store, file ->
        file.writeText("""{"version":99,"reports":[]}""")
        assertThrows(IllegalArgumentException::class.java) {
            store.create(TrafficReportKind.OTHER, TrafficReportSeverity.LOW, "new")
        }
        assertEquals(99, org.json.JSONObject(file.readText()).getInt("version"))
    }

    @Test fun invalidGpsValuesAndLongNotesAreRejected() = fixture { store, _ ->
        assertThrows(IllegalArgumentException::class.java) {
            TrafficReportLocation(Double.NaN, 105.0, 5.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrafficReportLocation(21.0, 105.0, 65.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.create(TrafficReportKind.OTHER, TrafficReportSeverity.LOW, "x".repeat(241))
        }
        assertTrue(store.list().isEmpty())
    }

    @Test fun atCapacityRequiresExplicitDeletionRatherThanAutoEviction() = fixture { store, _ ->
        repeat(TrafficReportStore.MAX_REPORTS) {
            store.create(TrafficReportKind.HAZARD, TrafficReportSeverity.LOW, "$it")
        }
        assertEquals(TrafficReportStore.MAX_REPORTS, store.list().size)
        assertThrows(IllegalStateException::class.java) {
            store.create(TrafficReportKind.HAZARD, TrafficReportSeverity.LOW, "extra")
        }
        assertEquals(TrafficReportStore.MAX_REPORTS, store.list().size)
    }

    @Test fun invalidRecordAndDuplicateIdsCannotReplaceExistingRecords() = fixture { store, file ->
        store.create(TrafficReportKind.OTHER, TrafficReportSeverity.LOW, "test")
        val root = org.json.JSONObject(file.readText())
        val arr = root.getJSONArray("reports")
        arr.put(arr.getJSONObject(0))
        file.writeText(root.toString())
        assertThrows(IllegalArgumentException::class.java) {
            store.create(TrafficReportKind.OTHER, TrafficReportSeverity.LOW, "next")
        }
    }
}
