package com.cartaxi.launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DashboardLayoutTest {
    private val classic = DashboardLayouts.presets.first().layout

    @Test fun exactlyEightUniquePresetsAreValid() {
        val presets = DashboardLayouts.presets
        assertEquals(8, presets.size)
        assertEquals(8, presets.map { it.id }.toSet().size)
        assertEquals("classic", presets.first().id)
        for (preset in presets) {
            assertEquals(DashboardPanel.entries.size, preset.layout.tiles.size)
            assertEquals(1, preset.layout.tile(DashboardPanel.ICONS).width)
            assertEquals(12, preset.layout.tile(DashboardPanel.ICONS).height)
            assertTrue(preset.layout.tile(DashboardPanel.MAP).width >= 4)
            for (i in preset.layout.tiles.indices)
                for (j in i + 1 until preset.layout.tiles.size)
                    assertFalse("${preset.id}: panels overlap",
                        preset.layout.tiles[i].overlaps(preset.layout.tiles[j]))
        }
    }

    @Test fun everyPresetSurvivesStrictJsonRoundTrip() {
        for (p in DashboardLayouts.presets) {
            assertEquals(p.layout, DashboardLayoutCodec.read(
                DashboardLayoutCodec.write(p.layout)
            ))
        }
    }

    @Test fun phaseFiveSettingsWithNoLayoutRemainBackwardCompatible() {
        val original = """{"version":1,"showQr":false,"showCalendar":true}"""
        val settings = DashboardSettingsCodec.read(original, false)
        assertEquals("classic", settings.layoutPresetId)
        assertFalse(settings.useCustomLayout)
        assertNull(settings.customLayout)
        assertEquals(classic, DashboardLayouts.active(settings))
        assertFalse(settings.showQr)
    }

    @Test fun selectingEachPresetChangesTheActiveLayout() {
        for (preset in DashboardLayouts.presets) {
            val settings = DashboardSettings(layoutPresetId = preset.id)
            assertEquals(preset.layout, DashboardLayouts.active(settings))
            val encoded = DashboardSettingsCodec.encode(settings)
            assertEquals(preset.id,
                DashboardSettingsCodec.read(encoded, false).layoutPresetId)
        }
    }

    @Test fun customEditIsSavedAndRestoredAcrossJsonReload() {
        // First create space by reducing width of the map.
        val shorterMap = classic.edit(DashboardPanel.MAP, 1, 0, 5, 12)
        val custom = shorterMap.edit(DashboardPanel.QR, 7, 4, 2, 8)
        val settings = DashboardSettings(
            layoutPresetId = "qr_first", useCustomLayout = true, customLayout = custom,
            showStreetName = false
        )
        val loaded = DashboardSettingsCodec.read(
            DashboardSettingsCodec.encode(settings), true
        )
        assertEquals(custom, loaded.customLayout)
        assertEquals(custom, DashboardLayouts.active(loaded))
        assertFalse(loaded.showStreetName)
        assertEquals("qr_first", loaded.layoutPresetId)
    }

    @Test fun switchingFromCustomToPresetPreservesCustomForLater() {
        val custom = classic.edit(DashboardPanel.QR, 7, 4, 2, 8)
        val selected = DashboardSettings(
            customLayout = custom, useCustomLayout = true, layoutPresetId = "wide_map"
        )
        val presetSelected = selected.copy(useCustomLayout = false)
        assertEquals(DashboardLayouts.byId("wide_map")!!.layout,
            DashboardLayouts.active(presetSelected))
        assertEquals(custom, presetSelected.customLayout)
        assertEquals(custom, DashboardLayouts.active(presetSelected.copy(useCustomLayout = true)))
    }

    @Test fun invalidOverlappingEditDoesNotMutatePreviousSnapshot() {
        assertThrows(IllegalArgumentException::class.java) {
            classic.edit(DashboardPanel.QR, 9, 4, 3, 8)
        }
        assertEquals(7, classic.tile(DashboardPanel.QR).x)
    }

    @Test fun mapAndIconRailHaveSafetyBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            classic.edit(DashboardPanel.MAP, 1, 0, 3, 12)
        }
        assertThrows(IllegalArgumentException::class.java) {
            classic.edit(DashboardPanel.ICONS, 1, 0, 1, 12)
        }
        assertThrows(IllegalArgumentException::class.java) {
            classic.edit(DashboardPanel.SIGNS, 11, 4, 2, 8)
        }
        assertEquals(DashboardTile(DashboardPanel.ICONS, 0, 0, 1, 12),
            classic.tile(DashboardPanel.ICONS))
    }

    @Test fun missingDuplicateAndUnknownPanelsAreRejected() {
        val raw = DashboardLayoutCodec.write(classic)
        val missing = JSONArray().apply {
            for (i in 0 until raw.length() - 1) put(raw.getJSONObject(i))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DashboardLayoutCodec.read(missing)
        }
        val bad = DashboardLayoutCodec.write(classic)
        bad.getJSONObject(1).put("panel", "FAKE")
        assertThrows(IllegalArgumentException::class.java) {
            DashboardLayoutCodec.read(bad)
        }
        val duplicate = DashboardLayoutCodec.write(classic)
        duplicate.getJSONObject(2).put("panel", "MAP")
        assertThrows(IllegalArgumentException::class.java) {
            DashboardLayoutCodec.read(duplicate)
        }
    }

    @Test fun corruptCustomLayoutMustNotResetUserConfiguration() {
        val bad = JSONObject(DashboardSettingsCodec.encode(DashboardSettings()))
        bad.put("useCustomLayout", true)
        assertThrows(IllegalArgumentException::class.java) {
            DashboardSettingsCodec.read(bad.toString(), false)
        }
        bad.put("customLayout", DashboardLayoutCodec.write(classic))
        bad.getJSONArray("customLayout").getJSONObject(1).put("width", 12)
        assertThrows(IllegalArgumentException::class.java) {
            DashboardSettingsCodec.read(bad.toString(), false)
        }
    }

    @Test fun invalidPresetIdFailsRatherThanChangingUserLayout() {
        assertThrows(IllegalArgumentException::class.java) {
            DashboardSettings(layoutPresetId = "nonexistent")
        }
        val json = JSONObject(DashboardSettingsCodec.encode(DashboardSettings()))
        json.put("layoutPresetId", "nonexistent")
        assertThrows(IllegalArgumentException::class.java) {
            DashboardSettingsCodec.read(json.toString(), false)
        }
    }

    @Test fun restoreDefaultPreservesExistingPhaseFiveVisibilityOptions() {
        val old = DashboardSettings(showCalendar = false, showQr = false,
            showPersonalReportsOnMap = true)
        val customized = old.copy(
            layoutPresetId = "bottom_qr", useCustomLayout = true,
            customLayout = classic.edit(DashboardPanel.QR, 7, 4, 2, 8)
        )
        val restored = customized.copy(
            layoutPresetId = "classic", useCustomLayout = false
        )
        assertFalse(restored.showCalendar)
        assertFalse(restored.showQr)
        assertTrue(restored.showPersonalReportsOnMap)
        assertEquals(classic, DashboardLayouts.active(restored))
    }
}
