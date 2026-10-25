package com.cartaxi.launcher

import android.content.Context
import org.json.JSONObject

/** Phase 5: safe, versioned settings model, shared by phone and car surfaces. */
enum class MapThemeMode(val title: String) {
    AUTO("Tự động theo ngày/đêm"),
    LIGHT("Luôn dùng giao diện sáng"),
    DARK("Luôn dùng giao diện tối")
}

data class DashboardSettings(
    val showCalendar: Boolean = true,
    val showQr: Boolean = true,
    val showSigns: Boolean = true,
    val showStreetName: Boolean = true,
    val mapTheme: MapThemeMode = MapThemeMode.AUTO,
    /** Personal notes only, never authoritative traffic signs. */
    val showPersonalReportsOnMap: Boolean = false
)

internal object DashboardSettingsCodec {
    const val VERSION = 1

    /**
     * Only the personal-report visibility setting has a legacy equivalent.
     * Legacy cfg/events and filesDir/qr.png remain untouched.
     */
    fun read(raw: String?, legacyReportsVisible: Boolean): DashboardSettings {
        if (raw == null) return DashboardSettings(
            showPersonalReportsOnMap = legacyReportsVisible
        )
        require(raw.isNotBlank()) { "Cấu hình Dashboard trống." }
        val obj = JSONObject(raw)
        require(obj.getInt("version") == VERSION) {
            "Phiên bản cấu hình chưa hỗ trợ."
        }
        fun bool(key: String, default: Boolean): Boolean {
            if (!obj.has(key)) return default
            val value = obj.get(key)
            require(value is Boolean) { "Trường cấu hình $key không hợp lệ." }
            return value
        }
        return DashboardSettings(
            showCalendar = bool("showCalendar", true),
            showQr = bool("showQr", true),
            showSigns = bool("showSigns", true),
            showStreetName = bool("showStreetName", true),
            mapTheme = MapThemeMode.valueOf(obj.optString("mapTheme", "AUTO")),
            showPersonalReportsOnMap = bool("showPersonalReportsOnMap", false)
        )
    }

    fun encode(value: DashboardSettings): String = JSONObject()
        .put("version", VERSION)
        .put("showCalendar", value.showCalendar)
        .put("showQr", value.showQr)
        .put("showSigns", value.showSigns)
        .put("showStreetName", value.showStreetName)
        .put("mapTheme", value.mapTheme.name)
        .put("showPersonalReportsOnMap", value.showPersonalReportsOnMap)
        .toString()
}

/**
 * One central preference for all new dashboard options.
 * Does not overwrite legacy cfg/events, report files, or the QR image.
 * Read errors propagate for phone recovery UI and are safely handled in car.
 */
class DashboardSettingsRepository(private val context: Context) {
    companion object {
        private const val NAME = "dashboard_settings"
        private const val VALUE_KEY = "payload_v1"
    }

    @Synchronized fun read(): DashboardSettings {
        val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(VALUE_KEY, null)
        val oldReportsVisible = context.getSharedPreferences(
            "traffic_report_privacy", Context.MODE_PRIVATE
        ).getBoolean("show_map", false)
        return DashboardSettingsCodec.read(raw, oldReportsVisible)
    }

    @Synchronized fun save(value: DashboardSettings) {
        val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        // A broken/future schema must not be silently replaced by normal edits.
        if (prefs.contains(VALUE_KEY)) read()
        check(prefs.edit().putString(
            VALUE_KEY, DashboardSettingsCodec.encode(value)
        ).commit()) { "Không lưu được cài đặt." }
    }

    @Synchronized fun change(block: (DashboardSettings) -> DashboardSettings): DashboardSettings {
        val updated = block(read())
        save(updated)
        return updated
    }

    /** Explicit recovery action only; never automatic after a parse failure. */
    @Synchronized fun reset() {
        val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        check(prefs.edit().putString(
            VALUE_KEY, DashboardSettingsCodec.encode(DashboardSettings())
        ).commit()) { "Không thể khôi phục cài đặt mặc định." }
    }
}
