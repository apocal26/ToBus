package com.example.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.example.BuildConfig
import com.example.data.model.CameraState
import com.example.data.model.DataSourceMode
import com.example.data.model.FavoriteStop
import com.example.data.model.LivePollInterval
import com.example.data.model.MapStyle
import com.example.data.model.RefreshInterval
import com.example.data.model.RouteColorPalette
import com.example.data.model.ServiceAlertCategory
import com.example.data.model.UserWidgetSettings
import com.example.data.model.WidgetCameraMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class ToBusPreferences private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<UserWidgetSettings> = _settings.asStateFlow()

    private val _favoriteStops = MutableStateFlow(readFavoriteStops())
    val favoriteStops: StateFlow<List<FavoriteStop>> = _favoriteStops.asStateFlow()

    private val _favoriteLines = MutableStateFlow(readFavoriteLines())
    val favoriteLines: StateFlow<List<String>> = _favoriteLines.asStateFlow()

    fun currentSettings(): UserWidgetSettings = _settings.value

    fun currentFavoriteStops(): List<FavoriteStop> = _favoriteStops.value

    fun currentFavoriteLines(): List<String> = _favoriteLines.value

    fun toggleFavoriteLine(lineCode: String): Pair<List<String>, Boolean> {
        val raw = lineCode.trim().uppercase()
        if (raw.isEmpty()) return _favoriteLines.value to false
        val cleaned = com.example.data.network.GtfsRtProtobufParser.normalizeGttLineCode(raw)
            .takeIf { it.isNotBlank() && it != "?" } ?: raw
        val current = _favoriteLines.value.toMutableList()
        val idx = current.indexOfFirst { it.equals(cleaned, ignoreCase = true) }
        val added: Boolean
        if (idx >= 0) {
            current.removeAt(idx)
            added = false
        } else {
            current.add(0, cleaned)
            if (current.size > MAX_FAVORITE_LINES) {
                current.subList(MAX_FAVORITE_LINES, current.size).clear()
            }
            added = true
        }
        saveFavoriteLines(current)
        _favoriteLines.value = current
        return current to added
    }

    fun updateSettings(transform: (UserWidgetSettings) -> UserWidgetSettings): UserWidgetSettings {
        val updated = transform(_settings.value)
        saveSettings(updated)
        _settings.value = updated
        return updated
    }

    fun toggleFavoriteStop(stop: FavoriteStop): Pair<List<FavoriteStop>, Boolean> {
        val current = readFavoriteStops().toMutableList()
        val existingIdx = current.indexOfFirst {
            it.stopId.equals(stop.stopId, ignoreCase = true)
        }
        val added: Boolean
        if (existingIdx >= 0) {
            current.removeAt(existingIdx)
            added = false
        } else {
            // Keep up to 6 favorites, newest first
            current.add(0, stop)
            if (current.size > MAX_FAVORITES) {
                current.subList(MAX_FAVORITES, current.size).clear()
            }
            added = true
        }
        saveFavoriteStops(current)
        _favoriteStops.value = current
        return current to added
    }

    fun removeFavoriteStop(stopId: String): List<FavoriteStop> {
        val updated = readFavoriteStops().filterNot { it.stopId.equals(stopId, ignoreCase = true) }
        saveFavoriteStops(updated)
        _favoriteStops.value = updated
        return updated
    }

    fun getActiveStopWidgetIndex(): Int = prefs.getInt(KEY_STOP_WIDGET_IDX, 0).coerceAtLeast(0)

    fun getStopWidgetIndex(appWidgetId: Int): Int {
        val perWidgetKey = "${KEY_STOP_WIDGET_IDX}_$appWidgetId"
        return if (prefs.contains(perWidgetKey)) {
            prefs.getInt(perWidgetKey, 0).coerceAtLeast(0)
        } else {
            getActiveStopWidgetIndex()
        }
    }

    fun cycleStopWidgetIndex(delta: Int): Int {
        val count = readFavoriteStops().size
        if (count <= 1) {
            prefs.edit().putInt(KEY_STOP_WIDGET_IDX, 0).apply()
            return 0
        }
        val cur = getActiveStopWidgetIndex() % count
        val next = ((cur + delta) % count + count) % count
        prefs.edit().putInt(KEY_STOP_WIDGET_IDX, next).apply()
        return next
    }

    fun cycleStopWidgetIndex(appWidgetId: Int, delta: Int): Int {
        val count = readFavoriteStops().size
        val perWidgetKey = "${KEY_STOP_WIDGET_IDX}_$appWidgetId"
        if (count <= 1) {
            prefs.edit().putInt(perWidgetKey, 0).apply()
            return 0
        }
        val cur = getStopWidgetIndex(appWidgetId) % count
        val next = ((cur + delta) % count + count) % count
        prefs.edit().putInt(perWidgetKey, next).apply()
        return next
    }

    fun clearStopWidgetIndex(appWidgetId: Int) {
        prefs.edit().remove("${KEY_STOP_WIDGET_IDX}_$appWidgetId").apply()
    }

    fun getGithubRepo(): String {
        val saved = prefs.getString(KEY_GITHUB_REPO, null)?.trim().orEmpty()
        return if (saved.isNotEmpty()) saved else BuildConfig.GITHUB_REPO
    }

    fun setGithubRepo(repoSlug: String) {
        prefs.edit().putString(KEY_GITHUB_REPO, repoSlug.trim()).apply()
    }

    fun getLastUpdateCheckMillis(): Long = prefs.getLong(KEY_LAST_UPDATE_CHECK_MS, 0L)

    fun setLastUpdateCheckMillis(millis: Long) {
        prefs.edit().putLong(KEY_LAST_UPDATE_CHECK_MS, millis).apply()
    }

    fun getDismissedUpdateTag(): String? = prefs.getString(KEY_DISMISSED_UPDATE_TAG, null)

    fun setDismissedUpdateTag(tag: String?) {
        prefs.edit().putString(KEY_DISMISSED_UPDATE_TAG, tag?.trim()).apply()
    }

    fun isAlertBaselineSeeded(): Boolean = prefs.getBoolean(KEY_ALERT_BASELINE_SEEDED, false)

    fun setAlertBaselineSeeded(seeded: Boolean) {
        prefs.edit().putBoolean(KEY_ALERT_BASELINE_SEEDED, seeded).apply()
    }

    fun getSeenAlertFingerprints(nowMillis: Long = System.currentTimeMillis()): MutableMap<String, Long> {
        val raw = prefs.getString(KEY_SEEN_ALERT_FINGERPRINTS, null) ?: return LinkedHashMap()
        val cutoff = nowMillis - SEEN_ALERT_RETENTION_MS
        return runCatching {
            val obj = JSONObject(raw)
            val result = LinkedHashMap<String, Long>(obj.length())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val ts = obj.optLong(key, 0L)
                if (key.isNotBlank() && ts >= cutoff) {
                    result[key] = ts
                }
            }
            result
        }.getOrDefault(LinkedHashMap())
    }

    fun markAlertFingerprintsSeen(
        fingerprints: Collection<String>,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        if (fingerprints.isEmpty()) return
        synchronized(seenAlertsLock) {
            val map = getSeenAlertFingerprints(nowMillis)
            for (fp in fingerprints) {
                val clean = fp.trim()
                if (clean.isNotEmpty()) {
                    map[clean] = nowMillis
                }
            }
            val pruned = if (map.size > MAX_SEEN_ALERT_FINGERPRINTS) {
                map.entries
                    .sortedByDescending { it.value }
                    .take(MAX_SEEN_ALERT_FINGERPRINTS)
                    .associateTo(LinkedHashMap()) { it.key to it.value }
            } else {
                map
            }
            val obj = JSONObject()
            for ((k, v) in pruned) {
                obj.put(k, v)
            }
            prefs.edit().putString(KEY_SEEN_ALERT_FINGERPRINTS, obj.toString()).apply()
        }
    }

    fun clearSeenAlertFingerprints(keepBaselineSeeded: Boolean = true) {
        synchronized(seenAlertsLock) {
            prefs.edit()
                .remove(KEY_SEEN_ALERT_FINGERPRINTS)
                .putBoolean(KEY_ALERT_BASELINE_SEEDED, keepBaselineSeeded)
                .apply()
        }
    }

    fun getLastWidgetRefreshAttemptMillis(): Long =
        prefs.getLong(KEY_LAST_WIDGET_REFRESH_MS, 0L)

    fun setLastWidgetRefreshAttemptMillis(millis: Long) {
        prefs.edit().putLong(KEY_LAST_WIDGET_REFRESH_MS, millis).apply()
    }

    fun getLastWidgetRenderedLine(): String =
        prefs.getString(KEY_LAST_WIDGET_RENDERED_LINE, "")?.trim()?.uppercase().orEmpty()

    fun setLastWidgetRenderedLine(lineCode: String) {
        prefs.edit().putString(KEY_LAST_WIDGET_RENDERED_LINE, lineCode.trim().uppercase()).apply()
    }

    fun getLastWidgetSuccessMillis(): Long =
        prefs.getLong(KEY_LAST_WIDGET_SUCCESS_MS, 0L)

    fun setLastWidgetSuccessMillis(millis: Long) {
        prefs.edit().putLong(KEY_LAST_WIDGET_SUCCESS_MS, millis).apply()
    }

    private fun readSettings(): UserWidgetSettings {
        val rawSelected = prefs.getString(KEY_LINE, "")?.trim()?.uppercase().orEmpty()
        val migratedSelected = if (rawSelected.isNotEmpty()) {
            com.example.data.network.GtfsRtProtobufParser.normalizeGttLineCode(rawSelected)
                .takeIf { it.isNotBlank() && it != "?" } ?: rawSelected
        } else {
            ""
        }
        return UserWidgetSettings(
            selectedLine = migratedSelected,
            mapStyle = MapStyle.fromId(prefs.getString(KEY_MAP_STYLE, MapStyle.DARK.id)),
            cameraMode = WidgetCameraMode.fromId(
                prefs.getString(KEY_CAMERA_MODE, WidgetCameraMode.AUTO_FIT_BUSES.id),
            ),
            fixedCamera = CameraState(
                centerLat = java.lang.Double.longBitsToDouble(
                    prefs.getLong(KEY_FIXED_LAT, java.lang.Double.doubleToRawLongBits(CameraState.TURIN_CENTER_LAT)),
                ),
                centerLon = java.lang.Double.longBitsToDouble(
                    prefs.getLong(KEY_FIXED_LON, java.lang.Double.doubleToRawLongBits(CameraState.TURIN_CENTER_LON)),
                ),
                zoom = java.lang.Double.longBitsToDouble(
                    prefs.getLong(KEY_FIXED_ZOOM, java.lang.Double.doubleToRawLongBits(13.2)),
                ).coerceIn(CameraState.MIN_ZOOM, CameraState.MAX_ZOOM),
            ),
            showVehicleLabels = prefs.getBoolean(KEY_SHOW_LABELS, false),
            showRouteLine = prefs.getBoolean(KEY_SHOW_ROUTE, true),
            showStopsOnMap = prefs.getBoolean(KEY_SHOW_STOPS_MAP, true),
            showDepotsOnMap = prefs.getBoolean(KEY_SHOW_DEPOTS_MAP, true),
            colorByDirection = prefs.getBoolean(KEY_COLOR_BY_DIR, true),
            routeColorPalette = RouteColorPalette.fromId(
                prefs.getString(KEY_ROUTE_COLOR_PALETTE, RouteColorPalette.TURIN_GOLD.id),
            ),
            showRouteInWidget = prefs.getBoolean(KEY_SHOW_ROUTE_WIDGET, true),
            showFavoriteStopsInWidget = prefs.getBoolean(KEY_SHOW_FAV_STOPS_WIDGET, true),
            hideInactiveInWidget = prefs.getBoolean(KEY_HIDE_INACTIVE_WIDGET, true),
            refreshInterval = RefreshInterval.fromMinutes(prefs.getInt(KEY_REFRESH_MIN, 5)),
            dataSourceMode = DataSourceMode.fromId(prefs.getString(KEY_DATA_SOURCE_MODE, DataSourceMode.AUTO.id)),
            livePollInterval = LivePollInterval.fromSeconds(prefs.getInt(KEY_LIVE_POLL_SEC, LivePollInterval.SEC_25.seconds)),
            keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false),
            showServiceAlerts = prefs.getBoolean(KEY_SHOW_SERVICE_ALERTS, true),
            notifyFavoriteLineAlerts = prefs.getBoolean(KEY_NOTIFY_FAVORITE_ALERTS, false),
            notifyAlertCategories = readNotifyAlertCategories(),
            hideInactiveOnMap = prefs.getBoolean(KEY_HIDE_INACTIVE_MAP, false),
        )
    }

    private fun readNotifyAlertCategories(): Set<ServiceAlertCategory> {
        if (!prefs.contains(KEY_NOTIFY_ALERT_CATEGORIES)) {
            return ServiceAlertCategory.DEFAULT_NOTIFY_CATEGORIES
        }
        val raw = prefs.getString(KEY_NOTIFY_ALERT_CATEGORIES, null)
            ?: return ServiceAlertCategory.DEFAULT_NOTIFY_CATEGORIES
        if (raw.isBlank()) return emptySet()
        return raw.split(',')
            .mapNotNull { token ->
                val clean = token.trim()
                ServiceAlertCategory.entries.firstOrNull { it.id.equals(clean, ignoreCase = true) }
            }
            .toSet()
    }

    private fun saveSettings(s: UserWidgetSettings) {
        prefs.edit()
            .putString(KEY_LINE, s.selectedLine.trim().uppercase())
            .putString(KEY_MAP_STYLE, s.mapStyle.id)
            .putString(KEY_CAMERA_MODE, s.cameraMode.id)
            .putLong(KEY_FIXED_LAT, java.lang.Double.doubleToRawLongBits(s.fixedCamera.centerLat))
            .putLong(KEY_FIXED_LON, java.lang.Double.doubleToRawLongBits(s.fixedCamera.centerLon))
            .putLong(KEY_FIXED_ZOOM, java.lang.Double.doubleToRawLongBits(s.fixedCamera.zoom))
            .putBoolean(KEY_SHOW_LABELS, s.showVehicleLabels)
            .putBoolean(KEY_SHOW_ROUTE, s.showRouteLine)
            .putBoolean(KEY_SHOW_STOPS_MAP, s.showStopsOnMap)
            .putBoolean(KEY_SHOW_DEPOTS_MAP, s.showDepotsOnMap)
            .putBoolean(KEY_COLOR_BY_DIR, s.colorByDirection)
            .putString(KEY_ROUTE_COLOR_PALETTE, s.routeColorPalette.id)
            .putBoolean(KEY_SHOW_ROUTE_WIDGET, s.showRouteInWidget)
            .putBoolean(KEY_SHOW_FAV_STOPS_WIDGET, s.showFavoriteStopsInWidget)
            .putBoolean(KEY_HIDE_INACTIVE_WIDGET, s.hideInactiveInWidget)
            .putInt(KEY_REFRESH_MIN, s.refreshInterval.minutes)
            .putString(KEY_DATA_SOURCE_MODE, s.dataSourceMode.id)
            .putInt(KEY_LIVE_POLL_SEC, s.livePollInterval.seconds)
            .putBoolean(KEY_KEEP_SCREEN_ON, s.keepScreenOn)
            .putBoolean(KEY_SHOW_SERVICE_ALERTS, s.showServiceAlerts)
            .putBoolean(KEY_NOTIFY_FAVORITE_ALERTS, s.notifyFavoriteLineAlerts)
            .putString(
                KEY_NOTIFY_ALERT_CATEGORIES,
                s.notifyAlertCategories.joinToString(",") { it.id },
            )
            .putBoolean(KEY_HIDE_INACTIVE_MAP, s.hideInactiveOnMap)
            .apply()
    }

    private fun readFavoriteLines(): List<String> {
        val raw = prefs.getString(KEY_FAVORITE_LINES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val list = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val rawCode = arr.optString(i).trim().uppercase()
                val code = if (rawCode.isNotEmpty()) {
                    com.example.data.network.GtfsRtProtobufParser.normalizeGttLineCode(rawCode)
                        .takeIf { it.isNotBlank() && it != "?" } ?: rawCode
                } else {
                    ""
                }
                if (code.isNotEmpty() && list.none { it.equals(code, ignoreCase = true) }) {
                    list.add(code)
                }
            }
            list
        }.getOrDefault(emptyList())
    }

    private fun saveFavoriteLines(lines: List<String>) {
        val arr = JSONArray()
        lines.forEach { arr.put(it) }
        prefs.edit().putString(KEY_FAVORITE_LINES, arr.toString()).apply()
    }

    private fun readFavoriteStops(): List<FavoriteStop> {
        val raw = prefs.getString(KEY_FAVORITE_STOPS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val list = ArrayList<FavoriteStop>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val stopId = obj.optString("id").trim()
                if (stopId.isEmpty()) continue
                val rawStopLine = obj.optString("line", "").trim()
                val migratedStopLine = if (rawStopLine.isNotEmpty()) {
                    com.example.data.network.GtfsRtProtobufParser.normalizeGttLineCode(rawStopLine)
                        .takeIf { it.isNotBlank() && it != "?" } ?: rawStopLine.uppercase()
                } else {
                    ""
                }
                list.add(
                    FavoriteStop(
                        stopId = stopId,
                        stopCode = obj.optString("code", stopId),
                        name = obj.optString("name", "Fermata $stopId"),
                        lat = obj.optDouble("lat", CameraState.TURIN_CENTER_LAT),
                        lon = obj.optDouble("lon", CameraState.TURIN_CENTER_LON),
                        lineCode = migratedStopLine,
                        directionId = obj.optInt("dir", 0),
                        terminusName = obj.optString("terminus", "").ifBlank { null },
                    ),
                )
            }
            list
        }.getOrDefault(emptyList())
    }

    private fun saveFavoriteStops(stops: List<FavoriteStop>) {
        val arr = JSONArray()
        for (st in stops) {
            arr.put(
                JSONObject()
                    .put("id", st.stopId)
                    .put("code", st.stopCode)
                    .put("name", st.name)
                    .put("lat", st.lat)
                    .put("lon", st.lon)
                    .put("line", st.lineCode)
                    .put("dir", st.directionId)
                    .put("terminus", st.terminusName.orEmpty()),
            )
        }
        prefs.edit().putString(KEY_FAVORITE_STOPS, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "tobus_torino_prefs"
        private const val KEY_LINE = "selected_line"
        private const val KEY_MAP_STYLE = "map_style"
        private const val KEY_CAMERA_MODE = "camera_mode"
        private const val KEY_FIXED_LAT = "fixed_lat"
        private const val KEY_FIXED_LON = "fixed_lon"
        private const val KEY_FIXED_ZOOM = "fixed_zoom"
        private const val KEY_SHOW_LABELS = "show_vehicle_labels"
        private const val KEY_SHOW_ROUTE = "show_route_line"
        private const val KEY_SHOW_STOPS_MAP = "show_stops_on_map"
        private const val KEY_SHOW_DEPOTS_MAP = "show_depots_on_map"
        private const val KEY_COLOR_BY_DIR = "color_by_direction"
        private const val KEY_ROUTE_COLOR_PALETTE = "route_color_palette"
        private const val KEY_SHOW_ROUTE_WIDGET = "show_route_in_widget"
        private const val KEY_SHOW_FAV_STOPS_WIDGET = "show_fav_stops_in_widget"
        private const val KEY_HIDE_INACTIVE_WIDGET = "hide_inactive_widget"
        private const val KEY_REFRESH_MIN = "refresh_minutes"
        private const val KEY_DATA_SOURCE_MODE = "data_source_mode"
        private const val KEY_LIVE_POLL_SEC = "live_poll_seconds"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_SHOW_SERVICE_ALERTS = "show_service_alerts"
        private const val KEY_NOTIFY_FAVORITE_ALERTS = "notify_favorite_line_alerts"
        private const val KEY_NOTIFY_ALERT_CATEGORIES = "notify_alert_categories_v1"
        private const val KEY_SEEN_ALERT_FINGERPRINTS = "seen_alert_fingerprints_v1"
        private const val KEY_ALERT_BASELINE_SEEDED = "alert_baseline_seeded_v1"
        private const val KEY_HIDE_INACTIVE_MAP = "hide_inactive_map"
        private const val KEY_FAVORITE_STOPS = "favorite_stops_v1"
        private const val KEY_FAVORITE_LINES = "favorite_lines_v1"
        private const val KEY_STOP_WIDGET_IDX = "stop_widget_fav_idx"
        private const val KEY_GITHUB_REPO = "github_update_repo"
        private const val KEY_LAST_UPDATE_CHECK_MS = "github_last_update_check_ms"
        private const val KEY_LAST_WIDGET_REFRESH_MS = "last_widget_refresh_attempt_ms"
        private const val KEY_LAST_WIDGET_RENDERED_LINE = "last_widget_rendered_line"
        private const val KEY_LAST_WIDGET_SUCCESS_MS = "last_widget_success_ms"
        private const val KEY_DISMISSED_UPDATE_TAG = "github_dismissed_update_tag"
        private const val MAX_FAVORITES = 6
        private const val MAX_FAVORITE_LINES = 12
        private const val MAX_SEEN_ALERT_FINGERPRINTS = 600
        private const val SEEN_ALERT_RETENTION_MS = 90L * 24L * 60L * 60L * 1_000L
        private val seenAlertsLock = Any()

        @Volatile
        private var instance: ToBusPreferences? = null

        fun getInstance(context: Context): ToBusPreferences {
            return instance ?: synchronized(this) {
                instance ?: ToBusPreferences(context).also { instance = it }
            }
        }
    }
}
