package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import com.example.ui.icons.ToBusIcons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.BusVehicle
import com.example.data.model.CameraState
import com.example.data.model.DataSourceMode
import com.example.data.model.FavoriteStop
import com.example.data.model.GttFleetAnalyzer
import com.example.data.model.ServiceAlertInfo
import com.example.data.model.StopArrival
import com.example.data.model.TransitStop
import com.example.data.model.VehicleFreshness
import com.example.data.model.VehicleHealthAssessment
import com.example.data.model.formatTurinLabel
import com.example.data.model.shortTurinPlaceName
import com.example.ui.components.InteractiveTurinMap
import com.example.ui.glass.AccentIconBadge
import com.example.ui.glass.DirectionColorBadge
import com.example.ui.glass.GlassCircleIconButton
import com.example.ui.glass.GlassModalDialog
import com.example.ui.glass.HeroEntranceText
import com.example.ui.glass.LightHapticKind
import com.example.ui.glass.LiquidGlassOverlaySurface
import com.example.ui.glass.MapOverlayGlassPill
import com.example.ui.glass.RecessedMetricPill
import com.example.ui.glass.glassInteractive
import com.example.ui.glass.glassSpring
import com.example.ui.glass.liquidGlassRimBrush
import com.example.ui.glass.performLightHaptic
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.AccentEmerald
import com.example.ui.theme.AccentGold
import com.example.ui.theme.AccentRed
import com.example.ui.theme.DarkInkOnAccent
import com.example.ui.theme.GlassRowBorder
import com.example.ui.theme.GlassRowFill
import com.example.ui.theme.LightMapScrimBottom
import com.example.ui.theme.LightMapScrimTop
import com.example.ui.theme.OverlayBusPillLightBottom
import com.example.ui.theme.OverlayBusPillLightTop
import com.example.ui.theme.OverlayPillBorder
import com.example.ui.theme.OverlayPillFill
import com.example.ui.theme.OverlayPillLightMapFill
import com.example.ui.theme.ScrimBottomEnd
import com.example.ui.theme.ScrimTopStart
import com.example.ui.theme.SmokedGlassBorder
import com.example.ui.theme.SmokedGlassFill
import com.example.ui.theme.TextBody
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSubtitle
import com.example.ui.viewmodel.ToBusUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private sealed interface MapInspectorTarget {
    data object None : MapInspectorTarget
    data class Bus(val vehicle: BusVehicle) : MapInspectorTarget
    data class Stop(val stop: TransitStop) : MapInspectorTarget
}

@Composable
fun MapScreen(
    uiState: ToBusUiState,
    pollProgressFlow: StateFlow<Float>,
    isForegroundActive: Boolean,
    isWideScreen: Boolean = false,
    bottomNavClearance: Dp,
    onCameraChange: (CameraState) -> Unit,
    onViewportSizeChange: (Int, Int) -> Unit,
    onOpenLinePicker: () -> Unit,
    onRefresh: () -> Unit,
    onUserLocationClick: () -> Unit,
    onLocationPermissionResult: (Boolean) -> Unit,
    onSelectVehicle: (BusVehicle?) -> Unit,
    onSelectStop: (TransitStop?) -> Unit,
    onRefreshStopArrivals: () -> Unit,
    onToggleFavoriteStop: (TransitStop) -> Unit,
    onOpenFavoriteStop: (FavoriteStop) -> Unit,
    onFocusArrivalBus: (StopArrival, Int) -> Unit,
    onSelectLine: (String) -> Unit,
    onSelectDataSourceMode: (DataSourceMode) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    var showAlertsDialog by rememberSaveable { mutableStateOf(false) }

    val latestSelectedVehicleId by rememberUpdatedState(uiState.selectedVehicleId)
    val latestSelectedStopId by rememberUpdatedState(uiState.selectedStop?.stopId)
    val latestOnSelectVehicle by rememberUpdatedState(onSelectVehicle)
    val latestOnSelectStop by rememberUpdatedState(onSelectStop)
    val latestOnOpenFavoriteStop by rememberUpdatedState(onOpenFavoriteStop)
    val latestOnFocusArrivalBus by rememberUpdatedState(onFocusArrivalBus)
    val latestOnUserLocationClick by rememberUpdatedState(onUserLocationClick)

    val handleSelectVehicle: (BusVehicle?) -> Unit = remember(view) {
        { vehicle ->
            if (vehicle != null && vehicle.vehicleId != latestSelectedVehicleId) {
                performLightHaptic(view, LightHapticKind.SELECT_BUS)
            }
            latestOnSelectVehicle(vehicle)
        }
    }
    val handleSelectStop: (TransitStop?) -> Unit = remember(view) {
        { stop ->
            if (stop != null && !stop.stopId.equals(latestSelectedStopId, ignoreCase = true)) {
                performLightHaptic(view, LightHapticKind.SWITCH_STOP)
            }
            latestOnSelectStop(stop)
        }
    }
    val handleOpenFavoriteStop: (FavoriteStop) -> Unit = remember(view) {
        { fav ->
            if (!fav.stopId.equals(latestSelectedStopId, ignoreCase = true)) {
                performLightHaptic(view, LightHapticKind.SWITCH_STOP)
            }
            latestOnOpenFavoriteStop(fav)
        }
    }
    val handleFocusArrivalBus: (StopArrival, Int) -> Unit = remember(view) {
        { arrival, indexInLine ->
            val hasLiveTarget = arrival.isRealtime &&
                (!arrival.vehicleId.isNullOrBlank() || !arrival.tripId.isNullOrBlank())
            if (hasLiveTarget) {
                performLightHaptic(view, LightHapticKind.SELECT_BUS)
            }
            latestOnFocusArrivalBus(arrival, indexInLine)
        }
    }

    BackHandler(enabled = uiState.selectedVehicle != null || uiState.selectedStop != null) {
        if (uiState.selectedVehicle != null) onSelectVehicle(null)
        if (uiState.selectedStop != null) onSelectStop(null)
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        onLocationPermissionResult(granted)
    }

    val handleUserLocationTap = remember(context, locationPermissionLauncher) {
        {
            val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            if (hasFine || hasCoarse) {
                latestOnUserLocationClick()
            } else {
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            }
        }
    }

    val spatialByVehicleId = remember(uiState.lineVehicles, uiState.routePatterns) {
        uiState.lineVehicles.associate { bus ->
            bus.vehicleId to GttFleetAnalyzer.assessSpatial(bus, uiState.routePatterns)
        }
    }
    val healthByVehicleId = remember(uiState.lineVehicles, spatialByVehicleId, uiState.nowMillis) {
        uiState.lineVehicles.associate { bus ->
            val spatial = spatialByVehicleId[bus.vehicleId]
                ?: GttFleetAnalyzer.assessSpatial(bus, uiState.routePatterns)
            bus.vehicleId to GttFleetAnalyzer.assessWithSpatial(bus, spatial, uiState.nowMillis)
        }
    }
    val resolvedDirectionById = remember(uiState.lineVehicles, uiState.routePatterns) {
        uiState.lineVehicles.associate { bus ->
            bus.vehicleId to GttFleetAnalyzer.resolveDirectionId(bus, uiState.routePatterns)
        }
    }
    val activeReliableCount = remember(healthByVehicleId) {
        healthByVehicleId.values.count { it.isReliableLive }
    }
    val depotCount = remember(healthByVehicleId) {
        healthByVehicleId.values.count { it.isInDepot }
    }
    val offRouteCount = remember(healthByVehicleId) {
        healthByVehicleId.values.count { !it.isInDepot && !it.isStaleGps && !it.isStuck && it.isOffRoute }
    }
    val staleOrStuckCount = remember(healthByVehicleId) {
        healthByVehicleId.values.count { !it.isInDepot && (it.isStaleGps || it.isStuck) }
    }
    var focusedDirectionFilter by remember(uiState.settings.selectedLine) { mutableStateOf<Int?>(null) }
    val directionTermini = remember(uiState.routePatterns) {
        uiState.routePatterns
            .groupBy { it.directionId }
            .mapValues { (_, patterns) ->
                val raw = patterns.firstNotNullOfOrNull { pat ->
                    pat.terminusName()?.takeIf { it.isNotBlank() && !it.startsWith("Linea ") }
                } ?: patterns.firstOrNull()?.terminusName().orEmpty()
                shortTurinPlaceName(raw)
            }
            .filterValues { it.isNotBlank() }
    }
    val visibleMapVehicles = remember(
        uiState.lineVehicles,
        healthByVehicleId,
        uiState.settings.hideInactiveOnMap,
        uiState.selectedVehicleId,
    ) {
        if (!uiState.settings.hideInactiveOnMap) {
            uiState.lineVehicles
        } else {
            uiState.lineVehicles.filter { bus ->
                bus.vehicleId == uiState.selectedVehicleId ||
                    healthByVehicleId[bus.vehicleId]?.let { !it.isInDepot && !it.isStaleGps } != false
            }
        }
    }
    val sortedVehicles = remember(
        visibleMapVehicles,
        healthByVehicleId,
        resolvedDirectionById,
        focusedDirectionFilter,
        uiState.selectedVehicleId,
    ) {
        visibleMapVehicles
            .filter { bus ->
                focusedDirectionFilter == null ||
                    bus.vehicleId == uiState.selectedVehicleId ||
                    (resolvedDirectionById[bus.vehicleId] ?: bus.directionId) == focusedDirectionFilter
            }
            .sortedWith(
                compareBy<BusVehicle> { healthByVehicleId[it.vehicleId]?.isInDepot == true }
                    .thenBy { healthByVehicleId[it.vehicleId]?.isOffRoute == true }
                    .thenBy { healthByVehicleId[it.vehicleId]?.isStaleGps == true }
                    .thenBy { healthByVehicleId[it.vehicleId]?.isStuck == true }
                    .thenBy { it.vehicleId },
            )
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 1. Full-bleed interactive Torino slippy map (clean street tiles, smooth bus motion & optional GPS dot)
        InteractiveTurinMap(
            camera = uiState.camera,
            mapStyle = uiState.settings.mapStyle,
            vehicles = visibleMapVehicles,
            focusedExternalVehicle = uiState.focusedExternalVehicle,
            focusedExternalRoutePatterns = uiState.focusedExternalRoutePatterns,
            userLocation = uiState.userLocation,
            cameraFlyRequest = uiState.cameraFlyRequest,
            routePatterns = uiState.routePatterns,
            selectedVehicleId = uiState.selectedVehicleId,
            selectedStop = uiState.selectedStop,
            selectedStopId = uiState.selectedStop?.stopId,
            favoriteStopIds = uiState.favoriteStopIds,
            nowMillis = uiState.nowMillis,
            isForegroundActive = isForegroundActive,
            showVehicleLabels = uiState.settings.showVehicleLabels,
            showRouteLine = uiState.settings.showRouteLine,
            colorByDirection = uiState.settings.colorByDirection,
            focusedDirectionFilter = focusedDirectionFilter,
            shiftCameraForBottomSheet = !isWideScreen,
            precomputedHealthById = healthByVehicleId,
            precomputedDirectionById = resolvedDirectionById,
            onCameraChange = onCameraChange,
            onViewportSizeChange = onViewportSizeChange,
            onVehicleTap = handleSelectVehicle,
            onStopTap = handleSelectStop,
        )

        val isLightBasemap = !uiState.settings.mapStyle.isDark

        // 2. Calibrated Top & Bottom Scrims (lighter & airy on Positron/Voyager, deeper on Dark/Satellite)
        val topScrimBrush = remember(isLightBasemap) {
            Brush.verticalGradient(
                listOf(
                    if (isLightBasemap) LightMapScrimTop else ScrimTopStart,
                    Color.Transparent,
                ),
            )
        }
        val bottomScrimBrush = remember(isLightBasemap) {
            Brush.verticalGradient(
                listOf(
                    Color.Transparent,
                    if (isLightBasemap) LightMapScrimBottom else ScrimBottomEnd,
                ),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isWideScreen) 104.dp else 124.dp)
                .align(Alignment.TopCenter)
                .background(topScrimBrush),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isWideScreen) 104.dp else 150.dp + bottomNavClearance)
                .align(Alignment.BottomCenter)
                .background(bottomScrimBrush),
        )

        // 3. Top Floating Liquid Glass Bar + Adaptive Direction & Favorite Stops Row
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // High-contrast Liquid Glass Line Selector Pill
                MapOverlayGlassPill(
                    onClick = onOpenLinePicker,
                    isLightBasemap = isLightBasemap,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .widthIn(max = 360.dp)
                        .testTag("open_line_picker_pill"),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 6.dp, top = 5.dp, end = 12.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .background(AccentGold, RoundedCornerShape(999.dp))
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = uiState.settings.selectedLine.ifBlank { "—" },
                                style = MaterialTheme.typography.labelLarge,
                                color = DarkInkOnAccent,
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .background(
                                            color = when {
                                                uiState.errorMessage != null -> AccentRed
                                                !uiState.settings.hasSelectedLine -> AccentGold
                                                activeReliableCount == 0 -> AccentGold
                                                else -> AccentEmerald
                                            },
                                            shape = CircleShape,
                                        ),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                HeroEntranceText(
                                    text = when {
                                        !uiState.settings.hasSelectedLine -> "Scegli una linea"
                                        uiState.isLoadingFeed -> "Connessione…"
                                        uiState.lineVehicles.isEmpty() -> "Nessun mezzo"
                                        activeReliableCount == 0 && depotCount > 0 -> "$depotCount in deposito"
                                        activeReliableCount == 0 && offRouteCount > 0 -> "$offRouteCount fuori linea"
                                        activeReliableCount == 1 -> "1 pullman attivo"
                                        else -> "$activeReliableCount pullman attivi"
                                    },
                                    key = "${uiState.settings.selectedLine}_${activeReliableCount}_${depotCount}_$offRouteCount",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = TextPrimary,
                                )
                            }
                            Text(
                                text = when {
                                    !uiState.settings.hasSelectedLine ->
                                        "Tocca per scegliere linea o fermata"
                                    uiState.settings.dataSourceMode.isFixed ->
                                        "Solo ${uiState.settings.dataSourceMode.labelIt} · Cambia linea"
                                    depotCount > 0 && staleOrStuckCount > 0 ->
                                        "$depotCount deposito · $staleOrStuckCount fermo · Cambia"
                                    depotCount > 0 && activeReliableCount > 0 ->
                                        "+$depotCount in deposito · Cambia linea"
                                    offRouteCount > 0 && activeReliableCount > 0 ->
                                        "+$offRouteCount fuori percorso · Cambia linea"
                                    staleOrStuckCount > 0 ->
                                        "$staleOrStuckCount con GPS fermo · Cambia linea"
                                    uiState.onlineSourcesCount > 0 ->
                                        "${uiState.onlineSourcesCount} fonti attive · Linee e fermate"
                                    else -> "Linee e fermate GTT"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (uiState.settings.dataSourceMode.isFixed) AccentGold else TextSubtitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.cd_select_line),
                            tint = TextSubtitle,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Glowing Glass GTT Alerts Button (optional via Settings)
                    if (uiState.settings.showServiceAlerts) {
                        GttAlertsGlassButton(
                            hasLineAlert = uiState.lineAlerts.isNotEmpty(),
                            hasGeneralAlerts = uiState.allAlerts.isNotEmpty(),
                            badgeCount = if (uiState.lineAlerts.isNotEmpty()) {
                                uiState.lineAlerts.size
                            } else {
                                uiState.allAlerts.size
                            },
                            isLightBasemap = isLightBasemap,
                            onClick = { showAlertsDialog = true },
                        )
                    }

                    // Discreet optional GPS Location button right beside Refresh
                    UserLocationGlassButton(
                        isEnabled = uiState.isUserLocationEnabled,
                        isLocating = uiState.isLocatingUser && isForegroundActive,
                        isLightBasemap = isLightBasemap,
                        onClick = handleUserLocationTap,
                    )

                    // Refresh button with integrated circular live-countdown progress ring
                    RefreshProgressGlassButton(
                        pollProgressFlow = pollProgressFlow,
                        isRefreshing = uiState.isRefreshing && isForegroundActive,
                        isLightBasemap = isLightBasemap,
                        onClick = onRefresh,
                        modifier = Modifier.testTag("refresh_map_button"),
                    )
                }
            }

            // Unobtrusive Direction Filter + Favorite Stops micro-pills bar
            val hasTwoDirections = directionTermini.size >= 2 && uiState.settings.showRouteLine
            if (hasTwoDirections || uiState.favoriteStops.isNotEmpty()) {
                val neutralPillRim = remember {
                    liquidGlassRimBrush(topAlpha = 0.46f, midAlpha = 0.14f, bottomAlpha = 0.24f)
                }
                val neutralPillFillBrush = remember {
                    Brush.verticalGradient(listOf(Color(0x16FFFFFF), Color.Transparent))
                }
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("favorite_stops_bar"),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (hasTwoDirections) {
                        item(key = "direction_filter_chip") {
                            val dir0Name = directionTermini[0] ?: "Andata"
                            val dir1Name = directionTermini[1] ?: "Ritorno"
                            val activeAccent = when (focusedDirectionFilter) {
                                0 -> AccentGold
                                1 -> if (uiState.settings.colorByDirection) AccentCyan else AccentGold
                                else -> TextPrimary
                            }
                            val isFiltered = focusedDirectionFilter != null
                            val dirInteraction = remember { MutableInteractionSource() }
                            val pillBaseFill = if (isLightBasemap) OverlayPillLightMapFill else OverlayPillFill
                            val dirBorderBrush = remember(isFiltered, activeAccent) {
                                if (isFiltered) {
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.White.copy(alpha = 0.56f),
                                            activeAccent.copy(alpha = 0.80f),
                                        ),
                                    )
                                } else {
                                    neutralPillRim
                                }
                            }
                            val dirFillBrush = remember(isFiltered, activeAccent) {
                                if (isFiltered) {
                                    Brush.verticalGradient(
                                        listOf(activeAccent.copy(alpha = 0.22f), activeAccent.copy(alpha = 0.05f)),
                                    )
                                } else {
                                    neutralPillFillBrush
                                }
                            }
                            Surface(
                                modifier = Modifier
                                    .glassInteractive(dirInteraction) {
                                        focusedDirectionFilter = when (focusedDirectionFilter) {
                                            null -> 0
                                            0 -> 1
                                            else -> null
                                        }
                                    }
                                    .testTag("direction_focus_chip"),
                                shape = RoundedCornerShape(999.dp),
                                color = pillBaseFill,
                                border = BorderStroke(
                                    width = if (isFiltered) 1.3.dp else 1.dp,
                                    brush = dirBorderBrush,
                                ),
                            ) {
                                Box(modifier = Modifier.background(dirFillBrush)) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        if (focusedDirectionFilter == null) {
                                            // Dual Gold + Cyan dots indicating both directions active
                                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(7.dp)
                                                        .background(AccentGold, CircleShape),
                                                )
                                                Box(
                                                    modifier = Modifier
                                                        .size(7.dp)
                                                        .background(
                                                            color = if (uiState.settings.colorByDirection) AccentCyan else AccentGold,
                                                            shape = CircleShape,
                                                        ),
                                                )
                                            }
                                            Text(
                                                text = "2 direzioni",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextPrimary,
                                                maxLines = 1,
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size(7.dp)
                                                    .background(activeAccent, CircleShape),
                                            )
                                            Text(
                                                text = "→ ${if (focusedDirectionFilter == 0) dir0Name else dir1Name}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = activeAccent,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.widthIn(max = 280.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    items(uiState.favoriteStops, key = { it.stopId }) { fav ->
                        val isSelectedFav = uiState.selectedStop?.stopId.equals(fav.stopId, ignoreCase = true)
                        val favInteraction = remember { MutableInteractionSource() }
                        val favLabel = remember(fav.name) { shortTurinPlaceName(fav.name) }
                        val favBorderBrush = remember(isSelectedFav) {
                            if (isSelectedFav) {
                                Brush.verticalGradient(
                                    listOf(Color.White.copy(alpha = 0.60f), AccentGold.copy(alpha = 0.85f)),
                                )
                            } else {
                                neutralPillRim
                            }
                        }
                        val favFillBrush = remember(isSelectedFav) {
                            if (isSelectedFav) {
                                Brush.verticalGradient(
                                    listOf(AccentGold.copy(alpha = 0.24f), AccentGold.copy(alpha = 0.06f)),
                                )
                            } else {
                                neutralPillFillBrush
                            }
                        }
                        Surface(
                            modifier = Modifier
                                .glassInteractive(favInteraction) { handleOpenFavoriteStop(fav) }
                                .testTag("favorite_stop_chip_${fav.stopId}"),
                            shape = RoundedCornerShape(999.dp),
                            color = if (isLightBasemap) OverlayPillLightMapFill else OverlayPillFill,
                            border = BorderStroke(
                                width = if (isSelectedFav) 1.25.dp else 1.dp,
                                brush = favBorderBrush,
                            ),
                        ) {
                            Box(modifier = Modifier.background(favFillBrush)) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Star,
                                        contentDescription = null,
                                        tint = AccentGold,
                                        modifier = Modifier.size(13.dp),
                                    )
                                    Text(
                                        text = favLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 128.dp),
                                    )
                                    Box(
                                        modifier = Modifier
                                            .background(AccentGold.copy(alpha = 0.20f), RoundedCornerShape(999.dp))
                                            .padding(horizontal = 5.dp, vertical = 1.dp),
                                    ) {
                                        Text(
                                            text = fav.lineCode,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = AccentGold,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val errorCardContent: @Composable (Modifier) -> Unit = { cardModifier ->
            AnimatedVisibility(
                visible = uiState.errorMessage != null,
                enter = if (isWideScreen) {
                    fadeIn() + slideInHorizontally(animationSpec = glassSpring()) { it / 2 }
                } else {
                    fadeIn() + slideInVertically { it / 2 }
                },
                exit = if (isWideScreen) {
                    fadeOut() + slideOutHorizontally { it / 2 }
                } else {
                    fadeOut() + slideOutVertically { it / 2 }
                },
            ) {
                LiquidGlassOverlaySurface(
                    isLightBasemap = isLightBasemap,
                    accentTint = AccentRed,
                    shape = RoundedCornerShape(20.dp),
                    modifier = cardModifier,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = ToBusIcons.WarningAmber,
                            contentDescription = null,
                            tint = AccentRed,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = uiState.errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextBody,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRefresh) {
                            Text("Riprova", color = AccentCyan)
                        }
                    }
                }
            }
        }

        val selBus = uiState.selectedVehicle
        val selStop = uiState.selectedStop
        val inspectorTarget = remember(selBus, selStop) {
            when {
                selBus != null -> MapInspectorTarget.Bus(selBus)
                selStop != null -> MapInspectorTarget.Stop(selStop)
                else -> MapInspectorTarget.None
            }
        }

        val inspectorCardSlot: @Composable (Modifier, Dp) -> Unit = { cardModifier, maxScrollHeight ->
            var renderedTarget by remember { mutableStateOf<MapInspectorTarget>(MapInspectorTarget.None) }
            if (inspectorTarget !is MapInspectorTarget.None) {
                renderedTarget = inspectorTarget
            }

            AnimatedVisibility(
                visible = inspectorTarget !is MapInspectorTarget.None,
                enter = fadeIn(tween(190, easing = FastOutSlowInEasing)) +
                    slideInVertically(tween(230, easing = FastOutSlowInEasing)) { it / 6 },
                exit = fadeOut(tween(145)) +
                    slideOutVertically(tween(180, easing = FastOutSlowInEasing)) { it / 6 },
            ) {
                AnimatedContent(
                    targetState = renderedTarget,
                    contentAlignment = if (isWideScreen) Alignment.BottomEnd else Alignment.BottomCenter,
                    contentKey = { target ->
                        when (target) {
                            MapInspectorTarget.None -> "none"
                            is MapInspectorTarget.Bus -> "bus_${target.vehicle.vehicleId}"
                            is MapInspectorTarget.Stop -> "stop_${target.stop.stopId}"
                        }
                    },
                    transitionSpec = {
                        val enterAnim = fadeIn(tween(190, delayMillis = 35, easing = FastOutSlowInEasing)) +
                            scaleIn(initialScale = 0.985f, animationSpec = tween(210, easing = FastOutSlowInEasing))
                        val exitAnim = fadeOut(tween(120))
                        enterAnim.togetherWith(exitAnim).using(
                            SizeTransform(clip = false) { _, _ ->
                                tween(durationMillis = 210, easing = FastOutSlowInEasing)
                            },
                        )
                    },
                    label = "map_inspector_card_transition",
                ) { target ->
                    when (target) {
                        MapInspectorTarget.None -> Unit

                        is MapInspectorTarget.Bus -> {
                            val bus = target.vehicle
                            val isExternal = uiState.focusedExternalVehicle?.vehicleId == bus.vehicleId &&
                                uiState.lineVehicles.none { it.vehicleId == bus.vehicleId }
                            val activePatterns = if (isExternal) uiState.focusedExternalRoutePatterns else uiState.routePatterns
                            val resolvedDirectionId = if (isExternal) {
                                GttFleetAnalyzer.resolveDirectionId(bus, activePatterns) ?: bus.directionId
                            } else {
                                resolvedDirectionById[bus.vehicleId]
                                    ?: GttFleetAnalyzer.resolveDirectionId(bus, activePatterns)
                            }
                            val currentStop = remember(bus, activePatterns, resolvedDirectionId) {
                                GttFleetAnalyzer.findCurrentStop(bus, activePatterns, resolvedDirectionId)
                            }
                            val health = if (isExternal) {
                                GttFleetAnalyzer.assess(bus, activePatterns, uiState.nowMillis)
                            } else {
                                healthByVehicleId[bus.vehicleId]
                                    ?: GttFleetAnalyzer.assess(bus, activePatterns, uiState.nowMillis)
                            }
                            SelectedBusInspectorCard(
                                bus = bus,
                                isExternal = isExternal,
                                isLightBasemap = isLightBasemap,
                                resolvedDirectionId = resolvedDirectionId,
                                health = health,
                                terminusName = if (isExternal) {
                                    uiState.focusedExternalTerminus
                                        ?: activePatterns.firstOrNull { it.directionId == resolvedDirectionId }?.terminusName()
                                } else {
                                    uiState.terminusForDirection(resolvedDirectionId)
                                },
                                currentStopName = currentStop?.name,
                                nowMillis = uiState.nowMillis,
                                colorByDirection = uiState.settings.colorByDirection,
                                onSwitchToLine = if (isExternal) {
                                    { onSelectLine(bus.lineCode) }
                                } else {
                                    null
                                },
                                onClose = { onSelectVehicle(null) },
                                modifier = cardModifier,
                            )
                        }

                        is MapInspectorTarget.Stop -> {
                            val stop = target.stop
                            val isStopOnTrackedLine = remember(stop.stopId, stop.stopCode, uiState.routePatterns) {
                                uiState.routePatterns.any { pat ->
                                    pat.stops.any {
                                        it.stopId.equals(stop.stopId, ignoreCase = true) ||
                                            it.stopCode.equals(stop.stopCode, ignoreCase = true)
                                    }
                                }
                            }
                            val stopTerminusName = if (isStopOnTrackedLine) {
                                uiState.terminusForDirection(stop.directionId)
                            } else {
                                uiState.stopArrivals.firstOrNull {
                                    it.headsign.isNotBlank() && !it.headsign.startsWith("Linea ", ignoreCase = true)
                                }?.headsign
                            }
                            SelectedStopArrivalsCard(
                                stop = stop,
                                terminusName = stopTerminusName,
                                selectedLine = uiState.settings.selectedLine,
                                arrivals = uiState.stopArrivals,
                                isLoading = uiState.isLoadingStopArrivals,
                                isFavorite = uiState.isFavoriteStop(stop.stopId),
                                isLightBasemap = isLightBasemap,
                                nowMillis = uiState.nowMillis,
                                colorByDirection = uiState.settings.colorByDirection,
                                maxScrollHeight = maxScrollHeight,
                                onToggleFavorite = { onToggleFavoriteStop(stop) },
                                onRefresh = onRefreshStopArrivals,
                                onArrivalTap = handleFocusArrivalBus,
                                onClose = { onSelectStop(null) },
                                modifier = cardModifier,
                            )
                        }
                    }
                }
            }
        }

        val activeBusChipsBar: @Composable () -> Unit = {
            if (sortedVehicles.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sortedVehicles, key = { it.entityId }) { bus ->
                        val isSelected = bus.vehicleId == uiState.selectedVehicleId
                        val health = healthByVehicleId[bus.vehicleId]
                            ?: GttFleetAnalyzer.assess(bus, uiState.routePatterns, uiState.nowMillis)
                        val resolvedDir = resolvedDirectionById[bus.vehicleId]
                        val directionAccent = when {
                            health.isInDepot || health.isOffRoute -> null
                            uiState.settings.colorByDirection && resolvedDir == 1 -> AccentCyan
                            else -> AccentGold
                        }
                        ActiveBusDirectionGlassPill(
                            bus = bus,
                            health = health,
                            nowMillis = uiState.nowMillis,
                            directionAccent = directionAccent,
                            isSelected = isSelected,
                            isLightBasemap = isLightBasemap,
                            onClick = { handleSelectVehicle(bus) },
                        )
                    }
                }
            } else if (uiState.settings.hasSelectedLine && !uiState.isLoadingFeed && uiState.errorMessage == null) {
                val isFixedMato = uiState.settings.dataSourceMode == DataSourceMode.MATO_LIVE
                val matoStatusDetail = if (isFixedMato) {
                    uiState.sourceStatuses.firstOrNull { it.id == "mato_live" }?.detail
                } else {
                    null
                }
                MapOverlayGlassPill(
                    isLightBasemap = isLightBasemap,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = ToBusIcons.DirectionsBus,
                            contentDescription = null,
                            tint = AccentGold,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                text = when {
                                    uiState.settings.hideInactiveOnMap && uiState.lineVehicles.isNotEmpty() ->
                                        "${uiState.lineVehicles.size} mezzi in deposito o fermi nascosti"
                                    isFixedMato ->
                                        "MaTO Live · 0 mezzi sulla linea ${uiState.settings.selectedLine}"
                                    else ->
                                        "Nessun mezzo attivo ora sulla linea ${uiState.settings.selectedLine}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextBody,
                            )
                            if (!matoStatusDetail.isNullOrBlank()) {
                                Text(
                                    text = matoStatusDetail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextBody.copy(alpha = 0.75f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (isFixedMato) {
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(
                                onClick = { onSelectDataSourceMode(DataSourceMode.AUTO) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("switch_to_auto_source_button"),
                            ) {
                                Text(
                                    text = "Usa Auto",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AccentCyan,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        }

        val isInspectingCard = uiState.selectedVehicle != null || uiState.selectedStop != null

        if (isWideScreen) {
            // Tablet / Foldable Layout:
            // - Inspector & stop arrivals dock cleanly to the bottom-end side panel so the map stays unobstructed
            // - Active bus pills float neatly above the system gesture bar along the bottom edge
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = 74.dp)
                    .widthIn(max = 408.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.End,
            ) {
                errorCardContent(Modifier.fillMaxWidth())
                inspectorCardSlot(Modifier.fillMaxWidth(), 356.dp)
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 14.dp),
            ) {
                activeBusChipsBar()
            }
        } else {
            // Smartphone Compact Layout: centered vertical stack above FloatingGlassBottomBar
            // Automatically collapses bottom bus pills while reading a Stop/Bus card to free vertical map space!
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = bottomNavClearance + 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val compactCardModifier = Modifier
                    .padding(horizontal = 16.dp)
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                errorCardContent(compactCardModifier)
                inspectorCardSlot(compactCardModifier, 332.dp)
                AnimatedVisibility(
                    visible = !isInspectingCard,
                    enter = fadeIn(tween(200)) + expandVertically(tween(220)) + slideInVertically(tween(220)) { it / 2 },
                    exit = fadeOut(tween(150)) + shrinkVertically(tween(180)) + slideOutVertically(tween(180)) { it / 2 },
                ) {
                    activeBusChipsBar()
                }
            }
        }
    }

    if (showAlertsDialog && uiState.settings.showServiceAlerts) {
        GttAlertsDialog(
            selectedLine = uiState.settings.selectedLine,
            lineAlerts = uiState.lineAlerts,
            allAlerts = uiState.allAlerts,
            onDismiss = { showAlertsDialog = false },
        )
    }
}

@Composable
private fun ActiveBusDirectionGlassPill(
    bus: BusVehicle,
    health: VehicleHealthAssessment,
    nowMillis: Long,
    directionAccent: Color?,
    isSelected: Boolean,
    isLightBasemap: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val freshness = bus.freshness(nowMillis)
    val indicatorColor = when {
        health.isInDepot -> Color(0xFFF59E0B)
        health.isOffRoute -> Color(0xFFF59E0B)
        health.isStaleGps -> AccentRed
        health.isStuck -> AccentRed
        health.isAtTerminus -> directionAccent ?: AccentCyan
        freshness == VehicleFreshness.LIVE -> AccentEmerald
        freshness == VehicleFreshness.SLOW -> AccentGold
        else -> AccentRed
    }
    val statusTag = when {
        health.isInDepot -> "Deposito"
        health.isOffRoute -> "Fuori linea"
        health.isStaleGps -> bus.gpsAgeSeconds(nowMillis)?.let {
            "agg. ${formatCompactAge(it)} fa"
        } ?: "GPS vecchio"
        health.isStuck -> health.stationarySeconds?.let {
            "fermo ${formatCompactAge(it)}"
        } ?: "fermo"
        health.isAtTerminus -> "Capolinea"
        else -> bus.gpsAgeSeconds(nowMillis)?.let {
            "agg. ${formatCompactAge(it)} fa"
        } ?: "agg. N/D"
    }

    val isDimmed = health.isInDepot || health.isStuck || health.isStaleGps || health.isOffRoute
    val pillShape = RoundedCornerShape(999.dp)
    val interaction = remember { MutableInteractionSource() }

    val smokedBaseBrush = remember(isSelected, isLightBasemap) {
        when {
            isSelected && isLightBasemap -> Brush.verticalGradient(
                colors = listOf(Color(0xE0212A3B), Color(0xD8151C29)),
            )
            isSelected -> Brush.verticalGradient(
                colors = listOf(Color(0xEB1D2230), Color(0xE811141E)),
            )
            isLightBasemap -> Brush.verticalGradient(
                colors = listOf(OverlayBusPillLightTop, OverlayBusPillLightBottom),
            )
            else -> Brush.verticalGradient(
                colors = listOf(Color(0xDD171B26), Color(0xD80E1119)),
            )
        }
    }

    val glassTintBrush = remember(directionAccent, isSelected, isDimmed, isLightBasemap) {
        if (directionAccent == null) {
            Brush.verticalGradient(
                colors = listOf(
                    Color(if (isSelected || isLightBasemap) 0x1EFFFFFF else 0x12FFFFFF),
                    Color.Transparent,
                ),
            )
        } else {
            val topAlpha = when {
                isSelected && !isDimmed -> 0.28f
                isSelected -> 0.18f
                !isDimmed -> if (isLightBasemap) 0.20f else 0.16f
                else -> 0.08f
            }
            val endAlpha = when {
                isSelected && !isDimmed -> 0.06f
                else -> 0.01f
            }
            Brush.verticalGradient(
                colors = listOf(
                    directionAccent.copy(alpha = topAlpha),
                    directionAccent.copy(alpha = endAlpha),
                ),
            )
        }
    }

    val borderBrush = remember(directionAccent, isSelected, isDimmed, isLightBasemap) {
        when {
            directionAccent == null -> liquidGlassRimBrush(
                topAlpha = if (isSelected) 0.72f else if (isLightBasemap) 0.56f else 0.46f,
                midAlpha = 0.16f,
                bottomAlpha = 0.26f,
            )
            isSelected -> Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.85f),
                    directionAccent.copy(alpha = 0.82f),
                ),
            )
            else -> Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (isDimmed) 0.28f else if (isLightBasemap) 0.58f else 0.46f),
                    directionAccent.copy(alpha = if (isDimmed) 0.18f else 0.44f),
                ),
            )
        }
    }

    Box(
        modifier = modifier
            .glassInteractive(interaction, onClick = onClick)
            .background(smokedBaseBrush, pillShape)
            .background(glassTintBrush, pillShape)
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                brush = borderBrush,
                shape = pillShape,
            )
            .testTag("bus_chip_${bus.vehicleId}"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(directionAccent ?: indicatorColor, CircleShape),
            )
            Text(
                text = "#${bus.label}",
                style = MaterialTheme.typography.labelLarge,
                color = if (health.isInDepot) TextSubtitle else TextPrimary,
            )
            Text(
                text = "· $statusTag",
                style = MaterialTheme.typography.labelSmall,
                color = indicatorColor,
            )
        }
    }
}

@Composable
private fun GttAlertsGlassButton(
    hasLineAlert: Boolean,
    hasGeneralAlerts: Boolean,
    badgeCount: Int,
    isLightBasemap: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val bgBrush = remember(hasLineAlert, hasGeneralAlerts, isLightBasemap) {
        val neutralFill = if (isLightBasemap) OverlayPillLightMapFill else OverlayPillFill
        when {
            hasLineAlert -> Brush.verticalGradient(
                listOf(Color(0xEE5C1420), Color(0xEB2F0A11)),
            )
            hasGeneralAlerts -> Brush.verticalGradient(
                listOf(Color(0xEE4A3712), Color(0xEB241B08)),
            )
            else -> Brush.verticalGradient(
                listOf(neutralFill, neutralFill),
            )
        }
    }
    val borderBrush = remember(hasLineAlert, hasGeneralAlerts) {
        when {
            hasLineAlert -> Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), AccentRed))
            hasGeneralAlerts -> Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), AccentGold.copy(alpha = 0.80f)))
            else -> liquidGlassRimBrush(topAlpha = 0.46f, midAlpha = 0.15f, bottomAlpha = 0.26f)
        }
    }
    val iconTint = when {
        hasLineAlert -> Color(0xFFFF7A7A)
        hasGeneralAlerts -> AccentGold
        else -> TextSubtitle
    }

    Box(
        modifier = modifier
            .size(48.dp)
            .glassInteractive(interaction, onClick = onClick)
            .testTag("gtt_alerts_button"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(bgBrush, CircleShape)
                .border(
                    width = if (hasLineAlert || hasGeneralAlerts) 1.4.dp else 1.dp,
                    brush = borderBrush,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (hasLineAlert || hasGeneralAlerts) {
                    ToBusIcons.NotificationsActive
                } else {
                    ToBusIcons.NotificationsNone
                },
                contentDescription = stringResource(R.string.cd_gtt_alerts),
                tint = iconTint,
                modifier = Modifier.size(20.dp),
            )
        }

        if (hasLineAlert || hasGeneralAlerts) {
            val dotColor = if (hasLineAlert) AccentRed else AccentGold
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 2.dp)
                    .background(dotColor, CircleShape)
                    .border(1.dp, Color(0xFF0A0A0E), CircleShape)
                    .padding(horizontal = if (badgeCount > 9) 4.dp else 5.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (badgeCount > 9) "9+" else "$badgeCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasLineAlert) Color.White else DarkInkOnAccent,
                )
            }
        }
    }
}

@Composable
private fun UserLocationGlassButton(
    isEnabled: Boolean,
    isLocating: Boolean,
    isLightBasemap: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val borderColor by animateColorAsState(
        targetValue = if (isEnabled) AccentCyan.copy(alpha = 0.85f) else OverlayPillBorder,
        animationSpec = tween(durationMillis = 240),
        label = "gps_border_color",
    )
    val iconTint by animateColorAsState(
        targetValue = if (isEnabled) AccentCyan else TextPrimary.copy(alpha = 0.88f),
        animationSpec = tween(durationMillis = 240),
        label = "gps_icon_tint",
    )
    val fillBrush = remember(isEnabled, isLightBasemap) {
        val neutralFill = if (isLightBasemap) OverlayPillLightMapFill else OverlayPillFill
        if (isEnabled) {
            Brush.verticalGradient(listOf(Color(0xEE103044), Color(0xEB091C2A)))
        } else {
            Brush.verticalGradient(listOf(neutralFill, neutralFill))
        }
    }
    val inactiveRimBrush = remember {
        liquidGlassRimBrush(topAlpha = 0.46f, midAlpha = 0.15f, bottomAlpha = 0.26f)
    }

    val locatingAlpha = if (isLocating) {
        val transition = rememberInfiniteTransition(label = "gps_locating_pulse")
        val a by transition.animateFloat(
            initialValue = 0.42f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "gps_locating_alpha",
        )
        a
    } else {
        1f
    }

    Box(
        modifier = modifier
            .size(48.dp)
            .glassInteractive(interaction, onClick = onClick)
            .testTag("user_location_button"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(fillBrush, CircleShape)
                .border(
                    width = if (isEnabled) 1.4.dp else 1.dp,
                    brush = if (isEnabled) {
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), borderColor))
                    } else {
                        inactiveRimBrush
                    },
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = ToBusIcons.MyLocation,
                contentDescription = stringResource(R.string.cd_user_location),
                tint = iconTint,
                modifier = Modifier
                    .size(19.dp)
                    .graphicsLayer { alpha = locatingAlpha },
            )
        }
    }
}

@Composable
private fun RefreshProgressGlassButton(
    pollProgressFlow: StateFlow<Float>,
    isRefreshing: Boolean,
    isLightBasemap: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress by pollProgressFlow.collectAsStateWithLifecycle()
    val interaction = remember { MutableInteractionSource() }
    val clampedProgress = progress.coerceIn(0f, 1f)
    val fillColor = if (isLightBasemap) OverlayPillLightMapFill else OverlayPillFill
    val smoothProgress = remember { Animatable(clampedProgress) }

    LaunchedEffect(clampedProgress) {
        if (clampedProgress < smoothProgress.value - 0.12f) {
            smoothProgress.snapTo(clampedProgress)
        } else {
            smoothProgress.animateTo(
                targetValue = clampedProgress,
                animationSpec = tween(durationMillis = 960, easing = LinearEasing),
            )
        }
    }

    val rotation = if (isRefreshing) {
        val transition = rememberInfiniteTransition(label = "refresh_icon_spin")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "refresh_spin_angle",
        )
        angle
    } else {
        0f
    }

    Box(
        modifier = modifier
            .size(48.dp)
            .glassInteractive(interaction, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val topSpecularBrush = remember {
            Brush.verticalGradient(
                listOf(
                    Color(0x16FFFFFF),
                    Color.Transparent,
                ),
            )
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(fillColor, CircleShape)
                .background(topSpecularBrush, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokePx = 2.dp.toPx()
                val inset = strokePx / 2f
                val arcSize = androidx.compose.ui.geometry.Size(
                    width = size.width - strokePx,
                    height = size.height - strokePx,
                )
                // Subtle glass outer rim track
                drawArc(
                    color = Color.White.copy(alpha = 0.16f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = 1.dp.toPx()),
                )
                if (isRefreshing) {
                    // Fluid rotating cyan/specular arc while live feeds are fetching
                    drawArc(
                        color = AccentCyan.copy(alpha = 0.28f),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = strokePx),
                    )
                    drawArc(
                        color = AccentCyan,
                        startAngle = -90f + rotation,
                        sweepAngle = 135f,
                        useCenter = false,
                        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round),
                    )
                    drawArc(
                        color = Color.White.copy(alpha = 0.90f),
                        startAngle = -90f + rotation + 92f,
                        sweepAngle = 42f,
                        useCenter = false,
                        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round),
                    )
                } else {
                    // Silky 60fps interpolated countdown sweep along the circular button rim
                    val sweep = smoothProgress.value * 360f
                    if (sweep > 0.5f) {
                        drawArc(
                            color = Color.White.copy(alpha = 0.86f),
                            startAngle = -90f,
                            sweepAngle = sweep,
                            useCenter = false,
                            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(width = strokePx, cap = StrokeCap.Round),
                        )
                    }
                }
            }
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = stringResource(R.string.cd_refresh_map),
                tint = if (isRefreshing) AccentCyan else TextPrimary,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = rotation },
            )
        }
    }
}

@Composable
private fun GttAlertsDialog(
    selectedLine: String,
    lineAlerts: List<ServiceAlertInfo>,
    allAlerts: List<ServiceAlertInfo>,
    onDismiss: () -> Unit,
) {
    val nowSec = remember { System.currentTimeMillis() / 1000L }
    val lineAlertIds = remember(lineAlerts) { lineAlerts.map { it.id }.toHashSet() }
    val otherAlerts = remember(allAlerts, lineAlertIds) {
        allAlerts.filterNot { it.id in lineAlertIds }
    }
    val headerAccent = when {
        lineAlerts.isNotEmpty() -> AccentRed
        allAlerts.isNotEmpty() -> AccentGold
        else -> AccentCyan
    }

    GlassModalDialog(
        onDismissRequest = onDismiss,
        accentTint = headerAccent,
    ) {
        // Glass Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AccentIconBadge(
                icon = if (allAlerts.isNotEmpty()) {
                    ToBusIcons.NotificationsActive
                } else {
                    ToBusIcons.NotificationsNone
                },
                accent = headerAccent,
                size = 42.dp,
                iconSize = 20.dp,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Avvisi GTT",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                )
                Text(
                    text = when {
                        lineAlerts.isNotEmpty() -> "${lineAlerts.size} per Linea $selectedLine · ${allAlerts.size} recenti"
                        allAlerts.isNotEmpty() -> "${allAlerts.size} eventi, lavori e deviazioni recenti"
                        else -> "Nessun avviso recente o nei prossimi 7 gg"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (lineAlerts.isNotEmpty()) Color(0xFFFF8A8A) else TextSubtitle,
                )
            }
            GlassCircleIconButton(
                icon = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.cd_close),
                onClick = onDismiss,
                modifier = Modifier.testTag("close_alerts_dialog_button"),
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (allAlerts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(GlassRowFill, RoundedCornerShape(16.dp))
                    .border(1.dp, GlassRowBorder, RoundedCornerShape(16.dp))
                    .padding(14.dp),
            ) {
                Text(
                    text = "Nessuna deviazione o avviso GTT attivo oggi o previsto nella prossima settimana.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextBody,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (lineAlerts.isNotEmpty()) {
                    Text(
                        text = "LINEA $selectedLine",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFFF8A8A),
                    )
                    lineAlerts.forEach { alert ->
                        AlertItemCard(
                            alert = alert,
                            nowSec = nowSec,
                            highlightForLine = true,
                        )
                    }
                }

                if (otherAlerts.isNotEmpty()) {
                    if (lineAlerts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "ALTRI AVVISI ATTUALI",
                            style = MaterialTheme.typography.labelMedium,
                            color = AccentGold,
                        )
                    }
                    otherAlerts.forEach { alert ->
                        AlertItemCard(
                            alert = alert,
                            nowSec = nowSec,
                            highlightForLine = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertItemCard(
    alert: ServiceAlertInfo,
    nowSec: Long,
    highlightForLine: Boolean,
) {
    val bgColor = if (highlightForLine) Color(0x28F87171) else GlassRowFill
    val borderColor = if (highlightForLine) AccentRed.copy(alpha = 0.60f) else GlassRowBorder
    val chipAccent = if (highlightForLine) AccentRed else AccentGold
    val validity = remember(alert, nowSec) { alert.validityLabel(nowSec) }
    val activeNow = remember(alert, nowSec) { alert.isActiveNow(nowSec) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor, RoundedCornerShape(16.dp))
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (alert.affectedLines.isNotEmpty() || validity != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (validity != null) {
                    val vColor = if (activeNow) AccentEmerald else AccentCyan
                    Box(
                        modifier = Modifier
                            .background(vColor.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
                            .border(1.dp, vColor.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = validity,
                            style = MaterialTheme.typography.labelSmall,
                            color = vColor,
                        )
                    }
                }
                if (alert.affectedLines.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        items(alert.affectedLines.toList().sorted()) { line ->
                            Box(
                                modifier = Modifier
                                    .background(chipAccent.copy(alpha = 0.20f), RoundedCornerShape(999.dp))
                                    .border(1.dp, chipAccent.copy(alpha = 0.48f), RoundedCornerShape(999.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = "Linea $line",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (highlightForLine) Color(0xFFFFB4B4) else AccentGold,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (alert.headerText.isNotBlank()) {
            Text(
                text = alert.headerText,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
            )
        }
        if (alert.descriptionText.isNotBlank() && alert.descriptionText != alert.headerText) {
            Text(
                text = alert.descriptionText,
                style = MaterialTheme.typography.bodySmall,
                color = TextBody,
            )
        }
    }
}

@Composable
private fun SelectedBusInspectorCard(
    bus: BusVehicle,
    isExternal: Boolean,
    isLightBasemap: Boolean,
    resolvedDirectionId: Int?,
    health: VehicleHealthAssessment,
    terminusName: String?,
    currentStopName: String?,
    nowMillis: Long,
    colorByDirection: Boolean,
    onSwitchToLine: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val freshness = bus.freshness(nowMillis)
    val freshnessColor = when {
        health.isInDepot || health.isOffRoute -> AccentGold
        health.isStaleGps -> AccentRed
        freshness == VehicleFreshness.LIVE -> AccentEmerald
        freshness == VehicleFreshness.SLOW -> AccentGold
        else -> AccentRed
    }
    val warningText = remember(health) { health.warningMessage() }
    val terminusText = remember(health) { health.terminusMessage() }
    val ageSeconds = bus.gpsAgeSeconds(nowMillis)
    val isAltDir = colorByDirection && resolvedDirectionId == 1
    val badgeAccent = when {
        isExternal -> AccentEmerald
        isAltDir -> AccentCyan
        else -> AccentGold
    }
    val shortTerminus = remember(terminusName) { terminusName?.takeIf { it.isNotBlank() }?.let(::shortTurinPlaceName) }
    val shortCurrentStop = remember(currentStopName) {
        currentStopName?.takeIf { it.isNotBlank() }?.let(::shortTurinPlaceName)
    }

    val stationarySeconds = remember(bus.stationarySinceMillis, nowMillis) {
        val since = bus.stationarySinceMillis ?: return@remember 0L
        ((nowMillis - since) / 1000L).coerceAtLeast(0L)
    }
    val isActuallyStationary = stationarySeconds >= 25L
    val movementText = when {
        health.isInDepot -> "In deposito"
        health.isAtTerminus -> "Al capolinea"
        isActuallyStationary -> "Fermo da ${formatCompactAge(stationarySeconds)}"
        else -> "In movimento"
    }

    val delayText = remember(bus.delaySeconds) {
        val d = bus.delaySeconds ?: return@remember "Non disp."
        val mins = (abs(d) / 60)
        when {
            abs(d) < 45 -> "In orario"
            d > 0 -> "+${mins.coerceAtLeast(1)} min ritardo"
            else -> "-${mins.coerceAtLeast(1)} min anticipo"
        }
    }
    val delayColor = remember(bus.delaySeconds) {
        val d = bus.delaySeconds ?: return@remember TextSubtitle
        when {
            abs(d) < 45 -> AccentEmerald
            d >= 180 -> AccentRed
            d > 0 -> AccentGold
            else -> AccentCyan
        }
    }

    LiquidGlassOverlaySurface(
        isLightBasemap = isLightBasemap,
        accentTint = badgeAccent,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.testTag("selected_bus_card"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Header: Line + Fleet + Direction badge + Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccentIconBadge(
                    icon = ToBusIcons.DirectionsBus,
                    accent = badgeAccent,
                    size = 44.dp,
                    iconSize = 22.dp,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Vettura #${bus.label}",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary,
                        )
                        if (onSwitchToLine != null) {
                            val switchInteraction = remember { MutableInteractionSource() }
                            Box(
                                modifier = Modifier
                                    .glassInteractive(switchInteraction, onClick = onSwitchToLine)
                                    .background(badgeAccent.copy(alpha = 0.22f), RoundedCornerShape(999.dp))
                                    .border(1.dp, badgeAccent.copy(alpha = 0.65f), RoundedCornerShape(999.dp))
                                    .padding(horizontal = 9.dp, vertical = 3.dp)
                                    .testTag("switch_to_external_line_button"),
                            ) {
                                Text(
                                    text = "Passa a ${bus.lineCode} ↗",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = badgeAccent,
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .background(badgeAccent.copy(alpha = 0.2f), RoundedCornerShape(999.dp))
                                    .border(1.dp, badgeAccent.copy(alpha = 0.5f), RoundedCornerShape(999.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = "Linea ${bus.lineCode}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = badgeAccent,
                                )
                            }
                        }
                    }
                    if (shortTerminus != null) {
                        DirectionColorBadge(
                            label = shortTerminus,
                            accent = badgeAccent,
                            prefix = "→",
                        )
                    }
                    if (shortCurrentStop != null) {
                        Row(
                            modifier = Modifier
                                .background(GlassRowFill, RoundedCornerShape(999.dp))
                                .border(1.dp, GlassRowBorder, RoundedCornerShape(999.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                .testTag("bus_current_stop_pill"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Place,
                                contentDescription = null,
                                tint = badgeAccent,
                                modifier = Modifier.size(12.dp),
                            )
                            Text(
                                text = shortCurrentStop,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                GlassCircleIconButton(
                    icon = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.cd_close),
                    onClick = onClose,
                )
            }

            // Smart Depot / Stale / Stuck / Off-Route Alert Strip OR calm Capolinea info strip
            if (warningText != null) {
                val warnColor = if (health.isStaleGps || health.isStuck) AccentRed else AccentGold
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(warnColor.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
                        .border(1.dp, warnColor.copy(alpha = 0.50f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                        .testTag("bus_health_warning"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = ToBusIcons.WarningAmber,
                        contentDescription = null,
                        tint = warnColor,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = warningText,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else if (terminusText != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(badgeAccent.copy(alpha = 0.14f), RoundedCornerShape(14.dp))
                        .border(1.dp, badgeAccent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                        .testTag("bus_terminus_info"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Place,
                        contentDescription = null,
                        tint = badgeAccent,
                        modifier = Modifier.size(17.dp),
                    )
                    Text(
                        text = terminusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Compact inline Live GPS indicator: pulsing dot + status + elapsed age
            val statusShortLabel = when {
                health.isInDepot -> "Deposito"
                health.isOffRoute -> "Fuori linea"
                health.isAtTerminus -> "Capolinea"
                freshness == VehicleFreshness.LIVE -> "Live"
                freshness == VehicleFreshness.SLOW -> "Segnale lento"
                else -> "Segnale fermo"
            }
            val shouldPulse = !health.isInDepot && !health.isOffRoute && freshness == VehicleFreshness.LIVE
            val pulseAlpha: Float
            val pulseScale: Float
            if (shouldPulse) {
                val pulseTransition = rememberInfiniteTransition(label = "bus_live_dot_pulse")
                val animAlpha by pulseTransition.animateFloat(
                    initialValue = 0.22f,
                    targetValue = 0.72f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 1050, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "bus_live_halo_alpha",
                )
                val animScale by pulseTransition.animateFloat(
                    initialValue = 1.0f,
                    targetValue = 1.95f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 1050, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "bus_live_halo_scale",
                )
                pulseAlpha = animAlpha
                pulseScale = animScale
            } else {
                pulseAlpha = 0f
                pulseScale = 1f
            }
            val ageDisplay = ageSeconds?.let { "agg. ${formatDetailedAge(it)}" } ?: "agg. N/D"

            Row(
                modifier = Modifier
                    .background(freshnessColor.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
                    .border(1.dp, freshnessColor.copy(alpha = 0.34f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(
                    modifier = Modifier.size(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (shouldPulse) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .graphicsLayer {
                                    scaleX = pulseScale
                                    scaleY = pulseScale
                                    alpha = pulseAlpha
                                }
                                .background(freshnessColor, CircleShape),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(freshnessColor, CircleShape),
                    )
                }
                Text(
                    text = statusShortLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = freshnessColor,
                )
                Text(
                    text = "·",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSubtitle,
                )
                Text(
                    text = ageDisplay,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextBody,
                    modifier = Modifier.testTag("selected_bus_age_text"),
                )
            }

            // Clean metrics: Movement & Delay (Speed removed as requested)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RecessedMetricPill(
                    label = "Posizione",
                    value = movementText,
                    valueColor = if (isActuallyStationary) AccentGold else AccentEmerald,
                    modifier = Modifier.weight(1f),
                )
                RecessedMetricPill(
                    label = "Ritardo",
                    value = delayText,
                    valueColor = delayColor,
                    modifier = Modifier.weight(1f),
                )
            }

            // Discreet 1-line data sources micro-footer (compact, non-intrusive)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .testTag("bus_sources_footer"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .background(AccentCyan.copy(alpha = 0.72f), CircleShape),
                )
                Text(
                    text = "Fonti: ${bus.contributingSources.joinToString(" · ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSubtitle.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}


private fun formatCompactAge(seconds: Long): String {
    return when {
        seconds < 60L -> "${seconds}s"
        seconds < 3600L -> "${seconds / 60}m"
        else -> "${seconds / 3600}h"
    }
}

private fun formatDetailedAge(seconds: Long): String {
    return when {
        seconds < 60L -> "${seconds}s fa"
        seconds < 3600L -> {
            val m = seconds / 60L
            val s = seconds % 60L
            "${m}m ${s}s fa"
        }
        else -> "${seconds / 3600L}h fa"
    }
}

@Composable
private fun SelectedStopArrivalsCard(
    stop: TransitStop,
    terminusName: String?,
    selectedLine: String,
    arrivals: List<StopArrival>,
    isLoading: Boolean,
    isFavorite: Boolean,
    isLightBasemap: Boolean,
    nowMillis: Long,
    colorByDirection: Boolean,
    maxScrollHeight: Dp = 246.dp,
    onToggleFavorite: () -> Unit,
    onRefresh: () -> Unit,
    onArrivalTap: (StopArrival, Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isAltDir = colorByDirection && stop.directionId == 1
    val badgeAccent = if (isAltDir) AccentCyan else AccentGold
    val clockFormat = remember { SimpleDateFormat("HH:mm", Locale.ITALY) }
    val displayStopName = remember(stop.name) { shortTurinPlaceName(stop.name) }
    val shortTerminus = remember(terminusName) { terminusName?.takeIf { it.isNotBlank() }?.let(::shortTurinPlaceName) }

    // Group arrivals cleanly by line (tracked line first, then by earliest arrival)
    val groupedLines = remember(arrivals, selectedLine) {
        arrivals
            .groupBy { it.lineCode.trim().uppercase() }
            .entries
            .map { (line, list) -> line to list.sortedBy { it.arrivalEpochSeconds }.take(4) }
            .sortedWith(
                compareByDescending<Pair<String, List<StopArrival>>> {
                    it.first.equals(selectedLine, ignoreCase = true)
                }.thenBy { it.second.firstOrNull()?.arrivalEpochSeconds ?: Long.MAX_VALUE },
            )
    }

    LiquidGlassOverlaySurface(
        isLightBasemap = isLightBasemap,
        accentTint = badgeAccent,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.testTag("selected_stop_card"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header: Stop icon, clean name, colored direction pill, Star + Refresh + Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccentIconBadge(
                    icon = Icons.Rounded.Place,
                    accent = badgeAccent,
                    size = 42.dp,
                    iconSize = 21.dp,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = displayStopName,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "#${stop.displayCode}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSubtitle,
                        )
                        if (shortTerminus != null) {
                            DirectionColorBadge(
                                label = shortTerminus,
                                accent = badgeAccent,
                                prefix = "→",
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    GlassCircleIconButton(
                        icon = if (isFavorite) Icons.Rounded.Star else ToBusIcons.StarOutline,
                        contentDescription = stringResource(R.string.cd_favorite_stop),
                        onClick = onToggleFavorite,
                        tint = if (isFavorite) AccentGold else TextSubtitle,
                        modifier = Modifier.testTag("favorite_stop_button"),
                    )
                    GlassCircleIconButton(
                        icon = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.cd_refresh_map),
                        onClick = onRefresh,
                        spinning = isLoading,
                        modifier = Modifier.testTag("refresh_stop_arrivals_button"),
                    )
                    GlassCircleIconButton(
                        icon = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.cd_close),
                        onClick = onClose,
                        modifier = Modifier.testTag("close_stop_card_button"),
                    )
                }
            }

            // Grouped arrivals content (one crisp card per line with time + minutes + Live/Stimato)
            when {
                isLoading && groupedLines.isEmpty() -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(GlassRowFill, RoundedCornerShape(14.dp))
                            .border(1.dp, GlassRowBorder, RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = badgeAccent,
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = "Cerco i pullman in arrivo…",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextBody,
                        )
                    }
                }

                groupedLines.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(GlassRowFill, RoundedCornerShape(14.dp))
                            .border(1.dp, GlassRowBorder, RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                    ) {
                        Text(
                            text = "Nessun passaggio imminente rilevato per questa fermata.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSubtitle,
                        )
                    }
                }

                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxScrollHeight)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Tocca un arrivo Live · mirino = posizione GPS attiva",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSubtitle,
                        )
                        groupedLines.forEach { (lineCode, lineArrivals) ->
                            val isTrackedLine = lineCode.equals(selectedLine, ignoreCase = true)
                            val headsign = lineArrivals
                                .firstOrNull { !com.example.data.model.isGenericDirectionLabel(it.headsign) }
                                ?.headsign
                                ?.let(::shortTurinPlaceName)
                                ?: (if (isTrackedLine) shortTerminus else null)
                                ?: lineArrivals
                                    .firstOrNull { it.headsign.isNotBlank() && !it.headsign.startsWith("Linea ", true) }
                                    ?.headsign
                                    ?.let(::shortTurinPlaceName)

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (isTrackedLine) Color(0x1EFFFFFF) else GlassRowFill, RoundedCornerShape(16.dp))
                                    .border(
                                        width = 1.dp,
                                        color = if (isTrackedLine) badgeAccent.copy(alpha = 0.45f) else GlassRowBorder,
                                        shape = RoundedCornerShape(16.dp),
                                    )
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                // Line header row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                color = if (isTrackedLine) badgeAccent else Color(0x28FFFFFF),
                                                shape = RoundedCornerShape(999.dp),
                                            )
                                            .padding(horizontal = 10.dp, vertical = 3.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = lineCode,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (isTrackedLine) DarkInkOnAccent else TextPrimary,
                                        )
                                    }
                                    if (!headsign.isNullOrBlank()) {
                                        Text(
                                            text = "→ $headsign",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = TextBody,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }

                                // Horizontal clickable chips: Minutes + Exact Clock + Live / Stimato -> tap to locate bus!
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                ) {
                                    lineArrivals.forEachIndexed { indexInLine, arrival ->
                                        val mins = arrival.minutesUntil(nowMillis).coerceAtLeast(0)
                                        val countdownText = when {
                                            mins <= 0 -> "Ora"
                                            mins == 1 -> "1 min"
                                            else -> "$mins min"
                                        }
                                        val clockText = remember(arrival.arrivalEpochSeconds, clockFormat) {
                                            clockFormat.format(Date(arrival.arrivalEpochSeconds * 1000L))
                                        }
                                        val chipBg = if (arrival.isRealtime) {
                                            AccentEmerald.copy(alpha = 0.14f)
                                        } else {
                                            Color(0x14FFFFFF)
                                        }
                                        val chipBorder = if (arrival.isRealtime) {
                                            AccentEmerald.copy(alpha = 0.48f)
                                        } else {
                                            GlassRowBorder
                                        }
                                        val chipInteraction = remember { MutableInteractionSource() }

                                        Column(
                                            modifier = Modifier
                                                .heightIn(min = 48.dp)
                                                .glassInteractive(chipInteraction) {
                                                    onArrivalTap(arrival, indexInLine)
                                                }
                                                .background(chipBg, RoundedCornerShape(12.dp))
                                                .border(1.dp, chipBorder, RoundedCornerShape(12.dp))
                                                .padding(horizontal = 10.dp, vertical = 7.dp)
                                                .testTag("arrival_chip_${lineCode}_$indexInLine"),
                                            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                            ) {
                                                Text(
                                                    text = countdownText,
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = if (mins <= 2) AccentEmerald else TextPrimary,
                                                )
                                                Text(
                                                    text = clockText,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = TextSubtitle,
                                                )
                                            }
                                            val linkedVid = arrival.vehicleId?.trim()?.takeIf { it.isNotEmpty() }
                                            val canLocateLive = arrival.isRealtime && linkedVid != null
                                            val statusLabel = when {
                                                arrival.isRealtime && linkedVid != null -> "Live · #$linkedVid"
                                                arrival.isRealtime -> "Live"
                                                else -> "Stimato"
                                            }
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(5.dp)
                                                        .background(
                                                            color = if (arrival.isRealtime) AccentEmerald else TextSubtitle,
                                                            shape = CircleShape,
                                                        ),
                                                )
                                                Text(
                                                    text = statusLabel,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (arrival.isRealtime) AccentEmerald else TextSubtitle,
                                                )
                                                if (canLocateLive) {
                                                    Icon(
                                                        imageVector = ToBusIcons.MyLocation,
                                                        contentDescription = "Mostra pullman sulla mappa",
                                                        tint = AccentEmerald.copy(alpha = 0.85f),
                                                        modifier = Modifier.size(11.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
