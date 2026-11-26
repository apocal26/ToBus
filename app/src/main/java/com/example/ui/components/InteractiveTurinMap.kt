package com.example.ui.components

import android.util.Log
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.model.BusVehicle
import com.example.data.model.CameraState
import com.example.data.model.GttFleetAnalyzer
import com.example.data.model.MapStyle
import com.example.data.model.RoutePattern
import com.example.data.model.TransitStop
import com.example.data.model.VehicleHealthAssessment
import com.example.ui.viewmodel.CameraFlyRequest
import com.example.ui.viewmodel.UserLocationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow

/**
 * MapLibre GL uses 512px vector tiles, whereas WebMercator/CameraState uses 256px scale:
 * MapLibre zoom == WebMercator zoom - 1.0.
 */
private const val ML_ZOOM_OFFSET = 1.0
private const val MIN_ML_ZOOM = 9.5
private const val MAX_ML_ZOOM = 18.5
private const val MAP_LOG_TAG = "ToBusMapLibre"

private data class AnimatedBusPose(
    val lat: Double,
    val lon: Double,
    val bearing: Float?,
)

@Immutable
private data class BusMapVisualHealth(
    val isInDepot: Boolean,
    val isStaleGps: Boolean,
    val isStuck: Boolean,
    val isOffRoute: Boolean,
    val isAtTerminus: Boolean,
)

private fun lerpAngleDeg(from: Float?, to: Float?, t: Float): Float? {
    if (to == null) return from
    if (from == null) return to
    val delta = (((to - from + 540f) % 360f) - 180f)
    return (from + delta * t + 360f) % 360f
}

@Composable
fun InteractiveTurinMap(
    camera: CameraState,
    mapStyle: MapStyle,
    vehicles: List<BusVehicle>,
    focusedExternalVehicle: BusVehicle? = null,
    focusedExternalRoutePatterns: List<RoutePattern> = emptyList(),
    userLocation: UserLocationState? = null,
    cameraFlyRequest: CameraFlyRequest? = null,
    routePatterns: List<RoutePattern>,
    favoriteStopIds: Set<String>,
    selectedVehicleId: String?,
    selectedStop: TransitStop? = null,
    selectedStopId: String?,
    nowMillis: Long,
    isForegroundActive: Boolean,
    showVehicleLabels: Boolean,
    showRouteLine: Boolean,
    colorByDirection: Boolean,
    focusedDirectionFilter: Int? = null,
    shiftCameraForBottomSheet: Boolean = true,
    precomputedHealthById: Map<String, VehicleHealthAssessment>? = null,
    precomputedDirectionById: Map<String, Int?>? = null,
    onCameraChange: (CameraState) -> Unit,
    onViewportSizeChange: (Int, Int) -> Unit,
    onVehicleTap: (BusVehicle?) -> Unit,
    onStopTap: (TransitStop?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current.density

    // Initialize MapLibre native library once before creating MapView
    remember(context) {
        MapLibre.getInstance(context.applicationContext)
    }

    val activeExternalBus = remember(vehicles, focusedExternalVehicle) {
        focusedExternalVehicle?.takeIf { ext ->
            vehicles.none { it.vehicleId == ext.vehicleId }
        }
    }

    val allAnimatableVehicles = remember(vehicles, activeExternalBus) {
        if (activeExternalBus != null) vehicles + activeExternalBus else vehicles
    }

    val baseVisibleStops = remember(routePatterns, focusedDirectionFilter) {
        routePatterns
            .flatMap { it.stops }
            .filter { focusedDirectionFilter == null || it.directionId == focusedDirectionFilter }
            .distinctBy { "${it.stopId}_${it.directionId}" }
    }
    val visibleStops = remember(baseVisibleStops, selectedStop) {
        if (selectedStop != null && baseVisibleStops.none { it.stopId == selectedStop.stopId }) {
            baseVisibleStops + selectedStop
        } else {
            baseVisibleStops
        }
    }

    val spatialById = remember(vehicles, routePatterns, precomputedHealthById) {
        if (precomputedHealthById != null) {
            emptyMap()
        } else {
            vehicles.associate { bus ->
                bus.vehicleId to GttFleetAnalyzer.assessSpatial(bus, routePatterns)
            }
        }
    }

    val busHealthById = remember(vehicles, spatialById, nowMillis, precomputedHealthById) {
        precomputedHealthById ?: vehicles.associate { bus ->
            val spatial = spatialById[bus.vehicleId] ?: GttFleetAnalyzer.assessSpatial(bus, routePatterns)
            bus.vehicleId to GttFleetAnalyzer.assessWithSpatial(
                bus = bus,
                spatial = spatial,
                nowMillis = nowMillis,
            )
        }
    }

    val busVisualHealthById = remember(busHealthById) {
        busHealthById.mapValues { (_, h) ->
            BusMapVisualHealth(
                isInDepot = h.isInDepot,
                isStaleGps = h.isStaleGps,
                isStuck = h.isStuck,
                isOffRoute = h.isOffRoute,
                isAtTerminus = h.isAtTerminus,
            )
        }
    }

    val resolvedDirectionById = remember(vehicles, routePatterns, precomputedDirectionById) {
        precomputedDirectionById ?: vehicles.associate { bus ->
            bus.vehicleId to GttFleetAnalyzer.resolveDirectionId(bus, routePatterns)
        }
    }

    val hasExternalRouteFocus = activeExternalBus != null && focusedExternalRoutePatterns.isNotEmpty()
    val highlightedMainDirectionId = remember(
        selectedVehicleId,
        activeExternalBus,
        resolvedDirectionById,
        vehicles,
        focusedDirectionFilter,
    ) {
        when {
            activeExternalBus != null -> null
            selectedVehicleId != null -> {
                resolvedDirectionById[selectedVehicleId]
                    ?: vehicles.firstOrNull { it.vehicleId == selectedVehicleId }?.directionId
            }
            else -> focusedDirectionFilter
        }
    }

    val fromPoseById = remember { mutableMapOf<String, AnimatedBusPose>() }
    val toPoseById = remember { mutableMapOf<String, AnimatedBusPose>() }
    val busMotionProgress = remember { Animatable(1f) }

    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var activeStyle by remember { mutableStateOf<Style?>(null) }
    var styleReadyTick by remember { mutableIntStateOf(0) }

    val latestVehicles by rememberUpdatedState(vehicles)
    val latestExternalBus by rememberUpdatedState(activeExternalBus)
    val latestSelectedVehicleId by rememberUpdatedState(selectedVehicleId)
    val latestBusHealthById by rememberUpdatedState(busHealthById)
    val latestResolvedDirectionById by rememberUpdatedState(resolvedDirectionById)
    val latestColorByDirection by rememberUpdatedState(colorByDirection)
    val latestShowVehicleLabels by rememberUpdatedState(showVehicleLabels)
    val latestShowRouteLine by rememberUpdatedState(showRouteLine)
    val latestFocusedDirectionFilter by rememberUpdatedState(focusedDirectionFilter)
    val latestVisibleStops by rememberUpdatedState(visibleStops)
    val latestOnCameraChange by rememberUpdatedState(onCameraChange)
    val latestOnVehicleTap by rememberUpdatedState(onVehicleTap)
    val latestOnStopTap by rememberUpdatedState(onStopTap)

    fun currentPoseForBus(bus: BusVehicle, t: Float = busMotionProgress.value): Triple<Double, Double, Float?> {
        val from = fromPoseById[bus.vehicleId]
        val to = toPoseById[bus.vehicleId]
        return if (from != null && to != null && t < 0.999f) {
            Triple(
                from.lat + (to.lat - from.lat) * t.toDouble(),
                from.lon + (to.lon - from.lon) * t.toDouble(),
                lerpAngleDeg(from.bearing, to.bearing, t),
            )
        } else {
            Triple(bus.latitude, bus.longitude, bus.bearing)
        }
    }

    fun pushLineBusesToStyle(style: Style, t: Float = busMotionProgress.value) {
        if (!style.isFullyLoaded) return
        val source = style.getSourceAs<GeoJsonSource>(MapLibreTransitIds.BUSES_SOURCE) ?: return
        val dirFilter = latestFocusedDirectionFilter
        val filteredVehicles = if (dirFilter == null) {
            latestVehicles
        } else {
            latestVehicles.filter { bus ->
                bus.vehicleId == latestSelectedVehicleId ||
                    (latestResolvedDirectionById[bus.vehicleId] ?: bus.directionId) == dirFilter
            }
        }
        val fc = buildBusesFeatureCollection(
            vehicles = filteredVehicles,
            poseForBus = { bus -> currentPoseForBus(bus, t) },
            selectedVehicleId = latestSelectedVehicleId,
            busHealthById = latestBusHealthById,
            resolvedDirectionById = latestResolvedDirectionById,
            colorByDirection = latestColorByDirection,
            showVehicleLabels = latestShowVehicleLabels,
        )
        source.setGeoJson(fc)
    }

    fun pushExternalBusToStyle(style: Style, t: Float = busMotionProgress.value) {
        if (!style.isFullyLoaded) return
        val source = style.getSourceAs<GeoJsonSource>(MapLibreTransitIds.EXTERNAL_BUS_SOURCE) ?: return
        val ext = latestExternalBus
        val pose = ext?.let { currentPoseForBus(it, t) }
        source.setGeoJson(buildExternalBusFeatureCollection(ext, pose))
    }

    // Create MapView with TextureView mode so smoked-glass Compose layers blend flawlessly above it
    val mapView = remember(context) {
        val initialCameraPos = CameraPosition.Builder()
            .target(LatLng(camera.centerLat, camera.centerLon))
            .zoom((camera.zoom - ML_ZOOM_OFFSET).coerceIn(MIN_ML_ZOOM, MAX_ML_ZOOM))
            .tilt(0.0)
            .bearing(0.0)
            .build()
        val options = MapLibreMapOptions.createFromAttributes(context, null)
            .textureMode(true)
            .camera(initialCameraPos)
            .compassEnabled(false)
            .rotateGesturesEnabled(false)
            .tiltGesturesEnabled(false)
            .logoEnabled(false)
            .attributionEnabled(false)

        MapView(context, options).apply {
            id = View.generateViewId()
        }
    }

    val latestIsForegroundActive by rememberUpdatedState(isForegroundActive)
    var isLifecycleResumed by remember { mutableStateOf(false) }
    var isMapResumed by remember { mutableStateOf(false) }

    // Robust lifecycle sync (handles Activity launch, tab switching, AND pausing MapLibre GL behind overlays)
    DisposableEffect(lifecycleOwner, mapView) {
        var created = false
        var started = false

        fun syncUpTo(state: Lifecycle.State) {
            if (!created && state.isAtLeast(Lifecycle.State.CREATED)) {
                mapView.onCreate(null)
                created = true
            }
            if (!started && state.isAtLeast(Lifecycle.State.STARTED)) {
                mapView.onStart()
                started = true
            }
            if (state.isAtLeast(Lifecycle.State.RESUMED)) {
                isLifecycleResumed = true
                if (!isMapResumed && latestIsForegroundActive) {
                    mapView.onResume()
                    isMapResumed = true
                }
            }
        }

        syncUpTo(lifecycleOwner.lifecycle.currentState)

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> if (!created) {
                    mapView.onCreate(null)
                    created = true
                }
                Lifecycle.Event.ON_START -> if (!started) {
                    mapView.onStart()
                    started = true
                }
                Lifecycle.Event.ON_RESUME -> {
                    isLifecycleResumed = true
                    if (!isMapResumed && latestIsForegroundActive) {
                        mapView.onResume()
                        isMapResumed = true
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    isLifecycleResumed = false
                    if (isMapResumed) {
                        mapView.onPause()
                        isMapResumed = false
                    }
                }
                Lifecycle.Event.ON_STOP -> if (started) {
                    mapView.onStop()
                    started = false
                }
                Lifecycle.Event.ON_DESTROY -> if (created) {
                    mapView.onDestroy()
                    created = false
                    started = false
                    isLifecycleResumed = false
                    isMapResumed = false
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        val failListener = MapView.OnDidFailLoadingMapListener { err ->
            Log.w(MAP_LOG_TAG, "MapLibre load warning: $err")
        }
        mapView.addOnDidFailLoadingMapListener(failListener)

        onDispose {
            mapView.removeOnDidFailLoadingMapListener(failListener)
            lifecycleOwner.lifecycle.removeObserver(observer)
            activeStyle = null
            mapLibreMap = null
            if (isMapResumed) {
                mapView.onPause()
                isMapResumed = false
            }
            isLifecycleResumed = false
            if (started) {
                mapView.onStop()
                started = false
            }
            if (created) {
                mapView.onDestroy()
                created = false
            }
        }
    }

    // Pause MapLibre OpenGL RenderThread while full-screen overlays (e.g. LinePickerSheet) cover the map
    LaunchedEffect(isForegroundActive, isLifecycleResumed, mapView) {
        val shouldBeResumed = isForegroundActive && isLifecycleResumed
        if (shouldBeResumed && !isMapResumed) {
            mapView.onResume()
            isMapResumed = true
        } else if (!shouldBeResumed && isMapResumed) {
            mapView.onPause()
            isMapResumed = false
        }
    }

    // Bind MapLibreMap once ready
    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            map.setMinZoomPreference(MIN_ML_ZOOM)
            map.setMaxZoomPreference(MAX_ML_ZOOM)
            map.uiSettings.apply {
                isCompassEnabled = false
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                isLogoEnabled = false
                isAttributionEnabled = false
                isZoomGesturesEnabled = true
                isScrollGesturesEnabled = true
                isDoubleTapGesturesEnabled = true
                isQuickZoomGesturesEnabled = true
            }

            map.addOnCameraIdleListener {
                val pos = map.cameraPosition
                val target = pos.target ?: return@addOnCameraIdleListener
                latestOnCameraChange(
                    CameraState(
                        centerLat = target.latitude,
                        centerLon = target.longitude,
                        zoom = (pos.zoom + ML_ZOOM_OFFSET).coerceIn(10.5, 19.5),
                    ),
                )
            }

            map.addOnMapClickListener { tapLatLng ->
                val tapScreen = map.projection.toScreenLocation(tapLatLng)
                val busHitPx = 44f * density
                val stopHitPx = 36f * density

                // 1. Prioritize focused external bus tap first, then tracked line buses (44dp touch target)
                var closestBus: BusVehicle? = null
                var closestBusDist = Float.MAX_VALUE
                latestExternalBus?.let { ext ->
                    val (lat, lon, _) = currentPoseForBus(ext)
                    val pt = map.projection.toScreenLocation(LatLng(lat, lon))
                    val d = hypot(pt.x - tapScreen.x, pt.y - tapScreen.y)
                    if (d <= busHitPx) {
                        closestBus = ext
                        closestBusDist = d
                    }
                }
                for (bus in latestVehicles) {
                    val (lat, lon, _) = currentPoseForBus(bus)
                    val pt = map.projection.toScreenLocation(LatLng(lat, lon))
                    val d = hypot(pt.x - tapScreen.x, pt.y - tapScreen.y)
                    if (d <= busHitPx && d < closestBusDist) {
                        closestBusDist = d
                        closestBus = bus
                    }
                }
                if (closestBus != null) {
                    latestOnVehicleTap(closestBus)
                    return@addOnMapClickListener true
                }

                // 2. Otherwise check route stops (36dp touch target) only while stop markers are visible
                if (latestShowRouteLine && map.cameraPosition.zoom >= MapLibreTransitIds.STOPS_MIN_ML_ZOOM.toDouble()) {
                    var closestStop: TransitStop? = null
                    var closestStopDist = Float.MAX_VALUE
                    for (stop in latestVisibleStops) {
                        val pt = map.projection.toScreenLocation(LatLng(stop.lat, stop.lon))
                        val d = hypot(pt.x - tapScreen.x, pt.y - tapScreen.y)
                        if (d <= stopHitPx && d < closestStopDist) {
                            closestStopDist = d
                            closestStop = stop
                        }
                    }
                    if (closestStop != null) {
                        latestOnStopTap(closestStop)
                        return@addOnMapClickListener true
                    }
                }

                // 3. Empty map tap closes any open inspector/stop sheet
                latestOnVehicleTap(null)
                latestOnStopTap(null)
                true
            }

            mapLibreMap = map
        }
    }

    // Load our deterministic, rich Turin vector style with sprites pre-rendered off the main thread
    LaunchedEffect(mapLibreMap, mapStyle) {
        val map = mapLibreMap ?: return@LaunchedEffect
        activeStyle = null
        val (styleJson, preRenderedLandmarks, preRenderedTransit) = withContext(Dispatchers.Default) {
            Triple(
                TurinVectorStyleBuilder.buildStyleJson(mapStyle),
                getOrCreateLandmarkDotSprites(isDark = mapStyle.isDark, density = density),
                getOrCreateTransitSprites(density = density),
            )
        }
        map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
            runCatching {
                installMajorLandmarksLayer(style, mapStyle, density, preRenderedLandmarks = preRenderedLandmarks)
                registerTransitSprites(style, density, preRendered = preRenderedTransit)
                installTransitLayers(style, isDarkBasemap = mapStyle.isDark)
                pushLineBusesToStyle(style, busMotionProgress.value)
                pushExternalBusToStyle(style, busMotionProgress.value)
            }.onFailure { err ->
                Log.e(MAP_LOG_TAG, "Failed setting up transit layers", err)
            }
            activeStyle = style
            styleReadyTick++
        }
    }

    // Smooth GPU camera flight when centering on GPS, a bus, a favorite stop, or a newly selected line
    LaunchedEffect(mapLibreMap, cameraFlyRequest?.id) {
        val map = mapLibreMap ?: return@LaunchedEffect
        val req = cameraFlyRequest ?: return@LaunchedEffect
        val mlZoom = (req.target.zoom - ML_ZOOM_OFFSET).coerceIn(MIN_ML_ZOOM, MAX_ML_ZOOM)
        // When a bottom glass inspector/stop sheet is open on compact screens, shift target gently south
        // so the focused bus/stop lands dead-center in the open map viewport ABOVE the card instead of hidden behind it.
        val hasBottomCard = shiftCameraForBottomSheet && (selectedVehicleId != null || selectedStopId != null)
        val adjustedLat = if (hasBottomCard) {
            val offsetDp = 88.0
            val worldDp = 512.0 * 2.0.pow(mlZoom)
            val degLatPerDp = (360.0 / worldDp) * cos(Math.toRadians(req.target.centerLat))
            (req.target.centerLat - offsetDp * degLatPerDp).coerceIn(-85.0, 85.0)
        } else {
            req.target.centerLat
        }
        val targetPos = CameraPosition.Builder()
            .target(LatLng(adjustedLat, req.target.centerLon))
            .zoom(mlZoom)
            .tilt(0.0)
            .bearing(0.0)
            .build()
        val current = map.cameraPosition
        val currentTarget = current.target
        if (currentTarget != null &&
            abs(currentTarget.latitude - adjustedLat) < 1e-6 &&
            abs(currentTarget.longitude - req.target.centerLon) < 1e-6 &&
            abs(current.zoom - targetPos.zoom) < 0.02
        ) {
            return@LaunchedEffect
        }
        map.cancelTransitions()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(targetPos), 460)
    }

    // Update Route Polylines & visibility (highlighting selected bus direction or dimming when inspecting external bus)
    LaunchedEffect(
        activeStyle,
        styleReadyTick,
        routePatterns,
        colorByDirection,
        showRouteLine,
        highlightedMainDirectionId,
        hasExternalRouteFocus,
    ) {
        val style = activeStyle ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect
        val source = style.getSourceAs<GeoJsonSource>(MapLibreTransitIds.ROUTE_SOURCE)
        source?.setGeoJson(
            buildRoutesFeatureCollection(
                routePatterns = routePatterns,
                colorByDirection = colorByDirection,
                highlightedDirectionId = highlightedMainDirectionId,
                dimAll = hasExternalRouteFocus,
            ),
        )

        val vis = if (showRouteLine) Property.VISIBLE else Property.NONE
        style.getLayer(MapLibreTransitIds.ROUTE_CASING_LAYER)?.setProperties(PropertyFactory.visibility(vis))
        style.getLayer(MapLibreTransitIds.ROUTE_GLOW_LAYER)?.setProperties(PropertyFactory.visibility(vis))
        style.getLayer(MapLibreTransitIds.ROUTE_CORE_LAYER)?.setProperties(PropertyFactory.visibility(vis))
        style.getLayer(MapLibreTransitIds.ROUTE_ARROWS_LAYER)?.setProperties(PropertyFactory.visibility(vis))
    }

    // Update Focused External Bus Route Overlay (e.g. showing Line 55's exact path when focusing a Line 55 bus)
    LaunchedEffect(
        activeStyle,
        styleReadyTick,
        activeExternalBus,
        focusedExternalRoutePatterns,
    ) {
        val style = activeStyle ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect
        val source = style.getSourceAs<GeoJsonSource>(MapLibreTransitIds.EXTERNAL_ROUTE_SOURCE)
        val fc = buildExternalRouteFeatureCollection(activeExternalBus, focusedExternalRoutePatterns)
        source?.setGeoJson(fc)

        val vis = if (activeExternalBus != null && focusedExternalRoutePatterns.isNotEmpty()) {
            Property.VISIBLE
        } else {
            Property.NONE
        }
        style.getLayer(MapLibreTransitIds.EXTERNAL_ROUTE_CASING_LAYER)?.setProperties(PropertyFactory.visibility(vis))
        style.getLayer(MapLibreTransitIds.EXTERNAL_ROUTE_GLOW_LAYER)?.setProperties(PropertyFactory.visibility(vis))
        style.getLayer(MapLibreTransitIds.EXTERNAL_ROUTE_CORE_LAYER)?.setProperties(PropertyFactory.visibility(vis))
    }

    // Update Stops & visibility
    LaunchedEffect(
        activeStyle,
        styleReadyTick,
        visibleStops,
        routePatterns,
        favoriteStopIds,
        selectedStopId,
        colorByDirection,
        showRouteLine,
        highlightedMainDirectionId,
        hasExternalRouteFocus,
    ) {
        val style = activeStyle ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect
        val source = style.getSourceAs<GeoJsonSource>(MapLibreTransitIds.STOPS_SOURCE)
        source?.setGeoJson(
            buildStopsFeatureCollection(
                stops = visibleStops,
                routePatterns = routePatterns,
                favoriteStopIds = favoriteStopIds,
                selectedStopId = selectedStopId,
                colorByDirection = colorByDirection,
                highlightedDirectionId = highlightedMainDirectionId,
                dimAll = hasExternalRouteFocus,
            ),
        )
        val vis = if (showRouteLine || selectedStopId != null) Property.VISIBLE else Property.NONE
        style.getLayer(MapLibreTransitIds.STOPS_HALO_LAYER)?.setProperties(PropertyFactory.visibility(vis))
        style.getLayer(MapLibreTransitIds.STOPS_DOT_LAYER)?.setProperties(PropertyFactory.visibility(vis))
        style.getLayer(MapLibreTransitIds.STOPS_FAV_STAR_LAYER)?.setProperties(PropertyFactory.visibility(vis))
        style.getLayer(MapLibreTransitIds.STOPS_SELECTED_LABEL_LAYER)?.setProperties(PropertyFactory.visibility(vis))
    }

    // Update User GPS Location dot
    LaunchedEffect(activeStyle, styleReadyTick, userLocation) {
        val style = activeStyle ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect
        val source = style.getSourceAs<GeoJsonSource>(MapLibreTransitIds.USER_SOURCE) ?: return@LaunchedEffect
        if (userLocation != null) {
            source.setGeoJson(
                FeatureCollection.fromFeature(
                    Feature.fromGeometry(Point.fromLngLat(userLocation.lon, userLocation.lat)),
                ),
            )
        } else {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        }
    }

    // Subtle pulsing GPS halo ONLY while GPS is enabled and app is in foreground (~24fps JNI update)
    LaunchedEffect(activeStyle, styleReadyTick, userLocation != null, isForegroundActive) {
        val style = activeStyle ?: return@LaunchedEffect
        if (userLocation == null || !isForegroundActive) return@LaunchedEffect
        val pulseLayer = style.getLayerAs<CircleLayer>(MapLibreTransitIds.USER_PULSE_LAYER) ?: return@LaunchedEffect
        val pulseAnim = Animatable(0f)
        var lastPulsePushNs = 0L
        pulseAnim.animateTo(
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1900, easing = LinearOutSlowInEasing),
                repeatMode = RepeatMode.Restart,
            ),
        ) {
            val nowNs = System.nanoTime()
            if (style.isFullyLoaded && nowNs - lastPulsePushNs >= 42_000_000L) {
                lastPulsePushNs = nowNs
                val radius = 10f + value * 16f
                val alpha = ((1f - value) * 0.34f).coerceIn(0f, 0.34f)
                pulseLayer.setProperties(
                    PropertyFactory.circleRadius(radius),
                    PropertyFactory.circleOpacity(alpha),
                )
            }
        }
    }

    // Smooth 1-second GPU bus glide whenever vehicle positions or headings update
    LaunchedEffect(allAnimatableVehicles) {
        val prevT = busMotionProgress.value
        val activeIds = HashSet<String>(allAnimatableVehicles.size)
        var anyMoved = false

        for (bus in allAnimatableVehicles) {
            val id = bus.vehicleId
            activeIds.add(id)
            val prevFrom = fromPoseById[id]
            val prevTo = toPoseById[id]
            val currentPose = if (prevFrom != null && prevTo != null) {
                AnimatedBusPose(
                    lat = prevFrom.lat + (prevTo.lat - prevFrom.lat) * prevT.toDouble(),
                    lon = prevFrom.lon + (prevTo.lon - prevFrom.lon) * prevT.toDouble(),
                    bearing = lerpAngleDeg(prevFrom.bearing, prevTo.bearing, prevT),
                )
            } else {
                prevTo
            }

            val nextPose = AnimatedBusPose(bus.latitude, bus.longitude, bus.bearing)
            if (currentPose != null) {
                val distM = GttFleetAnalyzer.haversineMeters(
                    currentPose.lat,
                    currentPose.lon,
                    nextPose.lat,
                    nextPose.lon,
                )
                val bearingChanged = currentPose.bearing != null && nextPose.bearing != null &&
                    GttFleetAnalyzer.angleDiffDeg(currentPose.bearing.toDouble(), nextPose.bearing.toDouble()) > 3.0
                if (distM in 2.0..2500.0 || (distM < 2500.0 && bearingChanged)) {
                    fromPoseById[id] = currentPose
                    toPoseById[id] = nextPose
                    anyMoved = true
                } else {
                    fromPoseById[id] = nextPose
                    toPoseById[id] = nextPose
                }
            } else {
                fromPoseById[id] = nextPose
                toPoseById[id] = nextPose
            }
        }

        fromPoseById.keys.retainAll(activeIds)
        toPoseById.keys.retainAll(activeIds)

        if (anyMoved) {
            busMotionProgress.snapTo(0f)
            var lastBusPushNs = 0L
            busMotionProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            ) {
                val nowNs = System.nanoTime()
                if (nowNs - lastBusPushNs >= 32_000_000L || value >= 0.999f) {
                    lastBusPushNs = nowNs
                    activeStyle?.let { style ->
                        pushLineBusesToStyle(style, value)
                        if (latestExternalBus != null) {
                            pushExternalBusToStyle(style, value)
                        }
                    }
                }
            }
            activeStyle?.let { style ->
                pushLineBusesToStyle(style, 1f)
                if (latestExternalBus != null) {
                    pushExternalBusToStyle(style, 1f)
                }
            }
        } else {
            busMotionProgress.snapTo(1f)
            activeStyle?.let { style ->
                pushLineBusesToStyle(style, 1f)
                pushExternalBusToStyle(style, 1f)
            }
        }
    }

    // Push tracked line buses immediately when style loads, selection changes, or display settings toggle
    LaunchedEffect(
        activeStyle,
        styleReadyTick,
        vehicles,
        selectedVehicleId,
        busVisualHealthById,
        resolvedDirectionById,
        colorByDirection,
        showVehicleLabels,
        focusedDirectionFilter,
    ) {
        activeStyle?.let { style -> pushLineBusesToStyle(style, busMotionProgress.value) }
    }

    // Push focused external bus immediately to its own isolated top-most layer whenever selected or refreshed
    LaunchedEffect(
        activeStyle,
        styleReadyTick,
        activeExternalBus,
    ) {
        activeStyle?.let { style -> pushExternalBusToStyle(style, busMotionProgress.value) }
    }

    val containerBg = when (mapStyle) {
        MapStyle.DARK -> Color(0xFF0F141E)
        MapStyle.POSITRON -> Color(0xFFECEFF5)
        MapStyle.VOYAGER -> Color(0xFFF3EFE7)
        MapStyle.SATELLITE -> Color(0xFF0B1218)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(containerBg)
            .testTag("interactive_turin_map")
            .onSizeChanged { size ->
                if (size.width > 0 && size.height > 0) {
                    onViewportSizeChange(size.width, size.height)
                }
            },
    ) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
