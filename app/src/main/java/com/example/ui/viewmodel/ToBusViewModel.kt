package com.example.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.BusVehicle
import com.example.data.model.CameraState
import com.example.data.model.DataSourceMode
import com.example.data.model.DataSourceStatus
import com.example.data.model.FavoriteStop
import com.example.data.model.GttDataOutageReport
import com.example.data.model.GttDepot
import com.example.data.model.GttFleetAnalyzer
import com.example.data.model.LineSummary
import com.example.data.model.LivePollInterval
import com.example.data.model.MapStyle
import com.example.data.model.RefreshInterval
import com.example.data.model.RouteColorPalette
import com.example.data.model.RoutePattern
import com.example.data.model.ServiceAlertCategory
import com.example.data.model.ServiceAlertInfo
import com.example.data.model.ServiceAlertOrganizer
import com.example.data.model.StopArrival
import com.example.data.model.StopSearchEntry
import com.example.data.model.TransitStop
import com.example.data.model.UserWidgetSettings
import com.example.data.model.WidgetCameraMode
import com.example.data.model.formatTurinLabel
import com.example.data.network.AppUpdateInfo
import com.example.data.network.GttRealtimeRepository
import com.example.data.network.GttStaticRouteStore
import com.example.data.network.UpdateInstallState
import com.example.data.preferences.ToBusPreferences
import com.example.map.WebMercator
import com.example.notifications.FavoriteAlertNotifier
import com.example.notifications.FavoriteAlertsScheduler
import com.example.widget.BusMapWidgetProvider
import com.example.widget.FavoriteAlertsWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

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
    ALERTS_LIVE("Avvisi", 340, 178, false),
}

@androidx.compose.runtime.Immutable
data class UserLocationState(
    val lat: Double,
    val lon: Double,
    val accuracyMeters: Float,
    val updatedAtMillis: Long,
)

@androidx.compose.runtime.Immutable
data class CameraFlyRequest(
    val target: CameraState,
    val id: Long,
    val instant: Boolean = false,
)

@androidx.compose.runtime.Immutable
data class LinePickerUiState(
    val selectedLine: String = "",
    val activeLines: List<LineSummary> = emptyList(),
    val favoriteLines: List<String> = emptyList(),
    val stopSearchResults: List<StopSearchEntry> = emptyList(),
    val isLoadingStopIndex: Boolean = false,
    val isLoadingLines: Boolean = false,
    val lineCatalogError: String? = null,
    val userLocation: UserLocationState? = null,
)

@androidx.compose.runtime.Immutable
data class ToBusUiState(
    val settings: UserWidgetSettings = UserWidgetSettings(),
    val camera: CameraState = CameraState(),
    val cameraFlyRequest: CameraFlyRequest? = null,
    val lineSelectionEpoch: Long = 0L,
    val userLocation: UserLocationState? = null,
    val isUserLocationEnabled: Boolean = false,
    val isLocatingUser: Boolean = false,
    val lineVehicles: List<BusVehicle> = emptyList(),
    val allActiveLines: List<LineSummary> = emptyList(),
    val routePatterns: List<RoutePattern> = emptyList(),
    val depotZones: List<GttDepot> = emptyList(),
    val stopNames: Map<String, String> = emptyMap(),
    val sourceStatuses: List<DataSourceStatus> = emptyList(),
    val allAlerts: List<ServiceAlertInfo> = emptyList(),
    val lineAlerts: List<ServiceAlertInfo> = emptyList(),
    val isRefreshingAlerts: Boolean = false,
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
    val favoriteStopIds: Set<String> = emptySet(),
    val favoriteLines: List<String> = emptyList(),
    val stopWidgetPreviewByStopId: Map<String, List<StopArrival>> = emptyMap(),
    val isLoadingStopWidgetPreview: Boolean = false,
    val isLoadingFeed: Boolean = true,
    val isLoadingInitialRoute: Boolean = false,
    val isLoadingLines: Boolean = false,
    val lineCatalogError: String? = null,
    val isRefreshing: Boolean = false,
    val lastUpdatedMillis: Long? = null,
    val feedTimestampSeconds: Long = 0L,
    val nowMillis: Long = System.currentTimeMillis(),
    val errorMessage: String? = null,
    val gttDataOutage: GttDataOutageReport? = null,
    val isGttOutageDismissed: Boolean = false,
    val totalCityVehicles: Int = 0,
    val previewSize: WidgetPreviewSize = WidgetPreviewSize.SQUARE_2X2,
    val widgetPreviewBitmap: Bitmap? = null,
    val isRenderingPreview: Boolean = false,
    val statusToast: String? = null,
    val availableUpdate: AppUpdateInfo? = null,
    val updateInstallState: UpdateInstallState = UpdateInstallState.Idle,
    val isCheckingUpdate: Boolean = false,
    val githubRepoSlug: String = "",
) {
    val selectedVehicle: BusVehicle?
        get() = lineVehicles.firstOrNull { it.vehicleId == selectedVehicleId }
            ?: focusedExternalVehicle?.takeIf { it.vehicleId == selectedVehicleId }

    val onlineSourcesCount: Int
        get() = sourceStatuses.count { it.isOnline }

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

    private val initialSettings = preferences.currentSettings()
    private val initialFavoriteStops = preferences.currentFavoriteStops()
    private val _uiState = MutableStateFlow(
        ToBusUiState(
            settings = initialSettings,
            camera = initialSettings.fixedCamera,
            favoriteStops = initialFavoriteStops,
            favoriteStopIds = initialFavoriteStops.map { it.stopId }.toSet(),
            favoriteLines = preferences.currentFavoriteLines(),
            isLoadingInitialRoute = initialSettings.hasSelectedLine,
            githubRepoSlug = preferences.getGithubRepo(),
        ),
    )
    val uiState: StateFlow<ToBusUiState> = _uiState.asStateFlow()

    // Dedicated slice for LinePickerSheet so background map/clock/telemetry updates never trigger
    // sheet recompositions during list flings on low-end devices.
    private val _linePickerState = MutableStateFlow(
        LinePickerUiState(
            selectedLine = initialSettings.selectedLine,
            activeLines = emptyList(),
            favoriteLines = _uiState.value.favoriteLines,
            stopSearchResults = emptyList(),
            isLoadingStopIndex = false,
            userLocation = null,
        ),
    )
    val linePickerState: StateFlow<LinePickerUiState> = _linePickerState.asStateFlow()

    private val alertMentionRegexCache = ConcurrentHashMap<String, Regex>()

    // Isolated 1Hz poll countdown flow so RefreshProgressGlassButton animates independently
    // without forcing full-screen recompositions on MapScreen / LinePickerSheet every second.
    private val _pollProgress = MutableStateFlow(0f)
    val pollProgress: StateFlow<Float> = _pollProgress.asStateFlow()

    private var hasAutoCenteredCurrentLine = false
    private var needsRouteRefit = true
    private var hasVerifiedLiveRouteForLine: String? = null
    private var hasRealViewportSize = false
    private var pendingViewportRouteRefit = false
    @Volatile
    private var isLinePickerOpen = false
    @Volatile
    private var pendingMatoSyncOnPickerClose = false
    private var consecutivePollFailures = 0
    private var lastViewportDp: Pair<Int, Int> = 390 to 820
    private var routeLoadJob: Job? = null
    private var routeLoadSeq: Long = 0L
    private var lineSwitchEpoch: Long = 0L
    private var refreshLiveJob: Job? = null
    private var liveRefreshSeq: Long = 0L
    private var lineCatalogJob: Job? = null
    private var stopArrivalsJob: Job? = null
    private var focusArrivalJob: Job? = null
    private var stopSearchJob: Job? = null
    private var foregroundPollingJob: Job? = null
    private var clockTickerJob: Job? = null
    private var depotLoadJob: Job? = null
    private var lastDepotAttemptMillis: Long = 0L
    private var lastWidgetSyncMillis: Long = 0L
    private var cameraFlySeq: Long = 0L
    private var dismissedOutageStableKey: String? = null

    private val locationController = MapLocationController(
        appContext = appContext,
        uiState = _uiState,
        syncLinePickerState = ::syncLinePickerState,
        nextCameraFlyId = { ++cameraFlySeq },
    )
    private val updateCoordinator = AppUpdateCoordinator(
        appContext = appContext,
        preferences = preferences,
        scope = viewModelScope,
        uiState = _uiState,
    )
    private val widgetCoordinator = WidgetPreviewCoordinator(
        appContext = appContext,
        repository = repository,
        scope = viewModelScope,
        uiState = _uiState,
    )

    init {
        if (initialSettings.hasSelectedLine) {
            loadRouteGeometry(initialSettings.selectedLine, isInitialRouteLoad = true)
        }
        // Hydrate verified OSM GTT depot compound polygons (instant from 30-day disk cache, or Overpass fetch).
        ensureDepotsLoadedIfMissing(forceImmediate = true)
        // Hydrate active line list immediately from tiny disk cache or static GTFS routes.txt (~1.5 KB)
        // for instant Line Picker before the first live HTTP poll completes.
        viewModelScope.launch(Dispatchers.IO) {
            val cachedLines = repository.getCachedActiveLinesFast()
            if (cachedLines.isNotEmpty()) {
                _uiState.update { state ->
                    if (state.allActiveLines.size <= 1) state.copy(allActiveLines = cachedLines) else state
                }
                syncLinePickerState()
            } else if (!initialSettings.hasSelectedLine) {
                ensureLineCatalogLoaded()
            }
        }
        // Stagger static GTFS zip & stop index warmup after the initial 2.5s cold-start burst
        // so MapLibre, route polyline, and the first live feed get 100% of CPU & I/O headroom.
        viewModelScope.launch(Dispatchers.IO) {
            delay(2_500L)
            repository.prefetchStaticGtfsInBackground()
            val staticCatalog = runCatching { repository.getKnownStaticLineCatalog() }.getOrDefault(emptyList())
            if (staticCatalog.isNotEmpty()) {
                _uiState.update { state ->
                    val merged = GttRealtimeRepository.mergeLiveSummariesWithStaticCatalog(
                        liveSummaries = state.allActiveLines,
                        staticCatalog = staticCatalog,
                    )
                    if (merged != state.allActiveLines) {
                        state.copy(allActiveLines = merged)
                    } else {
                        state
                    }
                }
                syncLinePickerState()
            }
            runCatching { repository.getStopSearchIndex() }
            if (_uiState.value.allActiveLines.size <= 1) {
                ensureLineCatalogLoaded()
            }
        }
        // Keep background favorite-line alerts JobScheduler synced with persisted opt-in state.
        viewModelScope.launch(Dispatchers.IO) {
            FavoriteAlertsScheduler.syncSchedule(
                context = appContext,
                enabled = initialSettings.notifyFavoriteLineAlerts,
                hasFavoriteLines = preferences.currentFavoriteLines().isNotEmpty(),
            )
        }
        updateCoordinator.scheduleStartupCheck()
    }

    private fun syncLinePickerState() {
        val s = _uiState.value
        val next = LinePickerUiState(
            selectedLine = s.settings.selectedLine,
            activeLines = s.allActiveLines,
            favoriteLines = s.favoriteLines,
            stopSearchResults = s.stopSearchResults,
            isLoadingStopIndex = s.isLoadingStopIndex,
            isLoadingLines = s.isLoadingLines,
            lineCatalogError = s.lineCatalogError,
            userLocation = s.userLocation,
        )
        if (_linePickerState.value != next) {
            _linePickerState.value = next
        }
    }

    /**
     * Energy-efficient lifecycle controller:
     * Starts polling, freshness clock & optional GPS ONLY while app is visible (ON_START),
     * and cancels them immediately in background (ON_STOP) so battery drain is ~0%.
     */
    fun setForegroundActive(active: Boolean) {
        if (active) {
            val currentSettings = _uiState.value.settings
            val interval = currentSettings.refreshInterval
            viewModelScope.launch(Dispatchers.IO) {
                BusMapWidgetProvider.schedulePeriodicUpdates(appContext, interval)
            }
            if (currentSettings.hasSelectedLine && GttRealtimeRepository.shouldRunMatoStream(currentSettings.dataSourceMode)) {
                repository.startMatoLiveStream(currentSettings.selectedLine) { onMatoLivePush() }
            } else {
                repository.stopMatoLiveStream()
            }
            startForegroundJobs()
            locationController.resumeIfEnabled()
        } else {
            repository.stopMatoLiveStream()
            foregroundPollingJob?.cancel()
            foregroundPollingJob = null
            clockTickerJob?.cancel()
            clockTickerJob = null
            focusArrivalJob?.cancel()
            focusArrivalJob = null
            locationController.stopLocationUpdates()
        }
    }

    fun setStudioTabActive(active: Boolean) {
        widgetCoordinator.setStudioTabActive(active)
    }

    fun setLinePickerOpen(open: Boolean) {
        if (isLinePickerOpen == open) return
        isLinePickerOpen = open
        if (open) {
            if (_uiState.value.allActiveLines.size <= 1) {
                ensureLineCatalogLoaded()
            }
        } else {
            if (pendingMatoSyncOnPickerClose) {
                pendingMatoSyncOnPickerClose = false
                onMatoLivePush()
            } else {
                _uiState.update { it.copy(nowMillis = System.currentTimeMillis()) }
            }
        }
    }

    fun ensureLineCatalogLoaded(force: Boolean = false) {
        if (!force && _uiState.value.allActiveLines.size > 1) {
            if (_uiState.value.isLoadingLines || _uiState.value.lineCatalogError != null) {
                _uiState.update { it.copy(isLoadingLines = false, lineCatalogError = null) }
                syncLinePickerState()
            }
            return
        }
        if (lineCatalogJob?.isActive == true && !force) return
        lineCatalogJob?.cancel()
        _uiState.update { it.copy(isLoadingLines = true, lineCatalogError = null) }
        syncLinePickerState()

        lineCatalogJob = viewModelScope.launch {
            try {
                val result = repository.fetchCitywideLineCatalog()
                _uiState.update { state ->
                    result.fold(
                        onSuccess = { lines ->
                            val merged = GttRealtimeRepository.mergeLineSummariesWithCatalog(
                                liveSummaries = state.allActiveLines,
                                catalogSummaries = lines,
                            )
                            state.copy(
                                allActiveLines = merged,
                                isLoadingLines = false,
                                lineCatalogError = if (merged.isEmpty()) "Nessuna linea GTT disponibile al momento" else null,
                            )
                        },
                        onFailure = { err ->
                            state.copy(
                                isLoadingLines = false,
                                lineCatalogError = if (state.allActiveLines.isEmpty()) {
                                    err.message ?: "Impossibile caricare la lista linee"
                                } else {
                                    null
                                },
                            )
                        },
                    )
                }
            } finally {
                if (_uiState.value.isLoadingLines) {
                    _uiState.update { it.copy(isLoadingLines = false) }
                }
                syncLinePickerState()
            }
        }
    }

    override fun onCleared() {
        repository.stopMatoLiveStream()
        locationController.stopLocationUpdates()
        super.onCleared()
    }

    fun hasLocationPermission(): Boolean = locationController.hasLocationPermission()

    fun onLocationPermissionResult(granted: Boolean) = locationController.onLocationPermissionResult(granted)

    fun onUserLocationButtonClicked() = locationController.onUserLocationButtonClicked()

    private fun startForegroundJobs() {
        if (foregroundPollingJob?.isActive != true) {
            foregroundPollingJob = viewModelScope.launch {
                while (isActive) {
                    refreshLiveData(isInitial = _uiState.value.lastUpdatedMillis == null)
                    val startMs = System.currentTimeMillis()
                    while (isActive) {
                        val pollIntervalMs = _uiState.value.settings.livePollInterval.millis
                        val elapsed = System.currentTimeMillis() - startMs
                        if (!isLinePickerOpen) {
                            val progress = (elapsed.toFloat() / pollIntervalMs.toFloat()).coerceIn(0f, 1f)
                            _pollProgress.value = progress
                        }
                        if (elapsed >= pollIntervalMs) break
                        delay(1000L)
                    }
                }
            }
        }
        if (clockTickerJob?.isActive != true) {
            clockTickerJob = viewModelScope.launch {
                while (isActive) {
                    if (!isLinePickerOpen) {
                        _uiState.update { it.copy(nowMillis = System.currentTimeMillis()) }
                    }
                    // Tick every 1s when inspecting a bus or stop so ages/countdowns update live, else every 5s
                    val interval = if (isLinePickerOpen) {
                        1000L
                    } else if (_uiState.value.selectedVehicleId != null || _uiState.value.selectedStop != null) {
                        1000L
                    } else {
                        5000L
                    }
                    delay(interval)
                }
            }
        }
    }

    private fun computeInteractiveRouteCamera(
        vehicles: List<BusVehicle>,
        patterns: List<RoutePattern>,
    ): CameraState = WebMercator.computeAutoFitCamera(
        vehicles = vehicles,
        patterns = patterns,
        viewportWidthPx = lastViewportDp.first,
        viewportHeightPx = lastViewportDp.second,
        paddingFraction = 0.09,
        maxZoom = 15.0,
        topPaddingFraction = 0.19,
        bottomPaddingFraction = 0.15,
        includeNearbyVehicles = false,
    )

    fun refreshLiveData(isInitial: Boolean = false) {
        val refreshSeq = ++liveRefreshSeq
        val epochAtStart = lineSwitchEpoch
        refreshLiveJob?.cancel()
        refreshLiveJob = viewModelScope.launch {
            try {
                _pollProgress.value = 0f
                _uiState.update {
                    it.copy(
                        isLoadingFeed = isInitial && it.lastUpdatedMillis == null,
                        isRefreshing = true,
                    )
                }

                // Ensure route geometry retries automatically if initial fetch hadn't completed yet
                val settingsBeforeFetch = _uiState.value.settings
                val currentLine = settingsBeforeFetch.selectedLine.trim().uppercase()
                if (currentLine.isNotEmpty() && _uiState.value.routePatterns.isEmpty() && routeLoadJob?.isActive != true) {
                    loadRouteGeometry(currentLine)
                }
                if (settingsBeforeFetch.showDepotsOnMap && _uiState.value.depotZones.isEmpty()) {
                    ensureDepotsLoadedIfMissing(forceImmediate = false)
                }

                val result = repository.fetchLiveVehicles(
                    includeSecondaryFeeds = true,
                    focusLine = currentLine.takeIf { it.isNotEmpty() },
                    dataSourceMode = settingsBeforeFetch.dataSourceMode,
                    includeAlerts = settingsBeforeFetch.showServiceAlerts || settingsBeforeFetch.notifyFavoriteLineAlerts,
                )
                if (liveRefreshSeq != refreshSeq || lineSwitchEpoch != epochAtStart) {
                    return@launch
                }
                result.fold(
                    onSuccess = { snapshot ->
                        val currentSettings = _uiState.value.settings
                        val line = currentSettings.selectedLine.trim().uppercase()
                        val evalNowMillis = System.currentTimeMillis()
                        val currentPatterns = _uiState.value.routePatterns
                        val filtered = if (line.isEmpty()) {
                            emptyList()
                        } else {
                            val rawLineVehicles = snapshot.allVehicles
                                .filter {
                                    it.lineCode.equals(line, ignoreCase = true) ||
                                        it.rawRouteId.equals(line, ignoreCase = true)
                                }
                                .sortedBy { it.vehicleId }
                            GttFleetAnalyzer.filterOutConfirmedPhantomsAndDeadGhosts(
                                vehicles = rawLineVehicles,
                                routePatterns = currentPatterns,
                                nowMillis = evalNowMillis,
                                healthyOfficialGtfsOnline = snapshot.healthyOfficialGtfsOnline,
                            )
                        }

                        val effectiveAlerts = if (currentSettings.showServiceAlerts) snapshot.alerts else emptyList()
                        val lineAlerts = if (line.isEmpty() || !currentSettings.showServiceAlerts) {
                            emptyList()
                        } else {
                            filterAlertsForLine(effectiveAlerts, line)
                        }

                        var pollFlyReq: CameraFlyRequest? = null
                        val nextCamera = if (!hasAutoCenteredCurrentLine && (filtered.isNotEmpty() || currentPatterns.isNotEmpty())) {
                            if (currentPatterns.isNotEmpty()) {
                                hasAutoCenteredCurrentLine = true
                            }
                            pendingViewportRouteRefit = !hasRealViewportSize
                            val fitted = computeInteractiveRouteCamera(filtered, currentPatterns)
                            pollFlyReq = CameraFlyRequest(target = fitted, id = ++cameraFlySeq, instant = false)
                            fitted
                        } else {
                            _uiState.value.camera
                        }

                        _uiState.update { state ->
                            if (state.lineSelectionEpoch != epochAtStart) return@update state
                            val updatedExternal = state.focusedExternalVehicle?.let { ext ->
                                snapshot.allVehicles.firstOrNull { it.vehicleId == ext.vehicleId } ?: ext
                            }
                            val keepSelected = state.selectedVehicleId?.takeIf { id ->
                                filtered.any { it.vehicleId == id } || updatedExternal?.vehicleId == id
                            }
                            val nextActiveLines = when {
                                snapshot.availableLines.size <= 1 && state.allActiveLines.size > 1 ->
                                    GttRealtimeRepository.mergeLineSummariesWithCatalog(
                                        liveSummaries = snapshot.availableLines,
                                        catalogSummaries = state.allActiveLines,
                                    )
                                snapshot.availableLines.none { it.activeVehicleCount == 0 } &&
                                    state.allActiveLines.any { it.activeVehicleCount == 0 } ->
                                    GttRealtimeRepository.mergeLiveSummariesWithStaticCatalog(
                                        liveSummaries = snapshot.availableLines,
                                        staticCatalog = state.allActiveLines,
                                    )
                                else -> snapshot.availableLines
                            }
                            val nextOutage = snapshot.outageReport
                            if (nextOutage == null) {
                                dismissedOutageStableKey = null
                            }
                            val nextOutageDismissed = nextOutage != null && nextOutage.stableKey == dismissedOutageStableKey
                            state.copy(
                                lineVehicles = filtered,
                                allActiveLines = nextActiveLines,
                                isLoadingLines = if (nextActiveLines.size > 1) false else state.isLoadingLines,
                                lineCatalogError = if (nextActiveLines.isNotEmpty()) null else state.lineCatalogError,
                                sourceStatuses = snapshot.sourceStatuses,
                                allAlerts = effectiveAlerts,
                                lineAlerts = lineAlerts,
                                camera = nextCamera,
                                cameraFlyRequest = pollFlyReq ?: state.cameraFlyRequest,
                                selectedVehicleId = keepSelected,
                                focusedExternalVehicle = if (keepSelected != null && updatedExternal?.vehicleId == keepSelected) updatedExternal else null,
                                focusedExternalTerminus = if (keepSelected != null && updatedExternal?.vehicleId == keepSelected) state.focusedExternalTerminus else null,
                                isLoadingFeed = false,
                                isRefreshing = false,
                                lastUpdatedMillis = snapshot.fetchedAtMillis,
                                feedTimestampSeconds = snapshot.feedTimestampSeconds,
                                nowMillis = evalNowMillis,
                                totalCityVehicles = snapshot.allVehicles.size,
                                errorMessage = null,
                                gttDataOutage = nextOutage,
                                isGttOutageDismissed = nextOutageDismissed,
                            )
                        }
                        syncLinePickerState()
                        resolveMissingVehicleStopNames(filtered)
                        // Once live vehicles with tripIds arrive on this line, verify route geometry against
                        // the live tripIds so active Sunday/holiday detours match the street immediately!
                        val liveTripIds = filtered.mapNotNull { it.tripId?.trim()?.takeIf { t -> t.isNotEmpty() } }.toSet()
                        if (line.isNotEmpty() && liveTripIds.isNotEmpty() && hasVerifiedLiveRouteForLine != line && routeLoadJob?.isActive != true) {
                            hasVerifiedLiveRouteForLine = line
                            loadRouteGeometry(line, activeTripIds = liveTripIds, forceRefresh = true)
                        }
                        regenerateWidgetPreview()
                        consecutivePollFailures = 0
                        val nowSync = System.currentTimeMillis()
                        if (nowSync - lastWidgetSyncMillis >= 60_000L) {
                            lastWidgetSyncMillis = nowSync
                            viewModelScope.launch(Dispatchers.IO) {
                                BusMapWidgetProvider.syncWidgetsWithCachedSnapshot(appContext)
                            }
                        }
                        if (currentSettings.notifyFavoriteLineAlerts && snapshot.alerts.isNotEmpty()) {
                            viewModelScope.launch(Dispatchers.IO) {
                                FavoriteAlertNotifier.processFetchedAlerts(
                                    context = appContext,
                                    alerts = snapshot.alerts,
                                    postNotifications = true,
                                    nowMillis = evalNowMillis,
                                )
                            }
                        }
                        if (snapshot.alerts.isNotEmpty() || currentSettings.showServiceAlerts) {
                            FavoriteAlertsWidgetProvider.syncWithAlerts(appContext, snapshot.alerts)
                        }
                        if (_uiState.value.selectedStop != null) {
                            refreshSelectedStopArrivals(silent = true)
                        }
                    },
                    onFailure = { err ->
                        consecutivePollFailures++
                        val now = System.currentTimeMillis()
                        val outageReport = repository.getCurrentOutageReport()
                        if (outageReport == null) {
                            dismissedOutageStableKey = null
                        }
                        val outageDismissed = outageReport != null && outageReport.stableKey == dismissedOutageStableKey
                        _uiState.update { state ->
                            if (state.lineSelectionEpoch != epochAtStart) return@update state
                            val hasRecentLiveData = state.lastUpdatedMillis != null &&
                                (now - state.lastUpdatedMillis) < 95_000L &&
                                (state.lineVehicles.isNotEmpty() || state.totalCityVehicles > 0)
                            val showOfflineBanner = !hasRecentLiveData || consecutivePollFailures >= 2
                            state.copy(
                                isLoadingFeed = false,
                                isRefreshing = false,
                                errorMessage = if (showOfflineBanner) {
                                    err.message ?: "Errore di connessione ai feed GTT"
                                } else {
                                    state.errorMessage
                                },
                                gttDataOutage = outageReport,
                                isGttOutageDismissed = outageDismissed,
                            )
                        }
                        regenerateWidgetPreview()
                    },
                )
            } finally {
                if (liveRefreshSeq == refreshSeq) {
                    _uiState.update { it.copy(isRefreshing = false, isLoadingFeed = false) }
                }
            }
        }
    }

    private fun onMatoLivePush() {
        val currentSettingsBefore = _uiState.value.settings
        if (!currentSettingsBefore.hasSelectedLine ||
            !GttRealtimeRepository.shouldRunMatoStream(currentSettingsBefore.dataSourceMode)
        ) {
            return
        }
        if (isLinePickerOpen) {
            pendingMatoSyncOnPickerClose = true
            return
        }
        val epochAtStart = lineSwitchEpoch
        val modeAtStart = currentSettingsBefore.dataSourceMode
        viewModelScope.launch(Dispatchers.Default) {
            if (lineSwitchEpoch != epochAtStart) return@launch
            val currentSettings = _uiState.value.settings
            if (!GttRealtimeRepository.shouldRunMatoStream(currentSettings.dataSourceMode)) return@launch
            val snapshot = repository.getLiveMatoOverlaySnapshot(currentSettings.dataSourceMode) ?: return@launch
            if (lineSwitchEpoch != epochAtStart) return@launch
            val line = currentSettings.selectedLine.trim().uppercase()
            if (line.isEmpty()) return@launch
            val evalNowMillis = System.currentTimeMillis()
            val currentPatterns = _uiState.value.routePatterns
            val rawLineVehicles = snapshot.allVehicles
                .filter {
                    it.lineCode.equals(line, ignoreCase = true) ||
                        it.rawRouteId.equals(line, ignoreCase = true)
                }
                .sortedBy { it.vehicleId }
            val filtered = GttFleetAnalyzer.filterOutConfirmedPhantomsAndDeadGhosts(
                vehicles = rawLineVehicles,
                routePatterns = currentPatterns,
                nowMillis = evalNowMillis,
                healthyOfficialGtfsOnline = snapshot.healthyOfficialGtfsOnline,
            )

            if (snapshot.allVehicles.isNotEmpty()) {
                consecutivePollFailures = 0
            }
            _uiState.update { state ->
                if (state.lineSelectionEpoch != epochAtStart || state.settings.dataSourceMode != modeAtStart) {
                    return@update state
                }
                val updatedExternal = state.focusedExternalVehicle?.let { ext ->
                    snapshot.allVehicles.firstOrNull { it.vehicleId == ext.vehicleId } ?: ext
                }
                val keepSelected = state.selectedVehicleId?.takeIf { id ->
                    filtered.any { it.vehicleId == id } || updatedExternal?.vehicleId == id
                }
                val nextActiveLines = when {
                    snapshot.availableLines.size <= 1 && state.allActiveLines.size > 1 ->
                        state.allActiveLines
                    snapshot.availableLines.none { it.activeVehicleCount == 0 } &&
                        state.allActiveLines.any { it.activeVehicleCount == 0 } ->
                        GttRealtimeRepository.mergeLiveSummariesWithStaticCatalog(
                            liveSummaries = snapshot.availableLines,
                            staticCatalog = state.allActiveLines,
                        )
                    else -> snapshot.availableLines
                }
                state.copy(
                    lineVehicles = filtered,
                    allActiveLines = nextActiveLines,
                    sourceStatuses = snapshot.sourceStatuses,
                    selectedVehicleId = keepSelected,
                    focusedExternalVehicle = if (keepSelected != null && updatedExternal?.vehicleId == keepSelected) updatedExternal else null,
                    focusedExternalTerminus = if (keepSelected != null && updatedExternal?.vehicleId == keepSelected) state.focusedExternalTerminus else null,
                    isLoadingFeed = false,
                    lastUpdatedMillis = snapshot.fetchedAtMillis,
                    feedTimestampSeconds = snapshot.feedTimestampSeconds,
                    nowMillis = evalNowMillis,
                    totalCityVehicles = snapshot.allVehicles.size,
                    errorMessage = if (snapshot.allVehicles.isNotEmpty()) null else state.errorMessage,
                )
            }
            syncLinePickerState()
            resolveMissingVehicleStopNames(filtered)
        }
    }

    fun selectLine(lineCode: String) {
        val raw = lineCode.trim().uppercase().ifBlank { return }
        val cleaned = com.example.data.network.GtfsRtProtobufParser.normalizeGttLineCode(raw)
            .takeIf { it.isNotBlank() && it != "?" } ?: raw
        val updated = preferences.updateSettings { it.copy(selectedLine = cleaned) }
        repository.notifyFocusLineSwitched(cleaned)
        if (GttRealtimeRepository.shouldRunMatoStream(updated.dataSourceMode)) {
            repository.startMatoLiveStream(cleaned) { onMatoLivePush() }
            repository.updateMatoLiveLine(cleaned)
        } else {
            repository.stopMatoLiveStream()
        }
        val nextEpoch = ++lineSwitchEpoch
        hasAutoCenteredCurrentLine = false
        needsRouteRefit = true
        hasVerifiedLiveRouteForLine = null
        pendingViewportRouteRefit = !hasRealViewportSize

        val cachedSnapshot = repository.getLastCachedSnapshot()
        val previousState = _uiState.value
        val isSameLoadedLine = previousState.settings.selectedLine.equals(cleaned, ignoreCase = true) &&
            previousState.routePatterns.isNotEmpty()
        val rawFiltered = cachedSnapshot?.allVehicles
            ?.filter {
                it.lineCode.equals(cleaned, ignoreCase = true) ||
                    it.rawRouteId.equals(cleaned, ignoreCase = true)
            }
            ?.sortedBy { it.vehicleId }
            .orEmpty()
        val filtered = GttFleetAnalyzer.filterOutConfirmedPhantomsAndDeadGhosts(
            vehicles = rawFiltered,
            routePatterns = if (isSameLoadedLine) previousState.routePatterns else emptyList(),
            nowMillis = System.currentTimeMillis(),
            healthyOfficialGtfsOnline = cachedSnapshot?.healthyOfficialGtfsOnline ?: false,
        )

        val allAlerts = if (updated.showServiceAlerts) cachedSnapshot?.alerts.orEmpty() else emptyList()
        val lineAlerts = if (updated.showServiceAlerts) filterAlertsForLine(allAlerts, cleaned) else emptyList()

        var selectFlyReq: CameraFlyRequest? = null
        val nextCamera = when {
            isSameLoadedLine -> {
                hasAutoCenteredCurrentLine = true
                needsRouteRefit = false
                val fitted = computeInteractiveRouteCamera(filtered, previousState.routePatterns)
                selectFlyReq = CameraFlyRequest(target = fitted, id = ++cameraFlySeq, instant = false)
                fitted
            }
            filtered.isNotEmpty() -> computeInteractiveRouteCamera(filtered, emptyList())
            else -> previousState.camera
        }

        _uiState.update {
            it.copy(
                settings = updated,
                lineSelectionEpoch = nextEpoch,
                lineVehicles = filtered,
                allAlerts = allAlerts,
                lineAlerts = lineAlerts,
                routePatterns = if (isSameLoadedLine) it.routePatterns else emptyList(),
                isLoadingInitialRoute = !isSameLoadedLine,
                selectedVehicleId = null,
                focusedExternalVehicle = null,
                focusedExternalTerminus = null,
                focusedExternalRoutePatterns = emptyList(),
                selectedStop = null,
                stopArrivals = emptyList(),
                isLoadingStopArrivals = false,
                camera = nextCamera,
                cameraFlyRequest = selectFlyReq ?: it.cameraFlyRequest,
                statusToast = "Linea $cleaned attiva",
            )
        }
        syncLinePickerState()

        if (!isSameLoadedLine) {
            val initialLiveTripIds = filtered
                .mapNotNull { it.tripId?.trim()?.takeIf { t -> t.isNotEmpty() } }
                .toSet()
            if (initialLiveTripIds.isNotEmpty()) {
                hasVerifiedLiveRouteForLine = cleaned
            }
            loadRouteGeometry(
                lineCode = cleaned,
                activeTripIds = initialLiveTripIds,
                isInitialRouteLoad = true,
            )
        }
        resolveMissingVehicleStopNames(filtered)
        if (filtered.isEmpty() || updated.dataSourceMode == DataSourceMode.MATO_LIVE) {
            refreshLiveData(isInitial = cachedSnapshot == null)
        }
        regenerateWidgetPreview()
        requestWidgetUpdatesAsync(includeMap = true, includeStops = false)
    }

    private fun filterAlertsForLine(
        alerts: List<ServiceAlertInfo>,
        lineCode: String,
    ): List<ServiceAlertInfo> {
        val target = lineCode.trim().uppercase()
        if (target.isEmpty()) return emptyList()
        val mentionRegex = alertMentionRegexCache.getOrPut(target) {
            Regex(
                pattern = "\\b(?:linea|linee)\\b[^.\\n]{0,45}\\b${Regex.escape(target)}\\b",
                option = RegexOption.IGNORE_CASE,
            )
        }
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
        isInitialRouteLoad: Boolean = false,
    ) {
        routeLoadJob?.cancel()
        val currentSeq = ++routeLoadSeq
        val epochAtStart = lineSwitchEpoch
        routeLoadJob = viewModelScope.launch {
            try {
                val patterns = repository.fetchRoutePatterns(
                    lineCode = lineCode,
                    activeTripIds = activeTripIds,
                    forceRefresh = forceRefresh,
                )
                if (lineSwitchEpoch != epochAtStart ||
                    !_uiState.value.settings.selectedLine.equals(lineCode, ignoreCase = true)
                ) {
                    return@launch
                }
                if (patterns.isNotEmpty()) {
                    val existingPatterns = _uiState.value.routePatterns
                    val isUnchangedRefresh = !isInitialRouteLoad &&
                        !needsRouteRefit &&
                        existingPatterns.size == patterns.size &&
                        existingPatterns.indices.all { i ->
                            val a = existingPatterns[i]
                            val b = patterns[i]
                            a.id == b.id &&
                                a.directionId == b.directionId &&
                                a.stops.size == b.stops.size &&
                                a.points.size == b.points.size
                        }
                    if (isUnchangedRefresh) {
                        return@launch
                    }
                    val shouldRefit = needsRouteRefit &&
                        _uiState.value.selectedStop == null &&
                        _uiState.value.selectedVehicleId == null
                    needsRouteRefit = false
                    var fittedTarget: CameraState? = null
                    val refitFly = if (shouldRefit) {
                        hasAutoCenteredCurrentLine = true
                        pendingViewportRouteRefit = !hasRealViewportSize
                        val target = computeInteractiveRouteCamera(_uiState.value.lineVehicles, patterns)
                        fittedTarget = target
                        cameraFlySeq++
                        CameraFlyRequest(
                            target = target,
                            id = cameraFlySeq,
                            instant = false,
                        )
                    } else {
                        null
                    }
                    val postRouteDrops = mutableListOf<Pair<BusVehicle, String>>()
                    _uiState.update { state ->
                        if (state.lineSelectionEpoch != epochAtStart) return@update state
                        val evalNow = System.currentTimeMillis()
                        val audit = GttFleetAnalyzer.filterOutConfirmedPhantomsAndDeadGhostsWithAudit(
                            vehicles = state.lineVehicles,
                            routePatterns = patterns,
                            nowMillis = evalNow,
                            healthyOfficialGtfsOnline = repository.getLastCachedSnapshot()?.healthyOfficialGtfsOnline ?: false,
                        )
                        val cleanedVehicles = audit.kept
                        if (audit.dropped.isNotEmpty() && postRouteDrops.isEmpty()) {
                            postRouteDrops.addAll(audit.dropped)
                        }
                        val keepSel = state.selectedVehicleId?.takeIf { selId ->
                            cleanedVehicles.any { it.vehicleId == selId } ||
                                state.focusedExternalVehicle?.vehicleId == selId
                        }
                        state.copy(
                            routePatterns = patterns,
                            lineVehicles = cleanedVehicles,
                            selectedVehicleId = keepSel,
                            nowMillis = evalNow,
                            isLoadingInitialRoute = false,
                            camera = fittedTarget ?: state.camera,
                            cameraFlyRequest = refitFly ?: state.cameraFlyRequest,
                        )
                    }
                    if (postRouteDrops.isNotEmpty()) {
                        com.example.data.network.ToBusDiagnosticsLog.record(
                            category = com.example.data.network.DiagCategory.VEHICLE,
                            message = "Percorso L.$lineCode caricato → scartati ${postRouteDrops.size} mezzi",
                            detail = postRouteDrops.joinToString(", ") { (b, r) -> "#${b.vehicleId}:$r" },
                            severity = com.example.data.network.DiagSeverity.WARN,
                        )
                    }
                    regenerateWidgetPreview()
                    requestWidgetUpdatesAsync(includeStops = false)
                }
            } finally {
                if (routeLoadSeq == currentSeq &&
                    lineSwitchEpoch == epochAtStart &&
                    (isInitialRouteLoad || _uiState.value.isLoadingInitialRoute)
                ) {
                    _uiState.update { it.copy(isLoadingInitialRoute = false) }
                }
            }
        }
    }

    fun updateCamera(camera: CameraState) {
        _uiState.update { it.copy(camera = camera) }
    }

    fun updateViewportSize(widthDp: Int, heightDp: Int) {
        if (widthDp > 0 && heightDp > 0) {
            val sizeChangedSignificantly = kotlin.math.abs(lastViewportDp.first - widthDp) > 16 ||
                kotlin.math.abs(lastViewportDp.second - heightDp) > 16
            val firstReal = !hasRealViewportSize
            hasRealViewportSize = true
            lastViewportDp = widthDp to heightDp
            val state = _uiState.value
            if ((firstReal || (pendingViewportRouteRefit && sizeChangedSignificantly)) &&
                state.routePatterns.isNotEmpty() &&
                state.selectedStop == null &&
                state.selectedVehicleId == null
            ) {
                pendingViewportRouteRefit = false
                hasAutoCenteredCurrentLine = true
                val fitted = computeInteractiveRouteCamera(state.lineVehicles, state.routePatterns)
                val flyReq = CameraFlyRequest(target = fitted, id = ++cameraFlySeq, instant = false)
                _uiState.update { current ->
                    if (current.routePatterns.isEmpty() || current.selectedStop != null || current.selectedVehicleId != null) {
                        current
                    } else {
                        current.copy(camera = fitted, cameraFlyRequest = flyReq)
                    }
                }
            }
        }
    }

    fun consumeCameraFlyRequest(flyId: Long) {
        _uiState.update { state ->
            if (state.cameraFlyRequest?.id == flyId) {
                state.copy(cameraFlyRequest = null)
            } else {
                state
            }
        }
    }

    fun fitLineInView() {
        val state = _uiState.value
        val next = computeInteractiveRouteCamera(state.lineVehicles, state.routePatterns)
        val fly = CameraFlyRequest(target = next, id = ++cameraFlySeq, instant = false)
        _uiState.update { it.copy(camera = next, cameraFlyRequest = fly) }
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
            val baseAllLive = repository.getLastCachedSnapshot()?.allVehicles.orEmpty().ifEmpty { state.lineVehicles }
            val cachedExtraLine = repository.getCachedSingleLineVehicles(targetLine, nowMillis)
            val allLive = if (cachedExtraLine.isNotEmpty()) {
                GttRealtimeRepository.mergeMultiSourceVehicles(
                    matoVehicles = cachedExtraLine,
                    gttVehicles = baseAllLive,
                    busOneVehicles = emptyList(),
                    nowMillis = nowMillis,
                )
            } else {
                baseAllLive
            }
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

            var matched = GttRealtimeRepository.findExactBusForArrival(
                arrival = arrival,
                stop = stop,
                allLivePool = allLive,
                targetLinePatterns = targetLinePatterns,
                claimedVehicleIds = claimedVehicleIds,
                nowMillis = System.currentTimeMillis(),
            )

            // If a "Live" arrival (especially on another line like 55/5) wasn't in the general GTFS-RT pool,
            // run an instant on-demand MaTO GraphQL query for that exact line before giving up.
            if (matched == null && arrival.isRealtime) {
                val onDemandLineBuses = repository.fetchLiveVehiclesForSingleLine(targetLine, forceRefresh = cachedExtraLine.isNotEmpty())
                if (_uiState.value.selectedStop?.stopId != stop.stopId) return@launch
                if (onDemandLineBuses.isNotEmpty()) {
                    val expandedPool = GttRealtimeRepository.mergeMultiSourceVehicles(
                        matoVehicles = onDemandLineBuses,
                        gttVehicles = allLive,
                        busOneVehicles = emptyList(),
                        nowMillis = System.currentTimeMillis(),
                    )
                    matched = GttRealtimeRepository.findExactBusForArrival(
                        arrival = arrival,
                        stop = stop,
                        allLivePool = expandedPool,
                        targetLinePatterns = targetLinePatterns,
                        claimedVehicleIds = claimedVehicleIds,
                        nowMillis = System.currentTimeMillis(),
                    )
                }
            }

            if (matched == null) {
                val clockStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ITALY)
                    .format(java.util.Date(arrival.arrivalEpochSeconds * 1000L))
                _uiState.update {
                    it.copy(
                        statusToast = if (arrival.isRealtime) {
                            "Orario live AVM confermato (linea $targetLine, $clockStr) · GPS del mezzo non trasmesso da GTT"
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
                .takeIf { it.isNotBlank() && !com.example.data.model.isGenericDirectionLabel(it) }
                ?: patternExtTerminus
                ?: arrival.headsign.takeIf { it.isNotBlank() && !it.startsWith("Linea ", ignoreCase = true) }

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
            if (query.isNotBlank()) {
                delay(90L)
            }
            val state = _uiState.value
            if (state.stopSearchResults.isEmpty()) {
                _uiState.update { it.copy(isLoadingStopIndex = true) }
                syncLinePickerState()
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
            syncLinePickerState()
        }
    }

    /**
     * Opens any stop selected from the global Stop Search sheet, centers the camera on that exact pole,
     * and loads its real-time arrivals without cluttering or unexpectedly replacing the active line.
     */
    fun openSearchedStop(entry: StopSearchEntry) {
        if (!_uiState.value.settings.hasSelectedLine) {
            val inferredLine = entry.lines.firstOrNull { it.isNotBlank() }
            if (inferredLine != null) {
                selectLine(inferredLine)
            }
        }
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
                forceRefresh = !silent,
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

    private fun requestWidgetUpdatesAsync(
        includeMap: Boolean = true,
        includeStops: Boolean = false,
        includeAlerts: Boolean = false,
        rescheduleInterval: RefreshInterval? = null,
    ) {
        widgetCoordinator.requestWidgetUpdatesAsync(
            includeMap = includeMap,
            includeStops = includeStops,
            includeAlerts = includeAlerts,
            rescheduleInterval = rescheduleInterval,
        )
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
                favoriteStopIds = updated.map { favStop -> favStop.stopId }.toSet(),
                statusToast = if (added) "Fermata \"$label\" salvata nei preferiti" else "Rimossa dai preferiti",
            )
        }
        regenerateWidgetPreview()
        refreshStopWidgetPreview()
        requestWidgetUpdatesAsync(includeMap = true, includeStops = true)
    }

    fun removeFavoriteStop(stopId: String) {
        val updated = preferences.removeFavoriteStop(stopId)
        _uiState.update {
            it.copy(
                favoriteStops = updated,
                favoriteStopIds = updated.map { favStop -> favStop.stopId }.toSet(),
                statusToast = "Fermata rimossa dai preferiti",
            )
        }
        regenerateWidgetPreview()
        refreshStopWidgetPreview()
        requestWidgetUpdatesAsync(includeMap = true, includeStops = true)
    }

    fun toggleFavoriteLine(lineCode: String) {
        val raw = lineCode.trim().uppercase().ifBlank { return }
        val cleaned = com.example.data.network.GtfsRtProtobufParser.normalizeGttLineCode(raw)
            .takeIf { it.isNotBlank() && it != "?" } ?: raw
        val (updated, added) = preferences.toggleFavoriteLine(cleaned)
        val settingsNow = _uiState.value.settings
        _uiState.update {
            it.copy(
                favoriteLines = updated,
                statusToast = if (added) {
                    "Linea $cleaned aggiunta ai preferiti"
                } else {
                    "Linea $cleaned rimossa dai preferiti"
                },
            )
        }
        syncLinePickerState()
        viewModelScope.launch(Dispatchers.IO) {
            if (added && settingsNow.notifyFavoriteLineAlerts) {
                val knownAlerts = _uiState.value.allAlerts.ifEmpty {
                    repository.getLastCachedSnapshot()?.alerts.orEmpty()
                }
                if (knownAlerts.isNotEmpty()) {
                    FavoriteAlertNotifier.markAlertsSeen(
                        context = appContext,
                        alerts = knownAlerts,
                        favoriteLines = listOf(cleaned),
                    )
                }
            }
            FavoriteAlertsScheduler.syncSchedule(
                context = appContext,
                enabled = settingsNow.notifyFavoriteLineAlerts,
                hasFavoriteLines = updated.isNotEmpty(),
            )
            val cachedAlerts = _uiState.value.allAlerts.takeIf { it.isNotEmpty() }
                ?: repository.getLastCachedSnapshot()?.alerts
            FavoriteAlertsWidgetProvider.syncWithAlerts(appContext, cachedAlerts)
        }
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
        requestWidgetUpdatesAsync(includeMap = true)
    }

    fun setCameraMode(mode: WidgetCameraMode) {
        val updated = preferences.updateSettings { it.copy(cameraMode = mode) }
        _uiState.update { it.copy(settings = updated) }
        regenerateWidgetPreview()
        requestWidgetUpdatesAsync(includeMap = true)
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
        requestWidgetUpdatesAsync(includeMap = true)
    }

    fun setShowVehicleLabels(show: Boolean) {
        val updated = preferences.updateSettings { it.copy(showVehicleLabels = show) }
        _uiState.update { it.copy(settings = updated) }
        regenerateWidgetPreview()
        requestWidgetUpdatesAsync(includeMap = true)
    }

    fun setShowRouteLine(show: Boolean) {
        val updated = preferences.updateSettings { it.copy(showRouteLine = show) }
        _uiState.update { it.copy(settings = updated) }
    }

    fun setShowStopsOnMap(show: Boolean) {
        val updated = preferences.updateSettings { it.copy(showStopsOnMap = show) }
        _uiState.update { it.copy(settings = updated) }
    }

    fun setShowDepotsOnMap(show: Boolean) {
        val updated = preferences.updateSettings { it.copy(showDepotsOnMap = show) }
        _uiState.update { it.copy(settings = updated) }
        if (show && _uiState.value.depotZones.isEmpty()) {
            ensureDepotsLoadedIfMissing(forceImmediate = true)
        }
    }

    private fun ensureDepotsLoadedIfMissing(forceImmediate: Boolean) {
        if (_uiState.value.depotZones.isNotEmpty()) return
        if (depotLoadJob?.isActive == true) return
        val now = System.currentTimeMillis()
        if (!forceImmediate && lastDepotAttemptMillis > 0L && (now - lastDepotAttemptMillis) < 40_000L) {
            return
        }
        lastDepotAttemptMillis = now
        depotLoadJob = viewModelScope.launch(Dispatchers.IO) {
            val cachedDepots = repository.getCachedVerifiedDepotsFast()
            if (cachedDepots.isNotEmpty()) {
                _uiState.update { it.copy(depotZones = cachedDepots) }
            }
            val loadedDepots = repository.ensureDepotPolygonsLoaded()
            if (loadedDepots.isNotEmpty() && loadedDepots != _uiState.value.depotZones) {
                _uiState.update { it.copy(depotZones = loadedDepots) }
            }
        }
    }

    fun setColorByDirection(enabled: Boolean) {
        val updated = preferences.updateSettings { it.copy(colorByDirection = enabled) }
        _uiState.update { it.copy(settings = updated) }
        regenerateWidgetPreview()
        requestWidgetUpdatesAsync(includeMap = true, includeStops = true)
    }

    fun setRouteColorPalette(palette: RouteColorPalette) {
        if (_uiState.value.settings.routeColorPalette == palette) return
        val updated = preferences.updateSettings { it.copy(routeColorPalette = palette) }
        _uiState.update { it.copy(settings = updated) }
        regenerateWidgetPreview()
        requestWidgetUpdatesAsync(includeMap = true)
    }

    fun setShowRouteInWidget(show: Boolean) {
        val updated = preferences.updateSettings { it.copy(showRouteInWidget = show) }
        _uiState.update { it.copy(settings = updated) }
        regenerateWidgetPreview()
        requestWidgetUpdatesAsync(includeMap = true)
    }

    fun setShowFavoriteStopsInWidget(show: Boolean) {
        val updated = preferences.updateSettings { it.copy(showFavoriteStopsInWidget = show) }
        _uiState.update { it.copy(settings = updated) }
        regenerateWidgetPreview()
        requestWidgetUpdatesAsync(includeMap = true)
    }

    fun setHideInactiveInWidget(hide: Boolean) {
        val updated = preferences.updateSettings { it.copy(hideInactiveInWidget = hide) }
        _uiState.update { it.copy(settings = updated) }
        regenerateWidgetPreview()
        requestWidgetUpdatesAsync(includeMap = true)
    }

    fun setRefreshInterval(interval: RefreshInterval) {
        val updated = preferences.updateSettings { it.copy(refreshInterval = interval) }
        _uiState.update { it.copy(settings = updated) }
        requestWidgetUpdatesAsync(includeMap = true, rescheduleInterval = interval)
    }

    fun setDataSourceMode(mode: DataSourceMode) {
        val previous = _uiState.value.settings.dataSourceMode
        if (previous == mode) return
        val updated = preferences.updateSettings { it.copy(dataSourceMode = mode) }
        if (updated.hasSelectedLine && GttRealtimeRepository.shouldRunMatoStream(mode)) {
            repository.startMatoLiveStream(updated.selectedLine) { onMatoLivePush() }
        } else {
            repository.stopMatoLiveStream()
        }
        consecutivePollFailures = 0
        _uiState.update {
            it.copy(
                settings = updated,
                errorMessage = null,
                statusToast = if (mode == DataSourceMode.AUTO) {
                    "Fonte dati: Automatico (multi-fonte)"
                } else {
                    "Fonte fissa: ${mode.labelIt}"
                },
            )
        }
        refreshLiveData(isInitial = false)
        requestWidgetUpdatesAsync(includeMap = true, includeStops = false)
    }

    fun setLivePollInterval(interval: LivePollInterval) {
        val updated = preferences.updateSettings { it.copy(livePollInterval = interval) }
        _uiState.update {
            it.copy(
                settings = updated,
                statusToast = "Aggiornamento mappa ogni ${interval.labelIt}",
            )
        }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        val updated = preferences.updateSettings { it.copy(keepScreenOn = enabled) }
        _uiState.update { it.copy(settings = updated) }
    }

    fun setShowServiceAlerts(enabled: Boolean) {
        val updated = preferences.updateSettings { it.copy(showServiceAlerts = enabled) }
        _uiState.update { state ->
            state.copy(
                settings = updated,
                allAlerts = if (enabled) state.allAlerts else emptyList(),
                lineAlerts = if (enabled) state.lineAlerts else emptyList(),
            )
        }
        if (enabled) {
            refreshLiveData(isInitial = false)
        }
    }

    fun setNotifyFavoriteLineAlerts(enabled: Boolean) {
        val updated = preferences.updateSettings { it.copy(notifyFavoriteLineAlerts = enabled) }
        val favorites = _uiState.value.favoriteLines
        val toast = when {
            !enabled -> "Notifiche avvisi disattivate"
            favorites.isEmpty() -> "Notifiche attivate · aggiungi una linea ai preferiti (★)"
            favorites.size == 1 -> "Notifiche attivate per la linea ${favorites.first()}"
            else -> "Notifiche attivate per ${favorites.size} linee preferite"
        }
        _uiState.update { it.copy(settings = updated, statusToast = toast) }

        viewModelScope.launch(Dispatchers.IO) {
            if (enabled) {
                FavoriteAlertNotifier.ensureChannel(appContext)
                // Seed currently active alerts as baseline so turning on notifications never
                // spams old already-active notices (only brand-new alerts will notify).
                val baselineAlerts = _uiState.value.allAlerts
                    .ifEmpty { repository.getLastCachedSnapshot()?.alerts.orEmpty() }
                    .ifEmpty { repository.fetchServiceAlertsNow().orEmpty() }
                if (baselineAlerts.isNotEmpty()) {
                    FavoriteAlertNotifier.markAlertsSeen(
                        context = appContext,
                        alerts = baselineAlerts,
                        favoriteLines = favorites.ifEmpty { null },
                    )
                }
                preferences.setAlertBaselineSeeded(true)
            }
            FavoriteAlertsScheduler.syncSchedule(
                context = appContext,
                enabled = enabled,
                hasFavoriteLines = favorites.isNotEmpty(),
            )
        }
    }

    fun onNotificationPermissionDenied() {
        val updated = preferences.updateSettings { it.copy(notifyFavoriteLineAlerts = false) }
        _uiState.update {
            it.copy(
                settings = updated,
                statusToast = "Permesso notifiche non concesso",
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            FavoriteAlertsScheduler.syncSchedule(
                context = appContext,
                enabled = false,
                hasFavoriteLines = false,
            )
        }
    }

    fun toggleNotifyAlertCategory(category: ServiceAlertCategory) {
        val current = preferences.currentSettings().notifyAlertCategories
        val next = if (category in current) current - category else current + category
        updateNotifyAlertCategories(next, newlyAdded = if (category !in current) setOf(category) else emptySet())
    }

    fun toggleAllNotifyAlertCategories(selectAll: Boolean) {
        val current = preferences.currentSettings().notifyAlertCategories
        val all = ServiceAlertCategory.entries.toSet()
        val next = if (selectAll) all else emptySet()
        updateNotifyAlertCategories(next, newlyAdded = next - current)
    }

    private fun updateNotifyAlertCategories(
        next: Set<ServiceAlertCategory>,
        newlyAdded: Set<ServiceAlertCategory>,
    ) {
        val updated = preferences.updateSettings { it.copy(notifyAlertCategories = next) }
        _uiState.update { it.copy(settings = updated) }
        if (updated.notifyFavoriteLineAlerts && newlyAdded.isNotEmpty()) {
            val favorites = _uiState.value.favoriteLines
            viewModelScope.launch(Dispatchers.IO) {
                val active = _uiState.value.allAlerts
                    .ifEmpty { repository.getLastCachedSnapshot()?.alerts.orEmpty() }
                    .filter { it.classifyCategory() in newlyAdded }
                if (active.isNotEmpty()) {
                    FavoriteAlertNotifier.markAlertsSeen(
                        context = appContext,
                        alerts = active,
                        favoriteLines = favorites.ifEmpty { null },
                    )
                }
            }
        }
    }

    fun refreshServiceAlerts() {
        if (!_uiState.value.settings.showServiceAlerts || _uiState.value.isRefreshingAlerts) return
        _uiState.update { it.copy(isRefreshingAlerts = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fresh = repository.fetchServiceAlertsNow()
                if (fresh != null) {
                    val activeLine = preferences.currentSettings().selectedLine
                    val lineAlerts = if (activeLine.isBlank()) {
                        emptyList()
                    } else {
                        filterAlertsForLine(fresh, activeLine)
                    }
                    _uiState.update { state ->
                        state.copy(
                            allAlerts = fresh,
                            lineAlerts = lineAlerts,
                            isRefreshingAlerts = false,
                        )
                    }
                    FavoriteAlertsWidgetProvider.syncWithAlerts(appContext, fresh)
                } else {
                    _uiState.update { state ->
                        state.copy(
                            isRefreshingAlerts = false,
                            statusToast = "Impossibile aggiornare gli avvisi GTT ora",
                        )
                    }
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(isRefreshingAlerts = false) }
            }
        }
    }

    fun markVisibleAlertsAsSeen() {
        val alerts = _uiState.value.allAlerts
        if (alerts.isEmpty()) return
        val favorites = _uiState.value.favoriteLines
        viewModelScope.launch(Dispatchers.IO) {
            FavoriteAlertNotifier.markAlertsSeen(
                context = appContext,
                alerts = alerts,
                favoriteLines = favorites.ifEmpty { null },
            )
        }
    }

    fun setHideInactiveOnMap(hide: Boolean) {
        val updated = preferences.updateSettings { it.copy(hideInactiveOnMap = hide) }
        _uiState.update { it.copy(settings = updated) }
    }

    fun dismissGttOutageBanner() {
        val current = _uiState.value.gttDataOutage ?: return
        dismissedOutageStableKey = current.stableKey
        _uiState.update { it.copy(isGttOutageDismissed = true) }
    }

    fun syncOutageStateFromRepository(resetDismissal: Boolean = true) {
        val report = repository.getCurrentOutageReport()
        if (resetDismissal || report == null) {
            dismissedOutageStableKey = null
        }
        _uiState.update {
            it.copy(
                gttDataOutage = report,
                isGttOutageDismissed = report != null && report.stableKey == dismissedOutageStableKey,
            )
        }
    }

    fun clearAppCaches() {
        viewModelScope.launch {
            repository.clearLocalCaches()
            hasVerifiedLiveRouteForLine = null
            val currentLine = _uiState.value.settings.selectedLine.trim().uppercase()
            _uiState.update {
                it.copy(
                    statusToast = "Cache svuotata · percorsi ricaricati",
                )
            }
            if (currentLine.isNotEmpty()) {
                loadRouteGeometry(currentLine, forceRefresh = true)
            }
            refreshLiveData(isInitial = false)
        }
    }

    fun setPreviewSize(size: WidgetPreviewSize) = widgetCoordinator.setPreviewSize(size)

    fun clearToast() {
        _uiState.update { it.copy(statusToast = null) }
    }

    fun triggerHomeWidgetSync() = widgetCoordinator.triggerHomeWidgetSync()

    fun openFavoriteStopById(stopId: String) {
        val cleaned = stopId.trim()
        if (cleaned.isEmpty()) return
        val fav = _uiState.value.favoriteStops.firstOrNull {
            it.stopId.equals(cleaned, ignoreCase = true)
        } ?: return
        openFavoriteStop(fav)
    }

    fun refreshStopWidgetPreview() = widgetCoordinator.refreshStopWidgetPreview()

    fun regenerateWidgetPreview(force: Boolean = false) = widgetCoordinator.regenerateWidgetPreview(force)

    fun checkForUpdatesManually() = updateCoordinator.checkForUpdatesManually()

    fun startInAppUpdateDownload() = updateCoordinator.startInAppUpdateDownload()

    fun cancelUpdateDownload() = updateCoordinator.cancelUpdateDownload()

    fun onInstallPermissionReturned() = updateCoordinator.onInstallPermissionReturned()

    fun retriggerReadyApkInstall() = updateCoordinator.retriggerReadyApkInstall()

    fun dismissUpdateDialog() = updateCoordinator.dismissUpdateDialog()

    fun ignoreCurrentUpdateVersion() = updateCoordinator.ignoreCurrentUpdateVersion()

    fun saveGithubRepoSlug(slug: String) = updateCoordinator.saveGithubRepoSlug(slug)
}
