package Thanh.TaxiDriver.AALauncher

import Thanh.TaxiDriver.AALauncher.reports.*
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class UserSignReportsTest {
    private fun temp(block: (TrafficReportStore) -> Unit) {
        val folder = Files.createTempDirectory("signreport").toFile()
        try { block(TrafficReportStore(File(folder,"reports.json"))) }
        finally { folder.deleteRecursively() }
    }

    @Test fun speedReportRetainsExactValueAndGpsAfterRestart() = temp { store ->
        val loc = TrafficReportLocation(21.13, 106.08, 8.0)
        val r = store.create(TrafficReportKind.SIGN_SPEED,
            TrafficReportSeverity.LOW, "Đầu đường", loc, 80)
        assertEquals(80, store.list().single().speedKmh)
        assertEquals(loc, store.list().single().location)
        assertTrue(store.exportJson().contains("\"speedKmh\": 80"))
        assertFalse(store.exportJson().contains("\"lat\""))
    }

    @Test fun speedReportRejectsInvalidAndOtherKinds() = temp { store ->
        assertThrows(IllegalArgumentException::class.java) {
            store.create(TrafficReportKind.SIGN_SPEED,
                TrafficReportSeverity.LOW, "", null, 181)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.create(TrafficReportKind.SIGN_STOP,
                TrafficReportSeverity.LOW, "", null, 60)
        }
    }

    @Test fun newKindsWorkWithoutChangingLegacyReports() = temp { store ->
        store.create(TrafficReportKind.CONGESTION, TrafficReportSeverity.HIGH, "")
        store.create(TrafficReportKind.SIGN_CAMERA_RED, TrafficReportSeverity.LOW, "")
        assertEquals(2, store.list().size)
        assertEquals(0, store.list().count { it.location != null })
        assertFalse(store.exportJson().contains("\"lat\""))
    }
}
