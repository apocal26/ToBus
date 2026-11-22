package com.example.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.example.data.model.CameraState
import com.example.data.model.FavoriteStop
import com.example.data.model.MapStyle
import com.example.data.model.RefreshInterval
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

    fun currentSettings(): UserWidgetSettings = readSettings().also { _settings.value = it }

    fun currentFavoriteStops(): List<FavoriteStop> = readFavoriteStops().also { _favoriteStops.value = it }

    fun updateSettings(transform: (UserWidgetSettings) -> UserWidgetSettings): UserWidgetSettings {
        val updated = transform(readSettings())
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

    private fun readSettings(): UserWidgetSettings {
        return UserWidgetSettings(
            selectedLine = prefs.getString(KEY_LINE, "12")?.ifBlank { "12" } ?: "12",
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
            showVehicleLabels = prefs.getBoolean(KEY_SHOW_LABELS, true),
            showRouteLine = prefs.getBoolean(KEY_SHOW_ROUTE, true),
            colorByDirection = prefs.getBoolean(KEY_COLOR_BY_DIR, true),
            hideInactiveInWidget = prefs.getBoolean(KEY_HIDE_INACTIVE_WIDGET, true),
            refreshInterval = RefreshInterval.fromMinutes(prefs.getInt(KEY_REFRESH_MIN, 5)),
        )
    }

    private fun saveSettings(s: UserWidgetSettings) {
        prefs.edit()
            .putString(KEY_LINE, s.selectedLine)
            .putString(KEY_MAP_STYLE, s.mapStyle.id)
            .putString(KEY_CAMERA_MODE, s.cameraMode.id)
            .putLong(KEY_FIXED_LAT, java.lang.Double.doubleToRawLongBits(s.fixedCamera.centerLat))
            .putLong(KEY_FIXED_LON, java.lang.Double.doubleToRawLongBits(s.fixedCamera.centerLon))
            .putLong(KEY_FIXED_ZOOM, java.lang.Double.doubleToRawLongBits(s.fixedCamera.zoom))
            .putBoolean(KEY_SHOW_LABELS, s.showVehicleLabels)
            .putBoolean(KEY_SHOW_ROUTE, s.showRouteLine)
            .putBoolean(KEY_COLOR_BY_DIR, s.colorByDirection)
            .putBoolean(KEY_HIDE_INACTIVE_WIDGET, s.hideInactiveInWidget)
            .putInt(KEY_REFRESH_MIN, s.refreshInterval.minutes)
            .apply()
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
                list.add(
                    FavoriteStop(
                        stopId = stopId,
                        stopCode = obj.optString("code", stopId),
                        name = obj.optString("name", "Fermata $stopId"),
                        lat = obj.optDouble("lat", CameraState.TURIN_CENTER_LAT),
                        lon = obj.optDouble("lon", CameraState.TURIN_CENTER_LON),
                        lineCode = obj.optString("line", "12"),
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
        private const val KEY_COLOR_BY_DIR = "color_by_direction"
        private const val KEY_HIDE_INACTIVE_WIDGET = "hide_inactive_widget"
        private const val KEY_REFRESH_MIN = "refresh_minutes"
        private const val KEY_FAVORITE_STOPS = "favorite_stops_v1"
        private const val KEY_STOP_WIDGET_IDX = "stop_widget_fav_idx"
        private const val MAX_FAVORITES = 6

        @Volatile
        private var instance: ToBusPreferences? = null

        fun getInstance(context: Context): ToBusPreferences {
            return instance ?: synchronized(this) {
                instance ?: ToBusPreferences(context).also { instance = it }
            }
        }
    }
}
