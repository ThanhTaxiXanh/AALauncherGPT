package Thanh.TaxiDriver.AALauncher

import Thanh.TaxiDriver.AALauncher.reports.TrafficReport
import Thanh.TaxiDriver.AALauncher.reports.TrafficReportKind
import Thanh.TaxiDriver.AALauncher.reports.TrafficReportLocation
import Thanh.TaxiDriver.AALauncher.reports.TrafficReportMapPolicy
import Thanh.TaxiDriver.AALauncher.reports.TrafficReportSeverity
import Thanh.TaxiDriver.AALauncher.reports.TrafficReportStore

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TrafficReportsPhase4Test {
    private val now = 1_900_000_000_000L

    private fun setup(block: (TrafficReportStore, File) -> Unit) {
        val dir = Files.createTempDirectory("phase4-reports").toFile()
        try {
            val file = File(dir, "traffic_reports_v1.json")
            var counter = 0
            block(TrafficReportStore(file, { now }, { "id-${++counter}" }), file)
        } finally { dir.deleteRecursively() }
    }

    @Test fun editPreservesStableIdentityAndOriginalCreationDate() = setup { store, file ->
        val original = store.create(
            TrafficReportKind.CONGESTION, TrafficReportSeverity.LOW, "Ùn xe",
            TrafficReportLocation(21.0, 105.0, 5.0)
        )
        val edited = requireNotNull(store.update(
            original.id, TrafficReportKind.ROADWORK, TrafficReportSeverity.HIGH,
            " Sửa đường ", original.location
        ))
        assertEquals(original.id, edited.id)
        assertEquals(original.createdAtMs, edited.createdAtMs)
        assertEquals("Sửa đường", edited.note)
        assertEquals(TrafficReportKind.ROADWORK, edited.kind)
        assertEquals(TrafficReportSeverity.HIGH, edited.severity)
        assertEquals(listOf(edited), TrafficReportStore(file).list())
    }

    @Test fun editMayRemoveGpsWithoutDeletingReport() = setup { store, file ->
        val before = store.create(
            TrafficReportKind.COLLISION, TrafficReportSeverity.MEDIUM, "Va chạm",
            TrafficReportLocation(21.0, 105.0, 6.0)
        )
        store.update(before.id, before.kind, before.severity, before.note, null)
        val after = TrafficReportStore(file).list().single()
        assertNull(after.location)
        assertEquals(before.id, after.id)
        assertFalse(file.readText().contains("\"lat\""))
    }

    @Test fun bulkGpsErasureKeepsAllNotesAndTypes() = setup { store, file ->
        val a = store.create(TrafficReportKind.OTHER,
            TrafficReportSeverity.LOW, "Giữ ghi chú",
            TrafficReportLocation(21.01, 105.01, 8.0))
        val b = store.create(TrafficReportKind.HAZARD,
            TrafficReportSeverity.HIGH, "Không GPS")
        assertEquals(1, store.clearAllLocations())
        assertEquals(0, store.clearAllLocations())
        val records = TrafficReportStore(file).list()
        assertEquals(2, records.size)
        assertNull(records.find { it.id == a.id }?.location)
        assertEquals("Giữ ghi chú", records.find { it.id == a.id }?.note)
        assertEquals(b, records.find { it.id == b.id })
        assertFalse(file.readText().contains("\"lon\""))
    }

    @Test fun defaultExportNeverIncludesAnyGpsFields() = setup { store, _ ->
        store.create(TrafficReportKind.HAZARD, TrafficReportSeverity.HIGH, "Test",
            TrafficReportLocation(21.0285, 105.8542, 6.0))
        val serialized = store.exportJson()
        assertTrue(serialized.contains("personal_unverified"))
        assertFalse(serialized.contains("\"lat\""))
        assertFalse(serialized.contains("\"lon\""))
        assertFalse(serialized.contains("\"accuracyM\""))
        assertFalse(JSONObject(serialized).getBoolean("includesGps"))
        assertEquals(1, JSONObject(serialized).getJSONArray("reports").length())
    }

    @Test fun explicitExportIncludesLocationWithoutMutatingStorage() = setup { store, file ->
        val original = store.create(TrafficReportKind.CLOSURE,
            TrafficReportSeverity.LOW, "Đường đóng",
            TrafficReportLocation(21.0285, 105.8542, 12.0))
        val before = file.readBytes()
        val root = JSONObject(store.exportJson(includeGps = true))
        assertTrue(root.getBoolean("includesGps"))
        val loc = root.getJSONArray("reports").getJSONObject(0).getJSONObject("location")
        assertEquals(21.0285, loc.getDouble("lat"), 0.00001)
        assertEquals(105.8542, loc.getDouble("lon"), 0.00001)
        assertArrayEquals(before, file.readBytes())
        assertEquals(original, store.list().single())
    }

    @Test fun missingEditAndCorruptFileDoNotAccidentallyCreateAnything() = setup { store, file ->
        assertNull(store.update("absent", TrafficReportKind.OTHER,
            TrafficReportSeverity.LOW, "Missing", null))
        val created = store.create(TrafficReportKind.OTHER,
            TrafficReportSeverity.LOW, "Gốc")
        file.writeText("{not-valid")
        val original = file.readBytes()
        assertThrows(Exception::class.java) {
            store.update(created.id, TrafficReportKind.HAZARD,
                TrafficReportSeverity.HIGH, "Mới", null)
        }
        assertThrows(Exception::class.java) { store.clearAllLocations() }
        assertThrows(Exception::class.java) { store.exportJson() }
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun editRejectsOversizeNotePreservingExistingRecord() = setup { store, file ->
        val original = store.create(TrafficReportKind.OTHER,
            TrafficReportSeverity.LOW, "Giữ nguyên")
        assertThrows(IllegalArgumentException::class.java) {
            store.update(original.id, original.kind, original.severity,
                "x".repeat(241), null)
        }
        assertEquals(listOf(original), TrafficReportStore(file).list())
    }

    @Test fun mapIsOptInRecentBoundedAndRequiresCoordinates() {
        val fresh = TrafficReport("1", TrafficReportKind.HAZARD,
            TrafficReportSeverity.HIGH, "A", now,
            TrafficReportLocation(21.0, 105.0, 10.0))
        val absent = fresh.copy(id = "2", location = null)
        val expired = fresh.copy(id = "3",
            createdAtMs = now - TrafficReportMapPolicy.MAX_AGE_MS - 1)
        val future = fresh.copy(id = "4", createdAtMs = now + 60_000)
        val tooFar = fresh.copy(id = "5",
            location = TrafficReportLocation(22.0, 105.0, 5.0))
        val records = listOf(fresh, absent, expired, future, tooFar)
        assertTrue(TrafficReportMapPolicy.visible(
            records, false, 21.0, 105.0, now).isEmpty())
        assertEquals(listOf(fresh), TrafficReportMapPolicy.visible(
            records, true, 21.0, 105.0, now))
        assertTrue(TrafficReportMapPolicy.visible(
            records, true, Double.NaN, 105.0, now).isEmpty())
    }

    @Test fun mapMarkerCountIsCappedAndClosestFirst() {
        val many = (0 until 60).map { i ->
            TrafficReport(i.toString(), TrafficReportKind.OTHER,
                TrafficReportSeverity.LOW, "", now,
                TrafficReportLocation(21.0 + i * 0.00002, 105.0, 5.0))
        }
        val shown = TrafficReportMapPolicy.visible(many, true, 21.0, 105.0, now)
        assertEquals(TrafficReportMapPolicy.MAX_MARKERS, shown.size)
        assertEquals("0", shown.first().id)
        assertEquals("39", shown.last().id)
    }
}
