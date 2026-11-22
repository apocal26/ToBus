package com.example.ui.viewmodel

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.BusVehicle
import com.example.data.model.CameraState
import com.example.data.model.DataSourceStatus
import com.example.data.model.FavoriteStop
import com.example.data.model.LineSummary
import com.example.data.model.MapStyle
import com.example.data.model.RefreshInterval
import com.example.data.model.RoutePattern
import com.example.data.model.ServiceAlertInfo
import com.example.data.model.StopArrival
import com.example.data.model.StopSearchEntry
import com.example.data.model.TransitStop
import com.example.data.model.UserWidgetSettings
import com.example.data.model.WidgetCameraMode
import com.example.data.model.formatTurinLabel
import com.example.data.model.shortTurinPlaceName
import com.example.data.network.GttRealtimeRepository
import com.example.data.network.GttStaticRouteStore
import com.example.data.preferences.ToBusPreferences
import com.example.map.TransitMapBitmapRenderer
import com.example.map.WebMercator
import com.example.widget.BusMapWidgetProvider
import com.example.widget.StopArrivalsWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class WidgetPreviewSize(
    val labelIt: String,
    val widthDp: Int,
    val heightDp: Int,
    val isSquare: Boolean,
) {
    SQUARE_2X2("2×2", 176, 176, true),
    WIDE_4X2("4×2", 320, 168, false),
    TABLET_5X3("5×3", 380, 224, false),
    STOPS_LIVE("Fermate", 340, 178, false),
}

data class UserLocationState(
    val lat: Double,
    val lon: Double,
    val accuracyMeters: Float,
    val updatedAtMillis: Long,
)

data class CameraFlyRequest(
    val target: CameraState,
    val id: Long,
)

data class ToBusUiState(
    val settings: UserWidgetSettings = UserWidgetSettings(),
    val camera: CameraState = CameraState(),
    val cameraFlyRequest: CameraFlyRequest? = null,
    val userLocation: UserLocationState? = null,
    val isUserLocationEnabled: Boolean = false,
    val isLocatingUser: Boolean = false,
    val lineVehicles: List<BusVehicle> = emptyList(),
    val allActiveLines: List<LineSummary> = emptyList(),
    val routePatterns: List<RoutePattern> = emptyList(),
    val stopNames: Map<String, String> = emptyMap(),
    val sourceStatuses: List<DataSourceStatus> = emptyList(),
    val allAlerts: List<ServiceAlertInfo> = emptyList(),
    val lineAlerts: List<ServiceAlertInfo> = emptyList(),
    val selectedVehicleId: String? = null,
    val focusedExternalVehicle: BusVehicle? = null,
    val focusedExternalTerminus: String? = null,
    val focusedExternalRoutePatterns: List<RoutePattern> = emptyList(),
    val selectedStop: TransitStop? = null,
    val stopArrivals: List<StopArrival> = emptyList(),
    val isLoadingStopArrivals: Boolean = false,
    val stopSearchResults: List<StopSearchEntry> = emptyList(),
    val isLoadingStopIndex: Boolean = false,
    val favoriteStops: List<FavoriteStop> = emptyList(),
    val stopWidgetPreviewByStopId: Map<String, List<StopArrival>> = emptyMap(),
    val isLoadingStopWidgetPreview: Boolean = false,
    val isLoadingFeed: Boolean = true,
    val isRefreshing: Boolean = false,
    val lastUpdatedMillis: Long? = null,
    val feedTimestampSeconds: Long = 0L,
    val nowMillis: Long = System.currentTimeMillis(),
    val nextPollProgress: Float = 0f,
    val errorMessage: String? = null,
    val totalCityVehicles: Int = 0,
    val previewSize: WidgetPreviewSize = WidgetPreviewSize.SQUARE_2X2,
    val widgetPreviewBitmap: Bitmap? = null,
    val isRenderingPreview: Boolean = false,
    val statusToast: String? = null,
) {
    val selectedVehicle: BusVehicle?
        get() = lineVehicles.firstOrNull { it.vehicleId == selectedVehicleId }
            ?: focusedExternalVehicle?.takeIf { it.vehicleId == selectedVehicleId }

    val onlineSourcesCount: Int
        get() = sourceStatuses.count { it.isOnline }

    val favoriteStopIds: Set<String>
        get() = favoriteStops.map { it.stopId }.toSet()

    fun isFavoriteStop(stopId: String): Boolean =
        favoriteStops.any { it.stopId.equals(stopId, ignoreCase = true) }

    fun terminusForDirection(directionId: Int?): String? {
        if (directionId == null) return null
        val exact = routePatterns.firstOrNull { it.directionId == directionId }
            ?: routePatterns.getOrNull(directionId)
        return exact?.terminusName()
    }

    fun stopNameForId(rawId: String?): String? {
        val key = rawId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        routePatterns.forEach { pattern ->
            val match = pattern.stops.firstOrNull {
                it.stopId.equals(key, ignoreCase = true) || it.stopCode.equals(key, ignoreCase = true)
            }
            if (match != null && match.name.isNotBlank()) {
                return formatTurinLabel(match.name)
            }
        }
        return stopNames[key]?.takeIf { it.isNotBlank() }?.let(::formatTurinLabel)
    }
}

class ToBusViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val preferences = ToBusPreferences.getInstance(appContext)
    private val repository = GttRealtimeRepository.getInstance(appContext)

    private val _uiState = MutableStateFlow(
        ToBusUiState(
            settings = preferences.currentSettings(),
            camera = preferences.currentSettings().fixedCamera,
            favoriteStops = preferences.currentFavoriteStops(),
        ),
    )
    val uiState: StateFlow<ToBusUiState> = _uiState.asStateFlow()

    private var hasAutoCenteredCurrentLine = false
    private var needsRouteRefit = true
    private var hasVerifiedLiveRouteForLine: String? = null
    private var isStudioTabActive = false
    private var previewDirty = true
    private var lastViewportPx: Pair<Int, Int> = 1000 to 1400
    private var previewRenderJob: Job? = null
    private var stopPreviewJob: Job? = null
    private var routeLoadJob: Job? = null
    private var stopArrivalsJob: Job? = null
    private var focusArrivalJob: Job? = null
    private var stopSearchJob: Job? = null
    private var foregroundPollingJob: Job? = null
    private var clockTickerJob: Job? = null
    private var lastPollElapsedMs: Long = 0L
    private var lastWidgetSyncMillis: Long = 0L
    private var cameraFlySeq: Long = 0L

    private val locationManager: LocationManager? =
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private var activeLocationListener: LocationListener? = null
    private var currentLocationCancelSignal: CancellationSignal? = null
    private var pendingCenterOnNextFix: Boolean = false

    init {
        loadRouteGeometry(_uiState.value.settings.selectedLine)
        // Warm static GTFS zip silently in background so future line switches are instant (~100ms)
        viewModelScope.launch(Dispatchers.IO) {
            repository.prefetchStaticGtfsInBackground()
        }
    }

    /**
     * Energy-efficient lifecycle controller:
     * Starts polling, freshness clock & optional GPS ONLY while app is visible (ON_START),
     * and cancels them immediately in background (ON_STOP) so battery drain is ~0%.
     */
    fun setForegroundActive(active: Boolean) {
        if (active) {
            // Re-arm periodic widget updates in case Android cleared alarms after reboot or update
            BusMapWidgetProvider.schedulePeriodicUpdates(appContext, _uiState.value.settings.refreshInterval)
            repository.startMatoLiveStream(_uiState.value.settings.selectedLine) { onMatoLivePush() }
            startForegroundJobs()
            if (_uiState.value.isUserLocationEnabled && hasLocationPermission()) {
                startLocationUpdates(centerOnNextFix = false)
            }
        } else {
            repository.stopMatoLiveStream()
            foregroundPollingJob?.cancel()
            foregroundPollingJob = null
            clockTickerJob?.cancel()
            clockTickerJob = null
            focusArrivalJob?.cancel()
            focusArrivalJob = null
            stopLocationUpdates()
        }
    }

    fun setStudioTabActive(active: Boolean) {
        isStudioTabActive = active
        if (active && (previewDirty || _uiState.value.widgetPreviewBitmap == null)) {
            previewDirty = false
            regenerateWidgetPreview(force = true)
        }
    }

    override fun onCleared() {
        repository.stopMatoLiveStream()
        stopLocationUpdates()
        super.onCleared()
    }

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun onLocationPermissionResult(granted: Boolean) {
        if (granted) {
            enableAndCenterUserLocation()
        } else {
            _uiState.update {
                it.copy(
                    isUserLocationEnabled = false,
                    isLocatingUser = false,
                    userLocation = null,
                    statusToast = "Permesso posizione non concesso",
                )
            }
        }
    }

    /**
     * Discreet 3-state GPS toggle when permission is already granted:
     * 1. Off -> turn ON and smoothly fly to user position
     * 2. On & map panned away -> smoothly re-center on user position
     * 3. On & already centered on user -> turn OFF cleanly to save battery
     */
    fun onUserLocationButtonClicked() {
        if (!hasLocationPermission()) return
        val state = _uiState.value
        if (!state.isUserLocationEnabled) {
            enableAndCenterUserLocation()
            return
        }

        val loc = state.userLocation
        if (loc == null) {
            startLocationUpdates(centerOnNextFix = true)
            return
        }

        val distFromCenter = haversineMeters(
            state.camera.centerLat,
            state.camera.centerLon,
            loc.lat,
            loc.lon,
        )
        if (distFromCenter > 85.0 || state.camera.zoom < 14.5) {
            flyCameraToUser(loc, announceNearestStop = true)
        } else {
            stopLocationUpdates()
            _uiState.update {
                it.copy(
                    isUserLocationEnabled = false,
                    isLocatingUser = false,
                    userLocation = null,
                    statusToast = "Posizione GPS disattivata",
                )
            }
        }
    }

    private fun enableAndCenterUserLocation() {
        val lm = locationManager
        if (lm == null) {
            _uiState.update { it.copy(statusToast = "Servizio GPS non disponibile") }
            return
        }
        val anyEnabled = runCatching {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
        if (!anyEnabled) {
            _uiState.update {
                it.copy(
                    isUserLocationEnabled = false,
                    isLocatingUser = false,
                    statusToast = "Attiva la geolocalizzazione sul dispositivo",
                )
            }
            return
        }

        _uiState.update { it.copy(isUserLocationEnabled = true, isLocatingUser = true) }
        startLocationUpdates(centerOnNextFix = true)
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates(centerOnNextFix: Boolean) {
        if (!hasLocationPermission()) return
        val lm = locationManager ?: return
        stopLocationUpdates()
        pendingCenterOnNextFix = centerOnNextFix

        // 1. Check freshest cached system fix for instant feedback
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        val bestLastKnown = providers
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .filter { loc -> System.currentTimeMillis() - loc.time <= 10 * 60_000L }
            .maxByOrNull { it.time }

        if (bestLastKnown != null) {
            handleNewLocation(bestLastKnown, fromFreshFix = false)
        }

        // 2. Request lightweight foreground updates (stopped immediately on ON_STOP)
        val listener = LocationListener { location ->
            handleNewLocation(location, fromFreshFix = true)
        }
        activeLocationListener = listener

        val primaryProviders = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) }.getOrDefault(false)
        ) {
            listOf(LocationManager.FUSED_PROVIDER)
        } else {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        }

        primaryProviders.forEach { provider ->
            runCatching {
                lm.requestLocationUpdates(provider, 4_000L, 6f, listener, Looper.getMainLooper())
            }
        }

        // 3. Kick off a single immediate fresh fix on API 30+ if no recent lastKnown fix exists
        if (bestLastKnown == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val fastProvider = primaryProviders.firstOrNull() ?: return
            val cancel = CancellationSignal()
            currentLocationCancelSignal = cancel
            runCatching {
                lm.getCurrentLocation(fastProvider, cancel, appContext.mainExecutor) { loc ->
                    if (loc != null) {
                        handleNewLocation(loc, fromFreshFix = true)
                    } else if (_uiState.value.userLocation == null) {
                        _uiState.update { it.copy(isLocatingUser = false) }
                    }
                }
            }
        }
    }

    private fun stopLocationUpdates() {
        currentLocationCancelSignal?.cancel()
        currentLocationCancelSignal = null
        activeLocationListener?.let { listener ->
            runCatching { locationManager?.removeUpdates(listener) }
        }
        activeLocationListener = null
    }

    private fun handleNewLocation(location: Location, fromFreshFix: Boolean) {
        val stateLoc = UserLocationState(
            lat = location.latitude,
            lon = location.longitude,
            accuracyMeters = location.accuracy.coerceIn(5f, 300f),
            updatedAtMillis = System.currentTimeMillis(),
        )
        _uiState.update {
            it.copy(
                userLocation = stateLoc,
                isLocatingUser = if (fromFreshFix) false else it.isLocatingUser && pendingCenterOnNextFix,
            )
        }
        if (pendingCenterOnNextFix) {
            pendingCenterOnNextFix = false
            flyCameraToUser(stateLoc, announceNearestStop = true)
            _uiState.update { it.copy(isLocatingUser = false) }
        }
    }

    private fun flyCameraToUser(loc: UserLocationState, announceNearestStop: Boolean) {
        val currentZoom = _uiState.value.camera.zoom
        val target = CameraState(
            centerLat = loc.lat.coerceIn(44.85, 45.30),
            centerLon = loc.lon.coerceIn(7.35, 8.05),
            zoom = currentZoom.coerceAtLeast(15.6).coerceAtMost(CameraState.MAX_ZOOM),
        )
        val nearestToast = if (announceNearestStop) {
            val stops = _uiState.value.routePatterns.flatMap { it.stops }
            val nearest = stops.minByOrNull { haversineMeters(loc.lat, loc.lon, it.lat, it.lon) }
            if (nearest != null) {
                val meters = haversineMeters(loc.lat, loc.lon, nearest.lat, nearest.lon).toInt()
                if (meters <= 500) {
                    "Fermata vicina: ${shortTurinPlaceName(nearest.name)} (${meters} m)"
                } else {
                    null
                }
            } else {
                null
            }
        } else {
            null
        }
        cameraFlySeq++
        _uiState.update {
            it.copy(
                cameraFlyRequest = CameraFlyRequest(target = target, id = cameraFlySeq),
                statusToast = nearestToast ?: it.statusToast,
            )
        }
    }

    private fun startForegroundJobs() {
        if (foregroundPollingJob?.isActive != true) {
            foregroundPollingJob = viewModelScope.launch {
                while (isActive) {
                    refreshLiveData(isInitial = _uiState.value.lastUpdatedMillis == null)
                    val startMs = System.currentTimeMillis()
                    lastPollElapsedMs = startMs
                    while (isActive) {
                        val elapsed = System.currentTimeMillis() - startMs
                        val progress = (elapsed.toFloat() / POLL_INTERVAL_MS.toFloat()).coerceIn(0f, 1f)
                        _uiState.update { it.copy(nextPollProgress = progress) }
                        if (elapsed >= POLL_INTERVAL_MS) break
                        delay(1000L)
                    }
                }
            }
        }
        if (clockTickerJob?.isActive != true) {
            clockTickerJob = viewModelScope.launch {
                while (isActive) {
                    _uiState.update { it.copy(nowMillis = System.currentTimeMillis()) }
                    // Tick every 1s when inspecting a bus or stop so ages/countdowns update live, else every 5s
                    val interval = if (_uiState.value.selectedVehicleId != null || _uiState.value.selectedStop != null) {
                        1000L
                    } else {
                        5000L
                    }
                    delay(interval)
                }
            }
        }
    }

    fun refreshLiveData(isInitial: Boolean = false) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingFeed = isInitial && it.lastUpdatedMillis == null,
                    isRefreshing = true,
                    nextPollProgress = 0f,
                )
            }

            // Ensure route geometry retries automatically if initial fetch hadn't completed yet
            val currentLine = _uiState.value.settings.selectedLine
            if (_uiState.value.routePatterns.isEmpty() && routeLoadJob?.isActive != true) {
                loadRouteGeometry(currentLine)
            }

            val result = repository.fetchLiveVehicles(
                includeSecondaryFeeds = true,
                focusLine = currentLine,
            )
            result.fold(
                onSuccess = { snapshot ->
                    val currentSettings = _uiState.value.settings
                    val line = currentSettings.selectedLine.trim().uppercase()
                    val filtered = snapshot.allVehicles
                        .filter {
                            it.lineCode.equals(line, ignoreCase = true) ||
                                it.rawRouteId.equals(line, ignoreCase = true)
                        }
                        .sortedBy { it.vehicleId }

                    val lineAlerts = filterAlertsForLine(snapshot.alerts, line)

                    val nextCamera = if (!hasAutoCenteredCurrentLine && filtered.isNotEmpty()) {
                        hasAutoCenteredCurrentLine = true
                        if (currentSettings.cameraMode == WidgetCameraMode.FIXED_AREA) {
                            currentSettings.fixedCamera
                        } else {
                            WebMercator.computeAutoFitCamera(
                                vehicles = filtered,
                                patterns = _uiState.value.routePatterns,
                                viewportWidthPx = lastViewportPx.first,
                                viewportHeightPx = lastViewportPx.second,
                            )
                        }
                    } else {
                        _uiState.value.camera
                    }

                    _uiState.update { state ->
                        val updatedExternal = state.focusedExternalVehicle?.let { ext ->
                            snapshot.allVehicles.firstOrNull { it.vehicleId == ext.vehicleId } ?: ext
                        }
                        val keepSelected = state.selectedVehicleId?.takeIf { id ->
                            filtered.any { it.vehicleId == id } || updatedExternal?.vehicleId == id
                        }
                        state.copy(
                            lineVehicles = filtered,
                            allActiveLines = snapshot.availableLines,
                            sourceStatuses = snapshot.sourceStatuses,
                            allAlerts = snapshot.alerts,
                            lineAlerts = lineAlerts,
                            camera = nextCamera,
                            selectedVehicleId = keepSelected,
                            focusedExternalVehicle = if (keepSelected != null && updatedExternal?.vehicleId == keepSelected) updatedExternal else null,
                            focusedExternalTerminus = if (keepSelected != null && updatedExternal?.vehicleId == keepSelected) state.focusedExternalTerminus else null,
                            isLoadingFeed = false,
                            isRefreshing = false,
                            lastUpdatedMillis = snapshot.fetchedAtMillis,
                            feedTimestampSeconds = snapshot.feedTimestampSeconds,
                            nowMillis = System.currentTimeMillis(),
                            totalCityVehicles = snapshot.allVehicles.size,
                            errorMessage = null,
                        )
                    }
                    resolveMissingVehicleStopNames(filtered)
                    // Once live vehicles with tripIds arrive on this line, verify route geometry against
                    // the live tripIds so active Sunday/holiday detours match the street immediately!
                    val liveTripIds = filtered.mapNotNull { it.tripId?.trim()?.takeIf { t -> t.isNotEmpty() } }.toSet()
                    if (liveTripIds.isNotEmpty() && hasVerifiedLiveRouteForLine != line && routeLoadJob?.isActive != true) {
                        hasVerifiedLiveRouteForLine = line
                        loadRouteGeometry(line, activeTripIds = liveTripIds, forceRefresh = true)
                    }
                    regenerateWidgetPreview()
                    val nowSync = System.currentTimeMillis()
                    if (nowSync - lastWidgetSyncMillis >= 60_000L) {
                        lastWidgetSyncMillis = nowSync
                        BusMapWidgetProvider.syncWidgetsWithCachedSnapshot(appContext)
                    }
                    if (_uiState.value.selectedStop != null) {
                        refreshSelectedStopArrivals(silent = true)
                    }
                },
                onFailure = { err ->
                    _uiState.update { state ->
                        state.copy(
                            isLoadingFeed = false,
                            isRefreshing = false,
                            errorMessage = err.message ?: "Errore di connessione ai feed GTT",
                        )
                    }
                    regenerateWidgetPreview()
                },
            )
        }
    }

    private fun onMatoLivePush() {
        viewModelScope.launch(Dispatchers.Default) {
            val snapshot = repository.getLiveMatoOverlaySnapshot() ?: return@launch
            val currentSettings = _uiState.value.settings
            val line = currentSettings.selectedLine.trim().uppercase()
            val filtered = snapshot.allVehicles
                .filter {
                    it.lineCode.equals(line, ignoreCase = true) ||
                        it.rawRouteId.equals(line, ignoreCase = true)
                }
                .sortedBy { it.vehicleId }

            _uiState.update { state ->
                val updatedExternal = state.focusedExternalVehicle?.let { ext ->
                    snapshot.allVehicles.firstOrNull { it.vehicleId == ext.vehicleId } ?: ext
                }
                val keepSelected = state.selectedVehicleId?.takeIf { id ->
                    filtered.any { it.vehicleId == id } || updatedExternal?.vehicleId == id
                }
                state.copy(
                    lineVehicles = filtered,
                    allActiveLines = snapshot.availableLines,
                    sourceStatuses = snapshot.sourceStatuses,
                    selectedVehicleId = keepSelected,
                    focusedExternalVehicle = if (keepSelected != null && updatedExternal?.vehicleId == keepSelected) updatedExternal else null,
                    focusedExternalTerminus = if (keepSelected != null && updatedExternal?.vehicleId == keepSelected) state.focusedExternalTerminus else null,
                    isLoadingFeed = false,
                    lastUpdatedMillis = snapshot.fetchedAtMillis,
                    feedTimestampSeconds = snapshot.feedTimestampSeconds,
                    nowMillis = System.currentTimeMillis(),
                    totalCityVehicles = snapshot.allVehicles.size,
                    errorMessage = if (filtered.isNotEmpty()) null else state.errorMessage,
                )
            }
            resolveMissingVehicleStopNames(filtered)
        }
    }

    fun selectLine(lineCode: String) {
        val cleaned = lineCode.trim().uppercase().ifBlank { return }
        val updated = preferences.updateSettings { it.copy(selectedLine = cleaned) }
        repository.updateMatoLiveLine(cleaned)
        hasAutoCenteredCurrentLine = false
        needsRouteRefit = true
        hasVerifiedLiveRouteForLine = null

        val cachedSnapshot = repository.getLastCachedSnapshot()
        val filtered = cachedSnapshot?.allVehicles
            ?.filter {
                it.lineCode.equals(cleaned, ignoreCase = true) ||
                    it.rawRouteId.equals(cleaned, ignoreCase = true)
            }
            ?.sortedBy { it.vehicleId }
            .orEmpty()

        val allAlerts = cachedSnapshot?.alerts.orEmpty()
        val lineAlerts = filterAlertsForLine(allAlerts, cleaned)

        val nextCamera = if (filtered.isNotEmpty()) {
            hasAutoCenteredCurrentLine = true
            WebMercator.computeAutoFitCamera(
                vehicles = filtered,
                patterns = emptyList(),
                viewportWidthPx = lastViewportPx.first,
                viewportHeightPx = lastViewportPx.second,
            )
        } else {
            _uiState.value.camera
        }

        _uiState.update {
            it.copy(
                settings = updated,
                lineVehicles = filtered,
                allAlerts = allAlerts,
                lineAlerts = lineAlerts,
                routePatterns = emptyList(),
                selectedVehicleId = null,
                focusedExternalVehicle = null,
                focusedExternalTerminus = null,
                focusedExternalRoutePatterns = emptyList(),
                selectedStop = null,
                stopArrivals = emptyList(),
                isLoadingStopArrivals = false,
                camera = nextCamera,
                statusToast = "Linea $cleaned attiva",
            )
        }

        loadRouteGeometry(cleaned)
        resolveMissingVehicleStopNames(filtered)
        regenerateWidgetPreview()
        BusMapWidgetProvider.requestUpdateAllWidgets(appContext)
    }

    private fun filterAlertsForLine(
        alerts: List<ServiceAlertInfo>,
        lineCode: String,
    ): List<ServiceAlertInfo> {
        val target = lineCode.trim().uppercase()
        if (target.isEmpty()) return emptyList()
        val mentionRegex = Regex(
            pattern = "\\b(?:linea|linee)\\b[^.\\n]{0,45}\\b${Regex.escape(target)}\\b",
            option = RegexOption.IGNORE_CASE,
        )
        return alerts.filter { alert ->
            if (alert.affectedLines.isNotEmpty()) {
                alert.affectedLines.any { it.equals(target, ignoreCase = true) }
            } else {
                mentionRegex.containsMatchIn(alert.headerText) ||
                    mentionRegex.containsMatchIn(alert.descriptionText)
            }
        }
    }

    private fun resolveMissingVehicleStopNames(vehicles: List<BusVehicle>) {
        val state = _uiState.value
        val candidateIds = vehicles
            .flatMap { listOfNotNull(it.stopId?.trim(), it.nextStopId?.trim()) }
            .filter { it.isNotEmpty() && state.stopNameForId(it) == null }
            .toSet()
        if (candidateIds.isEmpty()) return

        viewModelScope.launch {
            val resolved = repository.resolveStopNames(candidateIds)
            if (resolved.isNotEmpty()) {
                _uiState.update { current ->
                    current.copy(stopNames = current.stopNames + resolved)
                }
            }
        }
    }

    private fun loadRouteGeometry(
        lineCode: String,
        activeTripIds: Set<String> = emptySet(),
        forceRefresh: Boolean = false,
    ) {
        routeLoadJob?.cancel()
        routeLoadJob = viewModelScope.launch {
            val patterns = repository.fetchRoutePatterns(
                lineCode = lineCode,
                activeTripIds = activeTripIds,
                forceRefresh = forceRefresh,
            )
            if (patterns.isNotEmpty() && _uiState.value.settings.selectedLine.equals(lineCode, ignoreCase = true)) {
                val shouldRefit = needsRouteRefit && _uiState.value.selectedStop == null && _uiState.value.selectedVehicleId == null
                needsRouteRefit = false
                val refitFly = if (shouldRefit) {
                    hasAutoCenteredCurrentLine = true
                    val target = if (_uiState.value.settings.cameraMode == WidgetCameraMode.FIXED_AREA) {
                        _uiState.value.settings.fixedCamera
                    } else {
                        WebMercator.computeAutoFitCamera(
                            vehicles = _uiState.value.lineVehicles,
                            patterns = patterns,
                            viewportWidthPx = lastViewportPx.first,
                            viewportHeightPx = lastViewportPx.second,
                        )
                    }
                    cameraFlySeq++
                    CameraFlyRequest(target = target, id = cameraFlySeq)
                } else {
                    null
                }
                _uiState.update { state ->
                    state.copy(
                        routePatterns = patterns,
                        cameraFlyRequest = refitFly ?: state.cameraFlyRequest,
                    )
                }
                regenerateWidgetPreview()
                BusMapWidgetProvider.requestUpdateAllWidgets(appContext)
            }
        }
    }

    fun updateCamera(camera: CameraState) {
        _uiState.update { it.copy(camera = camera) }
    }

    fun updateViewportSize(widthPx: Int, heightPx: Int) {
        if (widthPx > 0 && heightPx > 0) {
            lastViewportPx = widthPx to heightPx
        }
    }

    fun fitLineInView() {
        val state = _uiState.value
        val next = WebMercator.computeAutoFitCamera(
            vehicles = state.lineVehicles,
            patterns = state.routePatterns,
            viewportWidthPx = lastViewportPx.first,
            viewportHeightPx = lastViewportPx.second,
        )
        _uiState.update { it.copy(camera = next) }
    }

    fun zoomBy(delta: Double) {
        _uiState.update { state ->
            val nextZoom = (state.camera.zoom + delta).coerceIn(CameraState.MIN_ZOOM, CameraState.MAX_ZOOM)
            state.copy(camera = state.camera.copy(zoom = nextZoom))
        }
    }

    fun selectVehicle(vehicle: BusVehicle?) {
        if (vehicle != null) {
            stopArrivalsJob?.cancel()
            focusArrivalJob?.cancel()
        }
        val flyReq = if (vehicle != null) {
            cameraFlySeq++
            val currentCam = _uiState.value.camera
            CameraFlyRequest(
                target = currentCam.copy(
                    centerLat = vehicle.latitude,
                    centerLon = vehicle.longitude,
                    zoom = currentCam.zoom.coerceAtLeast(15.2),
                ),
                id = cameraFlySeq,
            )
        } else {
            null
        }
        _uiState.update { state ->
            val keepExternal = vehicle != null && state.focusedExternalVehicle?.vehicleId == vehicle.vehicleId
            state.copy(
                selectedVehicleId = vehicle?.vehicleId,
                focusedExternalVehicle = if (keepExternal) state.focusedExternalVehicle else null,
                focusedExternalTerminus = if (keepExternal) state.focusedExternalTerminus else null,
                focusedExternalRoutePatterns = if (keepExternal) state.focusedExternalRoutePatterns else emptyList(),
                selectedStop = if (vehicle != null) null else state.selectedStop,
                stopArrivals = if (vehicle != null) emptyList() else state.stopArrivals,
                isLoadingStopArrivals = if (vehicle != null) false else state.isLoadingStopArrivals,
                cameraFlyRequest = flyReq ?: state.cameraFlyRequest,
                nowMillis = System.currentTimeMillis(),
            )
        }
    }

    /**
     * Locates and focuses the EXACT live bus corresponding to an arrival chip tapped inside a stop card
     * (works both for the currently tracked line AND for any other line passing through that stop, e.g. 55 or 5).
     *
     * Strictly uses the TARGET line's own route geometry and direction at this stop — NEVER guesses
     * a random "nearest straight-line bus" or opposite-direction bus.
     */
    fun focusBusForArrival(arrival: StopArrival, @Suppress("UNUSED_PARAMETER") indexInLine: Int) {
        val state = _uiState.value
        val stop = state.selectedStop ?: return
        val targetLine = arrival.lineCode.trim().uppercase()
        val isTrackedLine = targetLine.equals(state.settings.selectedLine, ignoreCase = true)

        // Exclude vehicles already explicitly assigned to OTHER arrival chips of the same line
        val claimedVehicleIds = state.stopArrivals
            .asSequence()
            .filter {
                it !== arrival &&
                    it.lineCode.equals(targetLine, ignoreCase = true) &&
                    !it.vehicleId.isNullOrBlank()
            }
            .mapNotNull { it.vehicleId?.trim()?.takeIf { id -> id.isNotEmpty() } }
            .toSet()

        focusArrivalJob?.cancel()
        focusArrivalJob = viewModelScope.launch {
            val nowMillis = System.currentTimeMillis()
            val allLive = repository.getLastCachedSnapshot()?.allVehicles.orEmpty().ifEmpty { state.lineVehicles }
            val targetLinePatterns = if (isTrackedLine) {
                state.routePatterns
            } else {
                val activeTrips = allLive
                    .asSequence()
                    .filter {
                        it.lineCode.equals(targetLine, ignoreCase = true) ||
                            it.rawRouteId.equals(targetLine, ignoreCase = true)
                    }
                    .mapNotNull { it.tripId?.trim()?.takeIf { tid -> tid.isNotEmpty() } }
                    .toSet()
                withContext(Dispatchers.IO) {
                    repository.getFastRoutePatternsForLine(targetLine, activeTrips)
                }
            }

            // Re-check if user closed/switched stop while loading target line geometry
            if (_uiState.value.selectedStop?.stopId != stop.stopId) return@launch

            val matched = GttRealtimeRepository.findExactBusForArrival(
                arrival = arrival,
                stop = stop,
                allLivePool = allLive,
                targetLinePatterns = targetLinePatterns,
                claimedVehicleIds = claimedVehicleIds,
                nowMillis = System.currentTimeMillis(),
            )

            if (matched == null) {
                val clockStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ITALY)
                    .format(java.util.Date(arrival.arrivalEpochSeconds * 1000L))
                _uiState.update {
                    it.copy(
                        statusToast = if (arrival.isRealtime) {
                            "Posizione GPS esatta di questa corsa (linea $targetLine, $clockStr) non disponibile"
                        } else {
                            "Corsa programmata linea $targetLine ($clockStr) · mezzo non ancora tracciato dal vivo"
                        },
                    )
                }
                return@launch
            }

            stopArrivalsJob?.cancel()
            val currentState = _uiState.value
            val isExternal = currentState.lineVehicles.none { it.vehicleId == matched.vehicleId }
            val resolvedExtDir = if (isExternal && targetLinePatterns.isNotEmpty()) {
                com.example.data.model.GttFleetAnalyzer.resolveDirectionId(matched, targetLinePatterns)
                    ?: matched.directionId
            } else {
                null
            }
            val patternExtTerminus = if (isExternal && resolvedExtDir != null) {
                targetLinePatterns.firstOrNull { it.directionId == resolvedExtDir }?.terminusName()
            } else {
                null
            }
            val cleanHeadsign = arrival.headsign
                .takeIf { it.isNotBlank() && !it.startsWith("Linea ", ignoreCase = true) }
                ?: patternExtTerminus

            cameraFlySeq++
            val targetCam = CameraState(
                centerLat = matched.latitude,
                centerLon = matched.longitude,
                zoom = currentState.camera.zoom.coerceAtLeast(15.6).coerceAtMost(CameraState.MAX_ZOOM),
            )
            _uiState.update { current ->
                current.copy(
                    selectedStop = null,
                    stopArrivals = emptyList(),
                    isLoadingStopArrivals = false,
                    selectedVehicleId = matched.vehicleId,
                    focusedExternalVehicle = if (isExternal) matched else null,
                    focusedExternalTerminus = if (isExternal) cleanHeadsign else null,
                    focusedExternalRoutePatterns = if (isExternal) targetLinePatterns else emptyList(),
                    cameraFlyRequest = CameraFlyRequest(target = targetCam, id = cameraFlySeq),
                    nowMillis = nowMillis,
                    statusToast = "Linea ${matched.lineCode} · Vettura #${matched.label}",
                )
            }
            resolveMissingVehicleStopNames(listOf(matched))
        }
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2.0) * kotlin.math.sin(dLat / 2.0) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2.0) * kotlin.math.sin(dLon / 2.0)
        return r * 2.0 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1.0 - a))
    }

    fun selectStop(stop: TransitStop?) {
        stopArrivalsJob?.cancel()
        focusArrivalJob?.cancel()
        if (stop == null) {
            _uiState.update {
                it.copy(
                    selectedStop = null,
                    stopArrivals = emptyList(),
                    isLoadingStopArrivals = false,
                )
            }
            return
        }

        _uiState.update { state ->
            state.copy(
                selectedVehicleId = null,
                focusedExternalVehicle = null,
                focusedExternalTerminus = null,
                focusedExternalRoutePatterns = emptyList(),
                selectedStop = stop,
                stopArrivals = emptyList(),
                isLoadingStopArrivals = true,
                nowMillis = System.currentTimeMillis(),
            )
        }
        refreshSelectedStopArrivals(silent = false)
    }

    /**
     * Searches all Turin GTT stops locally from the cached GTFS directory (matching stop name,
     * palina number, headsign, and serving lines together so homonymous stops are unambiguous).
     */
    fun searchStops(query: String) {
        stopSearchJob?.cancel()
        stopSearchJob = viewModelScope.launch {
            val state = _uiState.value
            if (state.stopSearchResults.isEmpty()) {
                _uiState.update { it.copy(isLoadingStopIndex = true) }
            }
            val index = withContext(Dispatchers.IO) {
                repository.getStopSearchIndex()
            }
            val current = _uiState.value
            val matches = withContext(Dispatchers.Default) {
                GttStaticRouteStore.searchStops(
                    index = index,
                    query = query,
                    preferredLine = current.settings.selectedLine,
                    userLat = current.userLocation?.lat,
                    userLon = current.userLocation?.lon,
                )
            }
            _uiState.update {
                it.copy(
                    stopSearchResults = matches,
                    isLoadingStopIndex = false,
                )
            }
        }
    }

    /**
     * Opens any stop selected from the global Stop Search sheet, centers the camera on that exact pole,
     * and loads its real-time arrivals without cluttering or unexpectedly replacing the active line.
     */
    fun openSearchedStop(entry: StopSearchEntry) {
        val state = _uiState.value
        val baseStop = entry.toTransitStop(directionId = 0)
        val resolvedDirOnCurrentLine = GttRealtimeRepository.resolveStopDirectionOnLinePatterns(
            stop = baseStop,
            linePatterns = state.routePatterns,
        )
        val stop = baseStop.copy(directionId = resolvedDirOnCurrentLine ?: 0)
        cameraFlySeq++
        val targetCam = CameraState(
            centerLat = stop.lat,
            centerLon = stop.lon,
            zoom = state.camera.zoom.coerceAtLeast(15.6).coerceAtMost(CameraState.MAX_ZOOM),
        )
        _uiState.update { current ->
            current.copy(
                cameraFlyRequest = CameraFlyRequest(target = targetCam, id = cameraFlySeq),
            )
        }
        selectStop(stop)
    }

    fun refreshSelectedStopArrivals(silent: Boolean = false) {
        val stop = _uiState.value.selectedStop ?: return
        val line = _uiState.value.settings.selectedLine
        val liveVehicles = _uiState.value.lineVehicles
        val routePatterns = _uiState.value.routePatterns
        stopArrivalsJob?.cancel()
        stopArrivalsJob = viewModelScope.launch {
            if (!silent) {
                _uiState.update { it.copy(isLoadingStopArrivals = true) }
            }
            val arrivals = repository.fetchStopArrivals(
                stop = stop,
                preferredLine = line,
                liveVehicles = liveVehicles,
                routePatterns = routePatterns,
            )
            if (_uiState.value.selectedStop?.stopId == stop.stopId) {
                _uiState.update {
                    it.copy(
                        stopArrivals = arrivals,
                        isLoadingStopArrivals = false,
                        nowMillis = System.currentTimeMillis(),
                    )
                }
            }
        }
    }

    fun toggleFavoriteStop(stop: TransitStop) {
        val state = _uiState.value
        val fav = FavoriteStop(
            stopId = stop.stopId,
            stopCode = stop.displayCode,
            name = stop.name,
            lat = stop.lat,
            lon = stop.lon,
            lineCode = state.settings.selectedLine,
            directionId = stop.directionId,
            terminusName = state.terminusForDirection(stop.directionId),
        )
        val (updated, added) = preferences.toggleFavoriteStop(fav)
        val label = formatTurinLabel(stop.name)
        _uiState.update {
            it.copy(
                favoriteStops = updated,
                statusToast = if (added) "Fermata \"$label\" salvata nei preferiti" else "Rimossa dai preferiti",
            )
        }
        regenerateWidgetPreview()
        refreshStopWidgetPreview()
        BusMapWidgetProvider.requestUpdateAllWidgets(appContext)
        StopArrivalsWidgetProvider.requestUpdateAll(appContext)
    }

    fun removeFavoriteStop(stopId: String) {
        val updated = preferences.removeFavoriteStop(stopId)
        _uiState.update {
            it.copy(
                favoriteStops = updated,
                statusToast = "Fermata rimossa dai preferiti",
            )
        }
        regenerateWidgetPreview()
        refreshStopWidgetPreview()
        BusMapWidgetProvider.requestUpdateAllWidgets(appContext)
        StopArrivalsWidgetProvider.requestUpdateAll(appContext)
    }

    fun openFavoriteStop(fav: FavoriteStop) {
        val currentLine = _uiState.value.settings.selectedLine
        val needsLineSwitch = fav.lineCode.isNotBlank() && !currentLine.equals(fav.lineCode, ignoreCase = true)
        if (needsLineSwitch) {
            selectLine(fav.lineCode)
        }
        val stop = fav.toTransitStop()
        cameraFlySeq++
        val targetCam = CameraState(
            centerLat = stop.lat,
            centerLon = stop.lon,
            zoom = _uiState.value.camera.zoom.coerceAtLeast(15.4).coerceAtMost(CameraState.MAX_ZOOM),
        )
        _uiState.update { state ->
            state.copy(
                cameraFlyRequest = CameraFlyRequest(target = targetCam, id = cameraFlySeq),
            )
        }
        selectStop(stop)
    }

    fun setMapStyle(style: MapStyle) {
        val updated = preferences.updateSettings { it.copy(mapStyle = style) }
        _uiState.update { it.copy(settings = updated) }
        regenerateWidgetPreview()
        BusMapWidgetProvider.requestUpdateAllWidgets(appContext)
    }

    fun setCameraMode(mode: WidgetCameraMode) {
        val updated = preferences.updateSettings { it.copy(cameraMode = mode) }
        _uiState.update { it.copy(settings = updated) }
        regenerateWidgetPreview()
        BusMapWidgetProvider.requestUpdateAllWidgets(appContext)
    }

    fun saveCurrentMapAsWidgetArea() {
        val currentCam = _uiState.value.camera
        val updated = preferences.updateSettings {
            it.copy(
                cameraMode = WidgetCameraMode.FIXED_AREA,
                fixedCamera = currentCam,
            )
        }
        _uiState.update {
            it.copy(
                settings = updated,
                statusToast = "Inquadratura salvata per il widget",
            )
        }
        regenerateWidgetPreview()
        BusMapWidgetProvider.requestUpdateAllWidgets(appContext)
    }

    fun setShowVehicleLabels(show: Boolean) {
        val updated = preferences.updateSettings { it.copy(showVehicleLabels = show) }
        _uiState.update { it.copy(settings = updated) }
        regenerateWidgetPreview()
        BusMapWidgetProvider.requestUpdateAllWidgets(appContext)
    }

    fun setShowRouteLine(show: Boolean) {
        val updated = preferences.updateSettings { it.copy(showRouteLine = show) }
        _uiState.update { it.copy(settings = updated) }
        regenerateWidgetPreview()
        BusMapWidgetProvider.requestUpdateAllWidgets(appContext)
    }

    fun setColorByDirection(enabled: Boolean) {
        val updated = preferences.updateSettings { it.copy(colorByDirection = enabled) }
        _uiState.update { it.copy(settings = updated) }
        regenerateWidgetPreview()
        BusMapWidgetProvider.requestUpdateAllWidgets(appContext)
    }

    fun setHideInactiveInWidget(hide: Boolean) {
        val updated = preferences.updateSettings { it.copy(hideInactiveInWidget = hide) }
        _uiState.update { it.copy(settings = updated) }
        regenerateWidgetPreview()
        BusMapWidgetProvider.requestUpdateAllWidgets(appContext)
    }

    fun setRefreshInterval(interval: RefreshInterval) {
        val updated = preferences.updateSettings { it.copy(refreshInterval = interval) }
        _uiState.update { it.copy(settings = updated) }
        BusMapWidgetProvider.schedulePeriodicUpdates(appContext, interval)
        BusMapWidgetProvider.requestUpdateAllWidgets(appContext)
    }

    fun setPreviewSize(size: WidgetPreviewSize) {
        _uiState.update { it.copy(previewSize = size) }
        if (size == WidgetPreviewSize.STOPS_LIVE) {
            refreshStopWidgetPreview()
        } else {
            regenerateWidgetPreview()
        }
    }

    fun clearToast() {
        _uiState.update { it.copy(statusToast = null) }
    }

    fun triggerHomeWidgetSync() {
        BusMapWidgetProvider.requestUpdateAllWidgets(appContext)
        StopArrivalsWidgetProvider.requestUpdateAll(appContext)
        if (_uiState.value.previewSize == WidgetPreviewSize.STOPS_LIVE) {
            refreshStopWidgetPreview()
        } else {
            regenerateWidgetPreview()
        }
        _uiState.update { it.copy(statusToast = "Widget sulla Home aggiornati") }
    }

    fun refreshStopWidgetPreview() {
        val visibleFavorites = _uiState.value.favoriteStops.take(3)
        if (visibleFavorites.isEmpty()) {
            _uiState.update { it.copy(stopWidgetPreviewByStopId = emptyMap(), isLoadingStopWidgetPreview = false) }
            return
        }
        stopPreviewJob?.cancel()
        stopPreviewJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingStopWidgetPreview = true) }
            val cachedAll = repository.getLastCachedSnapshot()?.allVehicles ?: _uiState.value.lineVehicles
            val byStopId: Map<String, List<StopArrival>> = coroutineScope {
                visibleFavorites.map { fav ->
                    async {
                        val lineVehicles = cachedAll.filter {
                            it.lineCode.equals(fav.lineCode, ignoreCase = true)
                        }
                        val arrivals = runCatching {
                            repository.fetchStopArrivals(
                                stop = fav.toTransitStop(),
                                preferredLine = fav.lineCode,
                                liveVehicles = lineVehicles,
                            )
                        }.getOrDefault(emptyList())
                        fav.stopId to arrivals
                    }
                }.awaitAll().toMap()
            }
            _uiState.update {
                it.copy(
                    stopWidgetPreviewByStopId = byStopId,
                    isLoadingStopWidgetPreview = false,
                )
            }
        }
    }

    fun regenerateWidgetPreview(force: Boolean = false) {
        if (!force && !isStudioTabActive) {
            previewDirty = true
            return
        }
        previewDirty = false
        if (_uiState.value.previewSize == WidgetPreviewSize.STOPS_LIVE) {
            refreshStopWidgetPreview()
            return
        }
        previewRenderJob?.cancel()
        previewRenderJob = viewModelScope.launch(Dispatchers.IO) {
            val state = _uiState.value
            _uiState.update { it.copy(isRenderingPreview = true) }
            val density = appContext.resources.displayMetrics.density.coerceIn(2.0f, 3.0f)
            val widthPx = (state.previewSize.widthDp * density).toInt()
            val heightPx = (state.previewSize.heightDp * density).toInt()

            val bitmap = runCatching {
                TransitMapBitmapRenderer.renderWidgetMap(
                    context = appContext,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    density = density,
                    settings = state.settings,
                    vehicles = state.lineVehicles,
                    routePatterns = state.routePatterns,
                    favoriteStops = state.favoriteStops,
                    isCompactSquare = state.previewSize.isSquare,
                )
            }.getOrNull()

            _uiState.update {
                it.copy(
                    widgetPreviewBitmap = bitmap ?: it.widgetPreviewBitmap,
                    isRenderingPreview = false,
                )
            }
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 25_000L
    }
}
