package com.example.ui.viewmodel

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.data.model.CameraState
import com.example.data.model.shortTurinPlaceName
import com.example.data.network.GttVehicleMergeEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Manages optional foreground-only GPS tracking and camera fly-to-user behavior for [ToBusViewModel].
 *
 * Updates are strictly bound to foreground visibility (`ON_START` / `ON_STOP`) so background
 * battery usage remains zero.
 */
internal class MapLocationController(
    private val appContext: Context,
    private val uiState: MutableStateFlow<ToBusUiState>,
    private val syncLinePickerState: () -> Unit,
    private val nextCameraFlyId: () -> Long,
) {
    private val locationManager: LocationManager? =
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private var activeLocationListener: LocationListener? = null
    private var currentLocationCancelSignal: CancellationSignal? = null
    private var pendingCenterOnNextFix: Boolean = false

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun resumeIfEnabled() {
        if (uiState.value.isUserLocationEnabled && hasLocationPermission()) {
            startLocationUpdates(centerOnNextFix = false)
        }
    }

    fun onLocationPermissionResult(granted: Boolean) {
        if (granted) {
            enableAndCenterUserLocation()
        } else {
            uiState.update {
                it.copy(
                    isUserLocationEnabled = false,
                    isLocatingUser = false,
                    userLocation = null,
                    statusToast = "Permesso posizione non concesso",
                )
            }
            syncLinePickerState()
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
        val state = uiState.value
        if (!state.isUserLocationEnabled) {
            enableAndCenterUserLocation()
            return
        }

        val loc = state.userLocation
        if (loc == null) {
            startLocationUpdates(centerOnNextFix = true)
            return
        }

        val distFromCenter = GttVehicleMergeEngine.haversineMeters(
            state.camera.centerLat,
            state.camera.centerLon,
            loc.lat,
            loc.lon,
        )
        if (distFromCenter > 85.0 || state.camera.zoom < 14.5) {
            flyCameraToUser(loc, announceNearestStop = true)
        } else {
            stopLocationUpdates()
            uiState.update {
                it.copy(
                    isUserLocationEnabled = false,
                    isLocatingUser = false,
                    userLocation = null,
                    statusToast = "Posizione GPS disattivata",
                )
            }
            syncLinePickerState()
        }
    }

    fun stopLocationUpdates() {
        currentLocationCancelSignal?.cancel()
        currentLocationCancelSignal = null
        activeLocationListener?.let { listener ->
            runCatching { locationManager?.removeUpdates(listener) }
        }
        activeLocationListener = null
    }

    private fun enableAndCenterUserLocation() {
        val lm = locationManager
        if (lm == null) {
            uiState.update { it.copy(statusToast = "Servizio GPS non disponibile") }
            return
        }
        val anyEnabled = runCatching {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
        if (!anyEnabled) {
            uiState.update {
                it.copy(
                    isUserLocationEnabled = false,
                    isLocatingUser = false,
                    statusToast = "Attiva la geolocalizzazione sul dispositivo",
                )
            }
            return
        }

        uiState.update { it.copy(isUserLocationEnabled = true, isLocatingUser = true) }
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
                lm.requestLocationUpdates(provider, 9_000L, 10f, listener, Looper.getMainLooper())
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
                    } else if (uiState.value.userLocation == null) {
                        uiState.update { it.copy(isLocatingUser = false) }
                    }
                }
            }
        }
    }

    private fun handleNewLocation(location: Location, fromFreshFix: Boolean) {
        val stateLoc = UserLocationState(
            lat = location.latitude,
            lon = location.longitude,
            accuracyMeters = location.accuracy.coerceIn(5f, 300f),
            updatedAtMillis = System.currentTimeMillis(),
        )
        uiState.update {
            it.copy(
                userLocation = stateLoc,
                isLocatingUser = if (fromFreshFix) false else it.isLocatingUser && pendingCenterOnNextFix,
            )
        }
        syncLinePickerState()
        if (pendingCenterOnNextFix) {
            pendingCenterOnNextFix = false
            flyCameraToUser(stateLoc, announceNearestStop = true)
            uiState.update { it.copy(isLocatingUser = false) }
        }
    }

    private fun flyCameraToUser(loc: UserLocationState, announceNearestStop: Boolean) {
        val currentZoom = uiState.value.camera.zoom
        val target = CameraState(
            centerLat = loc.lat.coerceIn(44.85, 45.30),
            centerLon = loc.lon.coerceIn(7.35, 8.05),
            zoom = currentZoom.coerceAtLeast(15.6).coerceAtMost(CameraState.MAX_ZOOM),
        )
        val nearestToast = if (announceNearestStop) {
            val stops = uiState.value.routePatterns.flatMap { it.stops }
            val nearest = stops.minByOrNull {
                GttVehicleMergeEngine.haversineMeters(loc.lat, loc.lon, it.lat, it.lon)
            }
            if (nearest != null) {
                val meters = GttVehicleMergeEngine.haversineMeters(loc.lat, loc.lon, nearest.lat, nearest.lon).toInt()
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
        val flyId = nextCameraFlyId()
        uiState.update {
            it.copy(
                cameraFlyRequest = CameraFlyRequest(target = target, id = flyId),
                statusToast = nearestToast ?: it.statusToast,
            )
        }
    }
}
