package com.cartaxi.launcher

import org.junit.Assert.*
import org.junit.Test

/** Phase 10 geometry must match the same validated Phase 6 layouts. */
class PhoneDashboardGeometryTest {
    private val width = 1280
    private val height = 720

    @Test fun eachOfEightPresetsHasIndependentlyAddressablePanels() {
        for (preset in DashboardLayouts.presets) {
            val layout = preset.layout
            for (panel in DashboardPanel.entries) {
                val b = PhoneDashboardGeometry.bounds(layout, panel, width, height)
                assertNotNull("panel: ${preset.id} / $panel", b)
                val bounds = b!!
                assertTrue(bounds.width > 0f && bounds.height > 0f)
                assertEquals(panel, PhoneDashboardGeometry.panelAt(
                    layout, width, height,
                    (bounds.left + bounds.right) / 2,
                    (bounds.top + bounds.bottom) / 2
                ))
            }
        }
    }

    @Test fun centerOfMapNormalizesToHalfwayForAllPresets() {
        for (p in DashboardLayouts.presets) {
            val b = PhoneDashboardGeometry.bounds(
                p.layout, DashboardPanel.MAP, width, height
            )!!
            val center = PhoneDashboardGeometry.normalizedMapPoint(
                p.layout, width, height,
                (b.left + b.right) / 2f, (b.top + b.bottom) / 2f
            )!!
            assertEquals(0.5f, center.first, 0.0001f)
            assertEquals(0.5f, center.second, 0.0001f)
        }
    }

    @Test fun noPanelHitOverlapsAnotherOnCommonPhoneSizes() {
        val sizes = listOf(640 to 360, 800 to 480, 1280 to 720,
            1920 to 1080, 480 to 960)
        for (preset in DashboardLayouts.presets) for ((w,h) in sizes) {
            val all = DashboardPanel.entries.map {
                PhoneDashboardGeometry.bounds(preset.layout, it, w, h)!!
            }
            for (i in all.indices) for (j in i + 1 until all.size) {
                val a = all[i]
                val b = all[j]
                val overlap = a.left < b.right && b.left < a.right &&
                    a.top < b.bottom && b.top < a.bottom
                assertFalse("${preset.id} at ${w}x$h overlaps", overlap)
            }
        }
    }

    @Test fun touchOutsideMapOrNaNIsSafelyRejected() {
        val layout = DashboardLayouts.presets.first().layout
        assertNull(PhoneDashboardGeometry.normalizedMapPoint(
            layout, width, height, -10f, -10f))
        assertNull(PhoneDashboardGeometry.normalizedMapPoint(
            layout, width, height, Float.NaN, 120f))
        val rail = PhoneDashboardGeometry.bounds(layout,
            DashboardPanel.ICONS, width, height)!!
        assertNull(PhoneDashboardGeometry.normalizedMapPoint(
            layout, width, height,
            (rail.left + rail.right) / 2, (rail.top + rail.bottom) / 2))
        assertNull(PhoneDashboardGeometry.panelAt(
            layout, width, height, Float.POSITIVE_INFINITY, 1f))
    }

    @Test fun zeroDimensionsNeverYieldTouchTargets() {
        val layout = DashboardLayouts.presets.first().layout
        assertNull(PhoneDashboardGeometry.bounds(layout,
            DashboardPanel.MAP, 0, 720))
        assertNull(PhoneDashboardGeometry.bounds(layout,
            DashboardPanel.MAP, 720, 0))
        assertNull(PhoneDashboardGeometry.panelAt(layout, 0, 0, 1f, 1f))
    }

    @Test fun touchCoordinatesTrackSavedCustomLayout() {
        val classic = DashboardLayouts.presets.first().layout
        val custom = classic.edit(DashboardPanel.QR, 7, 4, 2, 8)
        val settings = DashboardSettings(
            customLayout = custom, useCustomLayout = true,
            layoutPresetId = "wide_map"
        )
        val layout = DashboardLayouts.active(settings)
        assertEquals(custom, layout)
        val qr = PhoneDashboardGeometry.bounds(
            layout, DashboardPanel.QR, width, height
        )!!
        assertEquals(DashboardPanel.QR, PhoneDashboardGeometry.panelAt(
            layout, width, height, qr.left + qr.width / 2f, qr.top + qr.height / 2f
        ))
        assertNotEquals(
            PhoneDashboardGeometry.bounds(
                DashboardLayouts.byId("wide_map")!!.layout,
                DashboardPanel.QR, width, height
            ),
            qr
        )
    }

    @Test fun lifecycleRequiresActiveScreenAndValidDimensions() {
        val state = PhoneDashboardLifecycle()
        assertFalse(state.canRun(width, height))
        assertTrue(state.start())
        assertFalse(state.start())
        assertFalse(state.canRun(0, height))
        assertFalse(state.canRun(width, 20))
        assertTrue(state.canRun(width, height))
        assertTrue(state.stop())
        assertFalse(state.canRun(width, height))
        assertFalse(state.stop())
    }

    @Test fun lifecycleCanResumeButNotRestartAfterDestroy() {
        val state = PhoneDashboardLifecycle()
        state.start()
        state.stop()
        assertTrue(state.start())
        assertTrue(state.canRun(width, height))
        state.destroy()
        assertFalse(state.canRun(width, height))
        assertFalse(state.start())
        assertFalse(state.canRun(width, height))
    }
}
