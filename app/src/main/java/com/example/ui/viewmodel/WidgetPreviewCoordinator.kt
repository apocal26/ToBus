package com.example.ui.viewmodel

import android.content.Context
import com.example.data.model.RefreshInterval
import com.example.data.model.StopArrival
import com.example.data.network.GttRealtimeRepository
import com.example.map.TransitMapBitmapRenderer
import com.example.widget.BusMapWidgetProvider
import com.example.widget.FavoriteAlertsWidgetProvider
import com.example.widget.StopArrivalsWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Coordinates Home Screen AppWidget update broadcasts and live Studio preview rendering
 * (Map bitmap preview, Stop arrivals preview, and Alerts preview) for [ToBusViewModel].
 */
internal class WidgetPreviewCoordinator(
    private val appContext: Context,
    private val repository: GttRealtimeRepository,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ToBusUiState>,
) {
    private var isStudioTabActive = false
    private var previewDirty = true
    private var previewRenderJob: Job? = null
    private var stopPreviewJob: Job? = null

    fun setStudioTabActive(active: Boolean) {
        isStudioTabActive = active
        if (active && (previewDirty || uiState.value.widgetPreviewBitmap == null)) {
            previewDirty = false
            regenerateWidgetPreview(force = true)
        }
    }

    fun requestWidgetUpdatesAsync(
        includeMap: Boolean = true,
        includeStops: Boolean = false,
        includeAlerts: Boolean = false,
        rescheduleInterval: RefreshInterval? = null,
    ) {
        scope.launch(Dispatchers.IO) {
            if (rescheduleInterval != null) {
                BusMapWidgetProvider.schedulePeriodicUpdates(appContext, rescheduleInterval)
            }
            if (includeMap) {
                BusMapWidgetProvider.requestUpdateAllWidgets(appContext)
            }
            if (includeStops) {
                StopArrivalsWidgetProvider.requestUpdateAll(appContext)
            }
            if (includeAlerts) {
                FavoriteAlertsWidgetProvider.requestUpdateAll(appContext)
            }
        }
    }

    fun setPreviewSize(size: WidgetPreviewSize) {
        uiState.update { it.copy(previewSize = size) }
        when (size) {
            WidgetPreviewSize.STOPS_LIVE -> refreshStopWidgetPreview()
            WidgetPreviewSize.ALERTS_LIVE -> {
                if (uiState.value.allAlerts.isEmpty()) {
                    scope.launch(Dispatchers.IO) {
                        val alerts = repository.fetchServiceAlertsNow().orEmpty()
                        if (alerts.isNotEmpty()) {
                            val selected = uiState.value.settings.selectedLine
                            val forLine = if (selected.isNotEmpty()) {
                                alerts.filter { alert ->
                                    alert.isCurrentOrUpcoming() &&
                                        alert.affectedLines.any { it.equals(selected, ignoreCase = true) }
                                }
                            } else {
                                emptyList()
                            }
                            uiState.update { it.copy(allAlerts = alerts, lineAlerts = forLine) }
                        }
                    }
                }
            }
            else -> regenerateWidgetPreview()
        }
    }

    fun triggerHomeWidgetSync() {
        requestWidgetUpdatesAsync(includeMap = true, includeStops = true, includeAlerts = true)
        when (uiState.value.previewSize) {
            WidgetPreviewSize.STOPS_LIVE -> refreshStopWidgetPreview()
            WidgetPreviewSize.ALERTS_LIVE -> {
                scope.launch(Dispatchers.IO) {
                    val alerts = repository.fetchServiceAlertsNow()
                    if (alerts != null) {
                        val selected = uiState.value.settings.selectedLine
                        val forLine = if (selected.isNotEmpty()) {
                            alerts.filter { alert ->
                                alert.isCurrentOrUpcoming() &&
                                    alert.affectedLines.any { it.equals(selected, ignoreCase = true) }
                            }
                        } else {
                            emptyList()
                        }
                        uiState.update { it.copy(allAlerts = alerts, lineAlerts = forLine) }
                    }
                }
            }
            else -> regenerateWidgetPreview()
        }
        uiState.update { it.copy(statusToast = "Widget sulla Home aggiornati") }
    }

    fun refreshStopWidgetPreview() {
        val allFavorites = uiState.value.favoriteStops
        if (allFavorites.isEmpty()) {
            uiState.update { it.copy(stopWidgetPreviewByStopId = emptyMap(), isLoadingStopWidgetPreview = false) }
            return
        }
        stopPreviewJob?.cancel()
        stopPreviewJob = scope.launch {
            uiState.update { it.copy(isLoadingStopWidgetPreview = true) }
            val cachedAll = repository.getLastCachedSnapshot()?.allVehicles ?: uiState.value.lineVehicles
            val byStopId: Map<String, List<StopArrival>> = coroutineScope {
                allFavorites.map { fav ->
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
            uiState.update {
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
        if (uiState.value.previewSize == WidgetPreviewSize.STOPS_LIVE) {
            refreshStopWidgetPreview()
            return
        }
        if (uiState.value.previewSize == WidgetPreviewSize.ALERTS_LIVE) {
            return
        }
        previewRenderJob?.cancel()
        previewRenderJob = scope.launch(Dispatchers.IO) {
            val state = uiState.value
            uiState.update { it.copy(isRenderingPreview = true) }
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

            uiState.update {
                it.copy(
                    widgetPreviewBitmap = bitmap ?: it.widgetPreviewBitmap,
                    isRenderingPreview = false,
                )
            }
        }
    }
}
