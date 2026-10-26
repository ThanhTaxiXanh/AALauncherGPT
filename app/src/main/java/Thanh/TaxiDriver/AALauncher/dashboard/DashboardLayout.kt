package Thanh.TaxiDriver.AALauncher.dashboard



import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase 6: bounded, device-independent tile tree.
 * The map and left icon rail are required and never overlap other panels.
 * Layouts are phone-edited only; the car surface consumes a validated snapshot.
 */
enum class DashboardPanel(val title: String) {
    ICONS("Điều khiển"),
    MAP("Bản đồ"),
    CALENDAR("Lịch"),
    QR("Mã QR"),
    SIGNS("Biển báo")
}

data class DashboardTile(
    val panel: DashboardPanel, val x: Int, val y: Int,
    val width: Int, val height: Int
) {
    init {
        require(x in 0..11 && y in 0..11 && width in 1..12 && height in 1..12)
        require(x + width <= 12 && y + height <= 12) { "Ô vượt ngoài lưới." }
        if (panel == DashboardPanel.ICONS) {
            require(x == 0 && y == 0 && width == 1 && height == 12) {
                "Thanh điều khiển phải ở vị trí an toàn."
            }
        } else {
            require(x >= 1) { "Chừa thanh điều khiển." }
            if (panel == DashboardPanel.MAP) {
                require(width >= 4 && height >= 5) { "Bản đồ quá nhỏ." }
            } else {
                require(width >= 2 && height >= 2) { "Ô tiện ích quá nhỏ." }
            }
        }
    }

    fun overlaps(other: DashboardTile): Boolean =
        x < other.x + other.width && x + width > other.x &&
            y < other.y + other.height && y + height > other.y
}

data class DashboardLayout(val tiles: List<DashboardTile>) {
    init {
        require(tiles.size == DashboardPanel.entries.size &&
            tiles.map { it.panel }.toSet() == DashboardPanel.entries.toSet()) {
            "Bố cục phải chứa đầy đủ các loại ô, không trùng lặp."
        }
        for (i in tiles.indices) for (j in i + 1 until tiles.size) {
            require(!tiles[i].overlaps(tiles[j])) { "Các ô không được chồng lấn." }
        }
    }

    fun tile(panel: DashboardPanel): DashboardTile = tiles.first { it.panel == panel }

    /** Invalid edits throw; caller leaves previous valid layout intact. */
    fun edit(panel: DashboardPanel, x: Int, y: Int, width: Int, height: Int): DashboardLayout {
        require(panel != DashboardPanel.ICONS) { "Không di chuyển thanh điều khiển." }
        return DashboardLayout(tiles.map {
            if (it.panel == panel) DashboardTile(panel, x, y, width, height) else it
        })
    }
}

data class DashboardPreset(val id: String, val title: String, val layout: DashboardLayout)

object DashboardLayouts {
    private fun layout(vararg cells: DashboardTile) = DashboardLayout(listOf(
        DashboardTile(DashboardPanel.ICONS, 0, 0, 1, 12), *cells
    ))
    private fun tile(panel: DashboardPanel, x: Int, y: Int, w: Int, h: Int) =
        DashboardTile(panel, x, y, w, h)

    private val map = DashboardPanel.MAP
    private val cal = DashboardPanel.CALENDAR
    private val qr = DashboardPanel.QR
    private val signs = DashboardPanel.SIGNS

    val presets: List<DashboardPreset> = listOf(
        DashboardPreset("classic", "01 · Tiêu chuẩn", layout(
            tile(map,1,0,6,12), tile(cal,7,0,5,4),
            tile(qr,7,4,3,8), tile(signs,10,4,2,8)
        )),
        DashboardPreset("stacked", "02 · Ba tầng", layout(
            tile(map,1,0,6,12), tile(cal,7,0,5,4),
            tile(qr,7,4,5,4), tile(signs,7,8,5,4)
        )),
        DashboardPreset("signs_first", "03 · Ưu tiên biển báo", layout(
            tile(map,1,0,6,12), tile(signs,7,0,5,5),
            tile(cal,7,5,5,3), tile(qr,7,8,5,4)
        )),
        DashboardPreset("qr_first", "04 · Ưu tiên QR", layout(
            tile(map,1,0,6,12), tile(qr,7,0,5,6),
            tile(cal,7,6,5,3), tile(signs,7,9,5,3)
        )),
        DashboardPreset("wide_map", "05 · Bản đồ rộng", layout(
            tile(map,1,0,8,12), tile(cal,9,0,3,4),
            tile(qr,9,4,3,4), tile(signs,9,8,3,4)
        )),
        DashboardPreset("wide_tools", "06 · Tiện ích rộng", layout(
            tile(map,1,0,5,12), tile(cal,6,0,6,4),
            tile(qr,6,4,3,8), tile(signs,9,4,3,8)
        )),
        DashboardPreset("bottom_signs", "07 · Biển báo phía dưới", layout(
            tile(map,1,0,7,9), tile(cal,8,0,4,4),
            tile(qr,8,4,4,5), tile(signs,1,9,11,3)
        )),
        DashboardPreset("bottom_qr", "08 · QR phía dưới", layout(
            tile(map,1,0,7,8), tile(cal,8,0,4,4),
            tile(signs,8,4,4,4), tile(qr,1,8,11,4)
        ))
    )

    fun byId(id: String): DashboardPreset? = presets.firstOrNull { it.id == id }

    fun active(settings: DashboardSettings): DashboardLayout =
        if (settings.useCustomLayout && settings.customLayout != null)
            settings.customLayout
        else byId(settings.layoutPresetId)?.layout ?: presets.first().layout
}

internal object DashboardLayoutCodec {
    fun write(layout: DashboardLayout): JSONArray = JSONArray().apply {
        for (tile in layout.tiles) {
            put(JSONObject()
                .put("panel", tile.panel.name)
                .put("x", tile.x)
                .put("y", tile.y)
                .put("width", tile.width)
                .put("height", tile.height))
        }
    }

    fun read(array: JSONArray): DashboardLayout {
        require(array.length() == DashboardPanel.entries.size) { "Sai số ô." }
        return DashboardLayout((0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            DashboardTile(
                DashboardPanel.valueOf(item.getString("panel")),
                item.getInt("x"), item.getInt("y"),
                item.getInt("width"), item.getInt("height")
            )
        })
    }
}
